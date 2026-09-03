package com.wecom.bridge.openclaw;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 外部命令执行抽象，便于测试中替换掉真实进程调用。
 */
public interface ProcessRunner {

    Result run(List<String> command, Duration timeout) throws IOException, InterruptedException;

    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }
}
