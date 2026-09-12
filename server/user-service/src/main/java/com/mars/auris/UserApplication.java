package com.mars.auris;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * @author geyan
 * @date 2026/8/17
 */
@SpringBootApplication
public class UserApplication {

    /**
     * 全链路 UTC:与 MySQL 容器(UTC)对齐(时区决策见 P3 §9)
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
