package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectPlan;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_UNAVAILABLE;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.OPERATION_PERMISSION_DENIED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_PRODUCT_TYPE_UNSUPPORTED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_QUERY_SQL_INVALID;

/** DataForge 拥有的执行事实查询；不读 Governance 表，不建立到数或因果承诺。 */
@Service
public class SyncBatchObservationService {

    public static final int WINDOW = 20;

    @Resource
    private DataAccessTaskMapper runMapper;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private AccessDatabaseResolver resolver;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private DatabaseApi databaseApi;

    /**
     * 读取同步批次观测当前观测状态。
     *
     * @param request 请求参数
     * @return 同步批次观测
     */
    public SyncBatchObservationDTO observe(SyncBatchQueryReqDTO request) {
        Target target = authorize(request);
        var database = target.database();
        var relation = target.relation();
        var product = target.product();

        return observeAuthorized(request, database, relation, product);
    }

    /** 唯一的授权入口，供只读观察和边界登记共用；RPC 均在本地事务外。 */
    Target authorize(SyncBatchQueryReqDTO request) {
        // 以下权限 RPC 必须在平台事务和行锁之外执行，不扩大执行用户的数据范围。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Sync evidence observation must be outside a transaction");
        }

        if (request == null || request.executionUserId() == null
                || request.executionUserId() <= 0 || request.databaseId() == null || request.databaseId() <= 0
                || request.tableReference() == null || request.tableReference().length() > 2048) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        access.requireUserPermission(request.executionUserId(), "governance:quality-task:execute");
        var database = databaseMapper.selectById(request.databaseId());
        if (database == null || !Integer.valueOf(0).equals(database.getStatus())) {
            throw exception(DATABASE_UNAVAILABLE);
        }
        var connection = resolver.resolve(database);
        var product = connection.getProductType();
        RestrictedSelectPlan relation;
        try {
            relation = RestrictedSelectGuard.parseRelationReference(product, request.tableReference(),
                    connection.getSchema());
        } catch (UnsupportedOperationException unsupported) {
            throw exception(DATA_ACCESS_PRODUCT_TYPE_UNSUPPORTED, product.name());
        } catch (IllegalArgumentException invalid) {
            throw exception(DATABASE_QUERY_SQL_INVALID, "同步批次查询要求单一明确表引用");
        }
        // 核验精确物理对象存在；统计读取沿用任务操作权限。
        var columnsRequest = new DatabaseQueryColumnsReqDTO();
        columnsRequest.setDatabaseId(database.getId());
        columnsRequest.setSchemaName(relation.getSchemaName());
        columnsRequest.setTableName(relation.getTableName());
        var metadata = databaseApi.getQueryTableColumns(columnsRequest).getCheckedData();
        if (metadata == null || metadata.isEmpty()) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        return new Target(database, product, relation);
    }

    record Target(cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO database,
                  cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum product,
                  RestrictedSelectPlan relation) {

    }

    /**
     * 观测已授权。
     *
     * @param request 请求参数
     * @param database 数据源
     * @param relation 关系
     * @param product 产品
     * @return 同步批次观测
     */
    private SyncBatchObservationDTO observeAuthorized(SyncBatchQueryReqDTO request,
            cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO database,
            RestrictedSelectPlan relation, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum product) {
        var runs = runMapper.selectSyncObservations(database.getId().toString(), WINDOW + 1);
        var batches = new ArrayList<SyncBatchObservationDTO.Batch>();
        boolean unscoped = false;
        for (var run : runs.subList(0, Math.min(WINDOW, runs.size()))) {
            SyncBatchEvidenceDTO evidence;
            try {
                // 不使用会把损坏 JSON 原文写入日志的便捷入口。
                evidence = run.getSyncEvidence() == null ? null
                        : JsonUtils.getObjectMapper().readValue(run.getSyncEvidence(), SyncBatchEvidenceDTO.class);
            } catch (RuntimeException malformed) {
                evidence = null;
            }
            if (evidence == null || !Integer.valueOf(1).equals(evidence.schemaVersion())
                    || !Objects.equals(evidence.runId(), run.getId())
                    || !Objects.equals(evidence.attempt(), run.getAttemptCount())
                    || !Objects.equals(evidence.targetDatabaseId(), database.getId())
                    || !product.name().equals(evidence.targetProduct()) || evidence.tables().size() > 2000) {
                unscoped = true;
                continue;
            }
            var matches = evidence.tables().stream().filter(Objects::nonNull).filter(table ->
                    Objects.equals(relation.getSchemaName(), table.targetSchema())
                            && Objects.equals(relation.getTableName(), table.targetTable())).toList();
            if (matches.size() != 1) {
                // 没有表级清单的运行可能触及目标；不把它丢掉后再只报告某个旧成功。
                unscoped = true;
                continue;
            }
            var table = matches.getFirst();
            batches.add(new SyncBatchObservationDTO.Batch(run.getId(), run.getAttemptCount(), run.getRowVersion(),
                    run.getRunStatus(), !Boolean.FALSE.equals(run.getOccupiesSlot()),
                    !Boolean.FALSE.equals(run.getReviewRequired()), !Boolean.FALSE.equals(run.getStopRequested()),
                    run.getEffectState(), evidence.targetRevision(), evidence.mode(), evidence.termination(),
                    evidence.manifestComplete(), table.successful(), table.rows(), run.getSyncStartPosition(),
                    table.transfer()));
        }
        return new SyncBatchObservationDTO(1, database.getId(),
                database.getUpdateTime() == null ? null : database.getUpdateTime().toString(),
                product.name(), relation.getSchemaName(), relation.getTableName(), request.tableReference(),
                runs.size() > WINDOW, unscoped, batches);
    }
}
