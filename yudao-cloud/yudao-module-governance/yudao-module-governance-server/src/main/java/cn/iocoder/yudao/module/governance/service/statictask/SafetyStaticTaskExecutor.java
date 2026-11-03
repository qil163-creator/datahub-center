package cn.iocoder.yudao.module.governance.service.statictask;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.MetadataReadOptions;
import cn.iocoder.yudao.framework.dbswitch.core.schema.PostgresSchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.service.PostgresDmlTargetGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskFieldRuleVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.replaceitem.SafetyReplaceItemDO;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskingSpec;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.service.accesslog.SafetyAccessLogService;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskEngine;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.core.type.TypeReference;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STATIC_TASK_CONFIG_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STATIC_TASK_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STATIC_TASK_NOT_EXISTS;

/**
 * 静态脱敏任务执行器：源库表分批读取 → 按字段规则脱敏 → 批量写入目标库表
 *
 * 参照 {@link cn.iocoder.yudao.module.governance.service.master.MasterDistributeExecutor} 的
 * 异步提交 + 运行中防并发 + 执行记录模式；
 * 脱敏值计算由 {@link SafetyMaskEngine} 承载，规则取"当前生效版本"的参数。
 *
 * 约束：目标表需已存在且包含源表全部字段（本任务不负责建表）
 *
 * @author Li
 */
@Slf4j
@Component
public class SafetyStaticTaskExecutor {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    private static final int DEFAULT_BATCH_SIZE = 1000;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[\\w.$]+$");

    @Resource
    private SafetyStaticTaskMapper taskMapper;

    @Resource
    private SafetyStaticTaskLogMapper logMapper;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private SafetyRuleService ruleService;

    @Resource
    private SafetyAccessLogService accessLogService;

    private ExecutorService executor;

    /**
     * 运行中的任务注册表，key 为任务 id（单实例部署内有效）
     */
    private final ConcurrentMap<Long, Boolean> runningTasks = new ConcurrentHashMap<>();

    /**
     * 初始化静态脱敏任务使用的运行资源。
     */
    @PostConstruct
    public void init() {
        AtomicInteger counter = new AtomicInteger();
        executor = new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), runnable -> {
            Thread thread = new Thread(runnable, "safety-static-task-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 释放静态脱敏任务持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * 提交执行（异步）
     *
     * @return false 表示该任务正在执行中，本次跳过
     */
    public boolean submit(Long taskId, Integer runType) {
        SafetyStaticTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw exception(STATIC_TASK_NOT_EXISTS);
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (runningTasks.putIfAbsent(taskId, Boolean.TRUE) != null) {
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    // 工作线程显式恢复用户身份，执行完成后释放互斥状态。
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                    execute(taskId, runType);
                } finally {
                    SecurityContextHolder.clearContext();
                    runningTasks.remove(taskId);
                }
            });
            return true;
        } catch (Exception ex) {
            runningTasks.remove(taskId);
            throw ex;
        }
    }

    /**
     * 是否运行中
     */
    public boolean isRunning(Long taskId) {
        return runningTasks.containsKey(taskId);
    }

    /**
     * 执行静态脱敏任务处理流程，并维护相应运行结果。
     */
    private void execute(Long taskId, Integer runType) {
        SafetyStaticTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[execute][静态脱敏任务({}) 不存在，跳过执行]", taskId);
            return;
        }
        List<SafetyStaticTaskFieldRuleVO> fieldRules = parseFieldRules(task.getFieldRulesJson());
        SafetyStaticTaskLogDO logDO = SafetyStaticTaskLogDO.builder()
                .taskId(taskId)
                .runType(runType)
                .runStatus(SafetyStaticTaskLogDO.RUN_STATUS_RUNNING)
                .startTime(LocalDateTime.now())
                .dataCount(0L)
                .maskedColumnCount(fieldRules.size())
                .build();
        logMapper.insert(logDO);
        try {
            long dataCount = doExecute(task, fieldRules);
            logDO.setRunStatus(SafetyStaticTaskLogDO.RUN_STATUS_SUCCESS);
            logDO.setDataCount(dataCount);
            recordAccessLog(task, fieldRules, dataCount);
        } catch (Throwable ex) {
            log.error("[execute][静态脱敏任务({}) 执行失败，异常类型={}]", taskId, ex.getClass().getSimpleName());
            logDO.setRunStatus(SafetyStaticTaskLogDO.RUN_STATUS_FAILED);
            logDO.setErrorInfo("静态脱敏执行失败，请核查任务目标、脱敏规则和连接状态；不会自动重放未知写结果");
        } finally {
            logDO.setEndTime(LocalDateTime.now());
            logMapper.updateById(logDO);
        }
    }

    /**
     * 静态脱敏执行成功后写入敏感访问审计（STATIC_TASK 类型），失败不影响主流程
     */
    private void recordAccessLog(SafetyStaticTaskDO task, List<SafetyStaticTaskFieldRuleVO> fieldRules,
            long dataCount) {
        try {
            SafetyAccessLogCreateReqDTO reqDTO = new SafetyAccessLogCreateReqDTO();
            reqDTO.setAccessType(SafetyAccessLogCreateReqDTO.ACCESS_TYPE_STATIC_TASK);
            reqDTO.setResourceName(task.getTaskName());
            reqDTO.setDatabaseId(task.getSourceDatabaseId());
            reqDTO.setTableName(task.getSourceTableName());
            reqDTO.setSensitiveColumns(StrUtil.maxLength(String.join(",",
                    convertList(fieldRules, SafetyStaticTaskFieldRuleVO::getColumnName)), 1000));
            reqDTO.setColumnCount(fieldRules.size());
            reqDTO.setMaskedFlag(1);
            accessLogService.recordAccessLog(reqDTO);
        } catch (Exception ex) {
            log.warn("[recordAccessLog][静态脱敏任务({}) 审计写入失败：{}]", task.getId(), ex.getMessage());
        }
    }

    /**
     * 读取源表数据并按冻结字段脱敏规则写入目标表，返回处理数量。
     */
    private long doExecute(SafetyStaticTaskDO task, List<SafetyStaticTaskFieldRuleVO> fieldRules) throws Exception {
        if (fieldRules.isEmpty()) {
            throw new IllegalStateException("任务未配置脱敏字段");
        }
        validateIdentifier(task.getSourceTableName());
        validateIdentifier(task.getTargetTableName());
        // 1. 解析源/目标连接
        MasterDatabaseResolver.ConnectionInfo sourceConn =
                databaseResolver.resolve(task.getSourceDatabaseId(), STATIC_TASK_DATABASE_NOT_EXISTS);
        MasterDatabaseResolver.ConnectionInfo targetConn =
                databaseResolver.resolve(task.getTargetDatabaseId(), STATIC_TASK_DATABASE_NOT_EXISTS);
        boolean overwrite = SafetyStaticTaskDO.WRITE_MODE_OVERWRITE.equals(task.getWriteMode());
        if (sourceConn.getProductType() != ProductTypeEnum.POSTGRESQL
                || targetConn.getProductType() != ProductTypeEnum.POSTGRESQL) {
            throw exception(STATIC_TASK_CONFIG_INVALID, "安全写入仅开放已验证物理身份与事务回滚的 PostgreSQL 源和目标");
        }
        // 2. 源表字段列表
        List<DatabaseColumnRespDTO> columns = databaseApi.getTableColumnList(
                task.getSourceDatabaseId(), task.getSourceTableName()).getCheckedData();
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException(String.format("源表【%s】不存在或没有字段", task.getSourceTableName()));
        }
        List<String> columnNames = convertList(columns, DatabaseColumnRespDTO::getColumnName);
        // 3. 构建字段脱敏器（规则当前生效版本的参数 + 替换码表）
        Map<String, ColumnMasker> maskers = buildColumnMaskers(fieldRules, columnNames);
        // 4. 分批读取 → 脱敏 → 写入
        int batchSize = task.getBatchSize() == null || task.getBatchSize() < 1
                ? DEFAULT_BATCH_SIZE : task.getBatchSize();
        long dataCount = 0;
        try (HikariDataSource sourceDataSource = databaseResolver.createDataSource(sourceConn);
             HikariDataSource targetDataSource = databaseResolver.createDataSource(targetConn);
             Connection sourceIdentityConnection = sourceDataSource.getConnection();
             Connection targetConnection = targetDataSource.getConnection()) {
            sourceIdentityConnection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            sourceIdentityConnection.setAutoCommit(false);
            targetConnection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            targetConnection.setAutoCommit(false);
            String insertSql = buildInsertSql(targetConn.getProductType(), targetConn.getSchema(),
                    task.getTargetTableName(), columnNames);
            String deleteSql = "DELETE FROM " + targetConn.getProductType()
                    .quoteSchemaTableName(targetConn.getSchema(), task.getTargetTableName());
            var statements = overwrite ? List.of(deleteSql, insertSql) : List.of(insertSql);
            List<RestrictedDmlTarget> targets = new ArrayList<>(statements.size());
            for (int i = 0; i < statements.size(); i++) {
                targets.add(new RestrictedDmlTarget(i + 1, targetConn.getSchema(), task.getTargetTableName()));
            }
            try {
                // 源视图和继承路径不能靠表名证明独立；只读关系锁在全部取数期间保持。
                PostgresDmlTargetGuard.lockAndValidateReadSource(sourceIdentityConnection,
                        sourceConn.getSchema(), task.getSourceTableName());
                // DML 守卫同时拒绝内部外键触发器、规则、分区和继承，避免反向写入源数据。
                PostgresDmlTargetGuard.lockAndValidate(targetConnection,
                        SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, String.join(";\n", statements)), targets);
            } catch (IllegalArgumentException unsafeRelation) {
                throw exception(STATIC_TASK_CONFIG_INVALID,
                        "源必须是可核验的普通表，目标不得包含触发器、规则、继承或分区");
            }
            assertDistinctPhysicalTables(task, sourceConn, targetConn, sourceIdentityConnection, targetConnection);
            // 数据读取复用身份观测所在连接，避免连接池或多主机配置改变实际读取目标。
            IDatabaseOperator sourceOperator = dbSwitchRuntime.reader(
                    new SingleConnectionDataSource(sourceIdentityConnection, true), sourceConn.getProductType());
            sourceOperator.setFetchSize(batchSize);
            try (StatementResultSet srs = sourceOperator.queryTableData(
                    sourceConn.getSchema(), task.getSourceTableName(), columnNames);
                 ResultSet rs = srs.getResultset();
                 PreparedStatement insert = targetConnection.prepareStatement(insertSql)) {
                // 源读取与目标语句准备完成后才可清空；所有写入批次使用同一目标事务，保持关系锁。
                if (overwrite) {
                    try (Statement statement = targetConnection.createStatement()) {
                        statement.setQueryTimeout(120);
                        statement.executeUpdate(deleteSql);
                    }
                }
                insert.setQueryTimeout(120);
                int pending = 0;
                while (rs.next()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("任务已被中断");
                    }
                    for (int i = 0; i < columnNames.size(); i++) {
                        Object value = rs.getObject(columnNames.get(i));
                        ColumnMasker masker = maskers.get(columnNames.get(i));
                        if (masker != null) {
                            value = SafetyMaskEngine.mask(value, masker.methodCode(), masker.config(),
                                    masker.replaceItems());
                        }
                        insert.setObject(i + 1, value);
                    }
                    insert.addBatch();
                    pending++;
                    dataCount++;
                    if (pending >= batchSize) {
                        insert.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    insert.executeBatch();
                }
                targetConnection.commit();
            } catch (Exception ex) {
                targetConnection.rollback();
                throw ex;
            }
        }
        return dataCount;
    }

    /**
     * 校验静态脱敏任务的互异物理表，不满足约束时拒绝当前操作。
     */
    private void assertDistinctPhysicalTables(SafetyStaticTaskDO task,
            MasterDatabaseResolver.ConnectionInfo source, MasterDatabaseResolver.ConnectionInfo target,
            Connection sourceConnection, Connection targetConnection) throws Exception {
        var metadata = dbSwitchRuntime.products().metadata(ProductTypeEnum.POSTGRESQL);
        String sourceIdentity = PostgresSchemaChanges.physicalTargetFingerprint(sourceConnection, metadata,
                source.getSchema(), task.getSourceTableName(), MetadataReadOptions.fresh());
        String targetIdentity = PostgresSchemaChanges.physicalTargetFingerprint(targetConnection, metadata,
                target.getSchema(), task.getTargetTableName(), MetadataReadOptions.fresh());
        if (sourceIdentity.equals(targetIdentity)) {
            throw exception(STATIC_TASK_CONFIG_INVALID, "源与目标解析为同一张物理表，拒绝写入生产源表");
        }
    }

    /**
     * 构建字段 → 脱敏器映射：校验字段存在、规则存在且有生效版本
     */
    private Map<String, ColumnMasker> buildColumnMaskers(List<SafetyStaticTaskFieldRuleVO> fieldRules,
            List<String> columnNames) {
        Map<String, ColumnMasker> maskers = new HashMap<>();
        for (SafetyStaticTaskFieldRuleVO fieldRule : fieldRules) {
            if (!columnNames.contains(fieldRule.getColumnName())) {
                throw new IllegalStateException(String.format("脱敏字段【%s】在源表中不存在", fieldRule.getColumnName()));
            }
            SafetyMaskingSpec spec = ruleService.resolveUsableMaskingSpec(fieldRule.getRuleId());
            if (spec == null) {
                throw new IllegalStateException(
                        String.format("脱敏规则(%s)不存在或未发布生效版本", fieldRule.getRuleId()));
            }
            maskers.put(fieldRule.getColumnName(),
                    new ColumnMasker(spec.getMethodCode(), spec.getConfig(), spec.getReplaceItems()));
        }
        return maskers;
    }

    /**
     * 解析静态脱敏任务的字段规则。
     */
    private List<SafetyStaticTaskFieldRuleVO> parseFieldRules(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        List<SafetyStaticTaskFieldRuleVO> rules = JsonUtils.parseObject(json, new TypeReference<>() {
        });
        return rules == null ? List.of() : rules;
    }

    /**
     * 构造静态脱敏任务的新增SQL。
     */
    private String buildInsertSql(ProductTypeEnum product, String schema, String tableName, List<String> columnNames) {
        List<String> placeholders = new ArrayList<>(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            placeholders.add("?");
        }
        return "INSERT INTO " + product.quoteSchemaTableName(schema, tableName)
                + " (" + String.join(", ", columnNames.stream().map(product::quoteName).toList())
                + ") VALUES (" + String.join(", ", placeholders) + ")";
    }

    /**
     * 校验静态脱敏任务的标识符，不满足约束时拒绝当前操作。
     */
    private void validateIdentifier(String identifier) {
        if (StrUtil.isBlank(identifier) || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalStateException(String.format("表名【%s】包含非法字符", identifier));
        }
    }

    /**
     * 字段脱敏器：规则方式 + 生效版本参数 + 替换码表
     */
    private record ColumnMasker(String methodCode, Map<String, Object> config,
                                List<SafetyReplaceItemDO> replaceItems) {

    }

}
