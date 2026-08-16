package com.inspection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inspection.dto.ScanRequest;
import com.inspection.dto.ScanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final SshService sshService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public ScanResult executeScan(ScanRequest req) {
        ScanResult result = new ScanResult();
        result.setIp(req.getIp());
        result.setScanTime(LocalDateTime.now());

        try {
            String jsonOutput = sshService.executeInspection(
                    req.getIp(), req.getPort(), req.getUsername(),
                    req.getPassword(), req.getKeyPath());

            JsonNode root = objectMapper.readTree(jsonOutput);
            result.setSuccess(true);
            result.setRawJson(jsonOutput);

            if (root.has("hostname")) result.setHostname(root.get("hostname").asText());
            if (root.has("os")) result.setOsInfo(root.get("os").asText());
            if (root.has("cpu")) result.setCpuInfo(root.get("cpu").toString());
            if (root.has("memory")) result.setMemInfo(root.get("memory").toString());
            if (root.has("disk")) result.setDiskInfo(root.get("disk").toString());
            if (root.has("security")) result.setSecurityInfo(root.get("security").toString());

            saveToDb(result);
            upsertAsset(result);

        } catch (Exception e) {
            log.error("巡检失败: {}", req.getIp(), e);
            result.setSuccess(false);
            result.setErrorMsg(e.getMessage());
        }
        return result;
    }

    public List<Map<String, Object>> getHistory(String ip) {
        String sql = "SELECT * FROM inspection_record";
        List<Object> params = new ArrayList<>();
        if (ip != null && !ip.isEmpty()) {
            sql += " WHERE ip = ?";
            params.add(ip);
        }
        sql += " ORDER BY scan_time DESC LIMIT 100";
        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    public Map<String, Object> getRecordById(Long id) {
        String sql = "SELECT * FROM inspection_record WHERE id = ?";
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, id);
        return results.isEmpty() ? null : results.get(0);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        // 1. 服务器总数（去重IP）
        Integer totalServers = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT ip) FROM inspection_record", Integer.class);
        stats.put("totalServers", totalServers != null ? totalServers : 0);

        // 2. 在线/异常数（每个IP取最近一次巡检结果）
        String latestStatusSql =
                "SELECT t.ip, t.status " +
                "FROM inspection_record t " +
                "INNER JOIN (" +
                "    SELECT ip, MAX(scan_time) AS latest_time " +
                "    FROM inspection_record " +
                "    GROUP BY ip" +
                ") AS latest ON t.ip = latest.ip AND t.scan_time = latest.latest_time";
        List<Map<String, Object>> latestList = jdbcTemplate.queryForList(latestStatusSql);
        int online = 0, offline = 0;
        for (Map<String, Object> row : latestList) {
            String status = (String) row.get("status");
            if ("SUCCESS".equals(status)) online++;
            else offline++;
        }
        stats.put("online", online);
        stats.put("offline", offline);

        // 3. 最近7条成功巡检的CPU/内存使用率（用于趋势图）
        String trendSql =
                "SELECT ip, cpu_info, mem_info, scan_time " +
                "FROM inspection_record " +
                "WHERE status = 'SUCCESS' AND cpu_info IS NOT NULL AND mem_info IS NOT NULL " +
                "ORDER BY scan_time DESC LIMIT 7";
        List<Map<String, Object>> trendList = jdbcTemplate.queryForList(trendSql);
        List<Map<String, Object>> trends = new ArrayList<>();
        for (int i = trendList.size() - 1; i >= 0; i--) {
            Map<String, Object> row = trendList.get(i);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("ip", row.get("ip"));
            t.put("scanTime", row.get("scan_time"));
            try {
                Map<String, Object> cpu = objectMapper.readValue((String) row.get("cpu_info"), Map.class);
                Object usage = cpu.get("usage_percent");
                if (usage != null) t.put("cpuPercent", Double.parseDouble(usage.toString()));
            } catch (Exception e) { }
            try {
                Map<String, Object> mem = objectMapper.readValue((String) row.get("mem_info"), Map.class);
                Object usage = mem.get("usage_percent");
                if (usage != null) t.put("memPercent", Double.parseDouble(usage.toString()));
            } catch (Exception e) { }
            if (t.containsKey("cpuPercent") || t.containsKey("memPercent")) {
                trends.add(t);
            }
        }
        stats.put("trends", trends);

        // 4. 磁盘使用率 Top5
        String diskSql =
                "SELECT ip, disk_info FROM inspection_record " +
                "WHERE status = 'SUCCESS' AND disk_info IS NOT NULL " +
                "ORDER BY scan_time DESC";
        List<Map<String, Object>> diskList = jdbcTemplate.queryForList(diskSql);
        List<Map<String, Object>> diskTop = new ArrayList<>();
        Set<String> seenIps = new LinkedHashSet<>();
        for (Map<String, Object> row : diskList) {
            String ip = (String) row.get("ip");
            if (seenIps.contains(ip)) continue;
            seenIps.add(ip);
            try {
                Map<String, Object> disk = objectMapper.readValue((String) row.get("disk_info"), Map.class);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("ip", ip);
                Object usage = disk.get("usage_percent");
                if (usage == null) usage = disk.get("usage");
                if (usage != null) {
                    String us = usage.toString().replaceAll("[^0-9.-]", "");
                    if (!us.isEmpty()) d.put("usagePercent", Double.parseDouble(us));
                }
                d.put("total", disk.get("total"));
                d.put("used", disk.get("used"));
                diskTop.add(d);
            } catch (Exception e) { }
        }
        diskTop.sort((a, b) -> {
            Double pa = (Double) a.getOrDefault("usagePercent", 0.0);
            Double pb = (Double) b.getOrDefault("usagePercent", 0.0);
            return pb.compareTo(pa);
        });
        // 5. 告警数（磁盘使用率 > 80% 的服务器）
        int alert = 0;
        for (Map<String, Object> d : diskTop) {
            Object up = d.get("usagePercent");
            if (up instanceof Number && ((Number) up).doubleValue() > 80.0) alert++;
        }
        stats.put("alert", alert);
        if (diskTop.size() > 5) diskTop = diskTop.subList(0, 5);
        stats.put("diskTop", diskTop);

        return stats;
    }

    private void saveToDb(ScanResult result) {
        String sql =
            "INSERT INTO inspection_record "
            + "(ip, hostname, os_info, cpu_info, mem_info, disk_info, security_info, raw_json, status, error_msg, scan_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                result.getIp(), result.getHostname(), result.getOsInfo(),
                result.getCpuInfo(), result.getMemInfo(), result.getDiskInfo(),
                result.getSecurityInfo(), result.getRawJson(),
                result.isSuccess() ? "SUCCESS" : "FAIL",
                result.getErrorMsg(), result.getScanTime().toString()
        );
        log.info("巡检结果已入库: {}", result.getIp());
    }

    private void upsertAsset(ScanResult result) {
        String updateSql =
            "UPDATE asset_server SET "
            + "hostname = ?, os_name = ?, last_scan = ?, updated_at = datetime('now', 'localtime') "
            + "WHERE ip = ?";
        int updated = jdbcTemplate.update(updateSql,
                result.getHostname(), result.getOsInfo(),
                result.getScanTime().toString(), result.getIp());

        if (updated == 0) {
            String insertSql =
                "INSERT INTO asset_server (ip, hostname, os_name, last_scan, updated_at) "
                + "VALUES (?, ?, ?, ?, datetime('now', 'localtime'))";
            jdbcTemplate.update(insertSql,
                    result.getIp(), result.getHostname(), result.getOsInfo(),
                    result.getScanTime().toString());
        }
    }
}
