package com.inspection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A2A（Agent-to-Agent）规范与演示端点。
 * 本轮交付：AgentCard 规范 + task 演示态；真实多 Agent 协作留作后续演进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class A2aService {

    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @PostConstruct
    public void init() {
        log.info("A2aService ready (demo mode)");
    }

    // Agent Card 规范（静态）：描述本 Agent 的能力与接入点
    public Map<String, Object> agentCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "ai-infra-agent");
        card.put("version", "1.0.0");
        card.put("description", "AI Infra 系统 MVP 的运维 Agent，提供诊断、修复建议、记忆召回与 Agent Loop 编排。");
        card.put("protocol", "a2a/0.1 (demo)");
        card.put("endpoints", Map.of(
                "agentCard", "/api/a2a/agent-card",
                "tasks", "/api/a2a/tasks"));
        List<Map<String, Object>> skills = new ArrayList<>();
        skills.add(skill("diagnose", "故障诊断", "运行 GPU/网络/磁盘/vLLM 等检查，返回 findings"));
        skills.add(skill("agent-loop", "Agent Loop 编排", "诊断->建议->人确认->执行的闭环"));
        skills.add(skill("memory-recall", "记忆召回", "从长期故障记忆召回相似案例"));
        skills.add(skill("mcp-tools", "MCP 工具", "暴露巡检/修复脚本为 MCP Tool"));
        card.put("skills", skills);
        card.put("defaultInputModes", List.of("text/plain", "application/json"));
        card.put("defaultOutputModes", List.of("application/json"));
        return card;
    }

    private Map<String, Object> skill(String id, String name, String desc) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("name", name);
        s.put("description", desc);
        return s;
    }

    // 演示态 task：创建任务（模拟 A2A task 协议），返回 taskId
    public Map<String, Object> createTask(String skillId, String input) {
        String taskId = "task-" + System.currentTimeMillis() + "-" + seq.getAndIncrement();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", taskId);
        task.put("skill", skillId);
        task.put("input", input);
        task.put("state", "submitted");
        task.put("createdAt", new Date().toString());
        tasks.put(taskId, task);
        return task;
    }

    public Map<String, Object> getTask(String taskId) {
        Map<String, Object> t = tasks.get(taskId);
        if (t == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("found", false);
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>(t);
        m.put("found", true);
        return m;
    }
}
