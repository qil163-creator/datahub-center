package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.date.DatePattern;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        // 执行抢占 CAS：只允许从 待执行/失败 进入执行中，多实例安全
        if (mergeBatchMapper.compareAndSetRunning(batchId, batch.getStatus()) == 0) {
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
            onMergeFailed(batch);
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

        List<Object[]> recordValues = new ArrayList<>(rows.size());
        for (FillSubmissionRowDO row : rows) {
            Map<String, Object> data = parseRowData(row.getData());
            Object[] values = new Object[fieldNames.size()];
            int index = 0;
            for (Map<String, Object> field : fields) {
                values[index++] = convertValue(field, data.get(String.valueOf(field.get("fieldCode"))));
            }
            values[index++] = item.getReportingOrgId();
            values[index++] = periodKey;
            values[index++] = submission.getId();
            values[index] = row.getRowId();
            recordValues.add(values);
        }

        AccessDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(batch.getTargetDatabaseId(), FILL_MERGE_TARGET_INVALID);
        FillMergeStrategyEnum strategy = FillMergeStrategyEnum.of(batch.getMergeStrategy());
        int written;
        try (HikariDataSource dataSource = createDataSource(conn)) {
            written = switch (Objects.requireNonNull(strategy, "未知合并策略")) {
                case APPEND -> {
                    // 幂等：先清理本批次曾写入的行，再整批插入
                    deleteByCondition(conn, batch.getTargetTableName(),
                            quote(conn, SYS_COL_SUBMISSION_ID) + " = " + submission.getId());
                    yield insertAll(dataSource, conn, batch.getTargetTableName(), fieldNames, recordValues);
                }
                case REPLACE_SCOPE -> {
                    // 幂等：范围替换本身可重放；重试重新删范围再插，收敛一致
                    deleteByCondition(conn, batch.getTargetTableName(),
                            quote(conn, SYS_COL_ORG_ID) + " = " + item.getReportingOrgId()
                                    + " AND " + quote(conn, SYS_COL_PERIOD_KEY) + " = '" + periodKey + "'");
                    yield insertAll(dataSource, conn, batch.getTargetTableName(), fieldNames, recordValues);
                }
                case UPSERT -> upsertAll(dataSource, conn, batch.getTargetTableName(),
                        fieldNames, recordValues, resolveBusinessKeyColumns(version, fields));
            };
        }

        // 血缘明细：先删后插，可重放
        mergeDetailMapper.delete(new LambdaQueryWrapperX<FillMergeDetailDO>()
                .eq(FillMergeDetailDO::getSubmissionId, submission.getId()));
        String action = strategy == FillMergeStrategyEnum.UPSERT ? "UPSERT" : strategy.getStrategy();
        rows.forEach(row -> mergeDetailMapper.insert(FillMergeDetailDO.builder()
                .mergeBatchId(batch.getId())
                .submissionId(submission.getId())
                .sourceRowId(row.getRowId())
                .targetKey(item.getReportingOrgId() + ":" + periodKey)
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
        notifyBatch(batch, FillNotifyTypeEnum.MERGE_SUCCEEDED);
        broadcastStatus(batch.getWorkItemId(), FillWorkItemStatusEnum.MERGED.getStatus());
    }

    /**
     * 处理合并失败。
     *
     * @param batch 目标批次信息
     */
    private void onMergeFailed(FillMergeBatchDO batch) {
        workItemMapper.compareAndSetStatus(batch.getWorkItemId(),
                FillWorkItemStatusEnum.MERGING.getStatus(), FillWorkItemStatusEnum.MERGE_FAILED.getStatus());
        notifyBatch(batch, FillNotifyTypeEnum.MERGE_FAILED);
        broadcastStatus(batch.getWorkItemId(), FillWorkItemStatusEnum.MERGE_FAILED.getStatus());
    }

    /** 任务进度冗余刷新：全部组织已合并/已取消 → 任务完成 */
    private void refreshTaskProgress(Long taskId) {
        List<FillWorkItemDO> items = workItemMapper.selectListByTaskId(taskId);
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
     */
    private void notifyBatch(FillMergeBatchDO batch, FillNotifyTypeEnum type) {
        try {
            FillWorkItemDO item = workItemMapper.selectById(batch.getWorkItemId());
            FillTaskDO task = taskMapper.selectById(batch.getTaskId());
            List<Long> userIds = convertList(
                    assigneeMapper.selectValidListByWorkItemId(batch.getWorkItemId()),
                    FillAssigneeDO::getUserId);
            notifyService.sendToUsers(type, task, item, userIds, null);
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

    /** 快照 JSON 值 → 目标列类型（dbswitch 逻辑类型：1小数 2字符串 3日期 5整数 6长整型 9日期时间 10时间） */
    private Object convertValue(Map<String, Object> field, Object value) {
        if (value == null || (value instanceof String text && StrUtil.isBlank(text))) {
            return null;
        }
        Object fieldTypeObj = field.get("fieldType");
        int fieldType = fieldTypeObj instanceof Number number ? number.intValue() : ColumnMetaData.TYPE_STRING;
        String text = String.valueOf(value);
        try {
            return switch (fieldType) {
                case ColumnMetaData.TYPE_NUMBER -> new BigDecimal(text);
                case ColumnMetaData.TYPE_INTEGER -> Integer.parseInt(text.trim());
                case ColumnMetaData.TYPE_BIGNUMBER -> Long.parseLong(text.trim());
                case ColumnMetaData.TYPE_DATE -> DateUtil.parse(text, DatePattern.NORM_DATE_PATTERN);
                case ColumnMetaData.TYPE_TIMESTAMP -> DateUtil.parse(text, DatePattern.NORM_DATETIME_PATTERN);
                case ColumnMetaData.TYPE_TIME -> DateUtil.parse(text, DatePattern.NORM_TIME_PATTERN);
                default -> text;
            };
        } catch (Exception ex) {
            throw exception(FILL_MERGE_TARGET_INVALID,
                    String.format("字段【%s】的值不符合类型要求：%s", field.get("name"), text));
        }
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
