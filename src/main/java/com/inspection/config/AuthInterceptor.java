package com.inspection.config;

import com.inspection.controller.LoginController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private LoginController loginController;

    // 访客(guest) 允许的非 GET 接口：仅 LLM 演示，无内网副作用
    private static final List<String> GUEST_POST_ALLOW = Arrays.asList(
            "/api/llm/prompt", "/api/llm/complete", "/api/llm/structured"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String uri = request.getRequestURI();
        if ("/api/login".equals(uri)) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7);
        }
        String role = loginController.roleOf(token);
        if (role == null) {
            deny(response, "unauthorized");
            return false;
        }
        if ("admin".equals(role)) {
            return true;
        }
        // guest：GET 放行；白名单 POST 放行；其余写操作拦截
        if ("GET".equals(request.getMethod())) {
            return true;
        }
        if (GUEST_POST_ALLOW.contains(uri)) {
            return true;
        }
        deny(response, "demo_readonly");
        return false;
    }

    private void deny(HttpServletResponse response, String err) throws IOException {
        response.setStatus("demo_readonly".equals(err) ? HttpStatus.FORBIDDEN.value() : HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/octet-stream".equals(err) ? "application/json;charset=UTF-8" : "application/json;charset=UTF-8");
        if ("demo_readonly".equals(err)) {
            response.getWriter().write("{\"error\":\"demo_readonly\",\"message\":\"演示模式仅支持查看与 LLM 体验，不能执行写操作\"}");
        } else {
            response.getWriter().write("{\"error\":\"unauthorized\"}");
        }
    }
}
