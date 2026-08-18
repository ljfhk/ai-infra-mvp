package com.inspection.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class ScanRequest {
    @NotBlank(message = "IP地址不能为空")
    private String ip;

    private Integer port = 22;

    @NotBlank(message = "用户名不能为空")
    private String username;

    // 密码认证
    private String password;

    // 密钥认证路径
    private String keyPath;
}
