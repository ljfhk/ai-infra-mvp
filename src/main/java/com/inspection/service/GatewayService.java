package com.inspection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 档1 轻量推理网关：鉴权(API Key) + 限流(QPS/每日token) + 多模型路由 + 调用链追踪。
 * 无第三方依赖，限流用内存滑动窗口，配置/Key/追踪落 SQLite。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gateway.default-base-url:}")
    private String gwDefaultBaseUrl;
    @Value("${inference.vllm.base-url:http://127.0.0.1:8000}")
    private String inferenceBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final SecureRandom random = new SecureRandom();

    // 内存滑动窗口（每秒）限流：bucket -> 时间戳队列
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    // 每日 token 累计：keyValue|yyyy-MM-dd -> 已用 token
    private final Map<String, Long> dailyTokens = new ConcurrentHashMap<>();
    // 全局并发控制（同时进行的推理请求数）
    private volatile int globalConcurrency = 10;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    private String defaultBaseUrl() {
        String u = (gwDefaultBaseUrl != null && !gwDefaultBaseUrl.isBlank()) ? gwDefaultBaseUrl : inferenceBaseUrl;
        if (u == null || u.isBlank()) u = "http://127.0.0.1:8000";
        return u.trim().replaceAll("/+$", "");
    }

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS api_key (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, key_value TEXT UNIQUE NOT NULL, name TEXT, " +
                "status INTEGER DEFAULT 1, qps_limit INTEGER DEFAULT 0, token_daily_limit INTEGER DEFAULT 0, " +
                "created_at TEXT DEFAULT (datetime('now','localtime')), last_used_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_route (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, model TEXT NOT NULL, base_url TEXT NOT NULL, " +
                "priority INTEGER DEFAULT 0, enabled INTEGER DEFAULT 1)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gateway_config (" +
                "cfg_key TEXT PRIMARY KEY, cfg_value TEXT)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS inference_call_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, key_prefix TEXT, model TEXT, " +
                "prompt_tokens INTEGER DEFAULT 0, completion_tokens INTEGER DEFAULT 0, " +
                "latency_ms INTEGER DEFAULT 0, status INTEGER DEFAULT 0, " +
                "created_at TEXT DEFAULT (datetime('now','localtime')))");

        if (toInt(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gateway_config", Integer.class), 0) == 0) {
            jdbcTemplate.update("INSERT INTO gateway_config(cfg_key,cfg_value) VALUES('global_qps','20')");
            jdbcTemplate.update("INSERT INTO gateway_config(cfg_key,cfg_value) VALUES('default_base_url',?)", defaultBaseUrl());
            jdbcTemplate.update("INSERT INTO gateway_config(cfg_key,cfg_value) VALUES('global_concurrency','10')");
        }
        try {
            String c = jdbcTemplate.queryForObject("SELECT cfg_value FROM gateway_config WHERE cfg_key='global_concurrency'", String.class);
            globalConcurrency = toInt(c, 10);
        } catch (Exception ignored) {
            globalConcurrency = 10;
        }
        if (toInt(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gateway_route", Integer.class), 0) == 0) {
            jdbcTemplate.update("INSERT INTO gateway_route(model,base_url,priority,enabled) VALUES('*',?,0,1)", defaultBaseUrl());
        }
    }

    // ------------------- 配置读取 -------------------
    private int getGlobalQps() {
        String v = jdbcTemplate.queryForObject("SELECT cfg_value FROM gateway_config WHERE cfg_key='global_qps'", String.class);
        return toInt(v, 20);
    }
    private String getDefaultBaseUrl() {
        String v = jdbcTemplate.queryForObject("SELECT cfg_value FROM gateway_config WHERE cfg_key='default_base_url'", String.class);
        return (v != null && !v.isBlank()) ? v : defaultBaseUrl();
    }
    private String today() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    // ------------------- API Key -------------------
    public List<Map<String, Object>> listKeys() {
        return jdbcTemplate.queryForList(
                "SELECT id,key_value,name,status,qps_limit,token_daily_limit,created_at,last_used_at FROM api_key ORDER BY id DESC");
    }
    public Map<String, Object> createKey(String name, int qpsLimit, int tokenDailyLimit) {
        String key = "sk-" + randomHex(32);
        jdbcTemplate.update("INSERT INTO api_key(key_value,name,status,qps_limit,token_daily_limit) VALUES(?,?,1,?,?)",
                key, name, qpsLimit, tokenDailyLimit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("key", key);
        m.put("name", name);
        return m;
    }
    public void setKeyStatus(Long id, int status) {
        jdbcTemplate.update("UPDATE api_key SET status=? WHERE id=?", status, id);
    }
    public void deleteKey(Long id) {
        jdbcTemplate.update("DELETE FROM api_key WHERE id=?", id);
    }
    private String randomHex(int bits) {
        int bytes = bits / 4; // 每 4 bit 一个十六进制字符
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private Map<String, Object> findKey(String raw) {
        if (raw == null) return null;
        String k = raw.trim();
        if (k.toLowerCase().startsWith("bearer ")) k = k.substring(7).trim();
        if (k.isEmpty()) return null;
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT id,key_value,status,qps_limit,token_daily_limit FROM api_key WHERE key_value=?", k);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------- 全局/限流配置 -------------------
    public Map<String, Object> getConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("globalQps", getGlobalQps());
        m.put("defaultBaseUrl", getDefaultBaseUrl());
        m.put("globalConcurrency", globalConcurrency);
        return m;
    }
    public void setConfig(int globalQps, String defaultBaseUrl) {
        String g = String.valueOf(globalQps);
        jdbcTemplate.update("INSERT INTO gateway_config(cfg_key,cfg_value) VALUES('global_qps',?) " +
                "ON CONFLICT(cfg_key) DO UPDATE SET cfg_value=excluded.cfg_value", g);
        if (defaultBaseUrl != null && !defaultBaseUrl.isBlank()) {
            jdbcTemplate.update("INSERT INTO gateway_config(cfg_key,cfg_value) VALUES('default_base_url',?) " +
                    "ON CONFLICT(cfg_key) DO UPDATE SET cfg_value=excluded.cfg_value", defaultBaseUrl.trim().replaceAll("/+$", ""));
        }
    }
    public int getGlobalConcurrency() { return globalConcurrency; }
    public void setConcurrency(int c) {
        if (c < 0) c = 0;
        globalConcurrency = c;
        jdbcTemplate.update("INSERT INTO gateway_config(cfg_key,cfg_value) VALUES('global_concurrency',?) " +
                "ON CONFLICT(cfg_key) DO UPDATE SET cfg_value=excluded.cfg_value", String.valueOf(c));
    }

    // ------------------- 路由 -------------------
    public List<Map<String, Object>> listRoutes() {
        return jdbcTemplate.queryForList(
                "SELECT id,model,base_url,priority,enabled FROM gateway_route ORDER BY priority DESC, id");
    }
    public Map<String, Object> createRoute(String model, String baseUrl) {
        jdbcTemplate.update("INSERT INTO gateway_route(model,base_url,priority,enabled) VALUES(?,?,0,1)", model, baseUrl);
        return Map.of("success", true);
    }
    public void setRouteStatus(Long id, int enabled) {
        jdbcTemplate.update("UPDATE gateway_route SET enabled=? WHERE id=?", enabled, id);
    }
    public void deleteRoute(Long id) {
        jdbcTemplate.update("DELETE FROM gateway_route WHERE id=?", id);
    }
    public String resolveBaseUrl(String model) {
        if (model != null && !model.isBlank()) {
            try {
                List<Map<String, Object>> rs = jdbcTemplate.queryForList(
                        "SELECT base_url FROM gateway_route WHERE enabled=1 AND model=? ORDER BY priority DESC LIMIT 1", model);
                if (!rs.isEmpty()) return String.valueOf(rs.get(0).get("base_url")).trim().replaceAll("/+$", "");
            } catch (Exception ignored) {
            }
        }
        try {
            List<Map<String, Object>> rs = jdbcTemplate.queryForList(
                    "SELECT base_url FROM gateway_route WHERE enabled=1 AND model='*' ORDER BY priority DESC LIMIT 1");
            if (!rs.isEmpty()) return String.valueOf(rs.get(0).get("base_url")).trim().replaceAll("/+$", "");
        } catch (Exception ignored) {
        }
        return getDefaultBaseUrl();
    }

    // 内部并发准入（供 chat 使用）；返回 false 表示已达上限
    private boolean tryEnterConcurrency() {
        if (globalConcurrency <= 0) return true;
        int cur;
        do {
            cur = inFlight.get();
            if (cur >= globalConcurrency) return false;
        } while (!inFlight.compareAndSet(cur, cur + 1));
        return true;
    }
    private void releaseConcurrency() {
        if (globalConcurrency > 0) inFlight.decrementAndGet();
    }

    // 内部转发（压测/诊断用）：跳过鉴权与限流，直接转发到已解析后端，返回 {status, body, latencyMs}
    public Map<String, Object> internalChat(String body) {
        String model = null;
        String fwdBody = body;
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("model")) model = root.get("model").asText();
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("stream", false);
                fwdBody = objectMapper.writeValueAsString(root);
            }
        } catch (Exception ignored) {
        }
        String baseUrl = resolveBaseUrl(model);
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(fwdBody.getBytes(StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", resp.statusCode());
            m.put("body", resp.body());
            m.put("latencyMs", (int) (System.currentTimeMillis() - start));
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", 502);
            m.put("errorMsg", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return m;
        }
    }

    // 内部获取模型列表（诊断/平台状态用）：跳过鉴权
    public Map<String, Object> modelsInternal() {
        String baseUrl = resolveBaseUrl(null);
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", resp.statusCode());
            m.put("body", resp.body());
            m.put("latencyMs", (int) (System.currentTimeMillis() - start));
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", 502);
            m.put("errorMsg", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return m;
        }
    }

    // ------------------- 调用追踪 -------------------
    public List<Map<String, Object>> listCalls(int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT id,key_prefix,model,prompt_tokens,completion_tokens,latency_ms,status,created_at " +
                        "FROM inference_call_log ORDER BY id DESC LIMIT ?", lim);
    }

    // ------------------- 限流 -------------------
    private boolean allow(String bucket, int limit) {
        if (limit <= 0) return true; // 0 = 不限
        long now = System.currentTimeMillis();
        Deque<Long> q = windows.computeIfAbsent(bucket, k -> new ConcurrentLinkedDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }
    private boolean allowDaily(String keyValue, int limit, int addTokens) {
        if (limit <= 0) return true;
        String k = keyValue + "|" + today();
        Long used = dailyTokens.getOrDefault(k, 0L);
        if (used + addTokens > limit) return false; // 预先按已累计判断是否超额（允许时再加）
        dailyTokens.put(k, used + addTokens);
        return true;
    }

    // ------------------- 代理：对话 -------------------
    public Map<String, Object> chat(String authHeader, String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> key = findKey(authHeader);
        if (key == null || toInt(key.get("status"), 0) != 1) {
            out.put("status", 401);
            out.put("errorMsg", "无效或已禁用的 API Key");
            return out;
        }
        String keyValue = String.valueOf(key.get("key_value"));
        int qps = toInt(key.get("qps_limit"), 0);
        int daily = toInt(key.get("token_daily_limit"), 0);

        if (!allow("global", getGlobalQps())) {
            out.put("status", 429);
            out.put("errorMsg", "全局限流");
            return out;
        }
        if (!allow("key:" + keyValue, qps)) {
            out.put("status", 429);
            out.put("errorMsg", "该 Key 触发 QPS 限流");
            return out;
        }
        // 解析 model + 强制关闭流式
        String model = null;
        String fwdBody = body;
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("model")) model = root.get("model").asText();
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("stream", false);
                fwdBody = objectMapper.writeValueAsString(root);
            }
        } catch (Exception ignored) {
        }
        String baseUrl = resolveBaseUrl(model);
        if (!allowDaily(keyValue, daily, 0)) {
            out.put("status", 429);
            out.put("errorMsg", "超出该 Key 的每日 token 限额");
            return out;
        }
        if (!tryEnterConcurrency()) {
            out.put("status", 429);
            out.put("errorMsg", "全局并发上限(" + globalConcurrency + ")已达上限");
            return out;
        }

        long start = System.currentTimeMillis();
        try {
            byte[] payload = fwdBody.getBytes(StandardCharsets.UTF_8);
            log.info("gateway forward chat -> {} model={} payloadLen={}", baseUrl, model, payload.length);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int pt = 0, ct = 0;
            try {
                JsonNode r = objectMapper.readTree(resp.body());
                if (r.has("usage") && r.get("usage").isObject()) {
                    JsonNode u = r.get("usage");
                    pt = u.has("prompt_tokens") ? u.get("prompt_tokens").asInt() : 0;
                    ct = u.has("completion_tokens") ? u.get("completion_tokens").asInt() : 0;
                }
            } catch (Exception ignored) {
            }
            allowDaily(keyValue, daily, pt + ct);
            logCall(keyValue, model, pt, ct, (int) (System.currentTimeMillis() - start), resp.statusCode());
            out.put("status", resp.statusCode());
            out.put("body", resp.body());
        } catch (Exception e) {
            logCall(keyValue, model, 0, 0, (int) (System.currentTimeMillis() - start), 502);
            out.put("status", 502);
            out.put("errorMsg", (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            releaseConcurrency();
        }
        return out;
    }

    // ------------------- 代理：模型列表 -------------------
    public Map<String, Object> models(String authHeader) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> key = findKey(authHeader);
        if (key == null || toInt(key.get("status"), 0) != 1) {
            out.put("status", 401);
            out.put("errorMsg", "无效或已禁用的 API Key");
            return out;
        }
        String keyValue = String.valueOf(key.get("key_value"));
        if (!allow("global", getGlobalQps())) {
            out.put("status", 429);
            out.put("errorMsg", "全局限流");
            return out;
        }
        String baseUrl = resolveBaseUrl(null);
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            logCall(keyValue, null, 0, 0, (int) (System.currentTimeMillis() - start), resp.statusCode());
            out.put("status", resp.statusCode());
            out.put("body", resp.body());
        } catch (Exception e) {
            logCall(keyValue, null, 0, 0, (int) (System.currentTimeMillis() - start), 502);
            out.put("status", 502);
            out.put("errorMsg", (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return out;
    }

    private void logCall(String keyValue, String model, int pt, int ct, int latency, int status) {
        try {
            String prefix = keyValue.length() > 12 ? keyValue.substring(0, 12) : keyValue;
            jdbcTemplate.update(
                    "INSERT INTO inference_call_log(key_prefix,model,prompt_tokens,completion_tokens,latency_ms,status) VALUES(?,?,?,?,?,?)",
                    prefix, model, pt, ct, latency, status);
            jdbcTemplate.update("UPDATE api_key SET last_used_at=(datetime('now','localtime')) WHERE key_value=?", keyValue);
        } catch (Exception e) {
            log.warn("call log failed", e);
        }
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
