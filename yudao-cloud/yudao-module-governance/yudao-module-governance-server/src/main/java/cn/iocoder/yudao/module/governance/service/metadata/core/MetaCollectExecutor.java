package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByDatasourceService;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectTaskMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel.CollectedColumn;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel.CollectedTable;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_COLLECT_TASK_DATASOURCE_NOT_EXISTS;

/**
 * 元数据采集执行器（编排器）
 *
 * 生命周期：锁定任务 → 创建实例 → CONNECT（DatabaseApi 取连接 + dbswitch 建会话）
 * → READ（按范围读库/表/字段/主键）→ DIFF/WRITE（差异引擎，逐库逐表独立事务）
 * → 统计与游标 → 完成（成功/部分成功/失败），全程结构化日志分批落库；
 * 失败保存阶段与游标供定位；租约恢复重新全量扫描，最多尝试三次
 *
 * 任务通过数据库租约认领并以令牌隔离过期写入；工作线程有界。
 *
 * @author Li
 */
@Slf4j
@Component
public class MetaCollectExecutor {

    @Resource
    private MetaCollectTaskMapper taskMapper;

    @Resource
    private MetaCollectInstanceMapper instanceMapper;

    @Resource
    private MetaCollectLogMapper logMapper;

    @Resource
    private MetaDiffEngine diffEngine;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private MetaCollectQueue queue;

    @Resource
    private cn.iocoder.yudao.module.system.api.permission.PermissionApi permissions;

    @Resource
    private cn.iocoder.yudao.module.governance.service.metadata.MetaFileService files;

    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(2);

    private ExecutorService executor;

    @Resource
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private java.util.concurrent.ScheduledExecutorService heartbeat;

    /**
     * 初始化元数据采集使用的运行资源。
     */
    @PostConstruct
    public void init() {
        AtomicInteger counter = new AtomicInteger();
        executor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "meta-collect-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        // 各工作线程的内部心跳；每个节点仅续租自己持有的令牌。
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "meta-collect-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 释放元数据采集持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
        heartbeat.shutdownNow();
    }

    /**
     * 判断元数据采集是否处于运行状态。
     */
    public boolean isRunning(Long taskId) {
        return queue.active(taskId);
    }

    /**
     * 校验并提交元数据采集请求。
     */
    public boolean submit(Long taskId, int triggerType) {
        queue.enqueue(taskId, triggerType,
                cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId());
        dispatchQueued();
        return true;
    }

    /**
     * 提交定时。
     */
    public boolean submitScheduled(Long taskId, LocalDateTime expected, LocalDateTime next) {
        var created = queue.enqueue(taskId, MetaCollectInstanceDO.TRIGGER_SCHEDULE, null, expected, next);
        dispatchQueued();
        return created != null;
    }

    /**
     * 恢复与派发。
     */
    public void recoverAndDispatch() {
        queue.recoverExpired();
        dispatchQueued();
    }

    /**
     * 派发已入队。
     */
    public synchronized void dispatchQueued() {
        while (slots.tryAcquire()) {
            MetaCollectInstanceDO instance;
            try {
                instance = queue.claim();
            } catch (RuntimeException failure) {
                slots.release();
                throw failure;
            }
            if (instance == null) {
                slots.release();
                return;
            }
            try {
                executor.submit(() -> {
                    var renewal = heartbeat.scheduleAtFixedRate(() -> {
                        try {
                            instanceMapper.renew(instance.getId(), instance.getExecutionToken());
                        } catch (RuntimeException failure) {
                            log.warn("采集实例 {} 续租失败", instance.getId());
                        }
                    }, 0, 60, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        execute(instance.getTaskId(), instance.getTriggerType(), instance,
                                instance.getExecutionToken());
                    } finally {
                        renewal.cancel(false);
                        slots.release();
                        dispatchQueued();
                    }
                });
            } catch (RuntimeException rejected) {
                slots.release();
                // 停机时保留持久租约，由下次调度恢复，不丢弃已经认领的实例。
                return;
            }
        }
    }

    /**
     * 执行元数据采集处理流程，并维护相应运行结果。
     */
    private void execute(Long taskId, int triggerType, MetaCollectInstanceDO instance, String token) {
        MetaCollectTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        LocalDateTime startTime = instance.getStartTime();

        MetaCollectLogger logger = new MetaCollectLogger(logMapper, instance.getId(), taskId);
        MetaCollectContext ctx = new MetaCollectContext();
        ctx.setWriteFence(() -> assertLease(instance.getId(), token));
        ctx.setInstanceId(instance.getId());
        ctx.setTaskId(taskId);
        ctx.setDatasourceId(task.getDatasourceId());
        ctx.setSourceSystemId(task.getSourceSystemId());
        ctx.setSourceSystemName(task.getSourceSystemName());
        ctx.setTriggerType(triggerType);
        ctx.setLogger(logger);

        List<Map<String, String>> failDetail = new ArrayList<>();
        HikariDataSource dataSource = null;
        try {
            Long actor = instance.getExecutionUserId();
            if (actor == null || actor <= 0 || !Boolean.TRUE.equals(
                    permissions.hasAnyPermissions(actor, "governance:meta-collect-task:run").getCheckedData())
                    || !Boolean.TRUE.equals(
                    permissions.hasAnyPermissions(actor, "governance:meta-collect-task:query").getCheckedData())) {
                throw new IllegalStateException("执行人已失效或权限已撤销");
            }
            logger.info(MetaCollectInstanceDO.STAGE_CONNECT, null, "任务开始执行，准备数据源连接");
            if (Objects.equals(task.getCollectType(), 2)) {
                updateStage(instance, MetaCollectInstanceDO.STAGE_READ);
                var result = files.collect(task, instance, ctx.getWriteFence());
                instance.setFileCount(result.count());
                ctx.getTableAdd().set(result.added());
                ctx.getTableUpdate().set(result.updated());
                ctx.getTableDelete().set(result.missing());
                transactionTemplate.executeWithoutResult(tx -> {
                    assertLease(instance.getId(), token);
                    finishInstance(instance, task, ctx, failDetail, logger, startTime);
                });
                return;
            }
            // CONNECT：DatabaseApi 现取现用，密码不落任何元数据表
            MasterDatabaseResolver.ConnectionInfo conn;
            try {
                conn = databaseResolver.resolve(task.getDatasourceId(), META_COLLECT_TASK_DATASOURCE_NOT_EXISTS);
                dataSource = databaseResolver.createDataSource(conn);
            } catch (Exception ex) {
                finishFail(instance, task, MetaCollectInstanceDO.STAGE_CONNECT, classifyError(ex, true),
                        logger, ex);
                return;
            }
            ctx.setDbType(conn.getProductType().name());
            IMetaDataByDatasourceService metaService =
                    new MetaDataByDataSourceServiceImpl(dataSource, conn.getProductType());
            logger.info(MetaCollectInstanceDO.STAGE_CONNECT, null,
                    String.format("数据源连接就绪：%s（%s）", conn.getName(), conn.getProductType().name()));

            // READ：库范围解析
            updateStage(instance, MetaCollectInstanceDO.STAGE_READ);
            List<String> schemas;
            try {
                schemas = metaService.querySchemaList();
            } catch (Exception ex) {
                finishFail(instance, task, MetaCollectInstanceDO.STAGE_READ, classifyError(ex, false),
                        logger, ex);
                return;
            }
            Map<String, Set<String>> scopeMap = parseScope(task);
            List<String> blacklist = parseBlacklist(task);
            List<String> targetDbs = new ArrayList<>();
            for (String schema : schemas) {
                if (scopeMap != null && !scopeMap.containsKey(schema)) {
                    continue;
                }
                if (matchBlacklist(blacklist, schema, null)) {
                    logger.info(MetaCollectInstanceDO.STAGE_READ, "[DB] " + schema, "命中黑名单，跳过");
                    continue;
                }
                targetDbs.add(schema);
            }
            logger.info(MetaCollectInstanceDO.STAGE_READ, null,
                    String.format("库范围解析完成，共需处理数据库数量：%s", targetDbs.size()));

            // 逐库处理：READ → DIFF/WRITE，单库失败不影响其他库
            Set<String> finishedDbs = new LinkedHashSet<>();
            boolean anySchemaReadFail = false;
            for (String dbName : targetDbs) {
                updateCursor(instance, finishedDbs, dbName);
                try {
                    processSchema(ctx, metaService, task, dbName, scopeMap, blacklist, failDetail);
                    finishedDbs.add(dbName);
                } catch (Exception ex) {
                    anySchemaReadFail = true;
                    failDetail.add(Map.of("db", dbName,
                            "error", StrUtil.maxLength(safeError(ex), 500)));
                    logger.error(MetaCollectInstanceDO.STAGE_READ, "[DB] " + dbName,
                            "库处理失败：" + safeError(ex));
                    log.error("[execute][采集任务({}) 库({}) 处理失败]", taskId, dbName);
                }
            }
            updateCursor(instance, finishedDbs, null);

            // 整库范围且读取完整成功时，识别源端已消失的库（差异事件 + 待确认，不立即删除）
            if (task.getScopeType() != null && task.getScopeType() == MetaCollectTaskDO.SCOPE_ALL
                    && !anySchemaReadFail) {
                // 黑名单排除不等于源端删除，枚举到的全部 schema 都视为存在或被排除。
                diffEngine.markMissingDbs(ctx, new LinkedHashSet<>(schemas));
            }

            // 完成：统计与状态
            transactionTemplate.executeWithoutResult(tx -> {
                assertLease(instance.getId(), token);
                finishInstance(instance, task, ctx, failDetail, logger, startTime);
            });
        } catch (Throwable ex) {
            log.error("[execute][采集任务({}) 执行异常：{}]", taskId, ex.getClass().getSimpleName());
            finishFail(instance, task, instance.getStage(), classifyError(ex, false), logger, ex);
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
            logger.flush();
        }
    }

    /**
     * 校验元数据采集的租约，不满足约束时拒绝当前操作。
     */
    private void assertLease(Long instanceId, String token) {
        MetaCollectInstanceDO current = instanceMapper.lockInstance(instanceId);
        if (current == null || !Objects.equals(current.getStatus(), 1) || !Objects.equals(current.getExecutionToken(),
                token)
                || current.getLeaseUntil() == null || !current.getLeaseUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("采集租约已失效，拒绝旧实例写入");
        }
    }

    /**
     * 处理 DB Switch 枚举的单个关系命名空间：原样用于读表和持久化，保留产品的 catalog/schema 语义。
     */
    private void processSchema(MetaCollectContext ctx, IMetaDataByDatasourceService metaService,
            MetaCollectTaskDO task, String dbName,
            Map<String, Set<String>> scopeMap, List<String> blacklist,
            List<Map<String, String>> failDetail) {
        MetaCollectLogger logger = ctx.getLogger();
        logger.info(MetaCollectInstanceDO.STAGE_READ, "[DB] " + dbName, "开始处理数据库");
        MetaDbDO db = diffEngine.upsertDb(ctx, dbName);

        List<TableDescription> tableDescriptions = metaService.queryTableList(dbName);
        Set<String> scopeTables = scopeMap == null ? null : scopeMap.get(dbName);
        boolean wholeDb = scopeTables == null || scopeTables.isEmpty();

        Set<String> enumeratedNames = new LinkedHashSet<>();
        int processed = 0;
        for (TableDescription td : tableDescriptions) {
            String tableName = td.getTableName();
            if (!wholeDb && !scopeTables.contains(tableName)) {
                continue;
            }
            if (matchBlacklist(blacklist, dbName, tableName)) {
                logger.info(MetaCollectInstanceDO.STAGE_READ, "[TABLE] " + dbName + "." + tableName,
                        "命中黑名单，跳过（排除不等于源端删除）");
                enumeratedNames.add(tableName);
                continue;
            }
            // 枚举成功即证明对象存在；结构读取失败不得当作删除。
            enumeratedNames.add(tableName);
            CollectedTable collected;
            try {
                collected = readTable(metaService, dbName, td);
            } catch (Exception ex) {
                failDetail.add(Map.of("db", dbName, "table", tableName,
                        "error", StrUtil.maxLength(safeError(ex), 500)));
                logger.error(MetaCollectInstanceDO.STAGE_READ, "[TABLE] " + dbName + "." + tableName,
                        "结构读取失败，保留现有结构，不生成删除结论：" + safeError(ex));
                continue;
            }
            try {
                // DIFF/WRITE：单表独立事务，失败不回滚整库
                diffEngine.processTable(ctx, db, collected);
                processed++;
            } catch (Exception ex) {
                failDetail.add(Map.of("db", dbName, "table", tableName,
                        "error", StrUtil.maxLength(safeError(ex), 500)));
                logger.error(MetaCollectInstanceDO.STAGE_WRITE, "[TABLE] " + dbName + "." + tableName,
                        "差异写入失败：" + safeError(ex));
                log.error("[processSchema][任务({}) 表({}.{}) 差异写入失败]",
                        ctx.getTaskId(), dbName, tableName);
            }
        }

        // 整库范围时识别源端已消失的表（自定义表清单范围不做删除识别）
        if (wholeDb) {
            diffEngine.markMissingTables(ctx, db, enumeratedNames);
        }
        transactionTemplate.executeWithoutResult(tx -> {
            ctx.getWriteFence().run();
            diffEngine.refreshDbStat(db);
        });
        logger.info(MetaCollectInstanceDO.STAGE_WRITE, "[DB] " + dbName,
                String.format("数据库处理完成，处理表数=%s", processed));
    }

    /**
     * 读取单表结构（字段 + 主键），统一转换为采集模型
     */
    private CollectedTable readTable(IMetaDataByDatasourceService metaService, String dbName,
            TableDescription td) {
        List<ColumnDescription> columnDescriptions =
                metaService.queryTableColumnMeta(dbName, td.getTableName());
        List<String> primaryKeys;
        try {
            primaryKeys = metaService.queryTablePrimaryKeys(dbName, td.getTableName());
        } catch (Exception ex) {
            // 部分数据库/视图不支持主键查询，降级为空
            primaryKeys = new ArrayList<>();
        }
        Set<String> pkSet = new HashSet<>(primaryKeys);
        List<CollectedColumn> columns = new ArrayList<>(columnDescriptions.size());
        int ordinal = 0;
        for (ColumnDescription cd : columnDescriptions) {
            ordinal++;
            Long length = cd.getLength() != null ? cd.getLength().longValue()
                    : (cd.getDisplaySize() > 0 ? (long) cd.getDisplaySize() : null);
            Integer precision = cd.getPrecisionSize() > 0 ? cd.getPrecisionSize() : null;
            Integer scale = precision != null ? cd.getScaleSize() : null;
            columns.add(CollectedColumn.builder()
                    .columnName(cd.getFieldName())
                    .ordinalPosition(ordinal)
                    .remarks(cd.getRemarks())
                    .dataType(cd.getFieldTypeName())
                    .dataLength(length)
                    .dataPrecision(precision)
                    .dataScale(scale)
                    .nullableFlag(cd.isNullable() ? 1 : 0)
                    .defaultValue(cd.getDefaultValue())
                    .pkFlag(pkSet.contains(cd.getFieldName()) || cd.isPk() ? 1 : 0)
                    .autoIncrementFlag(cd.isAutoIncrement() ? 1 : 0)
                    .build());
        }
        return CollectedTable.builder()
                .tableName(td.getTableName())
                .tableType(td.isViewTable() ? "VIEW" : "TABLE")
                .remarks(td.getRemarks())
                .primaryKeys(primaryKeys)
                .columns(columns)
                .build();
    }

    // ==================== 范围与黑名单 ====================

    /**
     * 解析采集范围：返回 null 表示整个数据源；否则 key=库名，value=表清单（空集合表示整库）
     */
    private Map<String, Set<String>> parseScope(MetaCollectTaskDO task) {
        return MetaCollectSources.parseScope(task);
    }

    /**
     * 解析元数据采集的黑名单。
     */
    private List<String> parseBlacklist(MetaCollectTaskDO task) {
        if (StrUtil.isBlank(task.getBlacklist())) {
            return List.of();
        }
        List<String> patterns = JsonUtils.parseArray(task.getBlacklist(), String.class);
        return patterns == null ? List.of() : patterns;
    }

    /**
     * 黑名单匹配：`库.表` 精确到表；单段规则同时匹配库名与表名；支持 * 通配
     *
     * @param tableName 为空时仅做库级匹配
     */
    private boolean matchBlacklist(List<String> patterns, String dbName, String tableName) {
        for (String pattern : patterns) {
            if (StrUtil.isBlank(pattern)) {
                continue;
            }
            if (pattern.contains(".")) {
                if (tableName == null) {
                    continue;
                }
                String[] parts = pattern.split("\\.", 2);
                if (wildcardMatch(parts[0], dbName) && wildcardMatch(parts[1], tableName)) {
                    return true;
                }
            } else if (tableName == null ? wildcardMatch(pattern, dbName)
                    : wildcardMatch(pattern, tableName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按配置通配符判断采集对象名称是否匹配。
     */
    private boolean wildcardMatch(String pattern, String value) {
        if (StrUtil.isBlank(pattern) || value == null) {
            return false;
        }
        if (!pattern.contains("*")) {
            return pattern.equalsIgnoreCase(value);
        }
        String regex = "^" + String.join(".*",
                Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote).toArray(String[]::new)) + "$";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value).matches();
    }

    // ==================== 实例状态维护 ====================

    /**
     * 更新阶段。
     */
    private void updateStage(MetaCollectInstanceDO instance, String stage) {
        instance.setStage(stage);
        MetaCollectInstanceDO update = new MetaCollectInstanceDO();
        update.setId(instance.getId());
        update.setStage(stage);
        instanceMapper.update(update,
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectInstanceDO>()
                        .eq(MetaCollectInstanceDO::getId, instance.getId())
                        .eq(MetaCollectInstanceDO::getStatus, MetaCollectInstanceDO.STATUS_RUNNING)
                        .eq(MetaCollectInstanceDO::getExecutionToken, instance.getExecutionToken()));
    }

    /**
     * 更新游标。
     */
    private void updateCursor(MetaCollectInstanceDO instance, Set<String> finishedDbs, String currentDb) {
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("finishedDbs", new ArrayList<>(finishedDbs));
        cursor.put("currentDb", currentDb);
        MetaCollectInstanceDO update = new MetaCollectInstanceDO();
        update.setId(instance.getId());
        instance.setCollectCursor(JsonUtils.toJsonString(cursor));
        update.setCollectCursor(instance.getCollectCursor());
        update.setStage(MetaCollectInstanceDO.STAGE_DIFF);
        instanceMapper.update(update,
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectInstanceDO>()
                        .eq(MetaCollectInstanceDO::getId, instance.getId())
                        .eq(MetaCollectInstanceDO::getStatus, MetaCollectInstanceDO.STATUS_RUNNING)
                        .eq(MetaCollectInstanceDO::getExecutionToken, instance.getExecutionToken()));
    }

    /**
     * 结束实例。
     */
    private void finishInstance(MetaCollectInstanceDO instance, MetaCollectTaskDO task,
            MetaCollectContext ctx, List<Map<String, String>> failDetail,
            MetaCollectLogger logger, LocalDateTime startTime) {
        LocalDateTime endTime = LocalDateTime.now();
        MetaCollectInstanceDO update = new MetaCollectInstanceDO();
        update.setId(instance.getId());
        update.setFileCount(Objects.requireNonNullElse(instance.getFileCount(), 0));
        update.setTableCount(ctx.getTableProcessed().get());
        update.setColumnCount(ctx.getColumnProcessed().get());
        update.setDbCount(instanceDbCount(instance));
        update.setTableAdd(ctx.getTableAdd().get());
        update.setTableUpdate(ctx.getTableUpdate().get());
        update.setTableDelete(ctx.getTableDelete().get());
        update.setColumnAdd(ctx.getColumnAdd().get());
        update.setColumnUpdate(ctx.getColumnUpdate().get());
        update.setColumnDelete(ctx.getColumnDelete().get());
        update.setEndTime(endTime);
        update.setDurationMs(Duration.between(startTime, endTime).toMillis());
        boolean hasFail = !failDetail.isEmpty();
        boolean hasSuccess = ctx.getTableProcessed().get() > 0 || !hasFail;
        if (!hasFail) {
            update.setStatus(MetaCollectInstanceDO.STATUS_SUCCESS);
            update.setStage(MetaCollectInstanceDO.STAGE_DONE);
        } else if (hasSuccess) {
            update.setStatus(MetaCollectInstanceDO.STATUS_PARTIAL);
            update.setStage(MetaCollectInstanceDO.STAGE_DONE);
            update.setErrorType(MetaCollectInstanceDO.ERROR_PARTIAL);
            update.setFailDetail(JsonUtils.toJsonString(failDetail));
        } else {
            update.setStatus(MetaCollectInstanceDO.STATUS_FAIL);
            update.setErrorType(MetaCollectInstanceDO.ERROR_PARTIAL);
            update.setFailDetail(JsonUtils.toJsonString(failDetail));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tableAdd", ctx.getTableAdd().get());
        report.put("tableUpdate", ctx.getTableUpdate().get());
        report.put("tableDelete", ctx.getTableDelete().get());
        report.put("columnAdd", ctx.getColumnAdd().get());
        report.put("columnUpdate", ctx.getColumnUpdate().get());
        report.put("columnDelete", ctx.getColumnDelete().get());
        report.put("failCount", failDetail.size());
        update.setReport(JsonUtils.toJsonString(report));
        instanceMapper.update(update,
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectInstanceDO>()
                        .eq(MetaCollectInstanceDO::getId, instance.getId())
                        .eq(MetaCollectInstanceDO::getStatus, MetaCollectInstanceDO.STATUS_RUNNING)
                        .eq(MetaCollectInstanceDO::getExecutionToken, instance.getExecutionToken()));

        MetaCollectTaskDO taskUpdate = new MetaCollectTaskDO();
        taskUpdate.setId(task.getId());
        taskUpdate.setLastRunTime(startTime);
        taskUpdate.setLastInstanceId(instance.getId());
        taskMapper.update(taskUpdate,
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectTaskDO>()
                        .eq(MetaCollectTaskDO::getId, task.getId())
                        .and(w -> w.isNull(MetaCollectTaskDO::getLastInstanceId).or()
                                .le(MetaCollectTaskDO::getLastInstanceId, instance.getId())));

        // TODO P1b：接入平台统一事件（Outbox）后，在此发布 MetadataCollected.v1
        //  载荷：instanceId、datasourceId、report 摘要、changes[]（urn/objectType/changeType/breaking/versionFrom/To）；
        //  同时对接采集告警：失败或变更数量超过治理配置阈值时通知责任人

        if (Objects.equals(task.getCollectType(), 2)) {
            logger.info(MetaCollectInstanceDO.STAGE_DONE, null, String.format(
                    "任务执行完成：文件数=%s，文件新增=%s 物理变更=%s 源端缺失=%s",
                    instance.getFileCount(), ctx.getTableAdd().get(), ctx.getTableUpdate().get(),
                    ctx.getTableDelete().get()));
        } else {
            logger.info(MetaCollectInstanceDO.STAGE_DONE, null, String.format(
                    "任务执行完成：表新增=%s 变更=%s 待删除=%s，字段新增=%s 变更=%s 删除=%s，失败对象=%s",
                    ctx.getTableAdd().get(), ctx.getTableUpdate().get(), ctx.getTableDelete().get(),
                    ctx.getColumnAdd().get(), ctx.getColumnUpdate().get(), ctx.getColumnDelete().get(),
                    failDetail.size()));
        }
    }

    /**
     * 结束失败。
     */
    private void finishFail(MetaCollectInstanceDO instance, MetaCollectTaskDO task, String stage,
            String errorType, MetaCollectLogger logger, Throwable ex) {
        LocalDateTime endTime = LocalDateTime.now();
        MetaCollectInstanceDO update = new MetaCollectInstanceDO();
        update.setId(instance.getId());
        update.setStatus(MetaCollectInstanceDO.STATUS_FAIL);
        update.setStage(stage);
        update.setErrorType(errorType);
        update.setEndTime(endTime);
        if (instance.getStartTime() != null) {
            update.setDurationMs(Duration.between(instance.getStartTime(), endTime).toMillis());
        }
        update.setFailDetail(JsonUtils.toJsonString(List.of(Map.of(
                "error", StrUtil.maxLength(safeError(ex), 1000)))));
        instanceMapper.update(update,
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectInstanceDO>()
                        .eq(MetaCollectInstanceDO::getId, instance.getId())
                        .eq(MetaCollectInstanceDO::getStatus, MetaCollectInstanceDO.STATUS_RUNNING)
                        .eq(MetaCollectInstanceDO::getExecutionToken, instance.getExecutionToken()));

        MetaCollectTaskDO taskUpdate = new MetaCollectTaskDO();
        taskUpdate.setId(task.getId());
        taskUpdate.setLastRunTime(instance.getStartTime());
        taskUpdate.setLastInstanceId(instance.getId());
        taskMapper.update(taskUpdate,
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectTaskDO>()
                        .eq(MetaCollectTaskDO::getId, task.getId())
                        .and(w -> w.isNull(MetaCollectTaskDO::getLastInstanceId).or()
                                .le(MetaCollectTaskDO::getLastInstanceId, instance.getId())));

        logger.error(stage, null, String.format("任务执行失败（%s）：%s",
                errorType, safeError(ex)));
        log.error("[finishFail][采集实例({}) 执行失败，阶段={}，分类={}]",
                instance.getId(), stage, errorType);
    }

    /**
     * 统计指定采集实例涉及的数据库数量。
     */
    private Integer instanceDbCount(MetaCollectInstanceDO instance) {
        if (StrUtil.isBlank(instance.getCollectCursor())) {
            return 0;
        }
        try {
            Map<?, ?> cursor = JsonUtils.parseObject(instance.getCollectCursor(), Map.class);
            Object finished = cursor == null ? null : cursor.get("finishedDbs");
            return finished instanceof Collection<?> collection ? collection.size() : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * 错误分类：连接阶段区分认证/超时/连接失败，其余归解析类
     */
    private static String safeError(Throwable ex) {
        return "采集失败（" + ex.getClass().getSimpleName() + "），请检查连接、权限及采集范围";
    }

    /**
     * 按异常类型生成元数据采集失败分类。
     */
    private String classifyError(Throwable ex, boolean connectStage) {
        String message = StrUtil.nullToEmpty(ExceptionUtil.getMessage(ex)).toLowerCase();
        if (message.contains("password") || message.contains("denied")
                || message.contains("authentication") || message.contains("access denied")) {
            return MetaCollectInstanceDO.ERROR_AUTH;
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return MetaCollectInstanceDO.ERROR_TIMEOUT;
        }
        return connectStage ? MetaCollectInstanceDO.ERROR_CONNECT_FAIL
                : MetaCollectInstanceDO.ERROR_PARSE;
    }

}
