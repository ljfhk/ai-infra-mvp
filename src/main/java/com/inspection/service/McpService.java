package com.inspection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把巡检/修复脚本封装为 MCP Tool：扫描脚本目录，解析 @desc/@args 元信息，
 * 提供 listTools / callTool，并兼容 MCP JSON-RPC 2.0 的 tools/list、tools/call。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpService {

    @Value("${infra.script-dir:/opt/scripts}")
    private String scriptDir;

    private final List<Map<String, Object>> tools = new ArrayList<>();

    @PostConstruct
    public void init() {
        scanScripts();
    }

    public void scanScripts() {
        tools.clear();
        File dir = new File(scriptDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("script dir not found: {}", scriptDir);
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".sh") || name.endsWith(".py"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            Map<String, Object> meta = parseMeta(f);
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", f.getName().replaceAll("[^a-zA-Z0-9_]", "_"));
            tool.put("description", meta.getOrDefault("desc", "脚本工具: " + f.getName()));
            tool.put("inputSchema", meta.getOrDefault("schema", Map.of("type", "object", "properties", Map.of())));
            tool.put("scriptPath", f.getAbsolutePath());
            tools.add(tool);
        }
        log.info("MCP tools scanned: {}", tools.size());
    }

    private Map<String, Object> parseMeta(File f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("desc", "脚本工具: " + f.getName());
        m.put("schema", Map.of("type", "object", "properties", Map.of()));
        try {
            List<String> lines = Files.readAllLines(f.toPath());
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("# @desc")) {
                    m.put("desc", t.substring(t.indexOf("@desc") + 5).trim());
                } else if (t.startsWith("# @args")) {
                    String spec = t.substring(t.indexOf("@args") + 5).trim();
                    Map<String, Object> props = new LinkedHashMap<>();
                    List<String> required = new ArrayList<>();
                    for (String part : spec.split(",")) {
                        String[] kv = part.split(":");
                        if (kv.length >= 2) {
                            String pname = kv[0].trim();
                            String ptype = kv[1].trim();
                            Map<String, Object> prop = new LinkedHashMap<>();
                            prop.put("type", mapType(ptype));
                            if (kv.length >= 3) prop.put("description", kv[2].trim());
                            props.put(pname, prop);
                            required.add(pname);
                        }
                    }
                    Map<String, Object> schema = new LinkedHashMap<>();
                    schema.put("type", "object");
                    schema.put("properties", props);
                    schema.put("required", required);
                    m.put("schema", schema);
                }
            }
        } catch (Exception e) {
            log.warn("parse meta failed: {}", f.getName(), e);
        }
        return m;
    }

    private String mapType(String t) {
        switch (t.toLowerCase()) {
            case "int": case "integer": case "number": return "number";
            case "bool": case "boolean": return "boolean";
            default: return "string";
        }
    }

    public List<Map<String, Object>> listTools() {
        return tools;
    }

    public Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> tool = tools.stream()
                .filter(t -> t.get("name").equals(toolName)).findFirst().orElse(null);
        if (tool == null) {
            out.put("success", false);
            out.put("errorMsg", "tool not found: " + toolName);
            return out;
        }
        String scriptPath = String.valueOf(tool.get("scriptPath"));
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("bash");
            cmd.add(scriptPath);
            if (args != null) {
                for (Object v : args.values()) {
                    if (v != null) cmd.add(String.valueOf(v));
                }
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            out.put("success", code == 0);
            out.put("exitCode", code);
            out.put("output", result);
        } catch (Exception e) {
            out.put("success", false);
            out.put("errorMsg", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return out;
    }

    // 兼容 MCP JSON-RPC 2.0 的简化 dispatch
    public Map<String, Object> handleJsonRpc(Map<String, Object> req) {
        String method = String.valueOf(req.getOrDefault("method", ""));
        Object id = req.get("id");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        if ("tools/list".equals(method)) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("tools", tools);
            resp.put("result", res);
        } else if ("tools/call".equals(method)) {
            Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());
            String name = String.valueOf(params.getOrDefault("name", ""));
            Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
            Map<String, Object> call = callTool(name, arguments);
            Map<String, Object> res = new LinkedHashMap<>();
            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "text");
            c.put("text", String.valueOf(call.getOrDefault("output", call.getOrDefault("errorMsg", ""))));
            content.add(c);
            res.put("content", content);
            res.put("isError", !Boolean.TRUE.equals(call.get("success")));
            resp.put("result", res);
        } else {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", -32601);
            err.put("message", "method not found: " + method);
            resp.put("error", err);
        }
        return resp;
    }
}
