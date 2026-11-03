package cn.iocoder.yudao.framework.dolphinscheduler.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DS 3.4.2 原生 FLINK/SPARK 插件的有界、不可变参数。
 * 调用方负责资源授权、不可变存储、运行环境核验及生命周期。
 * 部署模式由管理员指定，不接受任务传入的任意 shell 选项。
 */
public final class NativeTaskParameters {

    public static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;

    private static final Pattern RESOURCE_NAME = Pattern.compile("[A-Za-z0-9_./:-]{1,2048}");

    private static final Pattern RELATIVE_PATH = Pattern.compile("[A-Za-z0-9_./-]{1,1024}");

    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]{0,5}[mMgG]");

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private static final Set<String> SESSION_COMMANDS = Set.of("SET", "RESET", "SOURCE", "RUN", "QUIT", "EXIT",
            "ADD", "REMOVE", "LOAD", "UNLOAD", "!", "DFS");

    /**
     * 初始化 {@code NativeTaskParameters}。
     */
    private NativeTaskParameters() {
    }

    public enum ProgramType {SQL, JAVA, SCALA, PYTHON}

    public enum FlinkMode {BATCH, STREAMING}

    public enum FlinkDeployment {CLUSTER, LOCAL, STANDALONE}

    /** 受管有界重启策略；不改变原始 SQL 的会话命令禁令，调用者只提供整数参数。 */
    public static Payload withFlinkRestartPolicy(Payload payload, int attempts, long delayMs) {
        if (payload == null || !"FLINK".equals(payload.taskType()) || attempts < 0 || attempts > 10
                || delayMs < 0 || delayMs > 300000 || attempts > 0 && delayMs < 1)
            throw new IllegalArgumentException("Flink 受管重启策略参数无效");
        var params = new LinkedHashMap<String, Object>(payload.taskParams());
        String strategy = attempts == 0 ? "none" : "fixed-delay";
        if ("SQL".equals(params.get("programType"))) {
            String init = Objects.toString(params.get("initScript"), "")
                    + "SET 'restart-strategy.type' = '" + strategy + "';\n";
            if (attempts > 0) init += "SET 'restart-strategy.fixed-delay.attempts' = '" + attempts + "';\n"
                    + "SET 'restart-strategy.fixed-delay.delay' = '" + delayMs + "ms';\n";
            params.put("initScript", init);
        } else {
            String flags = Objects.toString(params.get("others"), "") + " -Drestart-strategy.type=" + strategy;
            if (attempts > 0) flags += " -Drestart-strategy.fixed-delay.attempts=" + attempts + " -Drestart-strategy.fixed-delay.delay=" + delayMs + "ms";
            params.put("others", flags.strip());
        }
        return new Payload(payload.taskType(), params);
    }

    /** 受管 Catalog 的无凭据初始化语句与任务 SQL 使用相同安全约束。 */
    public static Payload withSqlInitialization(Payload payload, String initialization) {
        if (initialization == null || initialization.isBlank()) return payload;
        if (!"SQL".equals(payload.taskParams().get("programType"))
                || initialization.matches("(?is).*['\"]?(password|passwd|secret|token)['\"]?\\s*[:=].*")) {
            throw new IllegalArgumentException("原生初始化仅接受不含凭据的 SQL");
        }
        String combined = initialization.strip() + (initialization.strip().endsWith(";") ? "\n" : ";\n")
                + payload.taskParams().get("rawScript");
        validateSql(combined, "SPARK".equals(payload.taskType()));
        var params = new LinkedHashMap<String, Object>(payload.taskParams()); params.put("rawScript", combined);
        return new Payload(payload.taskType(), params);
    }

    /** 管理员选择受管部署模式；不接受来自任务表单的任意命令参数。 */
    public static Payload flink(FlinkOptions options, FlinkDeployment deployment, String jobManager) {
        Payload original = flink(options);
        if (deployment == FlinkDeployment.CLUSTER) return original;
        if (options.programType() != ProgramType.SQL) {
            throw new IllegalArgumentException("本地和独立部署当前仅支持 Flink SQL");
        }
        if (deployment == FlinkDeployment.STANDALONE && (jobManager == null
                || !jobManager.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}:[1-9][0-9]{0,4}"))) {
            throw new IllegalArgumentException("Flink JobManager 地址无效");
        }
        var params = new LinkedHashMap<String, Object>(original.taskParams());
        params.put("deployMode", deployment.name().toLowerCase(Locale.ROOT)); params.put("yarnQueue", "");
        String script = String.valueOf(params.get("initScript")).replaceAll("(?m)^SET 'yarn\\.[^\\n]*\\n", "");
        script += "SET 'execution.target' = '" + (deployment == FlinkDeployment.LOCAL ? "local" : "remote") + "';\n"
                + "SET 'jobmanager.memory.process.size' = '" + memory(options.jobManagerMemory()) + "';\n"
                + "SET 'taskmanager.memory.process.size' = '" + memory(options.taskManagerMemory()) + "';\n"
                + "SET 'taskmanager.numberOfTaskSlots' = '" + options.slots() + "';\n";
        if (deployment == FlinkDeployment.LOCAL) {
            script += "SET 'local.number-taskmanager' = '" + options.taskManagers() + "';\n";
        } else {
            int separator = jobManager.lastIndexOf(':');
            int port = Integer.parseInt(jobManager.substring(separator + 1));
            if (port > 65535) throw new IllegalArgumentException("Flink JobManager 端口无效");
            script += "SET 'rest.address' = '" + jobManager.substring(0, separator) + "';\n"
                    + "SET 'rest.port' = '" + port + "';\n";
        }
        params.put("initScript", script);
        return new Payload("FLINK", params);
    }

    /** 受管 JVM Launcher 的本地或独立集群部署入口；资源授权由调用方完成，任务不提供任意 flags。 */
    public static Payload flinkManagedProgram(FlinkOptions options, FlinkDeployment deployment, String jobManager) {
        if (options == null || options.programType() != ProgramType.JAVA || options.mainResource() == null || options.mainClass() == null || deployment == null)
            throw new IllegalArgumentException("受管 Flink 原生程序必须使用已授权的 JAVA 资源和入口类");
        Payload original = flink(options);
        if (deployment == FlinkDeployment.CLUSTER) return original;
        var params = new LinkedHashMap<String, Object>(original.taskParams()); params.put("deployMode", deployment.name().toLowerCase(Locale.ROOT)); params.put("yarnQueue", "");
        String flags = Objects.toString(params.get("others"), "")
                + " -Dexecution.target=" + (deployment == FlinkDeployment.LOCAL ? "local" : "remote")
                + " -Djobmanager.memory.process.size=" + memory(options.jobManagerMemory())
                + " -Dtaskmanager.memory.process.size=" + memory(options.taskManagerMemory())
                + " -Dtaskmanager.numberOfTaskSlots=" + options.slots();
        if (deployment == FlinkDeployment.LOCAL) flags += " -Dlocal.number-taskmanager=" + options.taskManagers();
        else {
            if (jobManager == null || !jobManager.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}:[1-9][0-9]{0,4}")) throw new IllegalArgumentException("Flink JobManager 地址无效");
            int separator = jobManager.lastIndexOf(':'); int port = Integer.parseInt(jobManager.substring(separator + 1));
            if (port > 65535) throw new IllegalArgumentException("Flink JobManager 端口无效");
            String host = jobManager.substring(0, separator);
            flags += " -Drest.address=" + host + " -Drest.port=" + port + " -Djobmanager.rpc.address=" + host;
        }
        params.put("others", flags.strip()); return new Payload("FLINK", params);
    }

    /** Spark 本地、Standalone 与 YARN 均由管理员指定 master，任务只提供资源配置。 */
    public static Payload spark(SparkOptions options, String master) {
        Payload original = spark(options);
        if ("yarn".equals(master)) return original;
        if (master == null || !(master.matches("local\\[(?:[1-9]|[1-5][0-9]|6[0-4])\\]")
                || master.matches("spark://[A-Za-z0-9][A-Za-z0-9.-]{0,252}:[1-9][0-9]{0,4}"))) {
            throw new IllegalArgumentException("Spark master 必须为受管本地核数或独立集群地址");
        }
        if (master.startsWith("spark://") && Integer.parseInt(master.substring(master.lastIndexOf(':') + 1)) > 65535) {
            throw new IllegalArgumentException("Spark master 端口无效");
        }
        var params = new LinkedHashMap<String, Object>(original.taskParams());
        params.put("master", master); params.put("deployMode", master.startsWith("local[") ? "local" : "client");
        params.put("yarnQueue", "");
        params.put("others", String.valueOf(params.get("others")).replace("--conf spark.yarn.submit.waitAppCompletion=true", "").strip());
        return new Payload("SPARK", params);
    }

    /** Storage absolute name plus the verified StorageOperator resourceRelativePath, when a dependency. */
    public record Resource(String resourceName, String workerRelativePath) {

        /**
         * 使用给定参数初始化 {@code Resource}。
         */
        public Resource(String resourceName) {
            this(resourceName, null);
        }

        /**
         * 校验构造参数并初始化 {@code Resource}。
         */
        public Resource {
            if (resourceName == null || !RESOURCE_NAME.matcher(resourceName).matches()
                    || hasTraversal(resourceName) || resourceName.endsWith("/")) {
                throw new IllegalArgumentException("Invalid native resource name");
            }
            if (workerRelativePath != null && (!RELATIVE_PATH.matcher(workerRelativePath).matches()
                    || workerRelativePath.startsWith("/") || workerRelativePath.contains("//") || hasTraversal(
                    workerRelativePath)
                    || workerRelativePath.endsWith("/"))) {
                throw new IllegalArgumentException("Invalid native worker relative resource path");
            }
        }

        /**
         * 读取或构建当前原生调度任务参数。
         */
        public Map<String, Object> taskParameter() {
            return Map.of("resourceName", resourceName);
        }
    }

    public record FlinkOptions(ProgramType programType, FlinkMode mode, String rawScript,
                               Resource mainResource, String mainClass, List<String> arguments,
                               List<Resource> dependencyResources, int slots, int taskManagers, int parallelism,
                               String jobManagerMemory, String taskManagerMemory, String yarnQueue,
                               String applicationName, long checkpointIntervalMs) {

        /**
         * 使用给定参数初始化 {@code FlinkOptions}。
         */
        public FlinkOptions(ProgramType programType, FlinkMode mode, String rawScript,
                Resource mainResource, String mainClass, List<String> arguments,
                List<Resource> dependencyResources, int slots, int taskManagers, int parallelism,
                String jobManagerMemory, String taskManagerMemory, String yarnQueue, String applicationName) {
            this(programType, mode, rawScript, mainResource, mainClass, arguments, dependencyResources, slots,
                    taskManagers, parallelism, jobManagerMemory, taskManagerMemory, yarnQueue, applicationName, 0);
        }

        /**
         * 初始化 {@code FlinkOptions}。
         */
        public FlinkOptions {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            dependencyResources = dependencyResources == null ? List.of() : List.copyOf(dependencyResources);
        }
    }

    public record SparkOptions(ProgramType programType, String rawScript, Resource mainResource,
                               String mainClass, List<String> arguments, List<Resource> dependencyResources,
                               int driverCores, String driverMemory, int executors, int executorCores,
                               String executorMemory, String yarnQueue, String applicationName) {

        /**
         * 初始化 {@code SparkOptions}。
         */
        public SparkOptions {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            dependencyResources = dependencyResources == null ? List.of() : List.copyOf(dependencyResources);
        }
    }

    public record Payload(String taskType, Map<String, Object> taskParams) {

        /**
         * 初始化 {@code Payload}。
         */
        public Payload {
            taskParams = Map.copyOf(taskParams);
        }
    }

    /** ResourceInfo's deprecated id/res cannot identify a DS 3.4.2 storage object. */
    public static Resource normalizeResource(Map<String, ?> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        String current = text(metadata.get("resourceName"));
        String fullName = text(metadata.get("fullName"));
        if (current != null && fullName != null && !current.equals(fullName)) {
            throw new IllegalArgumentException("Conflicting native resource names");
        }
        return new Resource(current == null ? fullName : current, text(metadata.get("resourceRelativePath")));
    }

    /**
     * 根据当前输入构建并返回 {@code Payload}。
     */
    public static Payload flink(FlinkOptions options) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(options.mode(), "mode");
        Map<String, Object> params = common(options.programType(), options.rawScript(), options.mainResource(),
                options.mainClass(), options.arguments(), options.dependencyResources(), options.applicationName(), false);
        bounded(options.slots(), 1, 1024, "slots");
        bounded(options.taskManagers(), 1, 10000, "taskManagers");
        bounded(options.parallelism(), 1, 100000, "parallelism");
        if (options.checkpointIntervalMs() < 0 || options.checkpointIntervalMs() > 86_400_000) {
            throw new IllegalArgumentException("Invalid native checkpoint interval");
        }
        int capacity = Math.multiplyExact(options.slots(), options.taskManagers());
        if (options.parallelism() > capacity) {
            throw new IllegalArgumentException("Parallelism exceeds managed slot capacity");
        }
        params.put("deployMode", "cluster");
        params.put("flinkVersion", ">=1.13");
        params.put("slot", options.slots());
        // DS retains taskManager for old runtimes. Modern Flink uses the explicit total slot ceiling below.
        params.put("taskManager", options.taskManagers());
        params.put("parallelism", options.parallelism());
        params.put("jobManagerMemory", memory(options.jobManagerMemory()));
        params.put("taskManagerMemory", memory(options.taskManagerMemory()));
        params.put("yarnQueue", queue(options.yarnQueue()));
        if (options.programType() == ProgramType.SQL) {
            StringBuilder init = new StringBuilder()
                    .append("SET 'execution.runtime-mode' = '").append(options.mode()).append("';\n")
                    .append("SET 'yarn.application.name' = '").append(label(options.applicationName())).append("';\n")
                    .append("SET 'pipeline.name' = '").append(label(options.applicationName())).append("';\n")
                    .append("SET 'sql-client.execution.result-mode' = 'TABLEAU';\n")
                    .append("SET 'table.dml-sync' = 'true';\n")
                    .append("SET 'execution.attached' = 'true';\n")
                    .append("SET 'execution.shutdown-on-attached-exit' = 'true';\n")
                    .append("SET 'slotmanager.number-of-slots.max' = '").append(capacity).append("';\n");
            if (options.checkpointIntervalMs() > 0) {
                init.append("SET 'execution.checkpointing.interval' = '").append(options.checkpointIntervalMs())
                        .append("ms';\n");
            }
            for (Resource dependency : options.dependencyResources()) {
                init.append("ADD JAR './").append(dependency.workerRelativePath()).append("';\n");
            }
            params.put("initScript", init.toString());
            params.put("others", "");
        } else {
            StringBuilder flags = new StringBuilder(
                    "-sae -Dexecution.attached=true -Dexecution.shutdown-on-attached-exit=true")
                    .append(" -Dexecution.runtime-mode=").append(options.mode())
                    .append(" -Dpipeline.name=").append(label(options.applicationName()))
                    .append(" -Dslotmanager.number-of-slots.max=").append(capacity);
            if (options.checkpointIntervalMs() > 0) {
                flags.append(" -Dexecution.checkpointing.interval=").append(options.checkpointIntervalMs())
                        .append("ms");
            }
            for (Resource dependency : options.dependencyResources()) {
                flags.append(" -C ").append(shellQuote("file:./" + dependency.workerRelativePath()));
            }
            params.put("others", flags.toString());
            params.put("initScript", "");
        }
        return new Payload("FLINK", params);
    }

    /**
     * 根据当前输入构建并返回 {@code Payload}。
     */
    public static Payload spark(SparkOptions options) {
        Objects.requireNonNull(options, "options");
        Map<String, Object> params = common(options.programType(), options.rawScript(), options.mainResource(),
                options.mainClass(), options.arguments(), options.dependencyResources(), options.applicationName(), true);
        bounded(options.driverCores(), 1, 1024, "driverCores");
        bounded(options.executors(), 1, 10000, "executors");
        bounded(options.executorCores(), 1, 1024, "executorCores");
        params.put("master", "yarn");
        // spark-sql is a driver CLI and does not support cluster deploy mode.
        params.put("deployMode", options.programType() == ProgramType.SQL ? "client" : "cluster");
        params.put("driverCores", options.driverCores());
        params.put("driverMemory", memory(options.driverMemory()));
        params.put("numExecutors", options.executors());
        params.put("executorCores", options.executorCores());
        params.put("executorMemory", memory(options.executorMemory()));
        params.put("yarnQueue", queue(options.yarnQueue()));
        params.put("sqlExecutionType", "SCRIPT");
        StringBuilder flags = new StringBuilder("--conf spark.yarn.submit.waitAppCompletion=true --conf spark.sql.parser.escapedStringLiterals=false");
        if (!options.dependencyResources().isEmpty()) {
            flags.append(" --jars ").append(shellQuote(options.dependencyResources().stream()
                    .map(resource -> "./" + resource.workerRelativePath()).reduce((left, right) -> left + "," + right)
                    .orElseThrow()));
        }
        params.put("others", flags.toString());
        return new Payload("SPARK", params);
    }

    /**
     * 构建当前请求或操作共享的参数部分。
     */
    private static Map<String, Object> common(ProgramType type, String script, Resource main,
            String mainClass, List<String> arguments,
            List<Resource> dependencies, String applicationName, boolean sparkEscapes) {
        Objects.requireNonNull(type, "programType");
        if (dependencies.size() > 32) {
            throw new IllegalArgumentException("Too many native dependencies");
        }
        Set<String> names = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        List<Map<String, Object>> resources = new ArrayList<>();
        if (main != null) {
            names.add(main.resourceName());
            resources.add(main.taskParameter());
            if (main.workerRelativePath() != null) {
                paths.add(main.workerRelativePath());
            }
        }
        for (Resource dependency : dependencies) {
            if (dependency.workerRelativePath() == null || !dependency.resourceName().endsWith(".jar")
                    || !dependency.workerRelativePath().endsWith(".jar") || !names.add(dependency.resourceName())
                    || !paths.add(dependency.workerRelativePath())) {
                throw new IllegalArgumentException("Native dependencies require distinct verified Jar relative paths");
            }
            resources.add(dependency.taskParameter());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("programType", type.name());
        params.put("appName", label(applicationName));
        params.put("resourceList", List.copyOf(resources));
        params.put("localParams", List.of());
        if (type == ProgramType.SQL) {
            if (main != null || !blank(mainClass) || !arguments.isEmpty()) {
                throw new IllegalArgumentException("Native SQL cannot also specify an executable program");
            }
            validateSql(script, sparkEscapes);
            params.put("rawScript", script);
        } else {
            if (main == null || !blank(script)) {
                throw new IllegalArgumentException("Native programs require a managed resource, not inline code");
            }
            String extension = type == ProgramType.PYTHON ? ".py" : ".jar";
            if (!main.resourceName().endsWith(extension)) {
                throw new IllegalArgumentException("Native program resource extension mismatch");
            }
            if (type == ProgramType.PYTHON) {
                if (!blank(mainClass)) {
                    throw new IllegalArgumentException("Python cannot specify a JVM main class");
                }
            } else if (mainClass == null || mainClass.length() > 512 || !CLASS_NAME.matcher(mainClass).matches()) {
                throw new IllegalArgumentException("Native JVM program requires a main class");
            } else {
                // DS 3.4.2 joins this value directly into a shell command; Java's legal '$' is not shell literal.
                params.put("mainClass", shellQuote(mainClass));
            }
            if (arguments.size() > 64) {
                throw new IllegalArgumentException("Too many native program arguments");
            }
            List<String> quoted = new ArrayList<>();
            for (String argument : arguments) {
                if (argument == null || argument.length() > 2048 || argument.codePoints()
                        .anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Invalid native program argument");
                }
                rejectTemplates(argument);
                quoted.add(shellQuote(argument));
            }
            params.put("mainJar", main.taskParameter());
            params.put("mainArgs", String.join(" ", quoted));
        }
        return params;
    }

    /** Session control commands are forbidden, including after comments or a statement boundary. */
    public static void validateSql(String script) {
        validateSql(script, false);
    }

    /** Spark 构建器固定字符串解析模式后，仅对单引号字符串识别其反斜线转义。 */
    private static void validateSql(String script, boolean sparkEscapes) {
        if (script == null || script.isBlank() || script.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES
                || script.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid native SQL script size");
        }
        rejectTemplates(script);
        boolean statementStart = true;
        for (int i = 0; i < script.length(); ) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                i += 2;
                while (i < script.length() && script.charAt(i) != '\n' && script.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                int close = script.indexOf("*/", i + 2);
                if (close < 0 || script.substring(i + 2, close).contains("/*")) {
                    throw new IllegalArgumentException("Unsupported native SQL comment");
                }
                // Spark's executable hints can carry runtime configuration; native profiles own those settings.
                if (i + 2 < script.length() && script.charAt(i + 2) == '!') {
                    throw new IllegalArgumentException("Executable SQL comments are unsupported");
                }
                i = close + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                if (statementStart) {
                    throw new IllegalArgumentException("Native SQL statement must start with a keyword");
                }
                char quote = c;
                i++;
                boolean closed = false;
                while (i < script.length()) {
                    char current = script.charAt(i++);
                    // Flink 与 Spark 的转义语义不同；只有固定解析模式的 Spark 单引号字符串可以消费下一字符。
                    if (current == '\\') {
                        if (sparkEscapes && quote == '\'' && i < script.length()) {
                            i++;
                            continue;
                        }
                        throw new IllegalArgumentException("Native SQL backslash quoted escapes are unsupported");
                    }
                    if (current == quote) {
                        if (i < script.length() && script.charAt(i) == quote) {
                            i++;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unterminated native SQL quote");
                }
                continue;
            }
            if (c == ';') {
                statementStart = true;
                i++;
                continue;
            }
            if (statementStart) {
                int end = i;
                while (end < script.length() && Character.isLetter(script.charAt(end))) {
                    end++;
                }
                String keyword = end == i ? String.valueOf(c) : script.substring(i, end).toUpperCase(Locale.ROOT);
                if (SESSION_COMMANDS.contains(keyword) || end == i) {
                    throw new IllegalArgumentException(
                            "Native SQL session commands are managed by the runtime profile");
                }
                statementStart = false;
                i = end;
                continue;
            }
            i++;
        }
    }

    /**
     * 返回当前内存。
     */
    private static String memory(String value) {
        if (value == null || !MEMORY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid native memory size");
        }
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        long megabytes = Character.toLowerCase(value.charAt(value.length() - 1)) == 'g' ? amount * 1024 : amount;
        if (megabytes > 512 * 1024) {
            throw new IllegalArgumentException("Native memory size exceeds component bound");
        }
        return value;
    }

    /**
     * 返回队列。
     */
    private static String queue(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid native queue");
        }
        return value;
    }

    /**
     * 返回当前标签。
     */
    private static String label(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid native application name");
        }
        return value;
    }

    /**
     * 以单引号规则转义 Shell 参数。
     */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 判断是否具有目录越界。
     */
    private static boolean hasTraversal(String value) {
        return List.of(value.split("/", -1)).contains("..") || List.of(value.split("/", -1)).contains(".");
    }

    /**
     * 拒绝会由外部任务引擎再次展开的模板占位内容。
     */
    private static void rejectTemplates(String value) {
        if (value.contains("${") || value.contains("$[") || value.contains("!{")) {
            throw new IllegalArgumentException("Native DS template expansion is unsupported");
        }
    }

    /**
     * 判断当前值是否为空或只包含空白文本。
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Invalid native resource metadata");
        }
        return string;
    }

    /**
     * 校验当前输入未超过约定的大小或数量上限。
     */
    private static void bounded(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Invalid native " + name);
        }
    }
}
