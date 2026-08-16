package com.inspection.controller;

import com.inspection.service.InferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/inference")
public class InferenceController {

    @Autowired
    private InferenceService inferenceService;

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam(required = false) String baseUrl) {
        try {
            return ResponseEntity.ok(inferenceService.getStatus(baseUrl));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @GetMapping("/models")
    public ResponseEntity<?> models(@RequestParam(required = false) String baseUrl) {
        try {
            return ResponseEntity.ok(inferenceService.getModels(baseUrl));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> metrics(@RequestParam(required = false) String baseUrl) {
        try {
            return ResponseEntity.ok(inferenceService.getMetrics(baseUrl));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }

    @PostMapping("/restart")
    public ResponseEntity<?> restart() {
        try {
            return ResponseEntity.ok(inferenceService.restart());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "errorMsg", e.getMessage()));
        }
    }
}
