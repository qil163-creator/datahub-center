package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 由服务端固定管理的容器启动器。Docker CLI 终止绝不能证明容器已经终止。 */
final class EtlDockerWorker {

    static final long MEMORY = 768L * 1024 * 1024;

    static final String OWNER_LABEL = "cn.iocoder.yudao.etl.worker-owner";

    static final String SERVICE_LABEL = "cn.iocoder.yudao.etl.worker-service";

    static final String STORAGE_LABEL = "cn.iocoder.yudao.etl.worker-storage";

    private static final String JAVA = "/opt/java/openjdk/bin/java";

    private static final String SUPERVISOR = "/usr/bin/timeout";

    private static final String TMPFS = "rw,noexec,nosuid,nodev,size=64m,uid=65532,gid=65532,mode=0700";

    private static final String INFO = "{\"os\":\"{{.OSType}}\",\"cgroup\":\"{{.CgroupVersion}}\",\"memory\":{{.MemoryLimit}},\"swap\":{{.SwapLimit}},\"pids\":{{.PidsLimit}},\"security\":{{json .SecurityOptions}}}";

    private static final String INSPECT = "{\"id\":\"{{.Id}}\",\"image\":\"{{.Image}}\",\"name\":\"{{.Name}}\",\"owner\":\"{{index .Config.Labels `" + OWNER_LABEL + "`}}\",\"state\":{{json .State}},\"config\":{{json .Config}},\"host\":{{json .HostConfig}},\"mounts\":{{json .Mounts}}}";

    record Mount(String source, String destination) {

    }

    record CommandResult(int exitCode, String stdout) {

    }

    @FunctionalInterface
    interface Commands {

        /**
         * 按工作进程协议处理当前任务并记录运行结果。
         */
        CommandResult run(List<String> arguments, long timeoutMillis);
    }

    private final EtlScriptWorkerPolicy policy;

    private final EtlScriptWorkerPolicy.Docker docker;

    private final List<Mount> mounts;

    private final Commands commands;

    private final EtlDockerWorkerLedger ledger;

    private EtlDockerWorkerLedger.Registration registration;

    private String owner = UUID.randomUUID().toString();

    private String name = "etl-script-" + owner;

    private String id;

    private String imageContentId;

    private boolean creationAttempted, creationAcknowledged;

    /**
     * 使用给定参数初始化 {@code EtlDockerWorker}。
     */
    EtlDockerWorker(EtlScriptWorkerPolicy policy, List<Path> entries) {
        this(policy, entries, null);
    }

    /**
     * 初始化 {@code EtlDockerWorker}。
     */
    EtlDockerWorker(EtlScriptWorkerPolicy policy, List<Path> entries, Commands commands) {
        this.policy = policy;
        this.docker = policy.docker();
        this.commands = commands == null ? this::command : commands;
        this.mounts = resolveMounts(entries, docker.hostPathMappings());
        ledger = docker.recovery() == null ? null : new EtlDockerWorkerLedger(policy);
        if (ledger != null) {
            for (Path entry : entries) {
                Path root = docker.recovery().directory().normalize();
                if (Files.isDirectory(entry) && (root.startsWith(entry) || entry.startsWith(root))) {
                    throw unavailable();
                }
            }
        }
    }

    /**
     * 在产生数据库或外部副作用前检查目标及资源条件。
     */
    void preflight() {
        if (ledger != null) {
            ledger.checkAvailable();
        }
        if (!Files.isRegularFile(docker.executable())) {
            throw unavailable();
        }
        JSONObject info = parse(
                require(commands.run(List.of("info", "--format", format(INFO)), docker.commandTimeoutMillis())));
        verifyCapabilities(info, docker.requireSwapLimit());
        JSONObject image = parse(require(commands.run(List.of("image", "inspect", docker.image(), "--format",
                format("{\"id\":\"{{.Id}}\",\"os\":\"{{.Os}}\"}")), docker.commandTimeoutMillis())));
        if (!"linux".equals(image.getStr("os")) || !image.getStr("id", "").matches("sha256:[a-f0-9]{64}")) {
            throw unavailable();
        }
        imageContentId = image.getStr("id");
    }

    /**
     * 校验能力信息。
     */
    static void verifyCapabilities(JSONObject info, boolean requireSwap) {
        if (!"linux".equals(info.getStr("os")) || !"2".equals(info.getStr("cgroup"))
                || !Boolean.TRUE.equals(info.getBool("memory")) || !Boolean.TRUE.equals(info.getBool("pids"))
                || info.getJSONArray("security") == null
                || info.getJSONArray("security").stream()
                .noneMatch(v -> String.valueOf(v).startsWith("name=seccomp,"))) {
            throw unavailable();
        }
        if (requireSwap && !Boolean.TRUE.equals(info.getBool("swap"))) {
            throw new IllegalStateException("ETL Docker worker 缺少受控 swap 限额，严格模式拒绝启动");
        }
    }

    /**
     * 返回当前起点。
     */
    Process start() throws IOException {
        if (ledger != null) {
            registration = ledger.reserve(owner, imageContentId,
                    mountProof(mounts)); // 先持久化意图并取得操作系统租约，再执行 Docker create。
        }
        creationAttempted = true;
        CommandResult created = commands.run(createArguments(), docker.commandTimeoutMillis());
        String candidate = require(created).trim();
        if (!candidate.matches("[a-f0-9]{64}")) {
            throw unavailable();
        }
        id = candidate;
        creationAcknowledged = true;
        if (registration != null) {
            registration.acknowledge(id);
        }
        verifyContainer(inspect(id, docker.commandTimeoutMillis()), true);
        require(commands.run(List.of("start", id), docker.commandTimeoutMillis()));
        JSONObject running = inspect(id, docker.commandTimeoutMillis());
        verifyContainer(running, false);
        if (!Boolean.TRUE.equals(running.getJSONObject("state").getBool("Running"))) {
            throw unavailable();
        }
        var exec = new ArrayList<>(
                List.of("exec", "--interactive", "--user", "65532:65532", "--workdir", "/tmp", id, JAVA));
        exec.addAll(javaArguments());
        return builder(exec).start();
    }

    /**
     * 返回编号。
     */
    String containerId() {
        return id;
    }

    /**
     * 创建参数集合。
     */
    List<String> createArguments() {
        var arguments = new ArrayList<>(List.of("create", "--pull", "never", "--name", name,
                "--label", OWNER_LABEL + "=" + owner, "--interactive", "--init", "--network", "none",
                "--read-only", "--user", "65531:65531", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                "--cpus", "1.000", "--memory", Long.toString(MEMORY), "--memory-swap", Long.toString(MEMORY),
                "--pids-limit", "64", "--ipc", "private", "--cgroupns", "private", "--log-driver", "none",
                "--tmpfs", "/tmp:" + TMPFS, "--workdir", "/", "--entrypoint", SUPERVISOR));
        if (ledger != null) {
            arguments.addAll(List.of("--label", SERVICE_LABEL + "=" + ledger.serviceIdentity(),
                    "--label", STORAGE_LABEL + "=" + ledger.storageIdentity()));
        }
        for (Mount mount : mounts) {
            arguments.addAll(List.of("--mount", "type=bind,source=" + mount.source()
                    + ",target=" + mount.destination() + ",readonly,bind-propagation=rprivate,bind-recursive=readonly"));
        }
        arguments.add(docker.image());
        arguments.addAll(supervisorArguments());
        return List.copyOf(arguments);
    }

    /**
     * 生成受控工作进程管理器的启动参数。
     */
    private List<String> supervisorArguments() {
        return List.of("--signal=KILL", Long.toString(docker.maxLifetimeMillis() / 1000) + "s", "/bin/sleep",
                "infinity");
    }

    /**
     * 生成当前 Java 工作进程的启动参数。
     */
    private List<String> javaArguments() {
        return List.of("-Xms32m", "-Xmx" + policy.heapMegabytes() + "m",
                "-XX:MaxMetaspaceSize=128m", "-XX:+ExitOnOutOfMemoryError", "-XX:ActiveProcessorCount=1",
                "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=/tmp", "-cp",
                String.join(":", mounts.stream().map(Mount::destination).toList()), EtlScriptWorker.class.getName());
    }

    /**
     * 运行 Docker inspect 命令并解析指定容器的受限检查结果。
     */
    private JSONObject inspect(String target, long timeout) {
        return parse(require(commands.run(List.of("inspect", target, "--format", format(INSPECT)), timeout)));
    }

    /**
     * 校验归属标识。
     */
    private void verifyOwner(JSONObject inspect) {
        if (!Objects.equals(id, inspect.getStr("id")) || !Objects.equals("/" + name, inspect.getStr("name"))
                || !Objects.equals(owner, inspect.getStr("owner"))) {
            throw unavailable();
        }
        if (ledger != null) {
            var config = inspect.getJSONObject("config");
            var labels = config == null ? null : config.getJSONObject("Labels");
            if (labels == null || !ledger.serviceIdentity().equals(labels.getStr(SERVICE_LABEL))
                    || !ledger.storageIdentity().equals(labels.getStr(STORAGE_LABEL))) {
                throw unavailable();
            }
        }
    }

    /**
     * 校验容器。
     */
    void verifyContainer(JSONObject inspect, boolean beforeStart) {
        verifyContainerBase(inspect, beforeStart, true);
        if (!mountProof(mounts).equals(inspectedMountProof(inspect))) {
            throw unavailable();
        }
    }

    /**
     * 校验容器基础。
     */
    private void verifyContainerBase(JSONObject inspect, boolean beforeStart, boolean enforceSwap) {
        verifyOwner(inspect);
        if (imageContentId != null && !imageContentId.equals(inspect.getStr("image"))) {
            throw unavailable();
        }
        JSONObject h = inspect.getJSONObject("host"), c = inspect.getJSONObject(
                "config"), state = inspect.getJSONObject("state");
        if (h == null || c == null || state == null || beforeStart && !"created".equals(state.getStr("Status"))
                || !"none".equals(h.getStr("NetworkMode")) || !Boolean.TRUE.equals(h.getBool("ReadonlyRootfs"))
                || !Boolean.FALSE.equals(h.getBool("Privileged")) || !Boolean.TRUE.equals(h.getBool("Init"))
                || !"65531:65531".equals(c.getStr("User")) || !"/".equals(c.getStr("WorkingDir"))
                || !docker.image().equals(c.getStr("Image")) || !"".equals(h.getStr("PidMode", ""))
                || !"private".equals(h.getStr("IpcMode")) || !"private".equals(h.getStr("CgroupnsMode"))
                || !Long.valueOf(1_000_000_000L).equals(h.getLong("NanoCpus"))
                || !Long.valueOf(MEMORY).equals(h.getLong("Memory")) || !Long.valueOf(64).equals(h.getLong("PidsLimit"))
                || enforceSwap && docker.requireSwapLimit() && !Long.valueOf(MEMORY).equals(h.getLong("MemorySwap"))
                || !List.of("ALL").equals(strings(h, "CapDrop"))
                || !List.of("no-new-privileges:true").equals(strings(h, "SecurityOpt"))
                || !List.of(SUPERVISOR).equals(strings(c, "Entrypoint"))
                || !supervisorArguments().equals(strings(c, "Cmd"))
                || h.getJSONObject("LogConfig") == null || !"none".equals(h.getJSONObject("LogConfig").getStr("Type"))
                || h.getJSONObject("Tmpfs") == null || h.getJSONObject("Tmpfs").size() != 1
                || !TMPFS.equals(h.getJSONObject("Tmpfs").getStr("/tmp"))) {
            throw unavailable();
        }
    }

    /** 只能删除已确认归本方所有的编号。创建结果不确定时保留调用方槽位。 */
    boolean stopAndConfirm() {
        return stopAndConfirm(Long.MAX_VALUE);
    }

    /**
     * 停止当前 Docker 工作容器并确认其实际退出。
     */
    private boolean stopAndConfirm(long outerDeadline) {
        if (!creationAttempted) {
            return true;
        }
        if (!creationAcknowledged) {
            return false;
        }
        long deadline = Math.min(outerDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3000));
        try {
            JSONObject observed = inspect(id, remaining(deadline));
            verifyOwner(observed);
            if (Boolean.TRUE.equals(observed.getJSONObject("state").getBool("Running"))) {
                // 与自然退出竞争可能导致 kill 返回非零值。随后通过 inspect 确定实际状态。
                commands.run(List.of("kill", "--signal", "KILL", id), remaining(deadline));
                observed = inspect(id, remaining(deadline));
                verifyOwner(observed);
            }
            if (!Boolean.FALSE.equals(observed.getJSONObject("state").getBool("Running"))) {
                return false;
            }
            require(commands.run(List.of("rm", id), remaining(deadline)));
            boolean absent = require(commands.run(List.of("ps", "--all", "--no-trunc", "--filter", "id=" + id,
                    "--format", "{{.ID}}"), remaining(deadline))).isBlank();
            if (absent && registration != null) {
                registration.cleaned();
                registration.close();
            }
            return absent;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** 恢复时绝不发现未登记对象，也不将守护进程错误解释为容器不存在。 */
    boolean reconcile(EtlDockerWorkerLedger.Registration lease) {
        if (ledger == null) {
            return false;
        }
        return reconcile(lease,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(docker.recovery().reconciliationTimeoutMillis()));
    }

    /**
     * 未确认的创建操作仍可能延迟完成。保留其意图。
     */
    boolean reconcile(EtlDockerWorkerLedger.Registration lease, long deadline) {
        if (ledger == null || !ledger.compatible(lease.entry())) {
            return false;
        }
        registration = lease;
        owner = lease.entry().owner();
        name = "etl-script-" + owner;
        id = lease.entry().id();
        var filters = new ArrayList<>(List.of("ps", "--all", "--no-trunc", "--filter", "name=^/" + name + "$",
                "--filter", "label=" + OWNER_LABEL + "=" + owner, "--filter",
                "label=" + SERVICE_LABEL + "=" + ledger.serviceIdentity(),
                "--filter", "label=" + STORAGE_LABEL + "=" + ledger.storageIdentity(), "--format", "{{.ID}}"));
        if (id != null) {
            filters.addAll(List.of("--filter", "id=" + id));
        }
        String found = require(commands.run(filters, remaining(deadline))).trim();
        if (found.isEmpty()) {
            if (id == null) {
                return false; // 未确认的创建操作仍可能延迟完成。保留其意图。
            }
            String exists = require(commands.run(List.of("ps", "--all", "--no-trunc", "--filter", "id=" + id,
                    "--format", "{{.ID}}"), remaining(deadline))).trim();
            if (!exists.isEmpty()) {
                return false;
            }
            registration.cleaned();
            return true;
        }
        if (!found.matches("[a-f0-9]{64}") || id != null && !id.equals(found)) {
            return false;
        }
        id = found;
        imageContentId = lease.entry().image();
        JSONObject observed = inspect(id, remaining(deadline));
        verifyContainerBase(observed, false, false);
        if (!lease.entry().mounts().equals(inspectedMountProof(observed))) {
            return false;
        }
        if (lease.entry().state().equals("INTENT")) {
            registration.acknowledge(id);
        }
        creationAttempted = true;
        creationAcknowledged = true;
        return stopAndConfirm(deadline);
    }

    /**
     * 返回当前剩余。
     */
    private long remaining(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
            throw unavailable();
        }
        return Math.min(docker.commandTimeoutMillis(), remaining);
    }

    /**
     * 解析挂载集合。
     */
    static List<Mount> resolveMounts(List<Path> entries, List<EtlScriptWorkerPolicy.HostPathMapping> mappings) {
        var result = new ArrayList<Mount>();
        for (int i = 0; i < entries.size(); i++) {
            Path entry = entries.get(i);
            String source = null;
            int longest = -1;
            for (var mapping : mappings) {
                try {
                    Path root = mapping.localRoot().toRealPath();
                    if (entry.startsWith(root) && root.getNameCount() > longest) {
                        String suffix = root.relativize(entry).toString().replace('\\', '/');
                        source = mapping.daemonRoot().replaceAll("/$", "") + (suffix.isEmpty() ? "" : "/" + suffix);
                        longest = root.getNameCount();
                    }
                } catch (IOException failure) {
                    throw unavailable();
                }
            }
            if (source == null && File.separatorChar == '/') {
                source = entry.toString();
            }
            if (source == null || !source.startsWith("/") || source.contains(",") || source.contains("\n")
                    || source.contains("\r") || source.contains("..") || source.contains(":")) {
                throw unavailable();
            }
            result.add(new Mount(source, "/worker/entry-" + i + (Files.isDirectory(entry) ? "" : ".jar")));
        }
        return List.copyOf(result);
    }

    // 仅持久化可信源/目标对的规范摘要，绝不保存部署路径。

    /**
     * 校验挂载目标不冲突并生成稳定的挂载证明。
     */
    static String mountProof(List<Mount> mounts) {
        var sorted = mounts.stream().sorted(Comparator.comparing(Mount::destination)).toList();
        var destinations = new HashSet<String>();
        var pairs = new ArrayList<List<String>>();
        for (Mount mount : sorted) {
            if (mount.source() == null || mount.destination() == null || !destinations.add(mount.destination())) {
                throw unavailable();
            }
            pairs.add(List.of(mount.source(), mount.destination()));
        }
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(pairs).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw unavailable();
        }
    }

    /**
     * 根据 Docker 实际检查结果核对挂载配置及文件身份。
     */
    private static String inspectedMountProof(JSONObject inspect) {
        JSONObject host = inspect.getJSONObject("host");
        var configured = host == null ? null : host.getJSONArray("Mounts");
        var actual = inspect.getJSONArray("mounts");
        if (configured == null || actual == null || configured.size() != actual.size()) {
            throw unavailable();
        }
        var configuredPairs = new ArrayList<Mount>();
        var actualPairs = new ArrayList<Mount>();
        for (int i = 0; i < configured.size(); i++) {
            JSONObject mount = configured.getJSONObject(i), bind = mount.getJSONObject("BindOptions");
            if (!"bind".equals(mount.getStr("Type")) || !Boolean.TRUE.equals(mount.getBool("ReadOnly"))
                    || bind == null || !Boolean.TRUE.equals(bind.getBool("ReadOnlyForceRecursive"))
                    || !"rprivate".equals(bind.getStr("Propagation"))) {
                throw unavailable();
            }
            configuredPairs.add(new Mount(mount.getStr("Source"), mount.getStr("Target")));
        }
        for (int i = 0; i < actual.size(); i++) {
            JSONObject mount = actual.getJSONObject(i);
            if (!"bind".equals(mount.getStr("Type")) || !Boolean.FALSE.equals(mount.getBool("RW"))
                    || !"rprivate".equals(mount.getStr("Propagation"))) {
                throw unavailable();
            }
            actualPairs.add(new Mount(mount.getStr("Source"), mount.getStr("Destination")));
        }
        String proof = mountProof(actualPairs);
        if (!proof.equals(mountProof(configuredPairs))) {
            throw unavailable();
        }
        return proof;
    }

    /**
     * 读取并整理当前输入中的字符串集合。
     */
    private static List<String> strings(JSONObject object, String key) {
        var array = object.getJSONArray(key);
        return array == null ? List.of() : array.stream().map(String::valueOf).toList();
    }

    /**
     * 返回当前解析。
     */
    private static JSONObject parse(String text) {
        try {
            return JSONUtil.parseObj(text);
        } catch (RuntimeException malformed) {
            throw unavailable();
        }
    }

    // Windows Docker.exe 会删除 ProcessBuilder 传入的字面双引号。在固定 Go 模板
    // 内部生成 JSON 引号，不调用 shell，也不接受客户端提供的模板。

    /**
     * 返回格式。
     */
    private static String format(String template) {
        return template.replace("{\"", "{ \"").replace("\"", "{{printf `%c` 34}}");
    }

    /**
     * 检查结果是否满足当前操作的约定。
     */
    private static String require(CommandResult result) {
        if (result.exitCode() != 0) {
            throw unavailable();
        }
        return result.stdout();
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalStateException}。
     */
    private static IllegalStateException unavailable() {
        return new IllegalStateException("ETL Docker worker 不可用或隔离配置未兑现，请管理员核查受控运行环境");
    }

    /**
     * 返回当前构建器。
     */
    private ProcessBuilder builder(List<String> arguments) {
        var command = new ArrayList<>(List.of(docker.executable().toString(), "--host", docker.endpoint()));
        command.addAll(arguments);
        var builder = new ProcessBuilder(command);
        builder.environment().clear();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            builder.environment().put("SystemRoot", systemRoot);
        }
        return builder;
    }

    /**
     * 返回当前命令。
     */
    private CommandResult command(List<String> arguments, long timeoutMillis) {
        Process process = null;
        var readers = Executors.newFixedThreadPool(2, task -> {
            var t = new Thread(task, "etl-docker-control");
            t.setDaemon(true);
            return t;
        });
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            process = builder(arguments).start();
            Process active = process;
            var output = readers.submit(() -> readBounded(active.getInputStream(), true));
            var error = readers.submit(() -> readBounded(active.getErrorStream(), false));
            process.getOutputStream().close();
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) {
                    throw unavailable();
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw unavailable();
            }
            byte[] bytes = output.get(remaining, TimeUnit.NANOSECONDS);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw unavailable();
            }
            error.get(remaining, TimeUnit.NANOSECONDS);
            return new CommandResult(process.exitValue(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | ExecutionException | TimeoutException failure) {
            throw unavailable();
        } finally {
            boolean confirmed = true, interrupted = Thread.interrupted();
            if (process != null) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                try {
                    process.getInputStream().close();
                    process.getErrorStream().close();
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                }
                try {
                    if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                        confirmed = false;
                    }
                } catch (InterruptedException stopped) {
                    interrupted = true;
                    confirmed = false;
                }
            }
            readers.shutdownNow();
            try {
                if (!readers.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    confirmed = false;
                }
            } catch (InterruptedException stopped) {
                interrupted = true;
                confirmed = false;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!confirmed) {
                throw unavailable();
            }
        }
    }

    /**
     * 读取有界。
     */
    private static byte[] readBounded(InputStream input, boolean retain) throws IOException {
        try (input; var buffer = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[2048];
            int count = 0, length;
            while ((length = input.read(bytes)) != -1) {
                count += length;
                if (count > 256 * 1024) {
                    throw new IOException("Control output limit");
                }
                if (retain) {
                    buffer.write(bytes, 0, length);
                }
            }
            return buffer.toByteArray();
        }
    }
}
