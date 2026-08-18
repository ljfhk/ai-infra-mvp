package com.inspection.controller;

import com.inspection.service.GatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;

    // ============ API Key 管理 ============
    @GetMapping("/keys")
    public Map<String, Object> keys() {
        return Map.of("success", true, "data", gatewayService.listKeys());
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(@RequestBody Map<String, Object> body) {
        try {
            String name = body.get("name") == null ? "key" : String.valueOf(body.get("name"));
            int qps = toInt(body.get("qpsLimit"), 0);
            int daily = toInt(body.get("tokenDailyLimit"), 0);
            return gatewayService.createKey(name, qps, daily);
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    @PostMapping("/keys/{id}/status")
    public Map<String, Object> setKeyStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        gatewayService.setKeyStatus(id, toInt(body.get("status"), 1));
        return Map.of("success", true);
    }

    @DeleteMapping("/keys/{id}")
    public Map<String, Object> deleteKey(@PathVariable Long id) {
        gatewayService.deleteKey(id);
        return Map.of("success", true);
    }

    // ============ 限流 / 全局配置 ============
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("success", true, "data", gatewayService.getConfig());
    }

    @PostMapping("/config")
    public Map<String, Object> setConfig(@RequestBody Map<String, Object> body) {
        gatewayService.setConfig(toInt(body.get("globalQps"), 20),
                body.get("defaultBaseUrl") == null ? "" : String.valueOf(body.get("defaultBaseUrl")));
        return Map.of("success", true);
    }

    // ============ 全局并发上限 ============
    @GetMapping("/concurrency")
    public Map<String, Object> concurrency() {
        return Map.of("success", true, "data", gatewayService.getGlobalConcurrency());
    }

    @PostMapping("/concurrency")
    public Map<String, Object> setConcurrency(@RequestBody Map<String, Object> body) {
        gatewayService.setConcurrency(toInt(body.get("globalConcurrency"), 10));
        return Map.of("success", true);
    }

    // ============ 多模型路由 ============
    @GetMapping("/routes")
    public Map<String, Object> routes() {
        return Map.of("success", true, "data", gatewayService.listRoutes());
    }

    @PostMapping("/routes")
    public Map<String, Object> createRoute(@RequestBody Map<String, Object> body) {
        gatewayService.createRoute(String.valueOf(body.get("model")), String.valueOf(body.get("baseUrl")));
        return Map.of("success", true);
    }

    @PostMapping("/routes/{id}/status")
    public Map<String, Object> setRouteStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        gatewayService.setRouteStatus(id, toInt(body.get("enabled"), 1));
        return Map.of("success", true);
    }

    @DeleteMapping("/routes/{id}")
    public Map<String, Object> deleteRoute(@PathVariable Long id) {
        gatewayService.deleteRoute(id);
        return Map.of("success", true);
    }

    // ============ 调用链追踪 ============
    @GetMapping("/calls")
    public Map<String, Object> calls(@RequestParam(defaultValue = "50") int limit) {
        return Map.of("success", true, "data", gatewayService.listCalls(limit));
    }

    // ============ 代理（需 API Key） ============
    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chatCompletions(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody String body) {
        Map<String, Object> r = gatewayService.chat(auth, body);
        int status = toInt(r.get("status"), 502);
        String respBody = r.containsKey("body")
                ? String.valueOf(r.get("body"))
                : "{\"error\":\"" + r.getOrDefault("errorMsg", "gateway error") + "\"}";
        return ResponseEntity.status(status).body(respBody);
    }

    @GetMapping(value = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> models(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Map<String, Object> r = gatewayService.models(auth);
        int status = toInt(r.get("status"), 502);
        String respBody = r.containsKey("body")
                ? String.valueOf(r.get("body"))
                : "{\"error\":\"" + r.getOrDefault("errorMsg", "gateway error") + "\"}";
        return ResponseEntity.status(status).body(respBody);
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
