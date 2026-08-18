package com.inspection.controller;

import com.inspection.dto.ScanRequest;
import com.inspection.dto.BatchScanRequest;
import com.inspection.dto.BatchScanResult;
import com.inspection.service.BatchScanService;
import com.inspection.dto.ScanResult;
import com.inspection.service.InspectionService;
import com.inspection.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection")
public class InspectionController {

    @Autowired
    private InspectionService inspectionService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private BatchScanService batchScanService;

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody ScanRequest request) {
        try {
            ScanResult result = inspectionService.executeScan(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(required = false) String ip) {
        try {
            List<Map<String, Object>> history = inspectionService.getHistory(ip);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @GetMapping("/export/{id}")
    public ResponseEntity<?> exportReport(@PathVariable Long id) {
        try {
            Map<String, Object> record = inspectionService.getRecordById(id);
            if (record == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] docx = reportService.generateReport(record);
            String fileName = "inspection_report_" + record.get("ip") + "_" + record.get("scan_time").toString().replace(" ", "_").replace(":", "-") + ".docx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(docx);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            Map<String, Object> stats = inspectionService.getStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    // ===== 批量扫描接口 =====

    @PostMapping("/batch-scan")
    public ResponseEntity<?> batchScan(@RequestBody BatchScanRequest request) {
        try {
            String taskId = batchScanService.startBatchScan(request);
            Map<String, String> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("message", "批量扫描已启动");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @GetMapping("/batch-scan/progress/{taskId}")
    public ResponseEntity<?> getBatchProgress(@PathVariable String taskId) {
        try {
            BatchScanService.BatchProgress progress = batchScanService.getProgress(taskId);
            if (progress == null) {
                return ResponseEntity.ok(Map.of("done", true, "message", "任务不存在或已过期"));
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("total", progress.getTotal());
            resp.put("completed", progress.getCompleted());
            resp.put("successCount", progress.getSuccessCount());
            resp.put("failCount", progress.getFailCount());
            resp.put("percent", progress.getPercent());
            resp.put("done", progress.isDone());
            resp.put("results", progress.getResults());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @PostMapping("/batch-scan/cancel/{taskId}")
    public ResponseEntity<?> cancelBatchScan(@PathVariable String taskId) {
        try {
            BatchScanService.BatchProgress progress = batchScanService.getProgress(taskId);
            if (progress != null) progress.setCancelled(true);
            return ResponseEntity.ok(Map.of("message", "已取消"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }
}
