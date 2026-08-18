package com.inspection.service;

import com.inspection.dto.BatchScanRequest;
import com.inspection.dto.BatchScanResult;
import com.inspection.dto.ScanRequest;
import com.inspection.dto.ScanResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class BatchScanService {

    private final SshService sshService;
    private final InspectionService inspectionService;

    @Value("${inspection.ssh.timeout:30000}")
    private int sshTimeout;

    // 存储批量扫描任务进度（taskId -> progress）
    private final Map<String, BatchProgress> progressMap = new ConcurrentHashMap<>();

    public BatchScanService(SshService sshService, InspectionService inspectionService) {
        this.sshService = sshService;
        this.inspectionService = inspectionService;
    }

    /**
     * 启动批量扫描（异步）
     * @return taskId 用于查询进度
     */
    public String startBatchScan(BatchScanRequest req) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        List<String> ips = parseIpRange(req.getIpRange());
        BatchProgress progress = new BatchProgress(ips.size());
        progressMap.put(taskId, progress);

        log.info("启动批量扫描 taskId={}, 共{}个IP", taskId, ips.size());

        // 异步执行
        new Thread(() -> runBatchScan(taskId, req, ips, progress)).start();

        return taskId;
    }

    public BatchProgress getProgress(String taskId) {
        return progressMap.get(taskId);
    }

    public void removeProgress(String taskId) {
        progressMap.remove(taskId);
    }

    private void runBatchScan(String taskId, BatchScanRequest req, List<String> ips, BatchProgress progress) {
        List<String> usernames = req.getUsernames() != null && !req.getUsernames().isEmpty()
                ? req.getUsernames() : Collections.singletonList("root");
        List<String> passwords = req.getPasswords() != null ? req.getPasswords() : Collections.emptyList();
        int port = req.getPort() > 0 ? req.getPort() : 22;

        for (String ip : ips) {
            if (progress.isCancelled()) {
                log.info("批量扫描 taskId={} 被取消", taskId);
                break;
            }

            BatchScanResult result = new BatchScanResult();
            result.setIp(ip);
            result.setPort(port);
            result.setStatus("FAILED");
            result.setScanTime(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

            try {
                // 1. 先Ping测试（可选，快速跳过不可达IP）
                if (!isReachable(ip, 3000)) {
                    result.setStatus("TIMEOUT");
                    result.setMessage("主机不可达（Ping超时）");
                    progress.addResult(result);
                    progress.increment();
                    continue;
                }

                // 2. 尝试各个用户名/密码组合
                boolean connected = false;
                String successUser = null, successPass = null;

                // 优先尝试密钥认证
                if (req.getKeyPath() != null && !req.getKeyPath().isEmpty()) {
                    try {
                        for (String user : usernames) {
                            ScanRequest probe = new ScanRequest();
                            probe.setIp(ip);
                            probe.setPort(port);
                            probe.setUsername(user);
                            probe.setKeyPath(req.getKeyPath());
                            sshService.executeInspection(ip, port, user, null, req.getKeyPath());
                            connected = true;
                            successUser = user;
                            break;
                        }
                    } catch (Exception e) {
                        log.debug("密钥认证失败 ip={}", ip);
                    }
                }

                // 尝试密码认证
                if (!connected) {
                    int attempts = 0;
                    outer:
                    for (String user : usernames) {
                        for (String pass : passwords) {
                            if (progress.isCancelled()) break outer;
                            attempts++;
                            if (req.getMaxAttemptsPerIp() > 0 && attempts > req.getMaxAttemptsPerIp()) {
                                break outer;
                            }
                            try {
                                sshService.executeInspection(ip, port, user, pass, null);
                                connected = true;
                                successUser = user;
                                successPass = pass;
                                break outer;
                            } catch (Exception e) {
                                String msg = e.getMessage();
                                if (msg != null && (msg.contains("Auth fail") || msg.contains("auth"))) {
                                    log.debug("认证失败 ip={} user={}", ip, user);
                                    continue;
                                }
                                // 其他错误（连接拒绝等）不再重试
                                break outer;
                            }
                        }
                    }
                }

                if (!connected) {
                    result.setStatus("AUTH_FAILED");
                    result.setMessage("所有用户名/密码组合均认证失败");
                    progress.addResult(result);
                    progress.increment();
                    continue;
                }

                // 3. 执行巡检
                ScanRequest scanReq = new ScanRequest();
                scanReq.setIp(ip);
                scanReq.setPort(port);
                scanReq.setUsername(successUser);
                scanReq.setPassword(successPass);
                scanReq.setKeyPath(req.getKeyPath());

                ScanResult scanResult = inspectionService.executeScan(scanReq);

                // 4. 填充结果
                result.setStatus("SUCCESS");
                result.setMessage("成功（用户:" + successUser + "）");
                result.setHostname(scanResult.getHostname());
                result.setOsInfo(scanResult.getOsInfo());
                result.setCpuInfo(scanResult.getCpuInfo() != null ? scanResult.getCpuInfo().toString() : null);
                result.setMemInfo(scanResult.getMemInfo() != null ? scanResult.getMemInfo().toString() : null);
                result.setDiskInfo(scanResult.getDiskInfo() != null ? scanResult.getDiskInfo().toString() : null);
                result.setSecurityInfo(scanResult.getSecurityInfo() != null ? scanResult.getSecurityInfo().toString() : null);
                result.setRawJson(scanResult.getRawJson());

                log.info("批量扫描成功: {}@{}", successUser, ip);

            } catch (Exception e) {
                result.setStatus("FAILED");
                result.setMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(200, e.getMessage().length())) : "未知错误");
                log.warn("批量扫描失败: ip={}, err={}", ip, e.getMessage());
            }

            progress.addResult(result);
            progress.increment();
        }

        progress.setDone(true);
        log.info("批量扫描完成 taskId={}, 成功={}, 失败={}", taskId, progress.getSuccessCount(), progress.getFailCount());
    }

    /** 解析IP范围，返回IP列表 */
    public static List<String> parseIpRange(String range) {
        List<String> ips = new ArrayList<>();
        try {
            if (range.contains("/")) {
                // CIDR格式：10.0.0.0/24
                String[] parts = range.split("/");
                String baseIp = parts[0];
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 24 || prefix > 30) {
                    throw new IllegalArgumentException("仅支持 /24-/30 网段");
                }
                String[] octets = baseIp.split("\\.");
                int base = Integer.parseInt(octets[3]);
                int count = (int) Math.pow(2, 32 - prefix);
                for (int i = 1; i < count; i++) {  // 跳过网络地址
                    int ipOctet = base + i;
                    if (ipOctet > 254) break;      // 跳过广播地址
                    ips.add(octets[0] + "." + octets[1] + "." + octets[2] + "." + ipOctet);
                }
            } else if (range.contains("-")) {
                // 范围格式：10.0.0.1-10.0.0.50 或 10.0.0.1-50
                String startIp, endIp;
                if (range.contains("--")) {
                    // 已经是完整格式
                    String[] parts = range.split("--");
                    startIp = parts[0];
                    endIp = parts[1];
                } else {
                    String[] parts = range.split("-");
                    if (parts[1].contains(".")) {
                        startIp = parts[0];
                        endIp = parts[1];
                    } else {
                        // 简写：10.0.0.1-50
                        String[] octets = parts[0].split("\\.");
                        startIp = parts[0];
                        endIp = octets[0] + "." + octets[1] + "." + octets[2] + "." + parts[1];
                    }
                }
                String[] start = startIp.split("\\.");
                String[] end = endIp.split("\\.");
                int s = Integer.parseInt(start[3]);
                int e = Integer.parseInt(end[3]);
                for (int i = s; i <= e; i++) {
                    ips.add(start[0] + "." + start[1] + "." + start[2] + "." + i);
                }
            } else if (range.contains(",")) {
                // 逗号分隔：10.0.0.1,10.0.0.2,10.0.0.3
                String[] arr = range.split(",");
                for (String ip : arr) {
                    ips.add(ip.trim());
                }
            } else {
                ips.add(range.trim());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("IP范围格式错误: " + range + "，错误信息: " + e.getMessage());
        }
        return ips;
    }

    private boolean isReachable(String ip, int timeoutMs) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isReachable(timeoutMs);
        } catch (Exception e) {
            return false;
        }
    }

    /** 批量扫描进度对象 */
    public static class BatchProgress {
        private final int total;
        private final AtomicInteger completed = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failCount = new AtomicInteger(0);
        private final List<BatchScanResult> results = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean done = false;
        private volatile boolean cancelled = false;

        public BatchProgress(int total) { this.total = total; }

        public void increment() {
            int c = completed.incrementAndGet();
            // 不需要额外操作
        }
        public void addResult(BatchScanResult r) {
            results.add(r);
            if ("SUCCESS".equals(r.getStatus())) successCount.incrementAndGet();
            else failCount.incrementAndGet();
        }
        public int getTotal() { return total; }
        public int getCompleted() { return completed.get(); }
        public int getSuccessCount() { return successCount.get(); }
        public int getFailCount() { return failCount.get(); }
        public double getPercent() { return total == 0 ? 0 : Math.round(completed.get() * 1000.0 / total) / 10.0; }
        public List<BatchScanResult> getResults() { return new ArrayList<>(results); }
        public boolean isDone() { return done; }
        public void setDone(boolean d) { this.done = d; }
        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean c) { this.cancelled = c; }
    }
}
