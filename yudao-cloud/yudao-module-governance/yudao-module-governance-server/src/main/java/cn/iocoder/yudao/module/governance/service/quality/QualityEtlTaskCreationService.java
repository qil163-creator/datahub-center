package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 治理领域创建编排；预检无本地锁，检查对象、任务关系和稳定回执同一短事务落库。 */
@Service
@Validated
public class QualityEtlTaskCreationService {

    @Resource
    private QualityEtlTaskCreationMapper creations;

    @Resource
    private QualityRuleMapper rules;

    @Resource
    private QualityTaskService tasks;

    @Resource
    private QualityCheckObjectService objects;

    @Resource
    private QualityTaskExecutor executor;

    @Resource
    private MasterDatabaseResolver databases;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 创建目标检查对象和 SQL 任务并保留幂等回执；不启动检测。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QualityEtlTaskCreateRespVO create(@Valid QualityEtlTaskCreateReqVO request) {
        long actor = actor();
        List<Long> ids = request.getRuleIds().stream().distinct().sorted().toList();
        if (ids.size() != request.getRuleIds().size()) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        String name = request.getTaskName().strip();
        String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of("ETL_TASK_CREATE_V1", actor,
                name, request.getDatabaseId(), request.getSchemaName(), request.getTableName(), ids,
                request.getActivate())));
        var previous = creations.find(request.getRequestId());
        if (previous != null) {
            return receipt(previous, actor, hash, true);
        }

        var connection = databases.resolve(request.getDatabaseId(), QUALITY_CHECK_OBJECT_NOT_EXISTS);
        // 当前批次质检按连接默认 schema + 裸表元数据名称匹配；未证明的引用语法不自动创建可用任务。
        if (connection.getProductType() != ProductTypeEnum.POSTGRESQL
                || !Objects.equals(connection.getDatabaseId(), request.getDatabaseId())
                || !Objects.equals(connection.getSchema(), request.getSchemaName())) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        var table = databaseApi.resolveQueryTable(request.getDatabaseId(), request.getTableName()).getCheckedData();
        if (table == null || !Objects.equals(table.getSchemaName(), request.getSchemaName())
                || table.getTableName() == null || !table.getTableName().matches("[a-z_][a-z0-9_]{0,62}")
                || table.getNormalizedSql() == null || table.getNormalizedSql().isBlank()) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        var columnRequest = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO();
        columnRequest.setDatabaseId(request.getDatabaseId());
        columnRequest.setSchemaName(table.getSchemaName());
        columnRequest.setTableName(table.getTableName());
        var columns = databaseApi.getQueryTableColumns(columnRequest).getCheckedData();
        if (columns == null || columns.isEmpty() || columns.size() > 2000) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        var selected = loadRules(ids, false);
        executor.validateEtlDraftRules(connection,
                QualityCheckObjectDO.builder().tableName(table.getTableName()).build(), selected,
                columns.stream()
                        .map(cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO::getColumnName)
                        .toList());
        String definitionHash = ruleHash(selected);
        var row = new QualityEtlTaskCreationDO();
        row.setRequestId(request.getRequestId());
        row.setRequestHash(hash);
        row.setActorId(actor);
        row.setTaskName(name);
        row.setTaskStatus(Boolean.TRUE.equals(request.getActivate()) ? 0 : 1);
        row.setDefinitionHash(definitionHash);
        row.setTargetJson(JsonUtils.toJsonString(
                Map.of("databaseId", request.getDatabaseId(), "schemaName", table.getSchemaName(),
                        "tableName", table.getTableName(), "ruleIds", ids)));
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return tx.execute(status -> {
            if (actor() != actor) {
                throw exception(QUALITY_RUN_FORBIDDEN);
            }
            int reserved = creations.reserve(row);
            var locked = creations.lock(request.getRequestId());
            if (reserved == 0) {
                return receipt(locked, actor, hash, true);
            }
            if (locked == null || locked.getTaskId() != null) {
                throw exception(QUALITY_RUN_KEY_CONFLICT);
            }
            // 防止预检期间规则被编辑；按编号加共享锁，创建完成后释放，锁内无远端调用。
            if (!Objects.equals(definitionHash, ruleHash(loadRules(ids, true)))) {
                throw exception(QUALITY_RUN_KEY_CONFLICT);
            }
            var object = new QualityCheckObjectSaveReqVO();
            object.setObjectName("ETL-" + request.getRequestId());
            object.setDatasourceConnId(request.getDatabaseId());
            object.setTableName(table.getTableName());
            object.setFormatName(table.getSchemaName());
            object.setRemark("由 ETL 目标生成独立检测对象");
            Long objectId = objects.createCheckObject(object);
            var task = new QualityTaskSaveReqVO();
            task.setTaskName(name);
            task.setTaskCode("ETL-" + request.getRequestId());
            task.setTaskCategory("ETL");
            task.setScheduleType(QualityTaskDO.SCHEDULE_MANUAL);
            task.setExecuteStrategy(QualityTaskDO.STRATEGY_SERIAL);
            task.setRetryCount(0);
            task.setOwnerUserId(actor);
            task.setTaskStatus(row.getTaskStatus());
            task.setObjectIds(List.of(objectId));
            task.setRuleIds(ids);
            task.setDescription("由 ETL 目标创建；ETL 运行按已提交批次独立检测。手动执行任务将按其对象范围检测。");
            Long taskId = tasks.createTask(task);
            if (objectId == null || taskId == null || creations.finish(locked.getId(), taskId, objectId) != 1) {
                throw exception(QUALITY_RUN_NOT_READY);
            }
            return receipt(creations.find(request.getRequestId()), actor, hash, false);
        });
    }

    /**
     * 查询本人创建检测任务的原始回执，用于请求结果未知时核对。
     */
    public QualityEtlTaskCreateRespVO result(String requestId) {
        long actor = actor();
        var stored = creations.find(requestId);
        return stored == null ? null : receipt(stored, actor, null, true);
    }

    /**
     * 加载质量ETL任务创建的规则。
     */
    private List<QualityRuleDO> loadRules(List<Long> ids, boolean lock) {
        var query = new LambdaQueryWrapperX<QualityRuleDO>().in(QualityRuleDO::getId, ids)
                .orderByAsc(QualityRuleDO::getId);
        if (lock) {
            query.last("FOR SHARE");
        }
        var selected = rules.selectList(query);
        if (selected.size() != ids.size() || selected.stream()
                .anyMatch(rule -> !QualityRuleDO.STATUS_ENABLE.equals(rule.getStatus()))) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        return selected;
    }

    /**
     * 计算 ETL 质量任务规则集合的稳定摘要。
     */
    private static String ruleHash(List<QualityRuleDO> rows) {
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(rows));
    }

    /**
     * 将任务创建结果转换为可重放的接口回执。
     */
    private static QualityEtlTaskCreateRespVO receipt(QualityEtlTaskCreationDO row, long actor, String hash,
            boolean replay) {
        if (row == null || !Objects.equals(row.getActorId(), actor) || hash != null && !Objects.equals(hash,
                row.getRequestHash())) {
            throw exception(QUALITY_RUN_KEY_CONFLICT);
        }
        if (row.getTaskId() == null || row.getCheckObjectId() == null) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        return new QualityEtlTaskCreateRespVO(row.getRequestId(), row.getTaskId(), row.getCheckObjectId(),
                row.getTaskName(), row.getTaskStatus(), replay);
    }

    /**
     * 读取质量ETL任务创建当前操作人的身份信息。
     */
    private static long actor() {
        Long id = SecurityFrameworkUtils.getLoginUserId();
        if (id == null || id <= 0) {
            throw exception(QUALITY_RUN_FORBIDDEN);
        }
        return id;
    }

}
