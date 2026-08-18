package com.inspection.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchScanRequest {
    /** IP范围，支持格式：10.0.0.1-10.0.0.50 或 10.0.0.0/24 */
    private String ipRange;
    /** SSH端口（统一） */
    private int port = 22;
    /** 用户名列表（按顺序尝试） */
    private List<String> usernames;
    /** 密码列表（按顺序尝试） */
    private List<String> passwords;
    /** 密钥路径（可选） */
    private String keyPath;
    /** 每个IP最大尝试的密码组合数（用户名×密码） */
    private int maxAttemptsPerIp = 10;
}
