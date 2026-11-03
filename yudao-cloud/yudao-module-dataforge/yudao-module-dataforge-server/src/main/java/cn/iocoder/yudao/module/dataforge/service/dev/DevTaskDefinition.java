package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.enums.dev.DevTaskTypeEnum;
import cn.iocoder.yudao.module.dataforge.model.dev.DevProgramConfig;
import tools.jackson.databind.DeserializationFeature;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 任务定义的领域规则。原生配置绝不落入历史 JDBC SQL 执行分支。 */
public final class DevTaskDefinition {

    private static final jakarta.validation.Validator EXECUTION_CONFIG_VALIDATOR =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * 限制开发任务定义工具的实例化，统一通过静态能力使用。
     */
    private DevTaskDefinition() {
    }

    /**
     * 校验类型。
     *
     * @param type 类型标识
     * @return 开发任务类型
     */
    public static DevTaskTypeEnum requireType(String type) {
        DevTaskTypeEnum resolved = DevTaskTypeEnum.fromType(StrUtil.blankToDefault(type, "SQL"));
        if (resolved == null) {
            throw exception(DEV_TASK_TYPE_NOT_SUPPORTED, type);
        }
        return resolved;
    }

    /**
     * 校验配置。
     *
     * @param type 类型标识
     * @param config 处理配置
     */
    public static void validateConfig(DevTaskTypeEnum type, DevProgramConfig config) {
        if (config == null) {
            return; // 基本信息允许逐步完善，完整任务保存时通过执行门禁。
        }
        if (!type.isEngineTask()) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "仅 Flink/Spark 任务可以配置引擎程序");
        }
        if (!Integer.valueOf(1).equals(config.getSchemaVersion())) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "不支持的配置版本");
        }
        if (!"BATCH".equals(config.getWorkloadMode()) && !"STREAMING".equals(config.getWorkloadMode())) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "运行模式必须是 BATCH 或 STREAMING");
        }
        if (!"PYTHON".equals(type.getProgramType()) && StrUtil.isNotBlank(config.getPythonEnvironment())) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "Python 环境只适用于 Python 任务");
        }
        if ("FLINK".equals(type.getEngine()) && (config.getDriverMemoryMb() != null
                || config.getExecutorMemoryMb() != null || config.getDriverCores() != null
                || config.getExecutorCores() != null || config.getNumExecutors() != null)) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "Flink 任务不能配置 Spark Driver/Executor 参数");
        }
        if ("SPARK".equals(type.getEngine()) && (config.getParallelism() != null
                || config.getJobManagerMemoryMb() != null || config.getTaskManagerMemoryMb() != null
                || config.getCheckpointIntervalMs() != null || config.getTaskManagerSlots() != null
                || config.getTaskManagerCount() != null)) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "Spark 任务不能配置 Flink 并行度或状态参数");
        }
    }

    /**
     * 读取开发任务定义的配置。
     *
     * @param json JSON 内容
     * @return 开发程序配置
     */
    public static DevProgramConfig readConfig(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            var tree = JsonUtils.getObjectMapper().reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException();
            }
            var version = tree.get("schemaVersion");
            if (version != null && (!version.isIntegralNumber() || !version.canConvertToInt()
                    || version.intValue() != 1)) {
                throw new IllegalArgumentException();
            }
            return JsonUtils.getObjectMapper().treeToValue(tree, DevProgramConfig.class);
        } catch (Exception ignored) {
            // 不记录原始配置、脚本或参数，避免敏感内容进入异常日志。
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "配置内容不是有效的程序对象");
        }
    }

    /**
     * 读取执行配置。
     *
     * @param json JSON 内容
     * @return 开发执行配置
     */
    public static cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig readExecutionConfig(String json) {
        try {
            cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig config = StrUtil.isBlank(json)
                    ? new cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig()
                    : JsonUtils.getObjectMapper()
                    .readerFor(cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
            if (config == null) {
                throw new IllegalArgumentException();
            }
            validateExecutionConfig(config);
            return config;
        } catch (Exception ignored) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "执行配置不合法，请修正后重新保存");
        }
    }

    /**
     * 校验执行配置。
     *
     * @param config 处理配置
     */
    public static void validateExecutionConfig(cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig config) {
        if (config == null) {
            return;
        }
        try {
            cn.iocoder.yudao.framework.common.util.validation.ValidationUtils.validate(EXECUTION_CONFIG_VALIDATOR,
                    config);
        } catch (jakarta.validation.ConstraintViolationException invalid) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "执行属性超出允许范围");
        }
        var names = new java.util.HashSet<String>();
        if (config.getLocalParams().stream().anyMatch(param -> !names.add(param.getProp()))) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "自定义参数名称不能重复");
        }
        if (config.getLocalParams().stream().anyMatch(param -> param.getProp().toLowerCase(java.util.Locale.ROOT)
                .matches(".*(?:password|passwd|token|secret|credential|private_?key|access_?key).*"))) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "自定义参数不能用于保存连接凭据或密钥");
        }
        var lineage = config.getTableLineage();
        if (lineage != null) {
            if (lineage.getUpstream().size() + lineage.getDownstream().size() > 100) {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "上下游表关系合计不能超过 100 项");
            }
            for (var references : java.util.List.of(lineage.getUpstream(), lineage.getDownstream())) {
                var identities = new java.util.HashSet<String>();
                if (references.stream().anyMatch(reference -> !identities.add(reference.getDatabaseId() + "\u0000"
                        + StrUtil.blankToDefault(reference.getSchemaName(), "") + "\u0000" + reference.getTableName()))) {
                    throw exception(DEV_TASK_DEPENDENCY_INVALID, "同一方向不能重复配置相同数据表");
                }
            }
        }
    }

    /**
     * 获取开发任务定义的能力。
     *
     * @param task 任务记录
     * @return 开发任务定义的能力文本
     */
    public static String capability(DevTaskDO task) {
        DevTaskTypeEnum type = requireType(task.getTaskType());
        if (type.isEngineTask() && (!type.isLegacySql() || StrUtil.isNotBlank(task.getProgramConfigJson()))) {
            return "NOT_READY";
        }
        return type.isLegacySql() ? "LEGACY_SQL" : "STANDARD";
    }

    /**
     * 校验可执行。
     *
     * @param task 任务记录
     */
    public static void requireExecutable(DevTaskDO task) {
        if ("NOT_READY".equals(capability(task))) {
            throw exception(DEV_NATIVE_RUNTIME_NOT_READY);
        }
    }
}
