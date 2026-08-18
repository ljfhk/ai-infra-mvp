package com.inspection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent Loop 编排：诊断 -> 召回记忆 -> LLM 建议 -> 人确认 -> 执行。
 * 复用 InfraService.diagnose() + MemoryService（召回/沉淀）+ GatewayService.internalChat（LLM 建议）
 * + SshService（执行修复）。不引入额外模型下载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoopService {

    private final InfraService infraService;
    private final MemoryService memoryService;
    private final GatewayService gatewayService;
    private final SshService sshService;
    private final ObjectMapper objectMapper;

    @Value("${agent.target.host:127.0.0.1}")
    private String targetHost;
    @Value("${agent.target.port:22}")
    private int targetPort;
    @Value("${agent.target.user:root}")
    private String targetUser;
    @Value("${agent.target.key:}")
    private String targetKey;

    // 内存会话状态机：sessionId -> 上下文
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @PostConstruct
    public void init() {
        log.info("AgentLoopService ready, target={}:{} user={}", targetHost, targetPort, targetUser);
    }

    // 状态机：run -> WAIT_CONFIRM -> (confirm approved) EXECUTING -> DONE / (confirm rejected) ABORTED
    public Map<String, Object> run(String issue) {
        String sessionId = "agent-" + System.currentTimeMillis() + "-" + seq.getAndIncrement();
        Map<String, Object> diag = infraService.diagnose();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) diag.getOrDefault("findings", Collections.emptyList());

        List<String> allFixes = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            List<String> fx = (List<String>) f.getOrDefault("fixes", Collections.emptyList());
            for (String x : fx) {
                if (x != null && !x.isBlank() && !x.trim().startsWith("#")) allFixes.add(x.trim());
            }
        }

        String q = (issue != null && !issue.isBlank()) ? issue : String.valueOf(diag.getOrDefault("summary", "diagnose"));
        List<Map<String, Object>> history = memoryService.recallLongTerm(q, 5);
        String suggestion = buildSuggestion(issue, findings, history);

        memoryService.saveShortTerm(sessionId, "user", "问题:" + (issue == null ? "" : issue));
        memoryService.saveShortTerm(sessionId, "assistant", suggestion);

        AgentSession s = new AgentSession();
        s.sessionId = sessionId;
        s.issue = issue;
        s.status = "WAIT_CONFIRM";
        s.findings = findings;
        s.fixes = allFixes;
        s.suggestion = suggestion;
        s.history = history;
        sessions.put(sessionId, s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("status", s.status);
        out.put("summary", diag.getOrDefault("summary", "unknown"));
        out.put("findings", findings);
        out.put("history", history);
        out.put("suggestion", suggestion);
        out.put("fixes", allFixes);
        return out;
    }

    public Map<String, Object> confirm(String sessionId, boolean approved) {
        AgentSession s = sessions.get(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("success", false);
            out.put("errorMsg", "session not found: " + sessionId);
            return out;
        }
        if (!"WAIT_CONFIRM".equals(s.status)) {
            out.put("success", false);
            out.put("errorMsg", "session already " + s.status);
            return out;
        }
        if (!approved) {
            s.status = "ABORTED";
            out.put("success", true);
            out.put("status", s.status);
            out.put("message", "已取消执行");
            return out;
        }
        s.status = "EXECUTING";
        List<String> executed = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        for (String fix : s.fixes) {
            if (isWindowsCommand(fix)) {
                manual.add(fix); // Windows/netsh 命令需在 Windows 宿主机执行，标记人工
                continue;
            }
            try {
                String r = sshService.executeCommand(targetHost, targetPort, targetUser, "", targetKey, fix);
                String shortR = (r == null) ? "ok" : r.replaceAll("\\s+", " ").trim();
                if (shortR.length() > 200) shortR = shortR.substring(0, 200) + "...";
                executed.add(fix + " => " + shortR);
            } catch (Exception e) {
                executed.add(fix + " => ERROR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
        s.status = "DONE";
        try {
            String problem = (s.issue != null ? s.issue : "") + " | findings=" + s.findings.size();
            memoryService.ingestFromDiagnosis("agent-loop", problem, s.suggestion, String.join("\n", s.fixes));
        } catch (Exception e) {
            log.warn("ingest memory failed", e);
        }
        out.put("success", true);
        out.put("status", s.status);
        out.put("executed", executed);
        out.put("manual", manual);
        out.put("memorySaved", true);
        return out;
    }

    public Map<String, Object> status(String sessionId) {
        AgentSession s = sessions.get(sessionId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            m.put("found", false);
            return m;
        }
        m.put("found", true);
        m.put("sessionId", s.sessionId);
        m.put("status", s.status);
        m.put("issue", s.issue);
        m.put("fixes", s.fixes);
        return m;
    }

    private String buildSuggestion(String issue, List<Map<String, Object>> findings, List<Map<String, Object>> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 AI Infra 运维助手。请基于以下诊断与历史记忆，给出处理建议（中文，分点）。\n");
        sb.append("用户问题: ").append(issue == null ? "(未提供)" : issue).append("\n\n");
        sb.append("【本轮诊断】\n");
        for (Map<String, Object> f : findings) {
            sb.append("- [").append(f.get("level")).append("] ").append(f.get("message"));
            if (f.get("suggestion") != null && !String.valueOf(f.get("suggestion")).isBlank())
                sb.append(" 建议:").append(f.get("suggestion"));
            sb.append("\n");
        }
        sb.append("\n【历史类似故障】\n");
        if (history.isEmpty()) sb.append("(无)\n");
        else for (Map<String, Object> h : history) {
            sb.append("- 问题:").append(h.get("problem")).append(" 根因:").append(h.get("root_cause")).append(" 处理:").append(h.get("fix")).append("\n");
        }
        sb.append("\n请输出：1) 根因判断 2) 推荐处理步骤 3) 风险提示。");
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", "default",
                    "messages", List.of(Map.of("role", "user", "content", sb.toString()))));
            Map<String, Object> r = gatewayService.internalChat(body);
            int st = toInt(r.get("status"), 502);
            if (st != 502) {
                String resp = String.valueOf(r.get("body"));
                JsonNode node = objectMapper.readTree(resp);
                if (node.has("choices") && node.get("choices").isArray() && node.get("choices").size() > 0) {
                    JsonNode msgNode = node.get("choices").get(0).get("message");
                    if (msgNode.has("content")) return msgNode.get("content").asText();
                }
            }
            String detail = String.valueOf(r.getOrDefault("errorMsg", r.getOrDefault("body", "")));
            if (detail.length() > 300) detail = detail.substring(0, 300);
            log.warn("LLM 建议生成失败 status={} degraded={} detail={}", st, r.get("degraded"), detail);
            return "（LLM 建议生成失败：status=" + st + (r.get("degraded") != null ? " 已降级" : "")
                    + "，原因：" + detail + "。请参考诊断 findings 与历史记忆人工判断）";
        } catch (Exception e) {
            log.warn("build suggestion failed", e);
            return "（LLM 建议生成异常：" + e.getClass().getSimpleName() + " " + e.getMessage()
                    + "。请参考诊断 findings 与历史记忆人工判断）";
        }
    }

    private boolean isWindowsCommand(String cmd) {
        String c = cmd.toLowerCase();
        return c.contains("netsh") || c.contains("powershell") || c.startsWith("windows");
    }

    private int toInt(Object o, int d) {
        if (o == null) return d;
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return d;
        }
    }

    private static class AgentSession {
        String sessionId;
        String issue;
        String status;
        List<Map<String, Object>> findings;
        List<Map<String, Object>> history;
        List<String> fixes;
        String suggestion;
    }
}
