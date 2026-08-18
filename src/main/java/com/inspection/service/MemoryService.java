package com.inspection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量记忆层：短期会话上下文（SQLite）+ 长期故障记忆（SQLite）。
 * 召回用关键词匹配，压缩/摘要复用本地 Qwen（经 GatewayService.internalChat），
 * 不引入额外模型下载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayService gatewayService;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_memory_short (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT, " +
                "content TEXT, seq INTEGER DEFAULT 0, created_at TEXT DEFAULT (datetime('now','localtime')))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_memory_long (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, problem TEXT, " +
                "root_cause TEXT, fix TEXT, source TEXT, created_at TEXT DEFAULT (datetime('now','localtime')))");
    }

    // ------------------- 短期记忆 -------------------
    public void saveShortTerm(String sessionId, String role, String content) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq),0) FROM agent_memory_short WHERE session_id=?", Integer.class, sessionId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        jdbcTemplate.update(
                "INSERT INTO agent_memory_short(session_id,role,content,seq) VALUES(?,?,?,?)",
                sessionId, role, content, seq);
    }

    public List<Map<String, Object>> getShortTerm(String sessionId, int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT role,content,seq FROM agent_memory_short WHERE session_id=? ORDER BY seq DESC LIMIT ?",
                sessionId, lim);
    }

    public void clearShortTerm(String sessionId) {
        jdbcTemplate.update("DELETE FROM agent_memory_short WHERE session_id=?", sessionId);
    }

    // ------------------- 长期记忆 -------------------
    public Map<String, Object> saveLongTerm(String category, String problem, String rootCause, String fix, String source) {
        jdbcTemplate.update(
                "INSERT INTO agent_memory_long(category,problem,root_cause,fix,source) VALUES(?,?,?,?,?)",
                category, problem, rootCause, fix, source);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        return m;
    }

    public List<Map<String, Object>> listLongTerm(int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.queryForList(
                "SELECT id,category,problem,root_cause,fix,source,created_at FROM agent_memory_long ORDER BY id DESC LIMIT ?", lim);
    }

    /**
     * 关键词召回。
     * 早期版本用「整句 LIKE」，自然语言描述（如"vLLM 服务显存不足，推理请求报错"）
     * 永远匹配不到库里的"vLLM 启动报 CUDA out of memory…显存不足"，等于召回失效。
     * 现改为：抽取英文/数字 token + 中文 bigram，按命中数打分排序。
     */
    public List<Map<String, Object>> recallLongTerm(String query, int limit) {
        int lim = Math.max(1, Math.min(limit, 20));
        List<Map<String, Object>> all = listLongTerm(500);
        if (all.isEmpty()) return all;

        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return all.subList(0, Math.min(lim, all.size()));
        }

        List<Map<String, Object>> hit = new java.util.ArrayList<>();
        for (Map<String, Object> row : all) {
            String hay = (str(row.get("problem")) + " " + str(row.get("root_cause")) + " "
                    + str(row.get("fix")) + " " + str(row.get("category"))).toLowerCase();
            int score = 0;
            for (String t : tokens) {
                if (hay.contains(t)) score++;
            }
            if (score > 0) {
                Map<String, Object> m = new LinkedHashMap<>(row);
                m.put("matchScore", score);
                hit.add(m);
            }
        }
        hit.sort((a, b) -> {
            int c = Integer.compare((Integer) b.get("matchScore"), (Integer) a.get("matchScore"));
            if (c != 0) return c;
            return Integer.compare(toInt(b.get("id"), 0), toInt(a.get("id"), 0));
        });
        return hit.subList(0, Math.min(lim, hit.size()));
    }

    /** 抽取检索 token：ASCII 词（长度>=2）+ 中文 bigram，去重并限量。 */
    private List<String> tokenize(String query) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (query == null || query.isBlank()) return new java.util.ArrayList<>(out);
        String s = query.toLowerCase();

        java.util.regex.Matcher ascii = java.util.regex.Pattern
                .compile("[a-z0-9][a-z0-9._-]{1,}").matcher(s);
        while (ascii.find()) out.add(ascii.group());

        java.util.regex.Matcher cn = java.util.regex.Pattern
                .compile("[\\u4e00-\\u9fa5]{2,}").matcher(s);
        while (cn.find()) {
            String seg = cn.group();
            out.add(seg);
            for (int i = 0; i + 2 <= seg.length(); i++) out.add(seg.substring(i, i + 2));
        }

        List<String> list = new java.util.ArrayList<>(out);
        return list.size() > 60 ? list.subList(0, 60) : list;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // 从诊断结果沉淀长期记忆（便捷）
    public void ingestFromDiagnosis(String category, String problem, String rootCause, String fix) {
        try {
            saveLongTerm(category, problem, rootCause, fix, "diagnosis");
        } catch (Exception e) {
            log.warn("ingest memory failed", e);
        }
    }

    // 调用本地 Qwen 压缩短期上下文为一条摘要（复用 GatewayService.internalChat）
    public String compressSession(String sessionId, int limit) {
        List<Map<String, Object>> msgs = getShortTerm(sessionId, limit);
        if (msgs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Map<String, Object> m = msgs.get(i);
            sb.append(m.get("role")).append(": ").append(m.get("content")).append("\n");
        }
        String prompt = "请把以下运维对话压缩为一条简洁的故障摘要（含现象、根因、处理）：\n" + sb;
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", "default",
                    "messages", List.of(Map.of("role", "user", "content", prompt))));
            Map<String, Object> r = gatewayService.internalChat(body);
            if (toInt(r.get("status"), 502) == 200) {
                String resp = String.valueOf(r.get("body"));
                JsonNode node = objectMapper.readTree(resp);
                if (node.has("choices") && node.get("choices").isArray() && node.get("choices").size() > 0) {
                    JsonNode msgNode = node.get("choices").get(0).get("message");
                    if (msgNode.has("content")) return msgNode.get("content").asText();
                }
            }
        } catch (Exception e) {
            log.warn("compress session failed", e);
        }
        return "";
    }

    private int toInt(Object o, int d) {
        if (o == null) return d;
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return d;
        }
    }
}
