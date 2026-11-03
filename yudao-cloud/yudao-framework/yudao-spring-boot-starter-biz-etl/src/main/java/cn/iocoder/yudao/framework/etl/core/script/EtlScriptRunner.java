package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/**
 * 只运行固定 ETL 工作进程，不继承应用类路径、环境、Spring
 * 上下文或凭据。部署仍须隔离操作系统身份、文件系统和网络。
 */
public final class EtlScriptRunner {

    public enum Language {JAVA, JS, CHECK_JAVA, QDATA_JAVA, QDATA_TOKEN}

    private final EtlScriptWorkerPolicy policy;

    private final Semaphore slots = new Semaphore(4);

    /**
     * 校验构造参数并初始化 {@code EtlScriptRunner}。
     */
    public EtlScriptRunner(EtlScriptWorkerPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    /**
     * 返回策略。
     */
    public EtlScriptWorkerPolicy policy() {
        return policy;
    }

    /**
     * 返回当前已占用的工作进程槽位数量。
     */
    public int activeWorkers() {
        return 4 - slots.availablePermits();
    }

    /** 仅提供技术计数；不暴露数据源、端点、身份或本地路径。 */
    public record RecoveryReport(int scanned, int active, int cleaned, int pending, int incompatible) {

    }

    /**
     * 初始化恢复。
     */
    public void initializeRecovery() {
        if (recoveryEnabled() && policy.docker().recovery().recoverOnStartup()) {
            recoverOrphanedWorkers();
        }
    }

    /**
     * 根据持久化账本核对并清理孤立的 Docker 工作容器。
     */
    public RecoveryReport recoverOrphanedWorkers() {
        if (!recoveryEnabled()) {
            return new RecoveryReport(0, 0, 0, 0, 0);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                policy.docker().recovery().reconciliationTimeoutMillis());
        var ledger = new EtlDockerWorkerLedger(policy);
        var report = ledger.scan(registration -> System.nanoTime() < deadline
                && new EtlDockerWorker(policy, List.of()).reconcile(registration, deadline));
        return new RecoveryReport(report.scanned(), report.active(), report.cleaned(), report.pending(),
                report.incompatible());
    }

    /**
     * 判断受控 Docker 工作进程的恢复能力是否已配置启用。
     */
    private boolean recoveryEnabled() {
        return policy.enabled() && policy.mode() == EtlScriptWorkerPolicy.Mode.DOCKER && policy.docker()
                .recovery() != null;
    }

    /** 在任何流水线节点启动前失败；此检查针对可信配置，绝不针对用户源码。 */
    public void ensureAvailable() {
        List<Path> entries = workerEntries();
        if (policy.mode() == EtlScriptWorkerPolicy.Mode.DOCKER) {
            new EtlDockerWorker(policy, entries).preflight();
        }
    }

    /**
     * 检查Java。
     */
    public List<String> checkJava(String source) {
        try (var session = open(Language.CHECK_JAVA, source, () -> {
        }, () -> {
        })) {
            return session.compilationErrors();
        } catch (IllegalStateException failure) {
            return List.of(failure.getMessage());
        }
    }

    /**
     * 打开会话并准备后续访问。
     */
    public Session open(Language language, String source, Runnable checkStop, Runnable terminationUnconfirmed) {
        return open(language, source, checkStop, terminationUnconfirmed, null);
    }
    /** 网络能力仅由受信调用方传入执行器，默认调用方仍没有HTTP桥。 */
    public Session open(Language language, String source, Runnable checkStop, Runnable terminationUnconfirmed, EtlScriptWorkerHttp.Executor httpExecutor) {
        Objects.requireNonNull(language);
        Objects.requireNonNull(checkStop);
        Objects.requireNonNull(terminationUnconfirmed);
        List<Path> entries = workerEntries();
        if (httpExecutor != null && !containsClass(entries, EtlScriptWorkerHttp.class.getName())) throw new IllegalStateException("隔离worker快照缺少受管HTTP桥，请更新固定worker包");
        EtlDockerWorker docker = null;
        if (policy.mode() == EtlScriptWorkerPolicy.Mode.DOCKER) {
            docker = new EtlDockerWorker(policy, entries);
            docker.preflight();
        }
        if (source == null || source.isBlank() || source.length() > policy.maxSourceBytes()
                || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > policy.maxSourceBytes()) {
            throw new IllegalStateException("脚本源码为空或超过 worker 字节上限");
        }
        checkStop.run();
        if (!slots.tryAcquire()) {
            throw new IllegalStateException("ETL script worker 并发资源已满");
        }
        try {
            return new Session(language, source, entries, docker, checkStop, terminationUnconfirmed, httpExecutor);
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    /**
     * 校验运行策略后构建工作进程的启动入口配置。
     */
    private List<Path> workerEntries() {
        if (!policy.enabled()) {
            throw new IllegalStateException("ETL Java/JavaScript worker 未启用，请由管理员配置受控运行环境");
        }
        Path java = javaExecutable();
        Path javac = java.getParent().resolve(isWindows() ? "javac.exe" : "javac");
        if (policy.mode() == EtlScriptWorkerPolicy.Mode.CHILD_JVM && (!Files.isRegularFile(
                java) || !Files.isRegularFile(javac))) {
            throw new IllegalStateException("ETL script worker 需要完整 JDK 运行环境");
        }
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        if (!policy.classpathEntries().isEmpty()) {
            entries.addAll(policy.classpathEntries());
        } else {
            for (Class<?> type : List.of(EtlScriptWorker.class, BoundedRowCodec.class, JSONUtil.class,
                    com.alibaba.fastjson2.JSONObject.class,
                    NashornScriptEngineFactory.class)) {
                entries.add(codeLocation(type));
            }
            for (String type : List.of("org.objectweb.asm.ClassReader", "org.objectweb.asm.commons.InstructionAdapter",
                    "org.objectweb.asm.tree.ClassNode", "org.objectweb.asm.tree.analysis.Analyzer",
                    "org.objectweb.asm.util.Printer")) {
                try {
                    entries.add(codeLocation(Class.forName(type, false, EtlScriptRunner.class.getClassLoader())));
                } catch (ClassNotFoundException failure) {
                    throw new IllegalStateException("ETL script worker 缺少 Nashorn 依赖");
                }
            }
        }
        var resolved = new ArrayList<Path>();
        for (Path entry : entries) {
            try {
                if (!entry.isAbsolute() || entry.toString().contains("*") || entry.toString()
                        .contains(File.pathSeparator)) {
                    throw new IOException("Invalid worker classpath");
                }
                Path canonical = entry.toRealPath();
                if (!Files.isDirectory(canonical) && !(Files.isRegularFile(canonical) && canonical.toString()
                        .endsWith(".jar"))) {
                    throw new IOException("Invalid worker classpath");
                }
                resolved.add(canonical);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException("ETL script worker classpath 必须是可信本地目录或单个 JAR");
            }
        }
        for (String name : List.of(EtlScriptWorker.class.getName(), EtlScriptProtocol.class.getName(),
                EtlJavaCompiler.class.getName(),
                EtlJavaScript.class.getName(), tech.qiantong.qdata.common.core.base.EtlBase.class.getName(),
                com.alibaba.fastjson2.JSONObject.class.getName(), BoundedRowCodec.class.getName(), JSONUtil.class.getName(),
                NashornScriptEngineFactory.class.getName(),
                "org.objectweb.asm.ClassReader", "org.objectweb.asm.commons.InstructionAdapter",
                "org.objectweb.asm.tree.ClassNode",
                "org.objectweb.asm.tree.analysis.Analyzer", "org.objectweb.asm.util.Printer")) {
            if (!containsClass(resolved, name)) {
                throw new IllegalStateException("ETL script worker classpath 缺少固定 worker 或运行依赖");
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * 取得指定类所属的本地代码文件位置。
     */
    private static Path codeLocation(Class<?> type) {
        try {
            var location = type.getProtectionDomain().getCodeSource().getLocation();
            if (!"file".equals(location.getProtocol())) {
                throw new URISyntaxException("worker", "Unsupported package layout");
            }
            return Path.of(location.toURI()).toAbsolutePath();
        } catch (URISyntaxException | RuntimeException failure) {
            throw new IllegalStateException(
                    "ETL script worker 不支持嵌套 JAR classpath，请配置独立本地 worker classpathEntries");
        }
    }

    /**
     * 判断是否包含类。
     */
    private static boolean containsClass(List<Path> entries, String className) {
        String name = className.replace('.', '/') + ".class";
        for (Path entry : entries) {
            if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve(name))) {
                return true;
            }
            if (!Files.isDirectory(entry)) {
                try (var jar = new JarFile(entry.toFile())) {
                    if (jar.getJarEntry(name) != null) {
                        return true;
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException("ETL script worker JAR 无法读取");
                }
            }
        }
        return false;
    }

    /**
     * 解析当前受控工作进程使用的 Java 可执行文件。
     */
    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toAbsolutePath();
    }

    /**
     * 判断当前运行环境是否为 Windows。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    public final class Session implements AutoCloseable {
        private final EtlScriptWorkerHttp.Executor httpExecutor;
        private int httpRequests;

        private final Runnable checkStop, terminationUnconfirmed;

        private final Set<ProcessHandle> descendants = new LinkedHashSet<>();

        private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "etl-worker-ipc");
            thread.setDaemon(true);
            return thread;
        });

        private final AtomicBoolean stderrExceeded = new AtomicBoolean();

        private final AtomicBoolean stderrFailed = new AtomicBoolean();

        private Process process;

        private Thread stderrReader;

        private Path workingDirectory;

        private Object directoryKey;

        private final EtlDockerWorker dockerWorker;

        private volatile boolean closed;

        private List<String> compilationErrors = List.of();

        /**
         * 校验构造参数并初始化 {@code Session}。
         */
        private Session(Language language, String source, List<Path> entries, EtlDockerWorker dockerWorker,
                Runnable checkStop, Runnable terminationUnconfirmed, EtlScriptWorkerHttp.Executor httpExecutor) {
            this.httpExecutor = httpExecutor;
            this.checkStop = Objects.requireNonNull(checkStop);
            this.terminationUnconfirmed = Objects.requireNonNull(terminationUnconfirmed);
            this.dockerWorker = dockerWorker;
            try {
                if (dockerWorker != null) {
                    process = dockerWorker.start();
                } else {
                    workingDirectory = Files.createTempDirectory("etl-worker-").toAbsolutePath();
                    directoryKey = Files.readAttributes(workingDirectory, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).fileKey();
                    var builder = new ProcessBuilder(javaExecutable().toString(), "-Xms32m",
                            "-Xmx" + policy.heapMegabytes() + "m",
                            "-XX:MaxMetaspaceSize=128m", "-XX:+ExitOnOutOfMemoryError", "-Dfile.encoding=UTF-8",
                            "-Djava.io.tmpdir=" + workingDirectory, "-cp", String.join(File.pathSeparator,
                            entries.stream().map(Path::toString).toList()), EtlScriptWorker.class.getName());
                    builder.environment().clear();
                    // Windows 可能需要此操作系统路径来加载 JVM 原生库；不传播应用变量。
                    if (isWindows()) {
                        String systemRoot = System.getenv("SystemRoot");
                        if (systemRoot != null && !systemRoot.isBlank()) {
                            builder.environment().put("SystemRoot", systemRoot);
                        }
                    }
                    builder.directory(workingDirectory.toFile());
                    process = builder.start();
                }
                stderrReader = new Thread(this::drainStderr, "etl-worker-stderr-" + process.pid());
                stderrReader.setDaemon(true);
                stderrReader.start();
                checkStop.run(); // 有界启动期间的取消必须在发送任何用户源码前生效。
                byte[] init = EtlScriptProtocol.encode(policy.maxSourceBytes() + 512, out -> {
                    out.writeByte(EtlScriptProtocol.INIT);
                    out.writeInt(1);
                    EtlScriptProtocol.writeText(out, language.name(), 16);
                    EtlScriptProtocol.writeText(out, source, policy.maxSourceBytes());
                    out.writeInt(policy.maxBatchBytes());
                    out.writeInt(policy.maxResultBytes());
                    out.writeInt(policy.maxBatchRows());
                    out.writeInt(policy.maxResultRows());
                    out.writeInt(policy.maxLogBytes());
                    if (httpExecutor != null) out.writeBoolean(true);
                });
                try (var response = exchange(init, policy.startupTimeoutMillis())) {
                    int status = response.readUnsignedByte();
                    if (language == Language.CHECK_JAVA && status == EtlScriptProtocol.CHECK_ERRORS) {
                        int count = response.readInt();
                        if (count < 0 || count > 20) {
                            throw new IOException("Invalid compiler response");
                        }
                        var errors = new ArrayList<String>(count);
                        for (int i = 0; i < count; i++) {
                            errors.add(EtlScriptProtocol.readText(response, 256));
                        }
                        EtlScriptProtocol.requireEnd(response);
                        compilationErrors = List.copyOf(errors);
                    } else {
                        requireOkay(response, status);
                    }
                }
            } catch (Throwable failure) {
                try {
                    close();
                } catch (RuntimeException cleanup) {
                    throw cleanup;
                }
                if (failure instanceof IllegalStateException safe) {
                    throw safe;
                }
                throw new IllegalStateException("ETL script worker 启动或协议校验失败");
            }
        }

        /**
         * 返回编译错误集合。
         */
        public List<String> compilationErrors() {
            return compilationErrors;
        }

        /**
         * 返回当前工作进程的标识。
         */
        public long processId() {
            return process.pid();
        }

        /** 供部署诊断使用的容器身份；Docker 模式下 processId 指向附加客户端。 */
        public String containerId() {
            return dockerWorker == null ? null : dockerWorker.containerId();
        }

        /**
         * 向受控工作进程发送执行输入，并读取当前脚本运行的协议响应。
         */
        public List<Map<String, Object>> execute(List<Map<String, Object>> rows) {
            if (closed) {
                throw new IllegalStateException("ETL script worker 已关闭");
            }
            try {
                byte[] request = EtlScriptProtocol.encode(policy.maxBatchBytes(), out -> {
                    out.writeByte(EtlScriptProtocol.BATCH);
                    EtlScriptProtocol.writeRows(out, rows, policy.maxBatchRows(), policy.maxBatchBytes());
                });
                try (var response = exchange(request, policy.batchTimeoutMillis())) {
                    int status = response.readUnsignedByte();
                    if (status != EtlScriptProtocol.OK) {
                        failResponse(response, status);
                    }
                    var result = EtlScriptProtocol.readRows(response, policy.maxResultRows(), policy.maxResultBytes());
                    EtlScriptProtocol.requireEnd(response);
                    return result;
                }
            } catch (Exception failure) {
                // 截止时间、取消或协议失败会使会话失效，并立即终止会话。
                // 清理不得依赖消费方最终到达 try-with-resources 边界。
                close();
                if (failure instanceof IllegalStateException safe) {
                    throw safe;
                }
                throw new IllegalStateException("ETL script worker 输入或输出不符合有界数据契约");
            }
        }

        /**
         * 返回当前交换。
         */
        private DataInputStream exchange(byte[] request, long timeoutMillis) {
            Future<DataInputStream> future = io.submit(() -> {
                EtlScriptProtocol.writeFrame(process.getOutputStream(), request);
                while (true) {
                    var response = EtlScriptProtocol.readFrame(process.getInputStream(), policy.maxResultBytes());
                    response.mark(1); int operation = response.readUnsignedByte(); response.reset();
                    if (operation != EtlScriptWorkerHttp.REQUEST) return response;
                    response.readUnsignedByte(); var requestHttp = EtlScriptWorkerHttp.read(response); EtlScriptProtocol.requireEnd(response);
                    if (httpExecutor == null || ++httpRequests > 32) throw new IOException("Script HTTP capability unavailable or request budget exceeded");
                    byte[] reply;
                    try { var result = httpExecutor.exchange(requestHttp); reply = EtlScriptProtocol.encode(policy.maxResultBytes(), out -> { out.writeByte(EtlScriptWorkerHttp.REPLY); out.writeBoolean(true); EtlScriptWorkerHttp.write(out, result); }); }
                    catch (RuntimeException failure) { reply = EtlScriptProtocol.encode(16, out -> { out.writeByte(EtlScriptWorkerHttp.REPLY); out.writeBoolean(false); }); }
                    EtlScriptProtocol.writeFrame(process.getOutputStream(), reply);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            try {
                while (true) {
                    process.descendants().forEach(descendants::add);
                    checkStop.run();
                    if (stderrExceeded.get() || stderrFailed.get()) {
                        throw new IllegalStateException("ETL script worker 日志或读取超限");
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new IllegalStateException("ETL script worker 执行超过截止时间");
                    }
                    try {
                        return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                                TimeUnit.NANOSECONDS);
                    } catch (TimeoutException waitAgain) { /* 即使工作进程没有输出，也要观察取消信号 */ }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ETL script worker 执行已停止");
            } catch (ExecutionException failure) {
                throw new IllegalStateException("ETL script worker 意外退出或协议无效");
            } finally {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        /**
         * 校验并取得成功状态。
         */
        private void requireOkay(DataInputStream response, int status) throws IOException {
            if (status != EtlScriptProtocol.OK) {
                failResponse(response, status);
            }
            if (!EtlScriptProtocol.readRows(response, policy.maxResultRows(), policy.maxResultBytes()).isEmpty()) {
                throw new IOException("Invalid startup response");
            }
            EtlScriptProtocol.requireEnd(response);
        }

        /**
         * 不信任工作进程选择的外部异常消息。
         */
        private void failResponse(DataInputStream response, int status) throws IOException {
            if (status != EtlScriptProtocol.FAILED) {
                throw new IOException("Invalid worker response");
            }
            String code = EtlScriptProtocol.readText(response, 64);
            EtlScriptProtocol.requireEnd(response);
            // 不信任工作进程选择的外部异常消息。
            throw new IllegalStateException(switch (code) {
                case "INITIALIZATION_FAILED" -> "ETL script worker 脚本加载或编译失败";
                case "LOG_LIMIT_EXCEEDED" -> "ETL script worker 日志超过字节上限";
                default -> "ETL script worker 脚本执行失败";
            });
        }

        /**
         * 持续消费工作进程标准错误流并限制累计读取量。
         */
        private void drainStderr() {
            try (InputStream input = process.getErrorStream()) {
                byte[] bytes = new byte[1024];
                long count = 0;
                int length;
                while ((length = input.read(bytes)) != -1) {
                    count += length;
                    if (count > policy.maxLogBytes()) {
                        stderrExceeded.set(true);
                    }
                }
            } catch (IOException failure) {
                if (process.isAlive() && !closed) {
                    stderrFailed.set(true);
                }
            }
        }

        /**
         * 关闭 {@code EtlScriptRunner} 当前持有的资源。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            boolean interrupted = Thread.interrupted();
            boolean confirmed = true;
            boolean directoryCleanupPending = false;
            try {
                if (dockerWorker != null) {
                    confirmed = dockerWorker.stopAndConfirm();
                }
                if (process != null) {
                    process.descendants().forEach(descendants::add);
                    descendants.forEach(child -> {
                        if (child.isAlive()) {
                            child.destroyForcibly();
                        }
                    });
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                        if (System.nanoTime() >= deadline) {
                            confirmed = false;
                            break;
                        }
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                    }
                    try {
                        process.getInputStream().close();
                        process.getOutputStream().close();
                        process.getErrorStream().close();
                    } catch (IOException failure) {
                        confirmed = false;
                    }
                }
                io.shutdownNow();
                try {
                    if (!io.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        confirmed = false;
                    }
                    if (stderrReader != null) {
                        stderrReader.join(500);
                        if (stderrReader.isAlive()) {
                            confirmed = false;
                        }
                    }
                } catch (InterruptedException failure) {
                    confirmed = false;
                    interrupted = true;
                }
                if (workingDirectory != null) {
                    try {
                        // 仅操作服务端创建的随机目录。Files.delete 不递归，也绝不跟随符号链接。
                        // 部分提供方（包括 Windows）不返回目录 fileKey；即使删除空目录，
                        // 也不能删除用户创建的任何内容。若替换对象的键已知且不同，则予以保留。
                        var current = Files.readAttributes(workingDirectory, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (!confirmed || !current.isDirectory() || directoryKey != null && !directoryKey.equals(
                                current.fileKey())) {
                            directoryCleanupPending = true;
                        } else {
                            Files.delete(workingDirectory);
                        }
                    } catch (NoSuchFileException absent) { /* 没有需要清理的目录 */ } catch (IOException failure) {
                        directoryCleanupPending = true;
                    }
                }
            } finally {
                if (confirmed) {
                    slots.release();
                } else {
                    terminationUnconfirmed.run();
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!confirmed) {
                throw new IllegalStateException("ETL script worker 资源未确认清理，需核查副作用");
            }
            if (directoryCleanupPending) {
                throw new IllegalStateException("ETL script worker 进程已退出，临时目录保留待清理");
            }
        }
    }
}
