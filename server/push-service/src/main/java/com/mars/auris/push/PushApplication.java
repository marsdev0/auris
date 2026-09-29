package com.mars.auris.push;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * @author geyan
 * @date 2026/8/17
 */
@EnableScheduling
@SpringBootApplication
public class PushApplication {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(PushApplication.class, args);
    }
}
