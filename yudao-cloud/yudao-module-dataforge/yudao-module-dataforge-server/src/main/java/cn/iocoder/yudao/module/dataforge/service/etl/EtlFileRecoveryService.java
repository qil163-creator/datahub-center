package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.config.EtlProperties;
import cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileStore;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 文件 I/O 由 biz-etl 负责；本服务负责标识、权限、持久化决策及有数量上限的恢复。 */
@Service
public class EtlFileRecoveryService {

    private static final Set<String> TOOLS = Set.of("write-csv", "write-json", "write-excel", "write-text");

    public static final String FILE_CLEANUP_PERMISSION = "dataforge:etl:file-cleanup";

    @Resource
    private EtlFileOutputMapper files;

    @Resource
    private EtlRecordMapper records;

    @Resource
    private EtlRunCoordinator coordinator;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private EtlProperties etlProperties;

    @Resource
    private EtlFileStorageProperties storageProperties;

    @Resource
    private PlatformTransactionManager transactionManager;

    public record Storage(String key, String label, String directory) {

    }

    /**
     * 获取ETL 文件恢复的存储。
     *
     * @return ETL 文件恢复的存储结果集合
     */
    public List<Storage> storages() {
        Long actor = actor();
        access.requireUserPermission(actor, ExecutionAccessService.PERMISSION_ETL_RUN);
        return storageProperties.getStorageRoots().keySet().stream().sorted()
                .filter(k -> k.matches("[A-Za-z0-9_-]{1,64}"))
                .map(k -> new Storage(k, k, "etl://" + k)).toList();
    }

    /** 预检在 startEffects 之前拒绝历史任意路径；仅不可变运行的执行者可获得命名空间。 */
    public EtlFileOutputPort createPort(EtlRecordDO record, EtlDag dag, Map<String, String> parameters) {
        Map<String, String> plans = new LinkedHashMap<>();
        var variables = new EtlVariableContext(parameters);
        for (var node : dag.getNodes()) {
            if (TOOLS.contains(node.getToolCode())) {
                var config = JSONUtil.parseObj(variables.resolveJson(node.getNodeJson()));
                String directory = config.getStr("filePath");
                String key = storageKey(directory);
                root(key);
                String name = config.getStr("fileName");
                try {
                    EtlFileStore.validateName(name);
                } catch (IllegalArgumentException invalid) {
                    throw exception(ETL_FILE_STORAGE_DENIED);
                }
                plans.put(node.getNo(), directory);
            }
        }
        if (plans.isEmpty()) {
            return null;
        }
        requireRunIdentity(record);
        access.requireUserPermission(record.getExecutionUserId(), ExecutionAccessService.PERMISSION_ETL_RUN);
        Map<String, EtlFileStore.Binding> reservations = new ConcurrentHashMap<>();
        return new EtlFileOutputPort() {
            /**
             * 预留当前操作所需的资源或领域占用，并返回对应凭据。
             *
             * @param node 节点
             * @param directory 目录
             * @param name 名称
             * @return 绑定
             */
            public EtlFileStore.Binding reserve(String node, String directory, String name) throws IOException {
                if (!Objects.equals(plans.get(node), directory)) {
                    throw exception(ETL_FILE_STATE_CHANGED);
                }
                access.requireUserPermission(record.getExecutionUserId(), ExecutionAccessService.PERMISSION_ETL_RUN);
                String storage = storageKey(directory);
                var existing = find(record.getId(), node);
                String operation = existing == null ? UUID.randomUUID().toString() : existing.getOperationKey();
                var binding = EtlFileStore.binding(root(storage),
                        namespace(record.getExecutionUserId(), record.getId(), node), operation, name);
                owned(record, () -> {
                    var row = find(record.getId(), node);
                    if (row != null) {
                        if (!"INTENT".equals(row.getState()) || !Objects.equals(row.getRunToken(),
                                record.getExecutionToken())
                                || !Objects.equals(row.getStorageKey(), storage) || !Objects.equals(row.getFileName(),
                                name)
                                || !Objects.equals(row.getStorageIdentity(), binding.storageIdentity())) {
                            throw exception(ETL_FILE_STATE_CHANGED);
                        }
                        return;
                    }
                    row = new EtlFileOutputDO();
                    row.setRecordId(record.getId());
                    row.setPipelineId(record.getPipelineId());
                    row.setNodeNo(node);
                    row.setOwnerId(record.getExecutionUserId());
                    row.setRunToken(record.getExecutionToken());
                    row.setOperationKey(operation);
                    row.setStorageKey(storage);
                    row.setStorageIdentity(binding.storageIdentity());
                    row.setFileName(name);
                    row.setState("INTENT");
                    row.setPublicationApproved(false);
                    row.setRecoveryStatus("PENDING");
                    row.setAttempts(0);
                    row.setRevision(0L);
                    row.setRetentionStatus("KEEP");
                    row.setPurgePhase("NONE");
                    if (files.insert(row) != 1) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    audit(row, "ABSENT", "RESERVE", "登记暂存意图");
                });
                reservations.put(operation, binding);
                return binding;
            }

            /**
             * 获取ETL 文件恢复的行。
             *
             * @param binding 绑定
             * @return ETL 文件输出
             */
            private EtlFileOutputDO row(EtlFileStore.Binding binding) {
                if (!binding.equals(reservations.get(binding.operationKey()))) {
                    throw exception(ETL_FILE_STATE_CHANGED);
                }
                var found = files.selectOne(Wrappers.lambdaQuery(EtlFileOutputDO.class)
                        .eq(EtlFileOutputDO::getRecordId, record.getId())
                        .eq(EtlFileOutputDO::getOperationKey, binding.operationKey()));
                if (found == null) {
                    throw exception(ETL_FILE_STATE_CHANGED);
                }
                return files.lock(found.getId());
            }

            /**
             * 在创建前记录创建。
             *
             * @param binding 绑定
             */
            public void beforeCreate(EtlFileStore.Binding binding) {
                owned(record, () -> {
                    var row = row(binding);
                    if (!"INTENT".equals(row.getState()) || !"PENDING".equals(row.getRecoveryStatus())) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                });
            }

            /**
             * 记录已创建ETL 文件恢复。
             *
             * @param binding 绑定
             * @param key 标识键
             */
            public void created(EtlFileStore.Binding binding, String key) {
                owned(record, () -> {
                    var row = row(binding);
                    if ("STAGED".equals(row.getState()) && Objects.equals(key, row.getTemporaryFileKey())) {
                        return;
                    }
                    if (!"INTENT".equals(row.getState()) || key == null || key.length() > 1024) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    change(row, "CREATED", "已登记文件系统身份", next -> {
                        next.setTemporaryFileKey(key);
                        next.setState("STAGED");
                    });
                });
            }

            /**
             * 记录就绪ETL 文件恢复。
             *
             * @param prepared 已准备的执行配置或记录
             */
            public void ready(EtlFileStore.Prepared prepared) {
                owned(record, () -> {
                    var row = row(prepared.binding());
                    requireResourcesCleaned(row);
                    if (!Objects.equals(row.getTemporaryFileKey(), prepared.fingerprint().fileKey())) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    if ("READY".equals(row.getState())) {
                        requirePayload(row, prepared.fingerprint());
                        return;
                    }
                    if (!"STAGED".equals(row.getState())) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    change(row, "READY", "文件内容封存", next -> {
                        next.setPayloadHash(prepared.fingerprint().sha256());
                        next.setPayloadBytes(prepared.fingerprint().bytes());
                        next.setState("READY");
                    });
                });
            }

            /**
             * 记录临时意图。
             *
             * @param binding 绑定
             * @param name 名称
             */
            public void temporaryIntent(EtlFileStore.Binding binding, String name) {
                var intent = new EtlFileStore.TemporaryResource(name, "INTENT", null);
                owned(record, () -> {
                    var row = row(binding);
                    var resources = resources(row);
                    if (!"STAGED".equals(row.getState()) || resources.size() >= 4 || resources.stream()
                            .anyMatch(r -> r.name().equals(name))) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    resources.add(intent);
                    change(row, "TEMP_INTENT", name,
                            next -> next.setTemporaryResourcesJson(encodeResources(resources)));
                });
            }

            /**
             * 记录临时已创建。
             *
             * @param binding 绑定
             * @param name 名称
             * @param key 标识键
             */
            public void temporaryCreated(EtlFileStore.Binding binding, String name, String key) {
                var created = new EtlFileStore.TemporaryResource(name, "CREATED", key);
                owned(record, () -> {
                    var row = row(binding);
                    var resources = resources(row);
                    int index = resourceIndex(resources, name);
                    if (!"STAGED".equals(row.getState())) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    if (resources.get(index).equals(created)) {
                        return;
                    }
                    if (!"INTENT".equals(resources.get(index).state())) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    resources.set(index, created);
                    change(row, "TEMP_CREATED", name,
                            next -> next.setTemporaryResourcesJson(encodeResources(resources)));
                });
            }

            /**
             * 记录临时已清理。
             *
             * @param binding 绑定
             * @param name 名称
             * @param key 标识键
             */
            public void temporaryCleaned(EtlFileStore.Binding binding, String name, String key) {
                owned(record,
                        () -> cleanResource(row(binding), new EtlFileStore.TemporaryResource(name, "CLEANED", key)));
            }

            /**
             * 批准发布。
             *
             * @param outputs 输出
             */
            public void approvePublish(List<EtlFileStore.Prepared> outputs) {
                if (outputs.size() != plans.size() || outputs.stream().map(p -> p.binding().operationKey()).distinct()
                        .count() != plans.size()) {
                    throw exception(ETL_FILE_STATE_CHANGED);
                }
                access.requireUserPermission(record.getExecutionUserId(), ExecutionAccessService.PERMISSION_ETL_RUN);
                // 在所有平台行锁之外检查当前挂载点与命名空间。
                for (var output : outputs) {
                    var observed = files.selectOne(Wrappers.lambdaQuery(EtlFileOutputDO.class)
                            .eq(EtlFileOutputDO::getRecordId, record.getId())
                            .eq(EtlFileOutputDO::getOperationKey, output.binding().operationKey()));
                    if (observed == null) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    try {
                        var current = EtlFileStore.binding(root(observed.getStorageKey()),
                                namespace(record.getExecutionUserId(), record.getId(), observed.getNodeNo()),
                                observed.getOperationKey(), observed.getFileName());
                        if (!current.equals(output.binding())) {
                            throw exception(ETL_FILE_STATE_CHANGED);
                        }
                    } catch (IOException invalid) {
                        throw exception(ETL_FILE_STORAGE_DENIED);
                    }
                }
                owned(record, () -> {
                    for (var output : outputs) {
                        var row = row(output.binding());
                        requirePayload(row, output.fingerprint());
                        requireResourcesCleaned(row);
                        if (!"READY".equals(row.getState())) {
                            throw exception(ETL_FILE_STATE_CHANGED);
                        }
                        change(row, "APPROVE", "整体图成功，批准逐文件发布", next -> {
                            next.setPublicationApproved(true);
                            next.setState("PUBLISHING");
                        });
                    }
                });
            }

            /**
             * 记录已发布ETL 文件恢复。
             *
             * @param output 输出目标
             */
            public void published(EtlFileStore.Prepared output) {
                owned(record, () -> {
                    var row = row(output.binding());
                    requirePayload(row, output.fingerprint());
                    requireResourcesCleaned(row);
                    if (!Boolean.TRUE.equals(row.getPublicationApproved()) || !Set.of("PUBLISHING", "PUBLISHED")
                            .contains(row.getState())) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    if ("PUBLISHED".equals(row.getState())) {
                        return;
                    }
                    change(row, "PUBLISHED", "原子发布已确认", next -> {
                        next.setState("PUBLISHED");
                        next.setRecoveryStatus("DONE");
                    });
                });
            }

            /**
             * 记录已丢弃ETL 文件恢复。
             *
             * @param binding 绑定
             * @param state 状态
             */
            public void discarded(EtlFileStore.Binding binding, String state) {
                owned(record, () -> {
                    var row = row(binding);
                    String terminal = Set.of("DISCARDED", "PUBLISHED").contains(state) ? state : "UNKNOWN";
                    if (resources(row).stream().anyMatch(r -> !"CLEANED".equals(r.state()))) {
                        terminal = "UNKNOWN";
                    }
                    if (Boolean.TRUE.equals(row.getPublicationApproved()) && !"PUBLISHED".equals(terminal)) {
                        terminal = "UNKNOWN";
                    }
                    String finalState = terminal;
                    if (Objects.equals(row.getState(), finalState) && "DONE".equals(row.getRecoveryStatus())) {
                        return;
                    }
                    change(row, "EXIT", "文件退出或清理状态", next -> {
                        next.setState(finalState);
                        next.setRecoveryStatus("UNKNOWN".equals(finalState) ? "PENDING" : "DONE");
                    });
                });
            }
        };
    }

    /**
     * 列出ETL 文件输出响应数据。
     *
     * @param recordId 记录编号
     * @return ETL 文件输出响应数据结果集合
     */
    public List<EtlFileOutputRespVO> list(Long recordId) {
        Long actor = actor();
        var rows = files.selectList(Wrappers.lambdaQuery(EtlFileOutputDO.class)
                        .eq(EtlFileOutputDO::getRecordId, recordId).eq(EtlFileOutputDO::getOwnerId, actor)
                        .orderByAsc(EtlFileOutputDO::getId).last("LIMIT 64"))
                .stream().map(row -> BeanUtils.toBean(row, EtlFileOutputRespVO.class)).toList();
        if (rows.isEmpty()) {
            requireOwnedRecord(recordId, actor);
        }
        return rows;
    }

    /** 将回执薄映射为组件标识；下载时绝不得创建缺失的命名空间或挂载标记。 */
    EtlFileStore.Binding downloadBinding(EtlFileOutputDO row) throws IOException {
        requireResourcesCleaned(row);
        var binding = EtlFileStore.existingBinding(root(row.getStorageKey()),
                namespace(row.getOwnerId(), row.getRecordId(), row.getNodeNo()), row.getOperationKey(),
                row.getFileName());
        if (!Objects.equals(row.getStorageIdentity(), binding.storageIdentity())) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        return binding;
    }

    /**
     * 重新调度ETL 文件恢复处理。
     *
     * @param request 请求参数
     */
    public void retry(EtlFileRetryReqVO request) {
        Long actor = actor();
        var observed = files.selectById(request.getId());
        if (observed == null || !Objects.equals(observed.getOwnerId(), actor)) {
            throw exception(ETL_FILE_NOT_OWNER);
        }
        access.requireUserPermission(actor, "REQUESTED".equals(
                observed.getRetentionStatus()) ? FILE_CLEANUP_PERMISSION : ExecutionAccessService.PERMISSION_ETL_RUN);
        tx().executeWithoutResult(status -> {
            var row = files.lock(request.getId());
            if (row == null || !Objects.equals(row.getOwnerId(), actor) || !Objects.equals(row.getRevision(),
                    request.getExpectedRevision())
                    || !Objects.equals(row.getRetentionStatus(), observed.getRetentionStatus()) || "PURGED".equals(
                    row.getRetentionStatus())) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            if (!Set.of("MANUAL", "RETRY").contains(row.getRecoveryStatus()) || files.liveRun(row.getRecordId(),
                    row.getRunToken()) != 0) {
                throw exception(ETL_FILE_RETRY_DENIED);
            }
            change(row, "MANUAL_RETRY", request.getReason(), next -> {
                next.setRecoveryStatus("PENDING");
                next.setAttempts(0);
                next.setNextAttemptAt(null);
                next.setRecoveryToken(null);
                next.setRecoveryLeaseUntil(null);
            });
        });
    }

    /** 操作须显式发起并限定归属者作用域；KEEP 文件的存储时长本身绝不构成删除授权。 */
    public EtlFileOutputRespVO requestRetirement(EtlFileRetireReqVO request) {
        Long actor = actor();
        access.requireUserPermission(actor, FILE_CLEANUP_PERMISSION);
        if (request == null || request.getId() == null || request.getId() <= 0 || request.getExpectedRevision() == null
                || request.getExpectedRevision() < 0 || !Boolean.TRUE.equals(
                request.getConfirmDeletion()) || request.getRetireAfter() == null
                || request.getReason() == null || request.getReason().isBlank() || request.getReason().length() > 500) {
            throw exception(ETL_FILE_RETRY_DENIED);
        }
        String reason = request.getReason().strip();
        return tx().execute(status -> {
            var row = files.lock(request.getId());
            if (row == null || !Objects.equals(row.getOwnerId(), actor)) {
                throw exception(ETL_FILE_NOT_OWNER);
            }
            if ("REQUESTED".equals(row.getRetentionStatus()) || "PURGED".equals(row.getRetentionStatus())) {
                Long requestRevision = files.retirementRequestRevision(row.getId());
                if (!Objects.equals(row.getRetirementRequestedBy(), actor) || !Objects.equals(row.getRetireAfter(),
                        request.getRetireAfter())
                        || !Objects.equals(row.getRetirementReason(), reason) || requestRevision == null
                        || (!Objects.equals(request.getExpectedRevision(), row.getRevision())
                        && !Objects.equals(request.getExpectedRevision(), requestRevision - 1))) {
                    throw exception(ETL_FILE_STATE_CHANGED);
                }
                // 即使已过期或完成，丢失的 HTTP 确认也可重放；不会创建新的执行意图。
                return BeanUtils.toBean(row, EtlFileOutputRespVO.class);
            }
            if (!"KEEP".equals(row.getRetentionStatus()) || !"NONE".equals(row.getPurgePhase())
                    || !Objects.equals(row.getRevision(), request.getExpectedRevision()) || !"DONE".equals(
                    row.getRecoveryStatus())) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            requireRetirementFacts(row);
            LocalDateTime now = records.databaseNow();
            if (request.getRetireAfter().isBefore(now.plusMinutes(1)) || request.getRetireAfter()
                    .isAfter(now.plusDays(90))) {
                throw exception(ETL_FILE_RETRY_DENIED);
            }
            change(row, "RETIRE_REQUEST", reason, next -> {
                next.setRetentionStatus("REQUESTED");
                next.setRetireAfter(request.getRetireAfter());
                next.setRetirementRequestedBy(actor);
                next.setRetirementReason(reason);
                next.setPurgePhase("NONE");
                next.setRecoveryStatus("PENDING");
                next.setAttempts(0);
                next.setRecoveryToken(null);
                next.setRecoveryLeaseUntil(null);
                next.setNextAttemptAt(null);
                next.setLastErrorCode(null);
            });
            return BeanUtils.toBean(row, EtlFileOutputRespVO.class);
        });
    }

    /** 全局标识扫描隔离在 Mapper 中；处理每个实际条目前校验业务记录。 */
    public void recoverPending() {
        int failures = 0;
        for (var candidate : files.candidates()) {
            try {
                recover(candidate.getId());
            } catch (RuntimeException unavailable) {
                failures++;
            }
        }
        if (failures != 0) {
            throw new IllegalStateException("部分文件恢复尚未完成：" + failures);
        }
    }

    /**
     * 恢复ETL 文件恢复。
     *
     * @param id 记录编号
     */
    public void recover(Long id) {
        var claimed = tx().execute(status -> {
            var row = files.lock(id);
            if (row == null || !Set.of("PENDING", "RETRY", "CLAIMED").contains(row.getRecoveryStatus())
                    || files.liveRun(row.getRecordId(), row.getRunToken()) != 0) {
                return null;
            }
            LocalDateTime now = records.databaseNow();
            if ("REQUESTED".equals(row.getRetentionStatus()) && (row.getRetireAfter() == null || row.getRetireAfter()
                    .isAfter(now))) {
                return null;
            }
            if (row.getRecoveryLeaseUntil() != null && row.getRecoveryLeaseUntil().isAfter(now)
                    || row.getNextAttemptAt() != null && row.getNextAttemptAt().isAfter(now)) {
                return null;
            }
            if (row.getAttempts() >= 10) {
                change(row, "MANUAL", "自动恢复次数已用尽", next -> next.setRecoveryStatus("MANUAL"));
                return null;
            }
            change(row, "CLAIM", "抢占已失去运行租约的文件意图", next -> {
                next.setRecoveryStatus("CLAIMED");
                next.setRecoveryToken(UUID.randomUUID().toString());
                next.setRecoveryLeaseUntil(now.plusMinutes(2));
                next.setAttempts(next.getAttempts() + 1);
            });
            return row;
        });
        if (claimed == null) {
            return;
        }
        if ("REQUESTED".equals(claimed.getRetentionStatus())) {
            recoverRetirement(claimed);
            return;
        }
        String state = null, failure = null;
        try {
            access.requireUserPermission(claimed.getOwnerId(), ExecutionAccessService.PERMISSION_ETL_RUN);
            var binding = EtlFileStore.binding(root(claimed.getStorageKey()),
                    namespace(claimed.getOwnerId(), claimed.getRecordId(), claimed.getNodeNo()),
                    claimed.getOperationKey(), claimed.getFileName());
            if (!binding.storageIdentity().equals(claimed.getStorageIdentity())) {
                state = "MANUAL";
                failure = "STORAGE_CHANGED";
            } else {
                EtlFileStore.Fingerprint prepared = claimed.getPayloadHash() == null ? null
                        : new EtlFileStore.Fingerprint(claimed.getTemporaryFileKey(), claimed.getPayloadBytes(),
                        claimed.getPayloadHash());
                state = EtlFileStore.reconcile(binding, claimed.getTemporaryFileKey(), prepared,
                        Boolean.TRUE.equals(claimed.getPublicationApproved()),
                        resources(claimed), cleaned -> tx().executeWithoutResult(status -> {
                            var current = files.lock(id);
                            if (current == null || !"CLAIMED".equals(current.getRecoveryStatus())
                                    || !Objects.equals(current.getRecoveryToken(), claimed.getRecoveryToken())) {
                                throw exception(ETL_FILE_STATE_CHANGED);
                            }
                            cleanResource(current, cleaned);
                        })).state();
                if ("MANUAL".equals(state)) {
                    failure = "IDENTITY_UNPROVEN";
                }
            }
        } catch (IOException io) {
            failure = "FILE_BUSY_OR_UNAVAILABLE";
        } catch (RuntimeException denied) {
            state = "MANUAL";
            failure = "AUTHORITY_OR_BINDING_CHANGED";
        }
        String resultState = state, error = failure;
        tx().executeWithoutResult(status -> {
            var current = files.lock(id);
            if (current == null || !"CLAIMED".equals(current.getRecoveryStatus())
                    || !Objects.equals(current.getRecoveryToken(), claimed.getRecoveryToken())) {
                return;
            }
            change(current, "RECONCILE", error == null ? "文件回执已核对" : error, next -> {
                next.setRecoveryToken(null);
                next.setRecoveryLeaseUntil(null);
                next.setLastErrorCode(error);
                if (resultState == null) {
                    next.setRecoveryStatus(next.getAttempts() >= 10 ? "MANUAL" : "RETRY");
                    next.setNextAttemptAt(
                            records.databaseNow().plusSeconds(Math.min(3600, 15L << Math.min(next.getAttempts(), 7))));
                } else {
                    next.setState(resultState);
                    next.setRecoveryStatus("MANUAL".equals(resultState) ? "MANUAL" : "DONE");
                    next.setNextAttemptAt(null);
                }
            });
        });
    }

    /**
     * 校验退役事实。
     *
     * @param row 当前行数据
     */
    private void requireRetirementFacts(EtlFileOutputDO row) {
        if (!"PUBLISHED".equals(row.getState()) || !Boolean.TRUE.equals(row.getPublicationApproved())
                || row.getPayloadBytes() == null || row.getPayloadHash() == null || row.getTemporaryFileKey() == null) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        EtlFileDownloadService.requireSuccessful(row, records.selectById(row.getRecordId()), row.getOwnerId());
        requireResourcesCleaned(row);
        new EtlFileStore.Fingerprint(row.getTemporaryFileKey(), row.getPayloadBytes(), row.getPayloadHash());
        if (files.otherIdentityReferences(row.getId(), row.getStorageIdentity(), row.getTemporaryFileKey()) != 0) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 恢复退役。
     *
     * @param claimed 已认领
     */
    private void recoverRetirement(EtlFileOutputDO claimed) {
        boolean complete = false, manual = false;
        String failure = null;
        try {
            access.requireUserPermission(claimed.getOwnerId(), "dataforge:etl:query");
            access.requireUserPermission(claimed.getOwnerId(), FILE_CLEANUP_PERMISSION);
            if (!Objects.equals(claimed.getRetirementRequestedBy(),
                    claimed.getOwnerId()) || claimed.getRetirementReason() == null) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            requireRetirementFacts(claimed);
            var binding = downloadBinding(claimed);
            var expected = new EtlFileStore.Fingerprint(claimed.getTemporaryFileKey(), claimed.getPayloadBytes(),
                    claimed.getPayloadHash());
            EtlFileStore.retirePublished(binding, expected,
                    EtlFileStore.RetirementPhase.valueOf(claimed.getPurgePhase()), phase -> {
                        // 哈希计算可能耗时；在平台事务之外重新校验当前清理权限。
                        if (phase != EtlFileStore.RetirementPhase.COMPLETE) {
                            access.requireUserPermission(claimed.getOwnerId(), "dataforge:etl:query");
                            access.requireUserPermission(claimed.getOwnerId(), FILE_CLEANUP_PERMISSION);
                        }
                        tx().executeWithoutResult(status -> {
                            var current = files.lock(claimed.getId());
                            requireRetirementClaim(current, claimed);
                            requireRetirementFacts(current);
                            if (phase.ordinal() < EtlFileStore.RetirementPhase.valueOf(current.getPurgePhase())
                                    .ordinal()) {
                                throw exception(ETL_FILE_STATE_CHANGED);
                            }
                            if (!phase.name().equals(current.getPurgePhase())) {
                                change(current, "RETIRE_PHASE", phase.name(), next -> next.setPurgePhase(phase.name()));
                            }
                        });
                    });
            complete = true;
        } catch (EtlFileStore.UnprovenIdentityException | IllegalArgumentException | NullPointerException unproven) {
            manual = true;
            failure = "RETIREMENT_IDENTITY_UNPROVEN";
        } catch (IOException busy) {
            failure = "FILE_BUSY_OR_UNAVAILABLE";
        } catch (RuntimeException unavailable) {
            // 已提交的文件解除链接操作若回执未知或丢失，按持久化的技术阶段重试。
            failure = "RETIREMENT_RECEIPT_OR_GUARD_CHANGED";
        }
        boolean finished = complete, requiresManual = manual;
        String error = failure;
        tx().executeWithoutResult(status -> {
            var current = files.lock(claimed.getId());
            if (current == null || !"REQUESTED".equals(current.getRetentionStatus()) || !"CLAIMED".equals(
                    current.getRecoveryStatus())
                    || !Objects.equals(current.getRecoveryToken(), claimed.getRecoveryToken())) {
                return;
            }
            change(current, "RETIRE_RECONCILE", error == null ? "已清理本人发布对象与身份锚点" : error, next -> {
                next.setRecoveryToken(null);
                next.setRecoveryLeaseUntil(null);
                next.setLastErrorCode(error);
                if (finished) {
                    next.setRetentionStatus("PURGED");
                    next.setPurgePhase("COMPLETE");
                    next.setRecoveryStatus("DONE");
                    next.setNextAttemptAt(null);
                } else if (requiresManual || next.getAttempts() >= 10) {
                    next.setRecoveryStatus("MANUAL");
                    next.setNextAttemptAt(null);
                } else {
                    next.setRecoveryStatus("RETRY");
                    next.setNextAttemptAt(
                            records.databaseNow().plusSeconds(Math.min(3600, 15L << Math.min(next.getAttempts(), 7))));
                }
                // 状态及成功运行的副作用仍保持 PUBLISHED/COMPLETE，历史记录仍记载原始写入。
            });
        });
    }

    /**
     * 校验退役认领。
     *
     * @param current 当前
     * @param claimed 已认领
     */
    private void requireRetirementClaim(EtlFileOutputDO current, EtlFileOutputDO claimed) {
        if (current == null || !"REQUESTED".equals(current.getRetentionStatus()) || !"CLAIMED".equals(
                current.getRecoveryStatus())
                || !Objects.equals(current.getRecoveryToken(),
                claimed.getRecoveryToken()) || current.getRecoveryLeaseUntil() == null
                || !current.getRecoveryLeaseUntil().isAfter(records.databaseNow()) || current.getRetireAfter() == null
                || current.getRetireAfter().isAfter(records.databaseNow()) || !Objects.equals(
                current.getRetirementRequestedBy(), current.getOwnerId())
                || !Objects.equals(current.getPipelineId(), claimed.getPipelineId())
                || !Objects.equals(current.getRunToken(), claimed.getRunToken()) || !Objects.equals(
                current.getRetireAfter(), claimed.getRetireAfter())
                || !Objects.equals(current.getRetirementReason(), claimed.getRetirementReason())
                || !Objects.equals(current.getRecordId(), claimed.getRecordId()) || !Objects.equals(
                current.getOwnerId(), claimed.getOwnerId())
                || !Objects.equals(current.getOperationKey(), claimed.getOperationKey()) || !Objects.equals(
                current.getStorageKey(), claimed.getStorageKey())
                || !Objects.equals(current.getStorageIdentity(), claimed.getStorageIdentity()) || !Objects.equals(
                current.getFileName(), claimed.getFileName())
                || !Objects.equals(current.getTemporaryFileKey(), claimed.getTemporaryFileKey()) || !Objects.equals(
                current.getPayloadHash(), claimed.getPayloadHash())
                || !Objects.equals(current.getPayloadBytes(), claimed.getPayloadBytes()) || !Objects.equals(
                current.getTemporaryResourcesJson(), claimed.getTemporaryResourcesJson())) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 获取ETL 文件恢复的根。
     *
     * @param key 标识键
     * @return 路径
     */
    private Path root(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw exception(ETL_FILE_STORAGE_DENIED);
        }
        String value = storageProperties.getStorageRoots().get(key);
        if (value == null || value.isBlank()) {
            throw exception(ETL_FILE_STORAGE_DENIED);
        }
        Path root = Path.of(value).toAbsolutePath().normalize();
        try {
            etlProperties.fileOutputPolicy().resolve(root.toString(), "etl-preflight-check");
        } catch (IOException | RuntimeException failure) {
            throw exception(ETL_FILE_STORAGE_DENIED);
        }
        return root;
    }

    /**
     * 获取ETL 文件恢复的存储键。
     *
     * @param directory 目录
     * @return ETL 文件恢复的存储键文本
     */
    private static String storageKey(String directory) {
        if (directory == null || !directory.matches("etl://[A-Za-z0-9_-]{1,64}")) {
            throw exception(ETL_FILE_STORAGE_DENIED);
        }
        return directory.substring(6);
    }

    /**
     * 获取ETL 文件恢复的命名空间。
     *
     * @param actor 操作人编号
     * @param record 运行记录
     * @param node 节点
     * @return ETL 文件恢复的命名空间结果集合
     */
    private static List<String> namespace(Long actor, Long record, String node) {
        if (actor == null || actor <= 0 || record == null || record <= 0) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        return List.of("owner-" + actor, "run-" + record, "node-" + DigestUtil.sha256Hex(node));
    }

    /**
     * 校验运行身份。
     *
     * @param record 运行记录
     */
    private void requireRunIdentity(EtlRecordDO record) {
        if (record == null || record.getExecutionUserId() == null
                || record.getExecutionToken() == null || record.getExecutionToken().isBlank()) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 校验持有执行权记录。
     *
     * @param id 记录编号
     * @param actor 操作人编号
     */
    private void requireOwnedRecord(Long id, Long actor) {
        var record = records.selectById(id);
        if (record == null || !Objects.equals(record.getExecutionUserId(), actor)) {
            throw exception(ETL_FILE_NOT_OWNER);
        }
    }

    /**
     * 查找ETL 文件输出。
     *
     * @param record 运行记录
     * @param node 节点
     * @return ETL 文件输出
     */
    private EtlFileOutputDO find(Long record, String node) {
        return files.selectOne(Wrappers.lambdaQuery(EtlFileOutputDO.class)
                .eq(EtlFileOutputDO::getRecordId, record).eq(EtlFileOutputDO::getNodeNo, node));
    }

    /**
     * 构造绑定当前执行权的状态筛选或更新条件。
     *
     * @param record 运行记录
     * @param action 待执行的操作
     */
    private void owned(EtlRecordDO record, Runnable action) {
        requireRunIdentity(record);
        if (!coordinator.writeOwned(record.getId(), record.getExecutionToken(), () -> {
            if (coordinator.shouldStop(record.getId(), record.getExecutionToken())) {
                throw exception(ETL_FILE_RETRY_DENIED);
            }
            action.run();
        })) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 校验载荷。
     *
     * @param row 当前行数据
     * @param fingerprint 指纹
     */
    private static void requirePayload(EtlFileOutputDO row, EtlFileStore.Fingerprint fingerprint) {
        if (!Objects.equals(row.getTemporaryFileKey(), fingerprint.fileKey()) || !Objects.equals(row.getPayloadHash(),
                fingerprint.sha256())
                || !Objects.equals(row.getPayloadBytes(), fingerprint.bytes())) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 获取ETL 文件恢复的资源。
     *
     * @param row 当前行数据
     * @return ETL 文件恢复的资源结果集合
     */
    private static List<EtlFileStore.TemporaryResource> resources(EtlFileOutputDO row) {
        String json = row.getTemporaryResourcesJson();
        if (json == null) {
            return new ArrayList<>();
        }
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        List<EtlFileStore.TemporaryResource> values;
        try {
            var mapper = JsonUtils.getObjectMapper();
            values = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, EtlFileStore.TemporaryResource.class));
        } catch (tools.jackson.core.JacksonException malformed) {
            // 损坏的持久化凭据可能包含任意文本；不得记录其内容，也不得保留含源片段的解析异常。
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        if (values == null || values.size() > 4 || values.stream().anyMatch(Objects::isNull)
                || values.stream().map(EtlFileStore.TemporaryResource::name).distinct().count() != values.size()) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        return new ArrayList<>(values);
    }

    /**
     * 编码资源。
     *
     * @param resources 资源
     * @return 编码资源文本
     */
    private static String encodeResources(List<EtlFileStore.TemporaryResource> resources) {
        String json = JsonUtils.toJsonString(resources);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        return json;
    }

    /**
     * 获取ETL 文件恢复的资源下标。
     *
     * @param resources 资源
     * @param name 名称
     * @return 资源下标数值
     */
    private static int resourceIndex(List<EtlFileStore.TemporaryResource> resources, String name) {
        for (int i = 0; i < resources.size(); i++) {
            if (resources.get(i).name().equals(name)) {
                return i;
            }
        }
        throw exception(ETL_FILE_STATE_CHANGED);
    }

    /**
     * 校验资源已清理。
     *
     * @param row 当前行数据
     */
    private static void requireResourcesCleaned(EtlFileOutputDO row) {
        if (resources(row).stream().anyMatch(r -> !"CLEANED".equals(r.state()))) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 清理已登记的文件或临时资源。
     *
     * @param row 当前行数据
     * @param cleaned 已清理
     */
    private void cleanResource(EtlFileOutputDO row, EtlFileStore.TemporaryResource cleaned) {
        var resources = resources(row);
        int index = resourceIndex(resources, cleaned.name());
        var before = resources.get(index);
        if (before.equals(cleaned)) {
            return;
        }
        if (!Objects.equals(before.fileKey(), cleaned.fileKey())) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        resources.set(index, cleaned);
        change(row, "TEMP_CLEANED", cleaned.name(), next -> next.setTemporaryResourcesJson(encodeResources(resources)));
    }

    /**
     * 变更ETL 文件恢复。
     *
     * @param row 当前行数据
     * @param action 待执行的操作
     * @param reason 操作原因
     * @param update 更新
     */
    private void change(EtlFileOutputDO row, String action, String reason, Consumer<EtlFileOutputDO> update) {
        String before = summary(row);
        long oldRevision = row.getRevision();
        update.accept(row);
        row.setRevision(oldRevision + 1);
        int count = files.update(null,
                Wrappers.lambdaUpdate(EtlFileOutputDO.class).eq(EtlFileOutputDO::getId, row.getId())
                        .eq(EtlFileOutputDO::getRevision, oldRevision)
                        .set(EtlFileOutputDO::getState, row.getState())
                        .set(EtlFileOutputDO::getTemporaryFileKey, row.getTemporaryFileKey())
                        .setSql("temporary_resources_json=CAST({0} AS jsonb)", row.getTemporaryResourcesJson())
                        .set(EtlFileOutputDO::getPayloadHash, row.getPayloadHash())
                        .set(EtlFileOutputDO::getPayloadBytes, row.getPayloadBytes())
                        .set(EtlFileOutputDO::getPublicationApproved, row.getPublicationApproved())
                        .set(EtlFileOutputDO::getRecoveryStatus, row.getRecoveryStatus())
                        .set(EtlFileOutputDO::getAttempts, row.getAttempts())
                        .set(EtlFileOutputDO::getRecoveryToken, row.getRecoveryToken())
                        .set(EtlFileOutputDO::getRecoveryLeaseUntil, row.getRecoveryLeaseUntil())
                        .set(EtlFileOutputDO::getNextAttemptAt, row.getNextAttemptAt())
                        .set(EtlFileOutputDO::getLastErrorCode, row.getLastErrorCode())
                        .set(EtlFileOutputDO::getRevision, row.getRevision())
                        .set(EtlFileOutputDO::getRetentionStatus, row.getRetentionStatus())
                        .set(EtlFileOutputDO::getRetireAfter, row.getRetireAfter())
                        .set(EtlFileOutputDO::getRetirementRequestedBy, row.getRetirementRequestedBy())
                        .set(EtlFileOutputDO::getRetirementReason, row.getRetirementReason())
                        .set(EtlFileOutputDO::getPurgePhase, row.getPurgePhase()));
        if (count != 1) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        audit(row, before, action, reason);
    }

    /**
     * 记录ETL 文件恢复审计信息。
     *
     * @param row 当前行数据
     * @param before 变更前状态
     * @param action 待执行的操作
     * @param reason 操作原因
     */
    private void audit(EtlFileOutputDO row, String before, String action, String reason) {
        if (files.audit(row.getId(), row.getOwnerId(), action, before, summary(row), row.getRevision(), reason) != 1) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 汇总ETL 文件恢复信息。
     *
     * @param row 当前行数据
     * @return 汇总ETL 文件恢复信息文本
     */
    private static String summary(EtlFileOutputDO row) {
        String manifest;
        try {
            manifest = encodeResources(resources(row));
        } catch (RuntimeException unverified) {
            // 无效持久化凭据仍须能被抢占并转为 MANUAL，不得通过规范化使其看似有效。
            manifest = "UNVERIFIED:" + row.getTemporaryResourcesJson();
        }
        return row.getState() + "/" + row.getRecoveryStatus() + "/" + row.getRevision()
                + "/retention:" + row.getRetentionStatus() + "/" + row.getPurgePhase()
                + "/aux:" + DigestUtil.sha256Hex(manifest).substring(0, 16);
    }

    /**
     * 获取ETL 文件恢复的操作人。
     *
     * @return 操作人数值
     */
    private Long actor() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(ETL_FILE_NOT_OWNER);
        }
        access.requireUserPermission(actor, "dataforge:etl:query");
        return actor;
    }

    /**
     * 创建当前业务操作使用的本地事务模板。
     *
     * @return 事务模板
     */
    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
