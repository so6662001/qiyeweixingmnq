package com.wecom.simulator.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 统一异步入口，确保非 Spring 代理对象也能安全投递后台任务。
 */
@Component
public class AsyncJobs {

    @Async
    public void submit(Runnable task) {
        task.run();
    }
}
