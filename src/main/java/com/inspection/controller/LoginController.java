package com.inspection.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LoginController {

    @Value("${app.access.password:ai-infra-demo}")
    private String accessPassword;

    @Value("${app.guest.password:ai-infra-guest}")
    private String guestPassword;

    private static final String SALT = "ai-infra-mvp-salt-v1";

    private String hash(String role, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((role + ":" + password + ":" + SALT).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String makeToken(String role, String password) {
        return Base64.getEncoder().encodeToString((role + ":" + hash(role, password)).getBytes(StandardCharsets.UTF_8));
    }

    // 解析 token 返回角色：admin / guest / null
    public String roleOf(String token) {
        if (token == null) return null;
        try {
            String dec = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            int idx = dec.indexOf(':');
            if (idx < 0) return null;
            String role = dec.substring(0, idx);
            String h = dec.substring(idx + 1);
            if ("admin".equals(role) && h.equals(hash("admin", accessPassword))) return "admin";
            if ("guest".equals(role) && h.equals(hash("guest", guestPassword))) return "guest";
        } catch (Exception ignored) {
        }
        return null;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, String> body) {
        Map<String, Object> resp = new HashMap<>();
        String pwd = body == null ? null : body.get("password");
        if (pwd != null && pwd.equals(accessPassword)) {
            resp.put("token", makeToken("admin", accessPassword));
            resp.put("role", "admin");
        } else if (pwd != null && pwd.equals(guestPassword)) {
            resp.put("token", makeToken("guest", guestPassword));
            resp.put("role", "guest");
        } else {
            resp.put("error", "invalid_password");
        }
        return resp;
    }
}
