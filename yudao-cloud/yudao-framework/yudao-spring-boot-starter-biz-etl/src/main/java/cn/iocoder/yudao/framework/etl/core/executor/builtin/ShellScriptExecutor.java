package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell 脚本节点（toolCode: shell-script）
 *
 * 配置：script 脚本内容；workingDirectory 工作目录；environmentVariables 环境变量对象。
 * Windows 用 cmd /c，其他平台用 /bin/sh -c；退出码非 0 视为失败。
 * 本地进程路径仍以服务身份执行，菜单权限不构成隔离；仅适用于可信脚本。
 *
 * @author Li
 */
public class ShellScriptExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "shell-script";

    private static final long DEFAULT_TIMEOUT_MINUTES = 60;

    private static final int LOG_LINE_LIMIT = 500;

    private static final int LOG_LINE_CHARS = 2048;

    /**
     * 初始化 {@code ShellScriptExecutor}及其父类状态。
     */
    public ShellScriptExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String script = requireConfig("script", "脚本内容");
        runProcess(buildCommand(script));
    }

    /**
     * 构建命令。
     */
    protected List<String> buildCommand(String script) {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
        } else {
            command.add("/bin/sh");
            command.add("-c");
        }
        command.add(script);
        return command;
    }

    /**
     * 判断当前运行环境是否为 Windows。
     */
    protected boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /**
     * 执行外部进程：stdout/stderr 逐行写入节点日志（限行），超时或非 0 退出码失败
     */
    protected void runProcess(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        String workingDirectory = config().getStr("workingDirectory");
        if (StrUtil.isNotBlank(workingDirectory)) {
            builder.directory(new File(workingDirectory));
        }
        JSONObject env = config().getJSONObject("environmentVariables");
        if (env != null) {
            env.forEach((key, value) -> builder.environment().put(key, String.valueOf(value)));
        }
        long timeoutMinutes = node.getOverTimeMinutes() != null && node.getOverTimeMinutes() > 0
                ? node.getOverTimeMinutes() : DEFAULT_TIMEOUT_MINUTES;
        logLine("启动进程：" + String.join(" ", command.subList(0, Math.min(2, command.size()))) + " ...");
        Process process = builder.start();
        // 读取 stdout 不承担截止时间检查；无输出及不换行输出均不能阻塞控制线程。
        BlockingQueue<String> logs = new ArrayBlockingQueue<>(64);
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread output = new Thread(() -> drainOutput(process, logs, readFailure), "etl-script-output-" + process.pid());
        output.setDaemon(true);
        output.start();
        Set<ProcessHandle> children = new LinkedHashSet<>();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes);
        try {
            while (true) {
                process.descendants().forEach(children::add);
                checkStop();
                emitLogs(logs);
                if (readFailure.get() != null) {
                    throw readFailure.get();
                }
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(String.format("脚本执行超时（%s 分钟）", timeoutMinutes));
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            checkStop();
            if (children.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new IllegalStateException("脚本退出后仍有子进程，已请求清理");
            }
            output.join(500);
            emitLogs(logs);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(String.format("脚本退出码 %s（非 0 视为失败）", process.exitValue()));
            }
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                // 先终止子进程再终止父进程，保留此前观察的句柄以覆盖父进程提前退出。
                process.descendants().forEach(children::add);
                children.forEach(child -> {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                    }
                });
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
                    if (System.nanoTime() >= cleanupDeadline) {
                        context.markTerminationUnconfirmed();
                        break;
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
            } finally {
                try {
                    process.getInputStream().close();
                    process.getOutputStream().close();
                    process.getErrorStream().close();
                    output.join(500);
                    if (output.isAlive() || process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
                        context.markTerminationUnconfirmed();
                        throw new IllegalStateException("脚本资源未确认退出，需核查外部副作用");
                    }
                } catch (IOException | InterruptedException ex) {
                    context.markTerminationUnconfirmed();
                    throw ex;
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        logLine("脚本执行成功");
    }

    /**
     * 生成日志集合。
     */
    private void emitLogs(BlockingQueue<String> logs) {
        String line;
        while ((line = logs.poll()) != null) {
            logLine(line);
        }
    }

    /** 纯 I/O 线程只入有界队列，业务监听器始终在原节点上下文中调用。 */
    private void drainOutput(Process process, BlockingQueue<String> logs, AtomicReference<IOException> failure) {
        try (var reader = new InputStreamReader(process.getInputStream(), Charset.defaultCharset())) {
            char[] chunk = new char[1024];
            StringBuilder line = new StringBuilder();
            int lines = 0;
            int count;
            while ((count = reader.read(chunk)) != -1) {
                for (int i = 0; i < count; i++) {
                    char ch = chunk[i];
                    if (ch == '\n') {
                        if (++lines <= LOG_LINE_LIMIT) {
                            logs.offer(line.toString());
                        } else if (lines == LOG_LINE_LIMIT + 1) {
                            logs.offer("（输出过长，后续省略...）");
                        }
                        line.setLength(0);
                    } else if (ch != '\r' && line.length() < LOG_LINE_CHARS) {
                        line.append(ch);
                    }
                }
            }
            if (!line.isEmpty() && lines < LOG_LINE_LIMIT) {
                logs.offer(line.toString());
            }
        } catch (IOException ex) {
            if (process.isAlive() && !context.isStopRequested()) {
                failure.set(ex);
            }
        }
    }

}
