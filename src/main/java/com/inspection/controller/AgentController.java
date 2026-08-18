package com.inspection.controller;

import com.inspection.service.A2aService;
import com.inspection.service.AgentLoopService;
import com.inspection.service.GatewayService;
import com.inspection.service.McpService;
import com.inspection.service.MemoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final GatewayService gatewayService;
    private final MemoryService memoryService;
    private final AgentLoopService agentLoopService;
    private final McpService mcpService;
    private final A2aService a2aService;
    private final JdbcTemplate jdbcTemplate;

    public AgentController(GatewayService gatewayService, MemoryService memoryService,
                           AgentLoopService agentLoopService, McpService mcpService,
                           A2aService a2aService, JdbcTemplate jdbcTemplate) {
        this.gatewayService = gatewayService;
        this.memoryService = memoryService;
        this.agentLoopService = agentLoopService;
        this.mcpService = mcpService;
        this.a2aService = a2aService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------- Agent Loop -------------------
    @PostMapping("/agent/run")
    public Map<String, Object> agentRun(@RequestBody(required = false) Map<String, Object> body) {
        String issue = body == null ? null : String.valueOf(body.getOrDefault("issue", ""));
        return agentLoopService.run(issue);
    }

    @PostMapping("/agent/confirm")
    public Map<String, Object> agentConfirm(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return agentLoopService.confirm(sessionId, approved);
    }

    @GetMapping("/agent/status")
    public Map<String, Object> agentStatus(@RequestParam String sessionId) {
        return agentLoopService.status(sessionId);
    }

    // ------------------- Memory -------------------
    @PostMapping("/memory/short/save")
    public Map<String, Object> saveShort(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        String role = String.valueOf(body.getOrDefault("role", "user"));
        String content = String.valueOf(body.getOrDefault("content", ""));
        memoryService.saveShortTerm(sessionId, role, content);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        return m;
    }

    @GetMapping("/memory/short")
    public List<Map<String, Object>> getShort(@RequestParam String sessionId,
                                              @RequestParam(defaultValue = "20") int limit) {
        return memoryService.getShortTerm(sessionId, limit);
    }

    @PostMapping("/memory/long/save")
    public Map<String, Object> saveLong(@RequestBody Map<String, Object> body) {
        return memoryService.saveLongTerm(
                String.valueOf(body.getOrDefault("category", "general")),
                String.valueOf(body.getOrDefault("problem", "")),
                String.valueOf(body.getOrDefault("rootCause", "")),
                String.valueOf(body.getOrDefault("fix", "")),
                String.valueOf(body.getOrDefault("source", "manual")));
    }

    @GetMapping("/memory/long")
    public List<Map<String, Object>> getLong(@RequestParam(defaultValue = "50") int limit) {
        return memoryService.listLongTerm(limit);
    }

    @GetMapping("/memory/recall")
    public List<Map<String, Object>> recall(@RequestParam String q,
                                            @RequestParam(defaultValue = "10") int limit) {
        return memoryService.recallLongTerm(q, limit);
    }

    @PostMapping("/memory/compress")
    public Map<String, Object> compress(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        int limit = toInt(body.get("limit"), 20);
        String summary = memoryService.compressSession(sessionId, limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("summary", summary);
        return m;
    }

    // ------------------- MCP -------------------
    @GetMapping("/mcp/tools")
    public List<Map<String, Object>> mcpTools() {
        return mcpService.listTools();
    }

    @PostMapping("/mcp/call")
    public Map<String, Object> mcpCall(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", ""));
        Map<String, Object> arguments = (Map<String, Object>) body.getOrDefault("arguments", Map.of());
        return mcpService.callTool(name, arguments);
    }

    @PostMapping("/mcp")
    public Map<String, Object> mcpJsonRpc(@RequestBody Map<String, Object> body) {
        return mcpService.handleJsonRpc(body);
    }

    // ------------------- A2A -------------------
    @GetMapping("/a2a/agent-card")
    public Map<String, Object> a2aCard() {
        return a2aService.agentCard();
    }

    @PostMapping("/a2a/tasks")
    public Map<String, Object> a2aCreate(@RequestBody Map<String, Object> body) {
        String skill = String.valueOf(body.getOrDefault("skill", ""));
        String input = String.valueOf(body.getOrDefault("input", ""));
        return a2aService.createTask(skill, input);
    }

    @GetMapping("/a2a/tasks")
    public Map<String, Object> a2aGet(@RequestParam String taskId) {
        return a2aService.getTask(taskId);
    }

    // ------------------- Gateway retry / fallback -------------------
    @PostMapping("/gateway/config/retry")
    public Map<String, Object> setRetry(@RequestBody Map<String, Object> body) {
        int retry = toInt(body.get("retryCount"), 2);
        boolean fb = Boolean.TRUE.equals(body.get("fallbackEnabled"));
        gatewayService.setRetryFallback(retry, fb);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("retryCount", gatewayService.getRetryCount());
        m.put("fallbackEnabled", gatewayService.isFallbackEnabled());
        return m;
    }

    /** 展示某个模型当前解析出的后端降级顺序，前端"重试降级"面板与排障都用它 */
    @GetMapping("/gateway/route-chain")
    public Map<String, Object> routeChain(@RequestParam(required = false, defaultValue = "default") String model) {
        return gatewayService.routeChainInfo(model);
    }

    @GetMapping("/gateway/fallback-logs")
    public List<Map<String, Object>> fallbackLogs(@RequestParam(defaultValue = "50") int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT id,model,failed_url,fail_reason,fallback_url,final_status,created_at " +
                        "FROM gateway_fallback_log ORDER BY id DESC LIMIT ?", lim);
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
