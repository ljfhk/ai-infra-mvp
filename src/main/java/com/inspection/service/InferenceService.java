package com.inspection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class InferenceService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${inference.vllm.base-url:http://127.0.0.1:8000}")
    private String vllmBaseUrl;

    @Value("${inference.restart.command:}")
    private String restartCommand;

    @Autowired
    public InferenceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 整体状态：在线/离线 + 模型数 + 当前生效地址（可临时覆盖） */
    public Map<String, Object> getStatus(String baseUrlOverride) {
        String url = resolveUrl(baseUrlOverride);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", url);
        result.put("modelCount", 0);
        try {
            Map<String, Object> modelsResp = callGet("/v1/models", url);
            Object data = modelsResp.get("data");
            int count = 0;
            if (data instanceof List) {
                count = ((List<?>) data).size();
            }
            result.put("online", true);
            result.put("modelCount", count);
            result.put("success", true);
        } catch (Exception e) {
            log.warn("vLLM 不可达: {}", url, e);
            result.put("online", false);
            result.put("success", false);
            result.put("errorMsg", (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return result;
    }

    /** 解析生效地址：优先用前端临时传入的地址，否则用配置地址 */
    private String resolveUrl(String baseUrlOverride) {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            String url = baseUrlOverride.trim().replaceAll("/+$", "");
            if (url.endsWith("/v1")) {
                url = url.substring(0, url.length() - 3);
            }
            return url.replaceAll("/+$", "");
        }
        return vllmBaseUrl;
    }

    /** 模型列表 */
    public Map<String, Object> getModels(String baseUrlOverride) {
        try {
            Map<String, Object> resp = callGet("/v1/models", resolveUrl(baseUrlOverride));
            return Map.of("success", true, "data", resp.getOrDefault("data", List.of()));
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** 指标（解析 Prometheus 文本为扁平 map） */
    public Map<String, Object> getMetrics(String baseUrlOverride) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String text = callGetText("/metrics", resolveUrl(baseUrlOverride));
            Map<String, Object> metrics = parsePrometheus(text);
            result.put("success", true);
            result.put("metrics", metrics);
            result.put("raw", text.length() > 4000 ? text.substring(0, 4000) : text);
        } catch (Exception e) {
            result.put("success", false);
            result.put("errorMsg", (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return result;
    }

    /** 重启推理服务（仅执行配置的命令，未配置则返回提示，绝不执行未知命令） */
    public Map<String, Object> restart() {
        if (restartCommand == null || restartCommand.isBlank()) {
            return Map.of("success", false,
                    "errorMsg", "未配置重启命令：请在 application.yml 设置 inference.restart.command");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", restartCommand);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            return Map.of("success", code == 0, "exitCode", code, "output", output);
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> callGet(String path, String baseUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("vLLM 返回 HTTP " + resp.statusCode());
        }
        return objectMapper.readValue(resp.body(), Map.class);
    }

    private String callGetText(String path, String baseUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("vLLM 返回 HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 把 Prometheus 文本指标解析为 name -> value 的扁平 map（忽略 label） */
    private Map<String, Object> parsePrometheus(String text) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0) continue;
            String namePart = line.substring(0, lastSpace);
            String valuePart = line.substring(lastSpace + 1).trim();
            int brace = namePart.indexOf('{');
            String name = brace >= 0 ? namePart.substring(0, brace) : namePart;
            try {
                double v = Double.parseDouble(valuePart);
                metrics.put(name, v);
            } catch (NumberFormatException e) {
                metrics.put(name, valuePart);
            }
        }
        return metrics;
    }
}
