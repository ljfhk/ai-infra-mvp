package com.inspection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MvpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MvpApplication.class, args);
        System.out.println("===== AI Infra MVP 启动成功 =====");
        System.out.println("访问地址: http://localhost:8080");
    }
}
