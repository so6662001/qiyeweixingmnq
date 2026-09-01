package com.wecom.bridge.support;

import com.wecom.bridge.openclaw.ProcessRunner;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 测试用假命令执行器：记录调用并返回预置结果。
 */
public class FakeProcessRunner implements ProcessRunner {

    private final List<List<String>> invocations = new ArrayList<>();
    private Function<List<String>, Result> handler =
            command -> new Result(0, "{\"ok\":true,\"messageId\":\"fake-1\"}", "", false);
    private IOException failure;

    public FakeProcessRunner respond(Function<List<String>, Result> handler) {
        this.handler = handler;
        return this;
    }

    public FakeProcessRunner respondOk(String stdout) {
        return respond(command -> new Result(0, stdout, "", false));
    }

    public FakeProcessRunner respondFailure(int exitCode, String stdout, String stderr) {
        return respond(command -> new Result(exitCode, stdout, stderr, false));
    }

    public FakeProcessRunner respondTimeout() {
        return respond(command -> new Result(-1, "", "命令超时", true));
    }

    public FakeProcessRunner failWith(IOException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public Result run(List<String> command, Duration timeout) throws IOException {
        invocations.add(List.copyOf(command));
        if (failure != null) {
            throw failure;
        }
        return handler.apply(command);
    }

    public List<List<String>> invocations() {
        return invocations;
    }

    public List<String> lastInvocation() {
        if (invocations.isEmpty()) {
            throw new IllegalStateException("没有命令被执行");
        }
        return invocations.get(invocations.size() - 1);
    }

    /** 取某个参数后面紧跟的值，例如 valueOf("--target")。 */
    public String valueOf(String flag) {
        List<String> command = lastInvocation();
        int index = command.indexOf(flag);
        if (index < 0 || index + 1 >= command.size()) {
            return null;
        }
        return command.get(index + 1);
    }

    public void reset() {
        invocations.clear();
        failure = null;
    }
}
