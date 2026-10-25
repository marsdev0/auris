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
     * Full-chain UTC: aligned with the MySQL container (UTC) (timezone decision in P3 §9)
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
