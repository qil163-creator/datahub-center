package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import lombok.Data;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_TASK_SNAPSHOT_INVALID;

/** 不可变的持久化执行载荷；仅包含引用及配置，绝不包含凭据。 */
@Data
public class DevTaskSnapshot {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 历史快照缺少版本字段时按 V1 读取，新保存必须显式记录版本。 */
    private Integer schemaVersion = CURRENT_SCHEMA_VERSION;

    private DevTaskDO task;

    private String workerGroup;

    private String tenantCode;

    /** 普通开发任务保存时冻结的受管环境；历史缺失继续使用 -1。 */
    private Long managedEnvironmentCode;

    private Long nativeProjectCode;

    private String nativeProfileRevision;

    private String nativeProfileFingerprint;

    private Map<Long, Long> dependencyVersions = new LinkedHashMap<>();

    /** 所有保存、运行、依赖与回滚读取共用此入口，未知版本不得按当前模型执行。 */
    public static DevTaskSnapshot read(String json) {
        if (json == null || json.isBlank()) {
            throw exception(DEV_TASK_SNAPSHOT_INVALID, "缺少执行快照");
        }
        try {
            var mapper = JsonUtils.getObjectMapper();
            var tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (tree == null || !tree.isObject() || !tree.path("task").isObject()) {
                throw exception(DEV_TASK_SNAPSHOT_INVALID, "缺少任务配置");
            }
            var version = tree.get("schemaVersion");
            if (version != null && (!version.isIntegralNumber() || !version.canConvertToInt()
                    || version.intValue() != CURRENT_SCHEMA_VERSION)) {
                throw exception(DEV_TASK_SNAPSHOT_INVALID, "不支持的快照版本，请升级应用或重新保存任务");
            }
            DevTaskSnapshot snapshot = mapper.treeToValue(tree, DevTaskSnapshot.class);
            snapshot.validate();
            return snapshot;
        } catch (JacksonException ex) {
            // 不透出 JSON 原文或解析器消息，避免脚本、连接参数进入响应与错误日志。
            throw exception(DEV_TASK_SNAPSHOT_INVALID, "快照格式不合法");
        }
    }

    /**
     * 写入开发任务快照数据。
     *
     * @return 写入开发任务快照数据文本
     */
    public String write() {
        validate();
        return JsonUtils.toJsonString(this);
    }

    /**
     * 校验开发任务快照输入及状态。
     */
    private void validate() {
        if (!Objects.equals(schemaVersion, CURRENT_SCHEMA_VERSION)) {
            throw exception(DEV_TASK_SNAPSHOT_INVALID, "不支持的快照版本，请升级应用或重新保存任务");
        }
        if (task == null || dependencyVersions == null
                || dependencyVersions.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey() <= 0 || entry.getValue() == null || entry.getValue() <= 0)) {
            throw exception(DEV_TASK_SNAPSHOT_INVALID, "任务配置或冻结依赖不完整");
        }
        if (managedEnvironmentCode != null && managedEnvironmentCode != -1L && managedEnvironmentCode <= 0L) throw exception(DEV_TASK_SNAPSHOT_INVALID, "冻结的受管环境编号无效");
        DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
    }
}
