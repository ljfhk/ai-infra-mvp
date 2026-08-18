package com.inspection.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ScanResult {
    private boolean success;
    private String ip;
    private String hostname;
    private String osInfo;
    private String cpuInfo;
    private String memInfo;
    private String diskInfo;
    private String securityInfo;
    private String rawJson;
    private String errorMsg;
    private LocalDateTime scanTime;
}
