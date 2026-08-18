package com.inspection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AI Infra 能力看板后端（档1，零新增依赖）。
 * 覆盖：运行环境 / 脚本交付 / 文档聚合 / 性能评测(真实轻量发压) / 故障排查 / 推理平台状态 / 量化(占位不下载)。
 * 说明：环境/诊断默认可在巡检服务器(182)本地执行；若目标推理机不在本机，页面会优雅显示 N/A。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfraService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayService gatewayService;

    @Value("${infra.script-dir:/root/java-pro/mvp/scripts}")
    private String scriptDir;
    @Value("${infra.doc-dir:/root/java-pro/mvp/docs}")
    private String docDir;
    @Value("${infra.vllm-log:/root/java-pro/mvp/logs/vllm.log}")
    private String vllmLogPath;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS benchmark_run (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, model TEXT, concurrency INTEGER, requests INTEGER, " +
                "ok INTEGER DEFAULT 0, ttft_avg REAL, ttft_p99 REAL, throughput REAL, " +
                "status TEXT, created_at TEXT DEFAULT (datetime('now','localtime')))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS quantize_task (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, base_model TEXT, method TEXT, bits INTEGER, " +
                "status TEXT, progress INTEGER DEFAULT 0, output_path TEXT, log TEXT, " +
                "created_at TEXT DEFAULT (datetime('now','localtime')))");
        // 确保目录存在（避免空目录导致前端列表为空）
        new File(scriptDir).mkdirs();
        new File(docDir).mkdirs();
    }

    // ===================== 运行环境（只读） =====================
    public Map<String, Object> envInfo() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("docker", runCmd(8, "docker", "--version").get("output"));
        r.put("nvidia", runCmd(8, "nvidia-smi", "-L").get("output"));
        r.put("cuda", runCmd(8, "nvcc", "-V").get("output"));
        r.put("java", runCmd(8, "java", "-version").get("output"));
        r.put("capturedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        r.put("host", "localhost");
        r.put("note", "展示本服务运行主机的本地环境；推理机的 Docker/CUDA 需在其本机查看");
        return r;
    }

    // ===================== 脚本交付 =====================
    public List<Map<String, Object>> listScripts() {
        List<Map<String, Object>> list = listDirFiles(scriptDir, ".sh", ".py");
        for (Map<String, Object> f : list) {
            File sf = new File(scriptDir, String.valueOf(f.get("name")));
            try {
                Map<String, String> meta = parseScriptMeta(sf);
                f.put("desc", meta.getOrDefault("desc", ""));
                f.put("args", meta.getOrDefault("args", ""));
            } catch (Exception ignored) {
                f.put("desc", "");
                f.put("args", "");
            }
        }
        return list;
    }

    public Map<String, Object> scriptContent(String name) {
        File dir = new File(scriptDir);
        File target = new File(dir, name);
        try {
            if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)
                    && !target.getCanonicalPath().equals(dir.getCanonicalPath())) {
                return Map.of("success", false, "errorMsg", "非法脚本路径");
            }
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", "路径校验失败");
        }
        if (!target.exists()) return Map.of("success", false, "errorMsg", "脚本不存在: " + name);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String content = Files.readString(target.toPath(), StandardCharsets.UTF_8);
            out.put("success", true);
            out.put("name", name);
            out.put("content", content);
            out.put("size", Files.size(target.toPath()));
        } catch (Exception e) {
            out.put("success", false);
            out.put("errorMsg", e.getMessage());
        }
        return out;
    }

    public Map<String, Object> saveScript(String name, String content) {
        File dir = new File(scriptDir);
        File target = new File(dir, name);
        try {
            if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)
                    && !target.getCanonicalPath().equals(dir.getCanonicalPath())) {
                return Map.of("success", false, "errorMsg", "非法脚本路径");
            }
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", "路径校验失败");
        }
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
            return Map.of("success", true, "name", name, "size", Files.size(target.toPath()));
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", e.getMessage());
        }
    }

    /** 解析脚本头部元信息：# @desc / # @args（也兼容中文 说明/参数） */
    private Map<String, String> parseScriptMeta(File f) {
        Map<String, String> meta = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int n = Math.min(lines.size(), 40);
            for (int i = 0; i < n; i++) {
                String l = lines.get(i);
                if (!l.trim().startsWith("#")) continue;
                String body = l.replaceFirst("^\\s*#\\s*", "").trim();
                String lower = body.toLowerCase();
                if ((lower.startsWith("@desc") || lower.startsWith("desc") || body.startsWith("说明") || body.startsWith("描述"))
                        && !meta.containsKey("desc")) {
                    meta.put("desc", body.replaceFirst("(?i)(@desc|desc|说明|描述)[:：]?\\s*", "").trim());
                } else if ((lower.startsWith("@args") || lower.startsWith("args") || body.startsWith("参数") || body.startsWith("入参"))
                        && !meta.containsKey("args")) {
                    meta.put("args", body.replaceFirst("(?i)(@args|args|参数|入参)[:：]?\\s*", "").trim());
                }
                if (meta.containsKey("desc") && meta.containsKey("args")) break;
            }
        } catch (Exception ignored) {
        }
        return meta;
    }

    public Map<String, Object> runScript(String name, String params) {
        // 仅允许运行脚本目录内的文件，防命令注入
        File dir = new File(scriptDir);
        File target = new File(dir, name);
        try {
            if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)
                    && !target.getCanonicalPath().equals(dir.getCanonicalPath())) {
                return Map.of("success", false, "errorMsg", "非法脚本路径");
            }
        } catch (Exception e) {
            return Map.of("success", false, "errorMsg", "路径校验失败");
        }
        if (!target.exists()) return Map.of("success", false, "errorMsg", "脚本不存在: " + name);
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(target.getAbsolutePath());
        if (params != null && !params.isBlank()) {
            for (String p : params.trim().split("\\s+")) cmd.add(p);
        }
        Map<String, Object> exec = runCmd(30, cmd.toArray(new String[0]));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", !exec.containsKey("error") && !Boolean.TRUE.equals(exec.get("timeout")));
        out.put("output", exec.getOrDefault("output", ""));
        if (exec.containsKey("error")) out.put("errorMsg", exec.get("error"));
        if (Boolean.TRUE.equals(exec.get("timeout"))) out.put("errorMsg", "执行超时(30s)");
        return out;
    }

    // ===================== 文档聚合 =====================
    public List<Map<String, Object>> listDocs() {
        List<Map<String, Object>> files = listDirFiles(docDir, ".md", ".html");
        for (Map<String, Object> f : files) {
            String n = String.valueOf(f.get("name"));
            String cat = "文档";
            if (n.contains("网关")) cat = "推理网关";
            else if (n.contains("看板") || n.contains("Infra")) cat = "AI Infra";
            else if (n.contains("选型") || n.contains("速查")) cat = "部署选型";
            f.put("category", cat);
            f.put("title", n.replaceAll("\\.(md|html)$", ""));
        }
        return files;
    }

    // ===================== 性能评测（真实轻量发压） =====================
    public Map<String, Object> runBenchmark(String model, int concurrency, int requests) {
        if (requests <= 0) requests = 5;
        if (requests > 50) requests = 50; // 上限保护，避免打爆
        if (concurrency <= 0) concurrency = 1;
        if (concurrency > 20) concurrency = 20;
        if (model == null || model.isBlank()) model = "Qwen/Qwen2.5-1.5B-Instruct";

        String body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"用一句话介绍你自己。\"}],\"max_tokens\":64}";
        ExecutorService es = Executors.newFixedThreadPool(concurrency);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < requests; i++) {
            futures.add(es.submit(() -> gatewayService.internalChat(body)));
        }
        List<Long> lats = new ArrayList<>();
        int ok = 0;
        AtomicInteger lastStatus = new AtomicInteger(0);
        for (Future<Map<String, Object>> f : futures) {
            try {
                Map<String, Object> m = f.get(70, TimeUnit.SECONDS);
                int st = toInt(m.get("status"), 502);
                lastStatus.set(st);
                if (st == 200) {
                    ok++;
                    lats.add(toLong(m.get("latencyMs"), 0L));
                }
            } catch (Exception ignored) {
            }
        }
        long total = System.currentTimeMillis() - t0;
        es.shutdownNow();

        double ttftAvg = 0, ttftP99 = 0, throughput = 0;
        if (!lats.isEmpty()) {
            Collections.sort(lats);
            ttftAvg = lats.stream().mapToLong(Long::longValue).average().orElse(0);
            int idx = (int) Math.min(lats.size() - 1, Math.floor(lats.size() * 0.99));
            ttftP99 = lats.get(idx);
        }
        throughput = ok / Math.max(0.001, total / 1000.0);
        String status = (ok == requests) ? "ok" : (ok == 0 ? "failed" : "partial");
        String errMsg = (ok == 0) ? ("后端返回 " + lastStatus.get() + "，请确认 vLLM 可达") : "";

        jdbcTemplate.update(
                "INSERT INTO benchmark_run(model,concurrency,requests,ok,ttft_avg,ttft_p99,throughput,status) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                model, concurrency, requests, ok, ttftAvg, ttftP99, throughput, status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok > 0);
        out.put("model", model);
        out.put("requests", requests);
        out.put("ok", ok);
        out.put("ttftAvg", Math.round(ttftAvg * 10) / 10.0);
        out.put("ttftP99", Math.round(ttftP99 * 10) / 10.0);
        out.put("throughput", Math.round(throughput * 100) / 100.0);
        out.put("totalMs", total);
        out.put("status", status);
        if (!errMsg.isEmpty()) out.put("errorMsg", errMsg);
        return out;
    }

    public List<Map<String, Object>> listBenchmarks() {
        return jdbcTemplate.queryForList(
                "SELECT id,model,concurrency,requests,ok,ttft_avg,ttft_p99,throughput,status,created_at " +
                        "FROM benchmark_run ORDER BY id DESC LIMIT 20");
    }

    // ===================== 故障排查（脚本化：每个检查对应 scripts/diag/*.sh，可点链查看/编辑） =====================
    public Map<String, Object> diagnose() {
        List<Map<String, Object>> findings = new ArrayList<>();
        String baseUrl = gatewayService.resolveBaseUrl(null);

        // 检查项：相对脚本路径 -> 参数列表
        Map<String, List<String>> checks = new LinkedHashMap<>();
        checks.put("diag/diag_gpu.sh", Collections.emptyList());
        checks.put("diag/diag_dmesg.sh", Collections.emptyList());
        checks.put("diag/diag_tcp.sh", baseUrl != null && !baseUrl.isBlank()
                ? Collections.singletonList(baseUrl.replaceFirst("^https?://", "").replaceFirst("/+$", ""))
                : Collections.emptyList());
        checks.put("diag/diag_api.sh", baseUrl != null && !baseUrl.isBlank()
                ? Collections.singletonList(baseUrl.replaceFirst("/+$", ""))
                : Collections.emptyList());
        checks.put("diag/diag_disk.sh", Collections.emptyList());
        checks.put("diag/diag_vllm_log.sh", Collections.emptyList());

        for (Map.Entry<String, List<String>> e : checks.entrySet()) {
            String script = e.getKey();
            try {
                String params = String.join(" ", e.getValue());
                Map<String, Object> exec = runScript(script, params);
                if (!Boolean.TRUE.equals(exec.get("success"))) {
                    findings.add(makeFinding("error",
                            "检查脚本执行失败：" + script,
                            String.valueOf(exec.getOrDefault("errorMsg", "未知错误")),
                            script, Collections.emptyList()));
                    continue;
                }
                String raw = String.valueOf(exec.getOrDefault("output", "{}")).trim();
                if (raw.isEmpty()) {
                    findings.add(makeFinding("warning",
                            "检查脚本无输出：" + script,
                            "脚本返回空，请检查脚本内容。",
                            script, Collections.emptyList()));
                    continue;
                }
                // 取最后一行 JSON（前面可能有命令本身的 stdout）
                int lastBrace = raw.lastIndexOf('}');
                int firstBrace = raw.lastIndexOf('{', lastBrace);
                String jsonLine = (firstBrace >= 0 && lastBrace > firstBrace)
                        ? raw.substring(firstBrace, lastBrace + 1) : raw;
                JsonNode node = objectMapper.readTree(jsonLine);
                String level = node.has("level") ? node.get("level").asText() : "info";
                String message = node.has("message") ? node.get("message").asText() : script;
                String suggestion = node.has("suggestion") ? node.get("suggestion").asText() : "";
                List<String> fixes = buildFixes(script, level, message, baseUrl);
                findings.add(makeFinding(level, message, suggestion, script, fixes));
            } catch (Exception ex) {
                findings.add(makeFinding("error",
                        "检查解析失败：" + script,
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
                        script, Collections.emptyList()));
            }
        }

        String summary = "ok";
        for (Map<String, Object> f : findings) {
            String lvl = String.valueOf(f.get("level"));
            if ("error".equals(lvl)) { summary = "error"; break; }
            if ("warning".equals(lvl)) summary = "warn";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", summary);
        out.put("findings", findings);
        out.put("checkedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    private Map<String, Object> makeFinding(String level, String message, String suggestion, String script, List<String> fixes) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("level", level);
        f.put("message", message);
        f.put("suggestion", suggestion);
        f.put("script", script);
        f.put("fixes", fixes == null ? Collections.emptyList() : fixes);
        return f;
    }

    private List<String> buildFixes(String script, String level, String message, String baseUrl) {
        if ("ok".equals(level) || "info".equals(level)) return Collections.emptyList();
        String target = baseUrl != null ? baseUrl.replaceFirst("^https?://", "").replaceFirst("/+$", "") : "localhost:8000";
        String host = target.contains(":") ? target.substring(0, target.indexOf(":")) : target;
        String port = target.contains(":") ? target.substring(target.indexOf(":") + 1) : "8000";
        List<String> fixes = new ArrayList<>();
        switch (script) {
            case "diag/diag_tcp.sh":
                fixes.add("# 在 Windows 管理员 PowerShell 添加端口转发（把 8000 转发到 WSL2）");
                fixes.add("netsh interface portproxy add v4tov4 listenport=" + port + " connectaddress=<WSL2_IP> connectport=" + port);
                fixes.add("netsh interface portproxy show all");
                fixes.add("# 防火墙放行");
                fixes.add("netsh advfirewall firewall add rule name=\"vLLM-" + port + "\" dir=in action=allow protocol=tcp localport=" + port);
                break;
            case "diag/diag_api.sh":
                fixes.add("# 检查 vLLM 进程是否存活");
                fixes.add("ps -ef | grep -E 'vllm|python' | grep -v grep | head -5");
                fixes.add("# 检查端口监听");
                fixes.add("ss -ltnp | grep " + port);
                fixes.add("# 在 WSL2 内手动测试");
                fixes.add("curl -s http://localhost:" + port + "/v1/models | head -c 200");
                break;
            case "diag/diag_gpu.sh":
                fixes.add("# 降低 vLLM 显存占用参数");
                fixes.add("--gpu-memory-utilization 0.7 --max-model-len 4096");
                break;
            case "diag/diag_dmesg.sh":
                if (message != null && message.toLowerCase().contains("oom")) {
                    fixes.add("# OOM 时降低 batch 与上下文长度");
                    fixes.add("--max-num-seqs 128 --max-model-len 4096");
                }
                break;
            case "diag/diag_disk.sh":
                fixes.add("# 清理 7 天前日志");
                fixes.add("find /root/java-pro/mvp/logs -name '*.log' -mtime +7 -delete");
                fixes.add("# 清理悬空 Docker 镜像");
                fixes.add("docker image prune -a -f");
                break;
            case "diag/diag_vllm_log.sh":
                if (message != null && message.toLowerCase().contains("oom")) {
                    fixes.add("# OOM 时降低 max-model-len / gpu-memory-utilization");
                    fixes.add("--max-model-len 4096 --gpu-memory-utilization 0.7");
                } else if (message != null && message.toLowerCase().contains("body")) {
                    fixes.add("# 400 missing body 通常是转发层触发 HTTP/2 h2c 升级");
                    fixes.add("# 确保 Windows netsh 转发目标只使用 HTTP/1.1");
                    fixes.add("# 或在 WSL2 内用 nginx 做一层 HTTP/1.1 反向代理");
                }
                break;
        }
        return fixes;
    }

    // ===================== 推理平台状态 =====================
    public Map<String, Object> platformStatus() {
        Map<String, Object> m = gatewayService.modelsInternal();
        Map<String, Object> out = new LinkedHashMap<>();
        int st = toInt(m.get("status"), 502);
        out.put("status", st);
        out.put("running", st == 200);
        out.put("baseUrl", gatewayService.resolveBaseUrl(null));
        List<String> models = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(String.valueOf(m.getOrDefault("body", "{}")));
            if (root.has("data") && root.get("data").isArray()) {
                for (JsonNode n : root.get("data")) {
                    if (n.has("id")) models.add(n.get("id").asText());
                }
            }
        } catch (Exception ignored) {
        }
        out.put("models", models);
        if (st != 200) out.put("errorMsg", m.getOrDefault("errorMsg", "平台不可达"));
        return out;
    }

    // ===================== 量化（占位：不下载模型） =====================
    public Map<String, Object> quantizeSubmit(String baseModel, String method, int bits) {
        // 按约定：量化需要下载大模型权重，本次仅生成任务占位，不实际下载/量化。
        String log = "已记录量化任务占位。按约定未下载基座模型权重（避免大流量下载），未执行实际量化。\n" +
                "如需真实量化，请确认本地已具备基座模型且显存充足后手动执行。";
        jdbcTemplate.update(
                "INSERT INTO quantize_task(base_model,method,bits,status,progress,log) VALUES(?,?,?,?,?,?)",
                baseModel, method, bits, "skipped", 100, log);
        Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("taskId", id);
        out.put("status", "skipped");
        out.put("message", "已跳过模型下载（按约定），仅生成任务占位");
        return out;
    }

    public List<Map<String, Object>> listQuantize() {
        return jdbcTemplate.queryForList(
                "SELECT id,base_model,method,bits,status,progress,log,created_at FROM quantize_task ORDER BY id DESC LIMIT 20");
    }

    // ===================== 工具方法 =====================
    private List<Map<String, Object>> listDirFiles(String dir, String... exts) {
        List<Map<String, Object>> res = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of(dir))) {
            s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        for (String e : exts) if (n.endsWith(e)) return true;
                        return false;
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        try {
                            m.put("name", p.getFileName().toString());
                            m.put("path", p.toString());
                            m.put("size", Files.size(p));
                            m.put("modified", LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(p).toInstant(),
                                    java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                        } catch (Exception e) {
                            m.put("name", p.getFileName().toString());
                        }
                        res.add(m);
                    });
        } catch (Exception e) {
            log.warn("list dir failed: {}", dir, e);
        }
        return res;
    }

    private Map<String, Object> runCmd(long timeoutSec, String... cmd) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() < 6000) sb.append(line).append("\n");
                }
            }
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                r.put("timeout", true);
                r.put("output", sb + "\n[超时]");
            } else {
                r.put("exit", p.exitValue());
                r.put("output", sb.toString().trim());
            }
        } catch (Exception e) {
            r.put("error", true);
            r.put("output", "N/A (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")");
        }
        return r;
    }

    private int toInt(Object o, int d) {
        if (o == null) return d;
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return d;
        }
    }

    private long toLong(Object o, long d) {
        if (o == null) return d;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return d;
        }
    }
}
