package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevRuntimeProfileRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.enums.dev.DevTaskTypeEnum;
import cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties;
import cn.iocoder.yudao.module.dataforge.model.dev.DevProgramConfig;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 原生集群程序领域护栏；执行协议与 argv 编码由 DS starter 承载。 */
@Service
public class DevNativeRuntimeSupport {

    private static final jakarta.validation.Validator VALIDATOR = jakarta.validation.Validation.buildDefaultValidatorFactory()
            .getValidator();

    @Resource
    private DevNativeRuntimeProperties properties;

    @Resource
    private ExecutionAccessService executionAccessService;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<org.springframework.boot.ssl.SslBundles> sslBundles;

    /**
     * 判断是否满足原生条件。
     *
     * @param task 任务记录
     * @return 判断结果
     */
    public static boolean isNative(DevTaskDO task) {
        var type = DevTaskDefinition.requireType(task.getTaskType());
        return type.isEngineTask() && (!type.isLegacySql() || StrUtil.isNotBlank(task.getProgramConfigJson()));
    }

    /**
     * 获取开发原生运行时支持的配置档案。
     *
     * @return 开发原生运行时支持的配置档案结果集合
     */
    public List<DevRuntimeProfileRespVO> profiles() {
        return properties.getProfiles().stream().filter(this::inScope).filter(this::validProfile).map(profile -> {
            var dto = new DevRuntimeProfileRespVO();
            dto.setCode(profile.getCode());
            dto.setName(profile.getName());
            dto.setEngine(profile.getEngine());
            dto.setEngineVersion(profile.getEngineVersion());
            dto.setDeployMode(profile.getDeployMode());
            dto.setQueue(profile.getQueue());
            dto.setAllowedTaskTypes(List.copyOf(profile.getAllowedTaskTypes()));
            dto.setArtifacts(profile.getArtifacts().stream().map(DevNativeRuntimeSupport::option).toList());
            dto.setDependencyResources(
                    profile.getDependencyResources().stream().map(DevNativeRuntimeSupport::option).toList());
            return dto;
        }).toList();
    }

    /**
     * 获取开发原生运行时支持的能力。
     *
     * @param task 任务记录
     * @return 开发原生运行时支持的能力文本
     */
    public String capability(DevTaskDO task) {
        if (!isNative(task)) {
            return DevTaskDefinition.capability(task);
        }
        try {
            resolve(task);
            return "NATIVE";
        } catch (RuntimeException incomplete) {
            return "NOT_READY";
        }
    }

    /**
     * 校验操作人。
     *
     * @param task 任务记录
     * @param actor 操作人编号
     */
    public void requireActor(DevTaskDO task, Long actor) {
        if (isNative(task)) {
            if (actor == null) {
                throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
            }
            executionAccessService.requireUserPermission(actor, ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE);
        }
    }

    /**
     * 解析开发原生运行时支持配置或身份。
     *
     * @param task 任务记录
     * @return 配置档案
     */
    public DevNativeRuntimeProperties.Profile resolve(DevTaskDO task) {
        var type = DevTaskDefinition.requireType(task.getTaskType());
        var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
        if (config == null) {
            throw exception(DEV_NATIVE_RUNTIME_NOT_READY);
        }
        try {
            ValidationUtils.validate(VALIDATOR, config);
        } catch (jakarta.validation.ConstraintViolationException invalid) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "程序参数超出允许范围");
        }
        DevTaskDefinition.validateConfig(type, config);
        var profile = requireProfile(config.getRuntimeProfileCode());
        if ("LOCAL".equals(profile.getDeployMode())) {
            validateLocalBudget(type, config);
        }
        if (!profile.getAllowedTaskTypes().contains(type.name()) || !Objects.equals(type.getEngine(),
                profile.getEngine())
                || !Objects.equals(config.getEngineVersion(), profile.getEngineVersion())
                || !Objects.equals(StrUtil.trimToNull(config.getQueue()), StrUtil.trimToNull(profile.getQueue()))) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "任务类型、版本或队列与受管运行环境不一致");
        }
        if (task.getDatabaseId() != null || task.getDsDatasourceId() != null || StrUtil.isNotBlank(
                task.getDsDatasourceType())) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "原生程序不能绑定 JDBC 或外部调度器数据源");
        }
        if (!DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson()).getLocalParams().isEmpty()) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "原生程序不支持 JDBC 自定义绑定参数");
        }
        if (task.getTimeoutSeconds() == null || task.getTimeoutSeconds() < 0 || task.getTimeoutSeconds() > 86400
                || (task.getTimeoutSeconds() == 0 && !"STREAMING".equals(config.getWorkloadMode()))) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "批任务必须设置正超时；流任务可使用 0 表示持续运行");
        }
        if (StrUtil.isNotBlank(config.getPythonEnvironment())) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "Python 环境由受管环境配置，不能覆盖运行环境");
        }
        if ("SPARK_SQL".equals(type.name()) && "STREAMING".equals(config.getWorkloadMode())) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "Spark SQL CLI 仅支持批处理；流程序请选择受管 JAR 或 Python");
        }
        if ("SQL".equals(type.getProgramType())) {
            if (StrUtil.isBlank(task.getContent())) {
                throw exception(DEV_TASK_CONTENT_EMPTY);
            }
            if (config.getArtifactFileId() != null || (config.getArguments() != null && !config.getArguments()
                    .isEmpty())) {
                throw exception(DEV_PROGRAM_CONFIG_INVALID, "SQL 使用当前保存脚本，不接受制品或命令参数");
            }
        } else {
            var artifact = artifact(profile, config.getArtifactFileId(), false);
            if (!artifact.getProgramTypes().contains(type.getProgramType())
                    || config.getArtifactSha256() == null || !artifact.getSha256()
                    .equalsIgnoreCase(config.getArtifactSha256())
                    || !Objects.equals(StrUtil.trimToNull(artifact.getEntryPoint()),
                    StrUtil.trimToNull(config.getEntryPoint()))) {
                throw exception(DEV_PROGRAM_CONFIG_INVALID, "受管制品摘要、程序类型或入口不一致");
            }
        }
        if (config.getDependencyFileIds() != null) {
            if (config.getDependencyFileIds().stream().distinct().count() != config.getDependencyFileIds().size()) {
                throw exception(DEV_PROGRAM_CONFIG_INVALID, "依赖制品不能重复");
            }
            config.getDependencyFileIds().forEach(id -> artifact(profile, id, true));
        }
        try {
            DevNativeParameters.build(task, profile);
        } catch (IllegalArgumentException invalid) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "原生脚本、资源或程序参数不符合受管执行契约");
        }
        return profile;
    }

    /**
     * 固定开发原生运行时支持执行快照。
     *
     * @param snapshot 快照
     */
    public void freeze(DevTaskSnapshot snapshot) {
        if (!isNative(snapshot.getTask())) {
            return;
        }
        var profile = resolve(snapshot.getTask());
        snapshot.setNativeProjectCode(profile.getDsProjectCode());
        snapshot.setNativeProfileRevision(profile.getRevision());
        snapshot.setNativeProfileFingerprint(fingerprint(profile));
    }

    /**
     * 校验冻结。
     *
     * @param snapshot 快照
     * @return 配置档案
     */
    public DevNativeRuntimeProperties.Profile requireFrozen(DevTaskSnapshot snapshot) {
        var profile = resolve(snapshot.getTask());
        if (!Objects.equals(snapshot.getNativeProjectCode(), profile.getDsProjectCode())
                || !Objects.equals(snapshot.getNativeProfileRevision(), profile.getRevision())
                || !Objects.equals(snapshot.getNativeProfileFingerprint(), fingerprint(profile))) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "受管运行环境已变更，请重新保存任务");
        }
        return profile;
    }

    /**
     * 校验记录访问。
     *
     * @param record 运行记录
     */
    public void requireRecordAccess(DevRecordDO record) {
        if (StrUtil.isBlank(record.getSnapshotJson())) {
            return;
        }
        var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        var task = snapshot.getTask();
        if (!isNative(task)) {
            return;
        }
        requireActor(task, SecurityFrameworkUtils.getLoginUserId());
        // 观察与取消使用冻结目标；环境撤回不会阻止清理已投递任务，也不会改投新的项目。
        if (snapshot.getNativeProjectCode() == null
                || !Objects.equals(record.getDsProjectCode(), snapshot.getNativeProjectCode())) {
            throw exception(DEV_RECORD_NOT_EXISTS);
        }
    }

    /**
     * 获取开发原生运行时支持的产物。
     *
     * @param profile 配置档案
     * @param id 记录编号
     * @param dependency 依赖
     * @return 产物
     */
    public static DevNativeRuntimeProperties.Artifact artifact(DevNativeRuntimeProperties.Profile profile, Long id,
            boolean dependency) {
        return (dependency ? profile.getDependencyResources() : profile.getArtifacts()).stream()
                .filter(resource -> Objects.equals(resource.getFileId(), id)).findFirst()
                .orElseThrow(() -> exception(DEV_PROGRAM_CONFIG_INVALID, "资源不属于当前受管环境"));
    }

    /**
     * 校验配置档案。
     *
     * @param code 编码
     * @return 配置档案
     */
    private DevNativeRuntimeProperties.Profile requireProfile(String code) {
        var matches = properties.getProfiles().stream().filter(this::inScope)
                .filter(profile -> Objects.equals(profile.getCode(), code)).toList();
        if (matches.size() != 1 || !validProfile(matches.getFirst())) {
            throw exception(DEV_NATIVE_RUNTIME_NOT_READY);
        }
        return matches.getFirst();
    }

    /**
     * 判断输入范围条件是否成立。
     *
     * @param profile 配置档案
     * @return 处理是否成功
     */
    private boolean inScope(DevNativeRuntimeProperties.Profile profile) {
        return profile != null;
    }

    /**
     * 判断有效配置档案条件是否成立。
     *
     * @param profile 配置档案
     * @return 处理是否成功
     */
    private boolean validProfile(DevNativeRuntimeProperties.Profile profile) {
        return profile.getCode() != null && profile.getCode().matches("[A-Za-z0-9_-]{1,100}") && StrUtil.isNotBlank(
                profile.getRevision())
                && "3.4.2".equals(profile.getDsVersion())
                && ("YARN_ATTACHED".equals(profile.getDeployMode()) || "LOCAL".equals(profile.getDeployMode()))
                && profile.getDsProjectCode() != null && profile.getDsProjectCode() > 0
                && profile.getEnvironmentCode() != null && profile.getEnvironmentCode() > 0
                && StrUtil.isNotBlank(profile.getWorkerGroup()) && !"default".equalsIgnoreCase(profile.getWorkerGroup())
                && profile.getTenantCode() != null && profile.getTenantCode()
                .matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}") && !"root".equalsIgnoreCase(profile.getTenantCode())
                && profile.getQueue() != null && profile.getQueue().matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}")
                && validEngineVersion(profile)
                && profile.getArtifacts() != null && profile.getDependencyResources() != null && profile.getAllowedTaskTypes() != null && !profile.getAllowedTaskTypes()
                .isEmpty()
                && profile.getAllowedTaskTypes().stream().allMatch(name -> {
            var type = DevTaskTypeEnum.fromType(name);
            return type != null && Objects.equals(type.getEngine(), profile.getEngine());
        })
                && (!"LOCAL".equals(profile.getDeployMode()) || profile.getAllowedTaskTypes().stream()
                .allMatch(name -> "FLINK_SQL".equals(name) || "SPARK_SQL".equals(name)))
                && properties.getProfiles().stream().filter(this::inScope)
                .filter(other -> Objects.equals(other.getCode(), profile.getCode())).count() == 1
                && java.util.stream.Stream.concat(profile.getArtifacts().stream(),
                        profile.getDependencyResources().stream())
                .filter(Objects::nonNull).map(DevNativeRuntimeProperties.Artifact::getFileId).distinct()
                .count() == profile.getArtifacts().size() + profile.getDependencyResources().size()
                && java.util.stream.Stream.concat(profile.getArtifacts().stream(),
                        profile.getDependencyResources().stream())
                .allMatch(resource -> resource != null && resource.getFileId() != null && resource.getFileId() > 0
                        && resource.getSha256() != null && resource.getSha256().matches("[a-fA-F0-9]{64}")
                        && StrUtil.isNotBlank(resource.getResourceName()) && resource.getProgramTypes() != null
                        && resource.getMaxBytes() != null && resource.getMaxBytes() > 0 && resource.getMaxBytes() <= 1_073_741_824L
                        && validResource(resource))
                && ("LOCAL".equals(profile.getDeployMode()) ? validLocalObservation(profile) : validYarnObservation(profile));
    }

    /** 本地模式只开放已验证版本，YARN 的既有版本边界保持不变。 */
    private boolean validEngineVersion(DevNativeRuntimeProperties.Profile profile) {
        if ("LOCAL".equals(profile.getDeployMode())) {
            return ("FLINK".equals(profile.getEngine()) && "1.20.3".equals(profile.getEngineVersion()))
                    || ("SPARK".equals(profile.getEngine()) && "3.5.6".equals(profile.getEngineVersion())
                    && profile.getSparkMaster() != null
                    && "local[1]".equals(profile.getSparkMaster()));
        }
        return profile.getEngineVersion() != null
                && (("FLINK".equals(profile.getEngine()) && profile.getEngineVersion().matches("1\\.18(?:\\.[0-9]+)?"))
                || ("SPARK".equals(profile.getEngine()) && profile.getEngineVersion().matches("3\\.5(?:\\.[0-9]+)?")));
    }

    /** 本地验证 Worker 的已验证预算；不将共享组件支持范围误认为本部署已支持范围。 */
    private static void validateLocalBudget(DevTaskTypeEnum type, DevProgramConfig config) {
        java.util.List<Integer> cores = "FLINK".equals(type.getEngine())
                ? java.util.Arrays.asList(config.getParallelism(), config.getTaskManagerSlots(), config.getTaskManagerCount())
                : java.util.Arrays.asList(config.getDriverCores(), config.getExecutorCores(), config.getNumExecutors());
        java.util.List<Integer> memory = "FLINK".equals(type.getEngine())
                ? java.util.Arrays.asList(config.getJobManagerMemoryMb(), config.getTaskManagerMemoryMb())
                : java.util.Arrays.asList(config.getDriverMemoryMb(), config.getExecutorMemoryMb());
        if (cores.stream().anyMatch(value -> value != null && value != 1)
                || memory.stream().anyMatch(value -> value != null && !java.util.Set.of(512, 768, 1024).contains(value))) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "当前本地运行环境仅支持 1 核及 512/768/1024 MB 内存，请调整后重新保存");
        }
    }

    /** 本地模式必须配置受信 HTTPS 观察服务及有效的客户端 TLS 档案。 */
    private boolean validLocalObservation(DevNativeRuntimeProperties.Profile profile) {
        if (StrUtil.isBlank(profile.getLocalObserverEndpoint()) || StrUtil.isBlank(profile.getLocalObserverSslBundle())) {
            return false;
        }
        try {
            cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.validateEndpoint(
                    java.net.URI.create(profile.getLocalObserverEndpoint()));
            var bundles = sslBundles.getIfAvailable();
            return bundles != null && bundles.getBundle(profile.getLocalObserverSslBundle()).createSslContext() != null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * 判断有效YARN观测条件是否成立。
     *
     * @param profile 配置档案
     * @return 处理是否成功
     */
    private boolean validYarnObservation(DevNativeRuntimeProperties.Profile profile) {
        if (StrUtil.isBlank(profile.getYarnEndpoint()) || StrUtil.isBlank(profile.getYarnSslBundle())) {
            return false;
        }
        try {
            cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.validateEndpoint(
                    java.net.URI.create(profile.getYarnEndpoint()));
            var bundles = sslBundles.getIfAvailable();
            return bundles != null && bundles.getBundle(profile.getYarnSslBundle()).createSslContext() != null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * 判断有效资源条件是否成立。
     *
     * @param resource 资源
     * @return 处理是否成功
     */
    private static boolean validResource(DevNativeRuntimeProperties.Artifact resource) {
        try {
            new cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters.Resource(
                    resource.getResourceName(), resource.getWorkerRelativePath());
            if (resource.getProgramTypes().stream().anyMatch(type -> !List.of("JAR", "PYTHON").contains(type))) {
                return false;
            }
            if (resource.getProgramTypes().contains("JAR") && (resource.getEntryPoint() == null
                    || !resource.getEntryPoint().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                    || !resource.getResourceName().endsWith(".jar"))) {
                return false;
            }
            if (resource.getProgramTypes().contains("PYTHON") && (StrUtil.isNotBlank(
                    resource.getEntryPoint()) || !resource.getResourceName().endsWith(".py"))) {
                return false;
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * 计算输入内容的稳定指纹。
     *
     * @param profile 配置档案
     * @return 计算后的摘要或签名字符串
     */
    private static String fingerprint(DevNativeRuntimeProperties.Profile profile) {
        if ("YARN_ATTACHED".equals(profile.getDeployMode())) {
            tools.jackson.databind.node.ObjectNode legacy = JsonUtils.getObjectMapper().valueToTree(profile);
            legacy.remove("localObserverEndpoint");
            legacy.remove("localObserverSslBundle");
            legacy.remove("sparkMaster");
            return DigestUtil.sha256Hex(JsonUtils.toJsonString(legacy));
        }
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(profile));
    }

    /**
     * 获取开发原生运行时支持的选项。
     *
     * @param resource 资源
     * @return 资源选项
     */
    private static DevRuntimeProfileRespVO.ResourceOption option(DevNativeRuntimeProperties.Artifact resource) {
        var dto = new DevRuntimeProfileRespVO.ResourceOption();
        dto.setFileId(resource.getFileId());
        dto.setName(resource.getName());
        dto.setSha256(resource.getSha256());
        dto.setEntryPoint(resource.getEntryPoint());
        dto.setProgramTypes(List.copyOf(resource.getProgramTypes()));
        return dto;
    }
}
