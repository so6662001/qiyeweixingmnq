package com.wecom.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 同时承载两部分能力：
 * <ul>
 *   <li>{@code com.wecom.simulator} —— 本地模拟器，用于联调与演示</li>
 *   <li>{@code com.wecom.bridge} —— 官方通道统一收件箱，真实收发消息</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "com.wecom")
public class WecomSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(WecomSimulatorApplication.class, args);
    }
}
