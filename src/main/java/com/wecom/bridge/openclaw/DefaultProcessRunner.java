package com.wecom.bridge.openclaw;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultProcessRunner implements ProcessRunner {

    @Override
    public Result run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();

        // 先关闭标准输入，避免子进程等待输入而挂住
        process.getOutputStream().close();

        String stdout;
        String stderr;
        try (InputStream out = process.getInputStream(); InputStream err = process.getErrorStream()) {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, "", "命令超时", true);
            }
            stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Result(process.exitValue(), stdout, stderr, false);
    }
}
