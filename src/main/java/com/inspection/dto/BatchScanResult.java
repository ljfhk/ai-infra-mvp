package com.inspection.dto;

import lombok.Data;

@Data
public class BatchScanResult {
    private String ip;
    private int port;
    private String hostname;
    private String status;       // SUCCESS / FAILED / AUTH_FAILED / TIMEOUT / SKIPPED
    private String message;
    private String credential;   // 成功时显示使用的用户名/密码（脱敏）
    private String cpuInfo;
    private String memInfo;
    private String diskInfo;
    private String osInfo;
    private String securityInfo;
    private String rawJson;
    private String scanTime;
}
