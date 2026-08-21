package com.inspection.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inspection.service.GatewayService;
import com.inspection.service.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 效果优化层 — Prompt 模板 / 参考素材注入 / 结构化输出（核心真实 + 边缘演示）。
 * 全部复用本地 Qwen（经 GatewayService.internalChat），不引入 LangChain 等重依赖。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    @Autowired
    private GatewayService gatewayService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private ObjectMapper objectMapper;

    // 内置系统提示模板（Prompt 优化层）
    private static final Map<String, String> TEMPLATES = new LinkedHashMap<>();
    static {
        TEMPLATES.put("diag_summary",
                "你是一名 AI Infra 运维专家，擅长 vLLM / k3s / GPU / CUDA 故障诊断。" +
                "请基于下方【参考素材】与【当前问题】，生成简洁的诊断摘要：根因分类 + 关键证据 + 初步建议。");
        TEMPLATES.put("fix_suggestion",
                "你是一名 AI Infra SRE，负责给出可执行的修复方案。" +
                "请基于下方【参考素材】与【当前问题】，输出修复步骤（命令/配置），并标注风险与回滚方式。");
        TEMPLATES.put("generic",
                "你是一名 AI Infra 助手，请专业、简洁地回答用户关于推理平台运维的问题。");
    }

    // 结构化输出 schema（工具调用 / 结构化输出层）
    private static final Map<String, String> SCHEMAS = new LinkedHashMap<>();
    static {
        SCHEMAS.put("diagnosis",
                "{\n  \"category\": \"oom|cuda|timeout|port|resource|other\",\n" +
                "  \"root_cause\": \"根因简述\",\n" +
                "  \"evidence\": \"关键证据\",\n" +
                "  \"severity\": \"low|medium|high\",\n" +
                "  \"confidence\": 0.0\n}");
        SCHEMAS.put("fix",
                "{\n  \"steps\": [\"步骤1\", \"步骤2\"],\n" +
                "  \"commands\": [\"可执行命令\"],\n" +
                "  \"risk\": \"风险说明\",\n" +
                "  \"rollback\": \"回滚方式\",\n" +
                "  \"confidence\": 0.0\n}");
    }

    /** 拼装最终 prompt：系统提示 + 记忆召回参考素材 + 当前问题 */
    @PostMapping("/prompt")
    public Map<String, Object> buildPrompt(@RequestBody Map<String, String> body) {
        String template = body.getOrDefault("template", "generic");
        String issue = body.getOrDefault("issue", "");
        String system = TEMPLATES.getOrDefault(template, TEMPLATES.get("generic"));

        List<Map<String, Object>> ctx = memoryService.recallLongTerm(issue, 5);

        StringBuilder sb = new StringBuilder();
        sb.append("【系统角色】\n").append(system).append("\n\n");
        sb.append("【参考素材（历史故障记忆，").append(ctx.size()).append(" 条）】\n");
        for (Map<String, Object> c : ctx) {
            sb.append("- [").append(c.get("category")).append("] ")
              .append(c.get("problem")).append(" => 修复: ").append(c.get("fix")).append("\n");
        }
        if (ctx.isEmpty()) sb.append("（无匹配历史记忆）\n");
        sb.append("\n【当前问题】\n").append(issue).append("\n");

        String finalPrompt = sb.toString();
        int estTokens = Math.max(1, (int) (finalPrompt.length() / 2.2));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("template", template);
        r.put("systemPrompt", system);
        r.put("context", ctx);
        r.put("finalPrompt", finalPrompt);
        r.put("tokenHint", estTokens);
        return r;
    }

    /** 执行 LLM 补全（复用 internalChat 走本地 Qwen） */
    @PostMapping("/complete")
    public Map<String, Object> complete(@RequestBody Map<String, String> body) {
        String prompt = body.getOrDefault("prompt", "");
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            String reqBody = objectMapper.writeValueAsString(Map.of(
                    "model", "default",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 500));
            Map<String, Object> gw = gatewayService.internalChat(reqBody);
            int status = toInt(gw.get("status"), 502);
            String text = extractContent(String.valueOf(gw.getOrDefault("body", "")));
            r.put("status", status);
            r.put("text", text);
            r.put("degraded", gw.get("degraded"));
            r.put("usedUrl", gw.get("usedUrl"));
        } catch (Exception e) {
            r.put("status", 500);
            r.put("text", "");
            r.put("error", e.getMessage());
        }
        return r;
    }

    /** 结构化输出：让 LLM 按 schema 输出 JSON 并容错解析 */
    @PostMapping("/structured")
    public Map<String, Object> structured(@RequestBody Map<String, String> body) {
        String schemaName = body.getOrDefault("schemaName", "diagnosis");
        String issue = body.getOrDefault("issue", "");
        String schema = SCHEMAS.getOrDefault(schemaName, SCHEMAS.get("diagnosis"));

        String prompt = "严格只输出如下 JSON 对象（不要 markdown、不要解释、不要多余字符）：\n"
                + schema + "\n\n故障现象：\n" + issue;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schemaName", schemaName);
        r.put("schema", schema);
        try {
            String reqBody = objectMapper.writeValueAsString(Map.of(
                    "model", "default",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 600));
            Map<String, Object> gw = gatewayService.internalChat(reqBody);
            int status = toInt(gw.get("status"), 502);
            String raw = extractContent(String.valueOf(gw.getOrDefault("body", "")));
            r.put("status", status);
            r.put("raw", raw);
            Map<String, Object> parsed = parseJsonRobust(raw);
            r.put("parsed", parsed);
            r.put("valid", parsed != null);
            r.put("degraded", gw.get("degraded"));
        } catch (Exception e) {
            r.put("status", 500);
            r.put("raw", "");
            r.put("parsed", null);
            r.put("valid", false);
            r.put("error", e.getMessage());
        }
        return r;
    }

    private String extractContent(String bodyStr) {
        if (bodyStr == null || bodyStr.isEmpty()) return "";
        try {
            JsonNode root = objectMapper.readTree(bodyStr);
            if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                JsonNode msg = root.get("choices").get(0).get("message");
                if (msg != null && msg.has("content")) return msg.get("content").asText();
            }
        } catch (Exception ignored) {
        }
        return bodyStr;
    }

    /** 容错解析：去掉 ```json 代码块、截取首个 { 到最后一个 } */
    private Map<String, Object> parseJsonRobust(String text) {
        if (text == null || text.isEmpty()) return null;
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstBrace = t.indexOf('{');
            int lastBrace = t.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) t = t.substring(firstBrace, lastBrace + 1);
        }
        int fb = t.indexOf('{');
        int lb = t.lastIndexOf('}');
        if (fb >= 0 && lb > fb) t = t.substring(fb, lb + 1);
        try {
            JsonNode n = objectMapper.readTree(t);
            if (n.isObject()) {
                Map<String, Object> m = new LinkedHashMap<>();
                n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString()));
                return m;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }
}
