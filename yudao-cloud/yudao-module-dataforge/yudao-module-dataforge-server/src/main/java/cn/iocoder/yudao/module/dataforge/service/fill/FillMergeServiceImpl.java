package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillMergeBatchPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillMergeBatchRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.*;
import cn.iocoder.yudao.module.dataforge.enums.fill.*;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-合并服务实现类
 *
 * 幂等策略（重试收敛）：
 *   APPEND        先删本批次已写入范围（source_submission_id = 本提交）再整批插入
 *   UPSERT        按业务键逐行存在判断，插入或更新（含系统来源列一并覆盖）
 *   REPLACE_SCOPE 先删 (source_org_id, period_key) 范围再整批插入
 * 目标表须含系统来源列（source_org_id / period_key / source_submission_id / source_row_id），
 * 发布物理表时已自动追加；存量表缺列会合并失败并提示重新发布。
 * 血缘明细本地先删后插，与目标写入同为可重放操作
 *
 * @author Li
 */
@Slf4j
@Service
public class FillMergeServiceImpl implements FillMergeService {

    @Resource
    private FillHierarchyService hierarchyService;

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    /** 系统来源列（发布建表自动追加；合并写入强制携带） */
    public static final String SYS_COL_ORG_ID = "source_org_id";

    public static final String SYS_COL_PERIOD_KEY = "period_key";

    public static final String SYS_COL_SUBMISSION_ID = "source_submission_id";

    public static final String SYS_COL_ROW_ID = "source_row_id";

    /** periodKey 白名单格式（拼接 REPLACE_SCOPE 删除 SQL 前强校验，防注入） */
    private static final Pattern PERIOD_KEY_PATTERN = Pattern.compile("^[0-9A-Za-z\\-]{1,32}$");

    private static final long RETRY_BASE_MINUTES = 5;

    private static final long RETRY_MAX_MINUTES = 360;

    /** 自动重试次数上限；超过后不再自动重试，只能人工重试或退回整改 */
    public static final int MAX_AUTO_RETRY = 8;

    /** 批次处于“执行中”超过该时长视为进程中断遗留，允许重新接管执行 */
    public static final long STALE_RUNNING_MINUTES = 60;

    @Resource
    private FillMergeBatchMapper mergeBatchMapper;

    @Resource
    private FillMergeDetailMapper mergeDetailMapper;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillSubmissionRowMapper submissionRowMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private FillNotifyService notifyService;

    @Resource
    private FillCollabService collabService;

    @Resource
    private FillCorrectionService correctionService;

    /**
     * 为审批通过的提交创建合并批次（重复调用返回既有批次）
     *
     * @param submissionId 提交编号
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createBatchForSubmission(Long submissionId) {
        FillMergeBatchDO exists = mergeBatchMapper.selectBySubmissionId(submissionId);
        if (exists != null) {
            return exists.getId();
        }
        FillSubmissionDO submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw exception(FILL_SUBMISSION_NOT_EXISTS);
        }
        FillTableVersionDO version = tableVersionService.getTableVersion(submission.getTableVersionId());
        Map<?, ?> targetMapping = JsonUtils.parseObject(version.getTargetMapping(), Map.class);
        FillMergeBatchDO batch = FillMergeBatchDO.builder()
                .submissionId(submissionId)
                .workItemId(submission.getWorkItemId())
                .taskId(submission.getTaskId())
                .mergeStrategy(version.getMergeStrategy())
                .targetDatabaseId(Long.valueOf(String.valueOf(targetMapping.get("databaseId"))))
                .targetTableName(String.valueOf(targetMapping.get("tableName")))
                .status(FillMergeBatchStatusEnum.PENDING.getStatus())
                .totalCount(submission.getRowCount())
                .successCount(0)
                .retryCount(0)
                .build();
        try {
            mergeBatchMapper.insert(batch);
            return batch.getId();
        } catch (DuplicateKeyException ex) {
            // UNIQUE(submission_id)：重复审批事件/并发创建收敛到同一批次
            return mergeBatchMapper.selectBySubmissionId(submissionId).getId();
        }
    }

    /**
     * 执行合并批次（内部先做执行抢占 CAS，Job 与人工触发均安全）
     *
     * @param batchId 批次编号
     */
    @Override
    public void executeBatch(Long batchId) {
        FillMergeBatchDO batch = mergeBatchMapper.selectById(batchId);
        if (batch == null) {
            return;
        }
        // 执行抢占 CAS：只允许从 待执行/失败 进入执行中，多实例安全；
        // 进程中断遗留的“执行中”批次超时后按开始时间抢占接管（写入可重放，重复执行结果一致）
        Integer status = batch.getStatus();
        boolean acquired;
        if (FillMergeBatchStatusEnum.RUNNING.getStatus().equals(status)) {
            acquired = mergeBatchMapper.takeOverStaleRunning(batchId,
                    LocalDateTime.now().minusMinutes(STALE_RUNNING_MINUTES)) > 0;
        } else {
            acquired = (FillMergeBatchStatusEnum.PENDING.getStatus().equals(status)
                    || FillMergeBatchStatusEnum.FAILED.getStatus().equals(status))
                    && mergeBatchMapper.compareAndSetRunning(batchId, status) > 0;
        }
        if (!acquired) {
            return;
        }
        // 工作项进入合并中（首次从待合并、重试从合并失败）
        workItemMapper.compareAndSetStatus(batch.getWorkItemId(),
                FillWorkItemStatusEnum.WAIT_MERGE.getStatus(), FillWorkItemStatusEnum.MERGING.getStatus());
        workItemMapper.compareAndSetStatus(batch.getWorkItemId(),
                FillWorkItemStatusEnum.MERGE_FAILED.getStatus(), FillWorkItemStatusEnum.MERGING.getStatus());
        try {
            int written = doMerge(batch);
            markBatchSuccess(batch, written);
            onMergeSuccess(batch);
        } catch (Exception ex) {
            log.error("[executeBatch][合并批次({}) 执行失败]", batchId, ex);
            markBatchFailed(batch, ex);
            // 只在首次失败时通知填报人；之后的自动/人工重试再失败不重复打扰
            onMergeFailed(batch, batch.getRetryCount() == null || batch.getRetryCount() == 0);
        }
    }

    /**
     * 人工重试失败批次：置回待执行，由合并 Job 拾取
     *
     * @param batchId 批次编号
     */
    @Override
    public void retryBatch(Long batchId) {
        FillMergeBatchDO batch = mergeBatchMapper.selectById(batchId);
        if (batch == null) {
            throw exception(FILL_MERGE_BATCH_NOT_EXISTS);
        }
        if (!FillMergeBatchStatusEnum.FAILED.getStatus().equals(batch.getStatus())) {
            throw exception(FILL_MERGE_BATCH_STATUS_INVALID);
        }
        FillMergeBatchDO update = new FillMergeBatchDO();
        update.setId(batchId);
        update.setStatus(FillMergeBatchStatusEnum.PENDING.getStatus());
        update.setNextRetryTime(null);
        mergeBatchMapper.updateById(update);
    }

    /**
     * 把合并失败的批次退回整改。
     *
     * <p>批次“失败 → 已退回”与工作项“合并失败 → 整改中”都走 CAS，与合并 Job 的自动重试互斥；
     * 任一迁移失败整体回滚。原提交快照与审批记录保留，填报人修改后重新提交会生成新快照并重新审批。</p>
     *
     * @param batchId 批次编号
     * @param reason 退回原因
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnToRework(Long batchId, String reason) {
        FillMergeBatchDO batch = mergeBatchMapper.selectById(batchId);
        if (batch == null) {
            throw exception(FILL_MERGE_BATCH_NOT_EXISTS);
        }
        if (mergeBatchMapper.compareAndSetStatus(batchId, FillMergeBatchStatusEnum.FAILED.getStatus(),
                FillMergeBatchStatusEnum.RETURNED.getStatus()) == 0
                || workItemMapper.compareAndSetStatus(batch.getWorkItemId(),
                FillWorkItemStatusEnum.MERGE_FAILED.getStatus(), FillWorkItemStatusEnum.REWORKING.getStatus()) == 0) {
            throw exception(FILL_MERGE_BATCH_STATUS_INVALID);
        }
        // 退回的是更正提交时，更正回到草拟，重新提交后继续按更正处理
        correctionService.reopenBySubmissionId(batch.getSubmissionId());
        notifyBatch(batch, FillNotifyTypeEnum.MERGE_RETURNED, Map.of("reason",
                StrUtil.blankToDefault(StrUtil.maxLength(StrUtil.trim(reason), 200), "数据未能写入正式表")));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcastStatus(batch.getWorkItemId(), FillWorkItemStatusEnum.REWORKING.getStatus());
                }
            });
        } else {
            broadcastStatus(batch.getWorkItemId(), FillWorkItemStatusEnum.REWORKING.getStatus());
        }
    }

    /**
     * 获得合并批次分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 合并批次分页结果
     */
    @Override
    public PageResult<FillMergeBatchRespVO> getBatchPage(FillMergeBatchPageReqVO pageReqVO) {
        PageResult<FillMergeBatchDO> pageResult = mergeBatchMapper.selectPage(pageReqVO);
        return new PageResult<>(BeanUtils.toBean(pageResult.getList(), FillMergeBatchRespVO.class),
                pageResult.getTotal());
    }

    /**
     * 获得合并批次。
     *
     * @param id 记录编号
     * @return 填报合并批次响应数据
     */
    @Override
    public FillMergeBatchRespVO getBatch(Long id) {
        FillMergeBatchDO batch = mergeBatchMapper.selectById(id);
        if (batch == null) {
            throw exception(FILL_MERGE_BATCH_NOT_EXISTS);
        }
        return BeanUtils.toBean(batch, FillMergeBatchRespVO.class);
    }

    // ---------------------------------------------------------------- 合并执行

    /**
     * 系统生成的 UUID 主键：由组织实例与行标识确定性派生（32 位十六进制）。
     * 同一行在重新提交、更正与合并重试时得到相同主键，不同组织实例、不同期次之间互不重复。
     */
    static String generatedKey(Long workItemId, String rowId) {
        return cn.hutool.crypto.digest.DigestUtil.md5Hex(workItemId + ":" + rowId);
    }

    /**
     * 合并填报合并。
     *
     * @param batch 目标批次信息
     * @return 合并数值
     */
    private int doMerge(FillMergeBatchDO batch) {
        FillSubmissionDO submission = submissionMapper.selectById(batch.getSubmissionId());
        FillWorkItemDO item = workItemMapper.selectById(batch.getWorkItemId());
        FillTaskDO task = taskMapper.selectById(batch.getTaskId());
        FillTableVersionDO version = tableVersionService.getTableVersion(submission.getTableVersionId());
        List<FillSubmissionRowDO> rows = submissionRowMapper.selectListBySubmissionId(submission.getId());
        String periodKey = task.getPeriodKey();
        if (!PERIOD_KEY_PATTERN.matcher(periodKey).matches()) {
            throw exception(FILL_MERGE_TARGET_INVALID, "periodKey 格式不合法：" + periodKey);
        }

        // 列映射：用户列（fieldCode → 目标列 + 类型）+ 系统来源列
        List<Map<String, Object>> fields = parseFields(version.getFieldSchema());
        List<String> fieldNames = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            fieldNames.add(String.valueOf(field.get("targetColumnName")));
        }
        fieldNames.add(SYS_COL_ORG_ID);
        fieldNames.add(SYS_COL_PERIOD_KEY);
        fieldNames.add(SYS_COL_SUBMISSION_ID);
        fieldNames.add(SYS_COL_ROW_ID);

        // 多级汇总：顶层实例的提交含各级下级的数据，每行按来源组织写入，范围替换时清理整棵子树的组织
        List<Long> scopeOrgIds = hierarchyService == null ? List.of(item.getReportingOrgId())
                : new ArrayList<>(hierarchyService.subtreeOrgIds(item));
        boolean hierarchical = scopeOrgIds.size() > 1
                || rows.stream().anyMatch(row -> row.getSourceOrgId() != null);
        List<Object[]> recordValues = new ArrayList<>(rows.size());
        for (FillSubmissionRowDO row : rows) {
            Map<String, Object> data = parseRowData(row.getData());
            Object[] values = new Object[fieldNames.size()];
            int index = 0;
            for (Map<String, Object> field : fields) {
                Object value = data.get(String.valueOf(field.get("fieldCode")));
                if (FillTableVersionServiceImpl.GENERATED_UUID.equals(field.get("generated"))
                        && (value == null || StrUtil.isBlank(String.valueOf(value)))) {
                    // 汇总行按原始录入位置生成：重复汇总、逐级上报后主键不变
                    value = row.getSourceWorkItemId() != null && row.getSourceRowId() != null
                            ? generatedKey(row.getSourceWorkItemId(), row.getSourceRowId())
                            : generatedKey(item.getId(), row.getRowId());
                }
                values[index++] = convertValue(field, value);
            }
            values[index++] = rowOrgId(item, row);
            values[index++] = periodKey;
            values[index++] = submission.getId();
            values[index] = row.getRowId();
            recordValues.add(values);
        }

        AccessDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(batch.getTargetDatabaseId(), FILL_MERGE_TARGET_INVALID);
        FillMergeStrategyEnum strategy = FillMergeStrategyEnum.of(batch.getMergeStrategy());
        // 同一组织实例此前的提交：更新插入策略下，它们写入而本次已删除的行需要从正式表清理
        List<Long> previousSubmissionIds = submissionMapper.selectListByWorkItemId(item.getId()).stream()
                .map(FillSubmissionDO::getId)
                .filter(id -> !Objects.equals(id, submission.getId()))
                .toList();
        int written;
        try (HikariDataSource dataSource = createDataSource(conn)) {
            Objects.requireNonNull(strategy, "未知合并策略");
            if (FillMergeJdbcWriter.supports(conn.getProductType())) {
                // 事务型目标库：清理与写入在同一事务内完成，失败整体回滚，正式表保持合并前的内容
                FillMergeJdbcWriter.Target target = new FillMergeJdbcWriter.Target(
                        conn.getProductType(), conn.getSchema(), batch.getTargetTableName());
                written = switch (strategy) {
                    // 幂等：先清理本批次曾写入的行，再整批插入
                    case APPEND -> FillMergeJdbcWriter.replaceScope(dataSource, target, fieldNames, recordValues,
                            List.of(SYS_COL_SUBMISSION_ID), List.of(submission.getId()));
                    // 幂等：范围替换本身可重放
                    case REPLACE_SCOPE -> FillMergeJdbcWriter.replaceScopes(dataSource, target, fieldNames,
                            recordValues, List.of(SYS_COL_ORG_ID, SYS_COL_PERIOD_KEY),
                            scopeOrgIds.stream().map(orgId -> List.<Object>of(orgId, periodKey)).toList());
                    // 此前提交只属于本实例：多级时不再按组织限定（行的来源组织可能是任一下级）
                    case UPSERT -> FillMergeJdbcWriter.upsert(dataSource, target, fieldNames, recordValues,
                            resolveBusinessKeyColumns(version, fields),
                            hierarchical
                                    ? new FillMergeJdbcWriter.StaleRows(List.of(SYS_COL_PERIOD_KEY),
                                    List.of(periodKey), SYS_COL_SUBMISSION_ID, previousSubmissionIds)
                                    : new FillMergeJdbcWriter.StaleRows(List.of(SYS_COL_ORG_ID, SYS_COL_PERIOD_KEY),
                                    List.of(item.getReportingOrgId(), periodKey), SYS_COL_SUBMISSION_ID,
                                    previousSubmissionIds));
                };
            } else {
                // 分析型、文档型等目标库不支持“删除 + 写入”同事务提交，沿用通用写入器：
                // 清理与写入分步执行，写入失败时依靠批次重试收敛
                List<Object[]> writerValues = toWriterValues(recordValues);
                written = switch (strategy) {
                    case APPEND -> {
                        deleteByCondition(conn, batch.getTargetTableName(),
                                quote(conn, SYS_COL_SUBMISSION_ID) + " = " + submission.getId());
                        yield insertAll(dataSource, conn, batch.getTargetTableName(), fieldNames, writerValues);
                    }
                    case REPLACE_SCOPE -> {
                        // 组织编号为数值、periodKey 已按白名单校验，直拼安全
                        deleteByCondition(conn, batch.getTargetTableName(),
                                quote(conn, SYS_COL_ORG_ID) + " IN (" + scopeOrgIds.stream().map(String::valueOf)
                                        .collect(java.util.stream.Collectors.joining(", ")) + ")"
                                        + " AND " + quote(conn, SYS_COL_PERIOD_KEY) + " = '" + periodKey + "'");
                        yield insertAll(dataSource, conn, batch.getTargetTableName(), fieldNames, writerValues);
                    }
                    case UPSERT -> {
                        int upserted = upsertAll(dataSource, conn, batch.getTargetTableName(),
                                fieldNames, writerValues, resolveBusinessKeyColumns(version, fields));
                        if (!previousSubmissionIds.isEmpty()) {
                            // 清理该组织实例此前提交留下、本次已不再包含的行；编号为数值直拼
                            deleteByCondition(conn, batch.getTargetTableName(),
                                    (hierarchical ? "" : quote(conn, SYS_COL_ORG_ID) + " = " + item.getReportingOrgId()
                                            + " AND ")
                                            + quote(conn, SYS_COL_PERIOD_KEY) + " = '" + periodKey + "'"
                                            + " AND " + quote(conn, SYS_COL_SUBMISSION_ID) + " IN ("
                                            + previousSubmissionIds.stream().map(String::valueOf)
                                            .collect(java.util.stream.Collectors.joining(", ")) + ")");
                        }
                        yield upserted;
                    }
                };
            }
        }

        // 血缘明细：先删后插，可重放
        mergeDetailMapper.delete(new LambdaQueryWrapperX<FillMergeDetailDO>()
                .eq(FillMergeDetailDO::getSubmissionId, submission.getId()));
        String action = strategy == FillMergeStrategyEnum.UPSERT ? "UPSERT" : strategy.getStrategy();
        rows.forEach(row -> mergeDetailMapper.insert(FillMergeDetailDO.builder()
                .mergeBatchId(batch.getId())
                .submissionId(submission.getId())
                .sourceRowId(row.getRowId())
                .targetKey(rowOrgId(item, row) + ":" + periodKey)
                .action(action)
                .build()));
        return written;
    }

    /**
     * 新增全部。
     *
     * @param dataSource 数据源
     * @param conn 已解析的数据源连接信息
     * @param tableName 表名
     * @param fieldNames 字段名集合
     * @param recordValues 记录值
     * @return 新增全部数值
     */
    private int insertAll(HikariDataSource dataSource, AccessDatabaseResolver.ConnectionInfo conn,
            String tableName, List<String> fieldNames, List<Object[]> recordValues) {
        if (recordValues.isEmpty()) {
            return 0;
        }
        IDatabaseWriter writer = dbSwitchRuntime.writer(
                dataSource, conn.getProductType(), cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
        writer.prepareWrite(conn.getSchema(), tableName, fieldNames);
        writer.write(fieldNames, recordValues);
        return recordValues.size();
    }

    /**
     * 获取填报合并的按键更新或新增全部。
     *
     * @param dataSource 数据源
     * @param conn 已解析的数据源连接信息
     * @param tableName 表名
     * @param fieldNames 字段名集合
     * @param recordValues 记录值
     * @param pkNames 主键名称
     * @return 按键更新或新增全部数值
     */
    private int upsertAll(HikariDataSource dataSource, AccessDatabaseResolver.ConnectionInfo conn,
            String tableName, List<String> fieldNames, List<Object[]> recordValues,
            List<String> pkNames) {
        if (recordValues.isEmpty()) {
            return 0;
        }
        IDatabaseOperator operator = dbSwitchRuntime.reader(
                dataSource, conn.getProductType());
        List<Object[]> insertRows = new ArrayList<>();
        List<Object[]> updateRows = new ArrayList<>();
        for (Object[] values : recordValues) {
            Map<String, Object> pkValues = new LinkedHashMap<>();
            for (String pkName : pkNames) {
                pkValues.put(pkName, values[fieldNames.indexOf(pkName)]);
            }
            boolean exists;
            try {
                exists = operator.getPkExist(conn.getSchema(), tableName, pkValues);
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException("业务键存在性检查失败：" + ex.getMessage(), ex);
            }
            if (exists) {
                updateRows.add(values);
            } else {
                insertRows.add(values);
            }
        }
        insertAll(dataSource, conn, tableName, fieldNames, insertRows);
        if (!updateRows.isEmpty()) {
            IDatabaseSynchronize synchronize = dbSwitchRuntime.synchronizer(
                    dataSource, conn.getProductType());
            synchronize.prepare(conn.getSchema(), tableName, fieldNames, pkNames);
            synchronize.executeUpdate(updateRows);
        }
        return recordValues.size();
    }

    /** UPSERT 业务键 fieldCode → 目标列名；业务键须隔离组织与所属期（版本发布已校验业务键必填） */
    private List<String> resolveBusinessKeyColumns(FillTableVersionDO version, List<Map<String, Object>> fields) {
        if (StrUtil.isBlank(version.getBusinessKeyFields())) {
            throw exception(FILL_MERGE_TARGET_INVALID, "UPSERT 策略缺少业务键配置");
        }
        Map<String, String> targetByCode = new LinkedHashMap<>();
        fields.forEach(field -> targetByCode.put(String.valueOf(field.get("fieldCode")),
                String.valueOf(field.get("targetColumnName"))));
        List<String> pkNames = new ArrayList<>();
        for (String fieldCode : version.getBusinessKeyFields().split(",")) {
            String target = targetByCode.get(fieldCode.trim());
            if (target == null) {
                throw exception(FILL_MERGE_TARGET_INVALID, "业务键字段不存在：" + fieldCode);
            }
            pkNames.add(target);
        }
        // 系统列兜底隔离：组织 + 所属期一并纳入业务键，禁止跨部门/跨期覆盖
        pkNames.add(SYS_COL_ORG_ID);
        pkNames.add(SYS_COL_PERIOD_KEY);
        return pkNames;
    }

    /**
     * 按条件删除填报合并。
     *
     * @param conn 已解析的数据源连接信息
     * @param tableName 表名
     * @param condition 条件
     */
    private void deleteByCondition(AccessDatabaseResolver.ConnectionInfo conn,
            String tableName, String condition) {
        // 条件只由服务端拼接：数值直拼、periodKey 已按白名单格式强校验
        String sql = "DELETE FROM "
                + conn.getProductType().quoteSchemaTableName(conn.getSchema(), tableName)
                + " WHERE " + condition;
        try {
            dbSwitchRuntime.jdbcMetadata(conn.getProductType())
                    .executeSql(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), sql);
        } catch (Exception ex) {
            throw exception(FILL_MERGE_TARGET_INVALID,
                    "清理目标范围失败（存量表可能缺少系统来源列，请重新发布填报表）：" + ex.getMessage());
        }
    }

    /**
     * 按目标数据库方言引用标识符。
     *
     * @param conn 已解析的数据源连接信息
     * @param name 名称
     * @return 按目标数据库方言引用标识符文本
     */
    private String quote(AccessDatabaseResolver.ConnectionInfo conn, String name) {
        return conn.getProductType().quoteName(name);
    }

    // ---------------------------------------------------------------- 状态与通知

    /**
     * 标记批次成功。
     *
     * @param batch 目标批次信息
     * @param written 已写入
     */
    private void markBatchSuccess(FillMergeBatchDO batch, int written) {
        FillMergeBatchDO update = new FillMergeBatchDO();
        update.setId(batch.getId());
        update.setStatus(FillMergeBatchStatusEnum.SUCCESS.getStatus());
        update.setSuccessCount(written);
        update.setErrorMessage(null);
        update.setFinishedAt(LocalDateTime.now());
        mergeBatchMapper.updateById(update);
    }

    /**
     * 标记批次失败。
     *
     * @param batch 目标批次信息
     * @param ex 异常信息
     */
    private void markBatchFailed(FillMergeBatchDO batch, Exception ex) {
        int retryCount = (batch.getRetryCount() == null ? 0 : batch.getRetryCount()) + 1;
        long delayMinutes = Math.min(RETRY_BASE_MINUTES * (1L << Math.min(retryCount - 1, 10)), RETRY_MAX_MINUTES);
        FillMergeBatchDO update = new FillMergeBatchDO();
        update.setId(batch.getId());
        update.setStatus(FillMergeBatchStatusEnum.FAILED.getStatus());
        update.setErrorMessage(StrUtil.brief(ex.getMessage(), 2000));
        update.setRetryCount(retryCount);
        update.setNextRetryTime(LocalDateTime.now().plusMinutes(delayMinutes));
        update.setFinishedAt(LocalDateTime.now());
        mergeBatchMapper.updateById(update);
    }

    /**
     * 处理合并成功。
     *
     * @param batch 目标批次信息
     */
    private void onMergeSuccess(FillMergeBatchDO batch) {
        workItemMapper.compareAndSetStatus(batch.getWorkItemId(),
                FillWorkItemStatusEnum.MERGING.getStatus(), FillWorkItemStatusEnum.MERGED.getStatus());
        refreshTaskProgress(batch.getTaskId());
        // 更正快照合并完成 → 闭环更正实例（非更正提交内部直接返回）
        correctionService.completeBySubmissionId(batch.getSubmissionId());
        notifyBatch(batch, FillNotifyTypeEnum.MERGE_SUCCEEDED, null);
        broadcastStatus(batch.getWorkItemId(), FillWorkItemStatusEnum.MERGED.getStatus());
    }

    /**
     * 处理合并失败。
     *
     * @param batch 目标批次信息
     * @param notify 是否通知填报人（仅首次失败通知）
     */
    private void onMergeFailed(FillMergeBatchDO batch, boolean notify) {
        workItemMapper.compareAndSetStatus(batch.getWorkItemId(),
                FillWorkItemStatusEnum.MERGING.getStatus(), FillWorkItemStatusEnum.MERGE_FAILED.getStatus());
        if (notify) {
            notifyBatch(batch, FillNotifyTypeEnum.MERGE_FAILED, null);
        }
        broadcastStatus(batch.getWorkItemId(), FillWorkItemStatusEnum.MERGE_FAILED.getStatus());
    }

    /** 写入正式表的来源组织：汇总行取原始报送组织，本单位录入的行取实例组织 */
    private static Long rowOrgId(FillWorkItemDO item, FillSubmissionRowDO row) {
        return row.getSourceOrgId() != null ? row.getSourceOrgId() : item.getReportingOrgId();
    }

    /** 任务进度冗余刷新：全部顶层组织已合并/已取消 → 任务完成（下级随上级上报，不参与判断） */
    private void refreshTaskProgress(Long taskId) {
        List<FillWorkItemDO> items = workItemMapper.selectListByTaskId(taskId).stream()
                .filter(item -> item.getParentWorkItemId() == null).toList();
        int merged = (int) items.stream()
                .filter(item -> FillWorkItemStatusEnum.MERGED.getStatus().equals(item.getStatus())).count();
        boolean allDone = items.stream().allMatch(item ->
                FillWorkItemStatusEnum.MERGED.getStatus().equals(item.getStatus())
                        || FillWorkItemStatusEnum.CANCELLED.getStatus().equals(item.getStatus()));
        FillTaskDO update = new FillTaskDO();
        update.setId(taskId);
        update.setMergedOrgCount(merged);
        if (allDone && !items.isEmpty()) {
            update.setStatus(FillTaskStatusEnum.FINISHED.getStatus());
        }
        taskMapper.updateById(update);
    }

    /**
     * 通知填报合并的批次。
     *
     * @param batch 目标批次信息
     * @param type 类型标识
     * @param extraParams 模板附加参数
     */
    private void notifyBatch(FillMergeBatchDO batch, FillNotifyTypeEnum type, Map<String, Object> extraParams) {
        try {
            FillWorkItemDO item = workItemMapper.selectById(batch.getWorkItemId());
            FillTaskDO task = taskMapper.selectById(batch.getTaskId());
            List<Long> userIds = convertList(
                    assigneeMapper.selectValidListByWorkItemId(batch.getWorkItemId()),
                    FillAssigneeDO::getUserId);
            notifyService.sendToUsers(type, task, item, userIds, extraParams);
        } catch (Exception ex) {
            log.warn("[notifyBatch][批次({}) 通知失败：{}]", batch.getId(), ex.getMessage());
        }
    }

    /**
     * 广播填报合并的状态。
     *
     * @param workItemId 工作项编号
     * @param status 目标状态
     */
    private void broadcastStatus(Long workItemId, Integer status) {
        try {
            collabService.broadcast(workItemId, FillSubmissionServiceImpl.WS_STATUS_CHANGED,
                    Map.of("workItemId", workItemId, "status", status));
        } catch (Exception ex) {
            log.warn("[broadcastStatus][工作项({}) 广播失败：{}]", workItemId, ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 值转换

    /**
     * 创建填报合并的数据源。
     *
     * @param conn 已解析的数据源连接信息
     * @return Hikari数据源
     */
    private HikariDataSource createDataSource(AccessDatabaseResolver.ConnectionInfo conn) {
        TargetDataSourceProperties properties = new TargetDataSourceProperties();
        properties.setUrl(conn.getJdbcUrl());
        properties.setDriverClassName(conn.getProductType().getDriveClassName());
        properties.setTargetProductType(conn.getProductType());
        properties.setUsername(conn.getUsername());
        properties.setPassword(conn.getPassword());
        properties.setPoolSize(4);
        return DataSourceUtils.createTargetDataSource(properties, null, null,
                dbSwitchRuntime.drivers().find(conn.getProductType()).orElse(null),
                dbSwitchRuntime.drivers().runtimes(), dbSwitchRuntime.options());
    }

    /** 快照 JSON 值 → 目标列的 Java 值；转换规则与提交强校验共用 {@link FillValueConverter} */
    private Object convertValue(Map<String, Object> field, Object value) {
        Object fieldTypeObj = field.get("fieldType");
        int fieldType = fieldTypeObj instanceof Number number ? number.intValue() : ColumnMetaData.TYPE_STRING;
        try {
            return FillValueConverter.convert(fieldType, value);
        } catch (IllegalArgumentException ex) {
            throw exception(FILL_MERGE_TARGET_INVALID,
                    String.format("字段【%s】的值不符合类型要求：%s", field.get("name"), value));
        }
    }

    /** 通用写入器沿用原有的日期时间取值类型（java.util.Date），其余类型原样传递 */
    private List<Object[]> toWriterValues(List<Object[]> recordValues) {
        List<Object[]> result = new ArrayList<>(recordValues.size());
        for (Object[] values : recordValues) {
            Object[] converted = new Object[values.length];
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof LocalDate date) {
                    converted[index] = DateUtil.date(date.atStartOfDay());
                } else if (value instanceof LocalDateTime dateTime) {
                    converted[index] = DateUtil.date(dateTime);
                } else if (value instanceof LocalTime time) {
                    converted[index] = DateUtil.date(LocalDate.EPOCH.atTime(time));
                } else {
                    converted[index] = value;
                }
            }
            result.add(converted);
        }
        return result;
    }

    /**
     * 解析填报合并的字段。
     *
     * @param fieldSchemaJson 字段结构JSON
     * @return 解析填报合并的字段结果集合
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFields(String fieldSchemaJson) {
        List<Map<String, Object>> fields = JsonUtils.parseObject(fieldSchemaJson, List.class);
        return fields != null ? fields : List.of();
    }

    /**
     * 解析行数据。
     *
     * @param dataJson 数据JSON
     * @return 处理后的键值映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRowData(String dataJson) {
        if (StrUtil.isBlank(dataJson)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> data = JsonUtils.parseObject(dataJson, Map.class);
        return data != null ? data : new LinkedHashMap<>();
    }

}
