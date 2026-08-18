package com.inspection.controller;

import com.inspection.service.InfraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/infra")
@RequiredArgsConstructor
public class InfraController {

    private final InfraService infraService;

    // 运行环境（只读）
    @GetMapping("/env")
    public Map<String, Object> env() {
        try {
            return Map.of("success", true, "data", infraService.envInfo());
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    // 脚本交付
    @GetMapping("/scripts")
    public Map<String, Object> scripts() {
        try {
            return Map.of("success", true, "data", infraService.listScripts());
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    @PostMapping("/scripts/run")
    public Map<String, Object> runScript(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        String params = body.get("params") == null ? "" : String.valueOf(body.get("params"));
        return infraService.runScript(name, params);
    }

    @PostMapping("/scripts/save")
    public Map<String, Object> saveScript(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        return infraService.saveScript(name, content);
    }

    @GetMapping("/scripts/content")
    public Map<String, Object> scriptContent(@RequestParam String name) {
        try {
            return infraService.scriptContent(name);
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    // 文档聚合
    @GetMapping("/docs")
    public Map<String, Object> docs() {
        try {
            return Map.of("success", true, "data", infraService.listDocs());
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    // 性能评测
    @PostMapping("/benchmark/run")
    public Map<String, Object> benchmark(@RequestBody Map<String, Object> body) {
        try {
            String model = body.get("model") == null ? "" : String.valueOf(body.get("model"));
            int concurrency = toInt(body.get("concurrency"), 1);
            int requests = toInt(body.get("requests"), 5);
            return Map.of("success", true, "data", infraService.runBenchmark(model, concurrency, requests));
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    @GetMapping("/benchmark/list")
    public Map<String, Object> benchmarkList() {
        try {
            return Map.of("success", true, "data", infraService.listBenchmarks());
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    // 故障排查
    @PostMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return Map.of("success", true, "data", infraService.diagnose());
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    // 推理平台状态
    @GetMapping("/platform/status")
    public Map<String, Object> platformStatus() {
        try {
            return Map.of("success", true, "data", infraService.platformStatus());
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    // 量化（占位，不下载模型）
    @PostMapping("/quantize/submit")
    public Map<String, Object> quantize(@RequestBody Map<String, Object> body) {
        try {
            String baseModel = body.get("baseModel") == null ? "unknown" : String.valueOf(body.get("baseModel"));
            String method = body.get("method") == null ? "awq" : String.valueOf(body.get("method"));
            int bits = toInt(body.get("bits"), 4);
            return Map.of("success", true, "data", infraService.quantizeSubmit(baseModel, method, bits));
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    @GetMapping("/quantize/list")
    public Map<String, Object> quantizeList() {
        try {
            return Map.of("success", true, "data", infraService.listQuantize());
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
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
