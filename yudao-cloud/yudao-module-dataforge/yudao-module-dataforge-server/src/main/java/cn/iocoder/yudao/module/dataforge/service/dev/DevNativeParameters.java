package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties;

import java.util.List;

/** 领域模型到 starter 公开原生协议的薄映射，不实现命令构建或 SQL 解析。 */
final class DevNativeParameters {

    /**
     * 限制开发原生参数工具的实例化，统一通过静态能力使用。
     */
    private DevNativeParameters() {
    }

    /**
     * 构造开发原生参数。
     *
     * @param task 任务记录
     * @param profile 配置档案
     * @return 载荷
     */
    static NativeTaskParameters.Payload build(DevTaskDO task, DevNativeRuntimeProperties.Profile profile) {
        return build(task, profile, "dh_native_" + task.getId());
    }

    /**
     * 构造开发原生参数。
     *
     * @param task 任务记录
     * @param profile 配置档案
     * @param appName 应用名称
     * @return 载荷
     */
    static NativeTaskParameters.Payload build(DevTaskDO task, DevNativeRuntimeProperties.Profile profile,
            String appName) {
        var type = DevTaskDefinition.requireType(task.getTaskType());
        var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
        var program = NativeTaskParameters.ProgramType.valueOf(
                "JAR".equals(type.getProgramType()) ? "JAVA" : type.getProgramType());
        var main = config.getArtifactFileId() == null ? null : resource(
                DevNativeRuntimeSupport.artifact(profile, config.getArtifactFileId(), false));
        var dependencies = config.getDependencyFileIds() == null ? List.<NativeTaskParameters.Resource>of()
                : config.getDependencyFileIds().stream()
                .map(id -> resource(DevNativeRuntimeSupport.artifact(profile, id, true))).toList();
        String script = "SQL".equals(type.getProgramType()) ? task.getContent() : null;
        if ("FLINK".equals(type.getEngine())) {
            var options = new NativeTaskParameters.FlinkOptions(program,
                    NativeTaskParameters.FlinkMode.valueOf(config.getWorkloadMode()), script, main,
                    config.getEntryPoint(),
                    config.getArguments(), dependencies, value(config.getTaskManagerSlots(), 1),
                    value(config.getTaskManagerCount(), 1),
                    value(config.getParallelism(), 1), value(config.getJobManagerMemoryMb(), 1024) + "m",
                    value(config.getTaskManagerMemoryMb(), 1024) + "m", profile.getQueue(), appName,
                    config.getCheckpointIntervalMs() == null ? 0 : config.getCheckpointIntervalMs());
            return "LOCAL".equals(profile.getDeployMode())
                    ? NativeTaskParameters.flink(options, NativeTaskParameters.FlinkDeployment.LOCAL, null)
                    : NativeTaskParameters.flink(options);
        }
        var options = new NativeTaskParameters.SparkOptions(program, script, main, config.getEntryPoint(),
                        config.getArguments(), dependencies, value(config.getDriverCores(), 1),
                        value(config.getDriverMemoryMb(), 1024) + "m",
                        value(config.getNumExecutors(), 1), value(config.getExecutorCores(), 1),
                        value(config.getExecutorMemoryMb(), 1024) + "m",
                        profile.getQueue(), appName);
        return "LOCAL".equals(profile.getDeployMode())
                ? NativeTaskParameters.spark(options, profile.getSparkMaster()) : NativeTaskParameters.spark(options);
    }

    /**
     * 提取开发原生参数字段值。
     *
     * @param value 待处理的值
     * @param defaultValue 默认值
     * @return 值数值
     */
    private static int value(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 获取开发原生参数的资源。
     *
     * @param artifact 产物
     * @return 资源
     */
    private static NativeTaskParameters.Resource resource(DevNativeRuntimeProperties.Artifact artifact) {
        return new NativeTaskParameters.Resource(artifact.getResourceName(), artifact.getWorkerRelativePath());
    }
}
