package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/** 短平台事务维护部署领域占用；远程校验和目标数据库执行均由事务外调用方完成。 */
@Service
public class ModelDeploymentAttemptStore {

    @Resource
    private ModelDeploymentAttemptMapper attempts;

    @Resource
    private ModelMaterializedMapper materialized;

    @Resource
    private ModelMaterializedLogMapper logs;

    @Resource
    private LogicModelMapper models;

    @Resource
    private LogicModelColumnService columns;

    @Resource
    private LogicModelService modelService;

    /**
     * 在模型锁内核对冻结快照，保存部署尝试并占用物理目标。
     *
     * @param desired 预期目标状态
     * @param attempt 当前部署尝试
     * @return 模型部署尝试
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentAttemptDO reserve(ModelMaterializedDO desired, ModelDeploymentAttemptDO attempt) {
        var model = models.selectByIdForUpdate(desired.getModelId());
        if (model == null || !Integer.valueOf(1).equals(model.getEnabled())
                || !LogicModelDO.STATUS_PUBLISHED.equals(model.getStatus())) {
            throw exception(MODEL_MATERIALIZED_MODEL_UNPUBLISHED);
        }
        // 锁内确认当前设计与准备时的任务输入完全一致，禁止把旧DDL配给新设计。
        if (!Objects.equals(model.getId(), attempt.getModelId())
                || !Objects.equals(model.getRevision(), attempt.getDesignRevision())
                || !Objects.equals(cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(
                        attempt.getFieldsJson(), cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem.class),
                        columns.listByModelId(model.getId()))
                || !Objects.equals(taskModelInput(model), attempt.getModelSnapshotJson())) {
            throw exception(MODEL_PUBLISH_CONTENT_CHANGED);
        }
        modelService.validateCurrentDependencies(model, columns.listByModelId(model.getId()));
        var existing = materialized.selectByModelAndDatasource(desired.getModelId(), desired.getDatasourceId());
        // UNKNOWN 仍代表目标可能已被写入，不能释放给新部署，否则对账会失去原始参照。
        if (existing != null && (ModelMaterializedDO.STATUS_RUNNING.equals(existing.getStatus())
                || ModelMaterializedDO.STATUS_UNKNOWN.equals(existing.getStatus()))) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
        desired.setStatus(ModelMaterializedDO.STATUS_RUNNING);
        desired.setDesignRevision(attempt.getDesignRevision());
        desired.setFieldsJson(attempt.getFieldsJson());
        if (existing == null) {
            materialized.insert(desired);
        } else {
            desired.setId(existing.getId());
        }
        attempt.setMaterializedId(desired.getId());

        attempt.setStatus(ModelMaterializedDO.STATUS_RUNNING);
        attempt.setPhase("PREPARED");
        attempt.setLeaseOwner(UUID.randomUUID().toString());
        attempt.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        try {
            attempts.insert(attempt);
        } catch (DuplicateKeyException conflict) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
        desired.setAttemptId(attempt.getId());
        if (materialized.updateById(desired) != 1) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
        return attempt;
    }

    /**
     * 读取模型部署尝试。
     *
     * @param id 记录编号
     * @return 模型部署尝试
     */
    public ModelDeploymentAttemptDO get(Long id) {
        var attempt = attempts.selectById(id);
        if (attempt == null) {
            throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
        }
        return attempt;
    }


    /**
     * 将当前部署尝试推进到外部执行阶段并延长租约。
     *
     * @param attempt 当前部署尝试
     */
    public void executing(ModelDeploymentAttemptDO attempt) {
        if (attempts.update(null,
                fenced(attempt).eq(ModelDeploymentAttemptDO::getStatus, ModelMaterializedDO.STATUS_RUNNING)
                        .set(ModelDeploymentAttemptDO::getPhase, "EXECUTING")
                        .set(ModelDeploymentAttemptDO::getLeaseUntil, LocalDateTime.now().plusMinutes(5))) != 1) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
    }

    /**
     * 为当前仍持有执行权的部署尝试续约。
     *
     * @param attempt 当前部署尝试
     */
    public void heartbeat(ModelDeploymentAttemptDO attempt) {
        if (attempts.update(null, fenced(attempt).in(ModelDeploymentAttemptDO::getStatus,
                        ModelMaterializedDO.STATUS_RUNNING, ModelMaterializedDO.STATUS_UNKNOWN)
                .set(ModelDeploymentAttemptDO::getLeaseUntil, LocalDateTime.now().plusMinutes(5))) != 1) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
    }

    /** 过期执行只能接管为对账，不释放物理目标给新部署。 */
    public ModelDeploymentAttemptDO claimRecovery(Long id) {
        String owner = UUID.randomUUID().toString();
        if (attempts.update(null,
                Wrappers.<ModelDeploymentAttemptDO>lambdaUpdate().eq(ModelDeploymentAttemptDO::getId, id)
                        .in(ModelDeploymentAttemptDO::getStatus, ModelMaterializedDO.STATUS_RUNNING,
                                ModelMaterializedDO.STATUS_UNKNOWN)
                        .le(ModelDeploymentAttemptDO::getLeaseUntil, LocalDateTime.now())
                        .set(ModelDeploymentAttemptDO::getStatus, ModelMaterializedDO.STATUS_UNKNOWN)
                        .set(ModelDeploymentAttemptDO::getLeaseOwner, owner)
                        .set(ModelDeploymentAttemptDO::getLeaseUntil, LocalDateTime.now().plusMinutes(5))) != 1) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
        return get(id);
    }

    /**
     * 以有效租约原子更新部署尝试、物化记录和日志，未知结果继续保留待对账状态。
     *
     * @param attempt 当前部署尝试
     * @param status 目标状态
     * @param message 消息
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(ModelDeploymentAttemptDO attempt, String status, String message) {
        // 未知结果只更新观测状态；仅确定结果结束尝试，部署记录和日志与本次状态一起提交。
        boolean terminal = !ModelMaterializedDO.STATUS_UNKNOWN.equals(status);
        var update = fenced(attempt).in(ModelDeploymentAttemptDO::getStatus,
                        ModelMaterializedDO.STATUS_RUNNING, ModelMaterializedDO.STATUS_UNKNOWN)
                .set(ModelDeploymentAttemptDO::getStatus, status).set(ModelDeploymentAttemptDO::getMessage, message)
                .set(ModelDeploymentAttemptDO::getLeaseUntil, LocalDateTime.now());
        if (terminal) {
            update.set(ModelDeploymentAttemptDO::getPhase, "FINISHED")
                    .set(ModelDeploymentAttemptDO::getFinishedTime, LocalDateTime.now());
        }
        if (attempts.update(null, update) != 1) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
        if (materialized.update(null, Wrappers.<ModelMaterializedDO>lambdaUpdate()
                .eq(ModelMaterializedDO::getId, attempt.getMaterializedId())
                .eq(ModelMaterializedDO::getAttemptId, attempt.getId())
                .set(ModelMaterializedDO::getStatus, status).set(ModelMaterializedDO::getMessage, message)) != 1) {
            throw exception(MODEL_MATERIALIZED_TARGET_BUSY);
        }
        var row = materialized.selectById(attempt.getMaterializedId());
        logs.insert(ModelMaterializedLogDO.builder().materializedId(row.getId()).modelId(row.getModelId())
                .attemptId(attempt.getId()).modelName(row.getModelName()).modelAlias(row.getModelAlias())
                .datasourceId(row.getDatasourceId()).datasourceName(row.getDatasourceName())
                .datasourceType(row.getDatasourceType()).description(row.getDescription())
                .publishMode(attempt.getPublishMode()).status(status).ddlSql(attempt.getDdlSql()).message(message)
                .operator(String.valueOf(
                        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()))
                .startTime(attempt.getCreateTime()).endTime(LocalDateTime.now()).build());
    }

    /**
     * 构造绑定租约持有者和有效期的更新条件，隔离已失去执行权的实例。
     *
     * @param attempt 当前部署尝试
     * @return Lambda更新条件包装器模型部署尝试
     */
    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModelDeploymentAttemptDO> fenced(
            ModelDeploymentAttemptDO attempt) {
        // 标识与有效期共同隔离执行权，已被恢复流程接管的旧执行者不能续约或回写结果。
        return Wrappers.<ModelDeploymentAttemptDO>lambdaUpdate().eq(ModelDeploymentAttemptDO::getId, attempt.getId())
                .eq(ModelDeploymentAttemptDO::getLeaseOwner, attempt.getLeaseOwner())
                .gt(ModelDeploymentAttemptDO::getLeaseUntil, LocalDateTime.now());
    }
    /** 现有部署执行记录的最小任务输入，非模型历史，不能被后续设计刷新。 */
    static String taskModelInput(LogicModelDO model) {
        LogicModelDO input = LogicModelDO.builder().id(model.getId()).revision(model.getRevision())
                .name(model.getName()).code(model.getCode()).layerId(model.getLayerId())
                .subjectDomainId(model.getSubjectDomainId()).dataDomainId(model.getDataDomainId())
                .bizCategoryId(model.getBizCategoryId()).tableType(model.getTableType())
                .typeConfig(model.getTypeConfig()).tableNameNotation(model.getTableNameNotation()).build();
        return cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(input);
    }

}
