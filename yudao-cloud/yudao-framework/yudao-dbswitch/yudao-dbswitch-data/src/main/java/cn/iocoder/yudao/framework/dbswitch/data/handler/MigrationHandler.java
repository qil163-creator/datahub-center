package cn.iocoder.yudao.framework.dbswitch.data.handler;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import cn.iocoder.yudao.framework.dbswitch.common.constant.ChangeDataSyncType;
import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformDefinition;
import cn.iocoder.yudao.framework.dbswitch.common.entity.FrozenRecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableBatchProgress;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.common.type.SyncOptionEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.DatabaseAwareUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcTypesUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.StringUtil;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByDatasourceService;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.BytesUnitUtils;
import cn.iocoder.yudao.framework.dbswitch.dbchange.ChangeCalculatorService;
import cn.iocoder.yudao.framework.dbswitch.dbchange.IDatabaseChangeCaculator;
import cn.iocoder.yudao.framework.dbswitch.dbchange.IDatabaseRowHandler;
import cn.iocoder.yudao.framework.dbswitch.dbchange.RecordChangeTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbchange.TaskParamEntity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 在一个线程内的单表迁移处理逻辑
 * <p>
 * 职责：负责“单张表”的完整迁移流程，实现 {@link Supplier} 接口，
 * 由 MigrationService 提交到线程池并发执行，返回本表的同步结果 {@link DbSwitchTableResult}。
 * 主要流程（见 {@link #get()}）：
 * <ul>
 * <li>读取源端表/字段/主键元数据，按映射规则计算目标端表名与字段名；</li>
 * <li>按配置处理表结构：删表重建、仅建表、目标表不存在时自动建表（可含索引）；</li>
 * <li>按配置选择数据同步方式：全量覆盖 {@code doFullCoverSynchronize}、
 * 基于主键比对的变化量同步 {@code doIncreaseSynchronize}、
 * 基于增量字段的同步 {@code doTypedCursorSynchronize}。</li>
 * </ul>
 * 协作关系：用 {@link MetaDataByDataSourceServiceImpl} 读元数据与生成建表 DDL，
 * 用 {@link DbSwitchRuntime} 创建目标端批量写入器和增量同步器（INSERT/UPDATE/DELETE），
 * 用 {@link ChangeCalculatorService} 计算两端数据的变化量
 *
 * @author jrl
 */
@Slf4j
public class MigrationHandler implements Supplier<DbSwitchTableResult> {

    /** 读写流水线写线程编号器 */
    private static final AtomicInteger PIPELINE_THREAD_SEQ = new AtomicInteger(0);

    /**
     * 读写流水线共享写线程池：按需创建线程（空闲 60 秒回收），
     * 每张迁移中的表在全量同步阶段占用一个写线程，与读线程形成双缓冲并行
     */
    private static final ExecutorService PIPELINE_WRITE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "dbswitch-pipeline-writer-" + PIPELINE_THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 单批次缓存数据的最大字节数上限，达到即触发一次批量写入。
     * 默认 128MB（对齐 dbswitch 2.0.2），可通过 yudao.dbswitch.migration.max-cache-bytes 配置
     */
    private final long maxCacheBytes;

    private final BatchLimits batchLimits;

    private final TableBatchProgress.Collector batchProgress;

    private volatile DbSwitchTableResult progressTarget;

    private boolean metadataPrepared;

    private Map<String, String> mappedColumns;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TableBinding sequenceBinding;

    private cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeMigrationScope.TableBinding nativeBinding;

    private IDatabaseWriter sequenceWriter;

    private volatile boolean progressClosed;

    private final DbSwitchRuntime runtime;

    private final cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read tableRead;

    private final cn.iocoder.yudao.framework.dbswitch.data.service.MigrationTargetSqlAuthorizer targetSqlAuthorizer;

    /** 每批读取/写入的行数，默认 500，源端配置更大时以配置为准 */
    private int fetchSize = 500;

    /** 全局迁移配置 */
    private final MigrationRequest properties;

    /** 源端数据源配置 */
    private final SourceDataSourceProperties sourceProperties;

    /** 目标端数据源配置 */
    private final TargetDataSourceProperties targetProperties;

    /**
     * 行级值转换钩子（可选，脱敏/值映射；对齐 dbswitch 2.0.2 的 RecordTransformProvider），
     * null 表示不转换；实现须线程安全（多表并发共用同一实例）
     */
    private final RecordTransformer recordTransformer;

    private cn.iocoder.yudao.framework.dbswitch.data.util.AcceptedTypeMappingValues acceptedTypeMappingValues;

    /** 中断标记：外部调用 interrupt() 置位后，迁移会在下一个检查点抛异常终止 */
    private volatile boolean interrupted = false;

    /**
     * 全量读行正在使用的源端 Statement。所有权仍在 StatementResultSet，这里只供跨线程 cancel。
     * 查询超时由 reader 在 executeQuery 之前设置，这里不再调用 setQueryTimeout。
     */
    private volatile Statement activeReadStatement;

    /** 计数的是尚未真正退出的写线程（包含排队中），不是未完成的 Future。 */
    private final AtomicInteger activeWriters = new AtomicInteger();

    private volatile Runnable writerExitObserver = () -> {
    };

    /**
     * 检查当前作用域是否仍存在活跃写入器。
     */
    public boolean hasActiveWriters() {
        return activeWriters.get() != 0;
    }

    /** 执行方内部的退出事件：每个流水线写入器实际退出后触发，不得阻塞或抛出异常。 */
    public void observeWriterExit(Runnable observer) {
        this.writerExitObserver = java.util.Objects.requireNonNull(observer);
    }

    // 来源端
    private final HikariDataSource sourceDataSource;

    private ProductTypeEnum sourceProductType;

    private String sourceSchemaName;

    private String sourceTableName;

    private String sourceTableRemarks;

    private final String targetTableComment;

    private String nativeTableCommentOverride;

    private final cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions dorisOptions;

    private List<ColumnDescription> sourceColumnDescriptions;

    private List<String> sourcePrimaryKeys;

    private String increaseColumnName;

    private ColumnDescription increaseColumn;

    private String timestampColumnName;

    private List<PatternMapper> regexTableMapper;

    private IMetaDataByDatasourceService sourceMetaDataService;

    // 目的端
    private final HikariDataSource targetDataSource;

    private ProductTypeEnum targetProductType;

    private String targetSchemaName;

    private String targetTableName;

    private List<ColumnDescription> targetColumnDescriptions;

    private List<String> targetPrimaryKeys;

    // 日志输出字符串使用
    private String tableNameMapString;

    /**
     * 静态工厂方法：为一张源表创建迁移处理器实例
     *
     * @param td                    源表描述信息
     * @param properties            全局迁移配置
     * @param sourcePropertiesIndex 该表所属源端配置的下标
     * @param sds                   源端连接池
     * @param tds                   目标端连接池
     * @return 迁移处理器实例
     */
    public static MigrationHandler createInstance(TableDescription td,
            MigrationRequest properties,
            Integer sourcePropertiesIndex,
            HikariDataSource sds,
            HikariDataSource tds) {
        return new MigrationHandler(td, properties, sourcePropertiesIndex, sds, tds, null,
                DbSwitchRuntime.standalone());
    }

    /**
     * 静态工厂方法（带行级值转换钩子）：为一张源表创建迁移处理器实例
     *
     * @param recordTransformer 行级值转换钩子（脱敏/值映射），null 表示不转换
     */
    public static MigrationHandler createInstance(TableDescription td,
            MigrationRequest properties,
            Integer sourcePropertiesIndex,
            HikariDataSource sds,
            HikariDataSource tds,
            RecordTransformer recordTransformer) {
        return new MigrationHandler(td, properties, sourcePropertiesIndex, sds, tds, recordTransformer,
                DbSwitchRuntime.standalone());
    }

    /**
     * 构造方法：解析该表的映射配置并计算目标表名
     * <p>
     * 主要流程：
     * 1. 按映射类型取配置：ALL 用源端全局统一配置，否则按表名从 configMap 取该表独立配置
     *    （表名/列名映射规则、主键、增量字段、时间戳字段）；
     * 2. fetchSize 取源端配置与默认值 500 的较大者；
     * 3. 计算目标表名：SQL 抽取模式直接用配置的目标表名，表模式按正则映射转换，
     *    再依次追加表名前缀、按配置转成小写/大写；
     * 4. 生成“源表 --> 目标表”映射描述字符串，供日志输出使用
     */
    public static MigrationHandler createInstance(TableDescription td, MigrationRequest properties,
            Integer sourcePropertiesIndex, HikariDataSource sds, HikariDataSource tds,
            RecordTransformer recordTransformer, DbSwitchRuntime runtime) {
        return new MigrationHandler(td, properties, sourcePropertiesIndex, sds, tds, recordTransformer, runtime);
    }

    /**
     * 使用给定参数初始化 {@code MigrationHandler}。
     */
    private MigrationHandler(TableDescription td,
            MigrationRequest properties,
            Integer sourcePropertiesIndex,
            HikariDataSource sds,
            HikariDataSource tds,
            RecordTransformer recordTransformer, DbSwitchRuntime runtime) {
        this(td, properties, sourcePropertiesIndex, sds, tds, recordTransformer, runtime, null);
    }

    /**
     * 创建实例。
     */
    public static MigrationHandler createInstance(TableDescription td, MigrationRequest properties,
            Integer sourcePropertiesIndex, HikariDataSource sds, HikariDataSource tds,
            RecordTransformer recordTransformer, DbSwitchRuntime runtime,
            cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read read) {
        return new MigrationHandler(td, properties, sourcePropertiesIndex, sds, tds, recordTransformer, runtime, read);
    }

    /**
     * 校验构造参数并初始化 {@code MigrationHandler}。
     */
    private MigrationHandler(TableDescription td, MigrationRequest properties, Integer sourcePropertiesIndex,
            HikariDataSource sds, HikariDataSource tds, RecordTransformer recordTransformer, DbSwitchRuntime runtime,
            cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read read) {
        this(td, properties, sourcePropertiesIndex, sds, tds, recordTransformer, runtime, read, null);
    }

    /** 使用调用方提供的授权回调，禁止把该回调序列化为任务配置。 */
    public static MigrationHandler createInstance(TableDescription td, MigrationRequest properties,
            Integer sourcePropertiesIndex, HikariDataSource sds, HikariDataSource tds,
            RecordTransformer recordTransformer, DbSwitchRuntime runtime,
            cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read read,
            cn.iocoder.yudao.framework.dbswitch.data.service.MigrationTargetSqlAuthorizer authorizer) {
        return new MigrationHandler(td, properties, sourcePropertiesIndex, sds, tds, recordTransformer, runtime, read,
                authorizer);
    }

    private MigrationHandler(TableDescription td, MigrationRequest properties, Integer sourcePropertiesIndex,
            HikariDataSource sds, HikariDataSource tds, RecordTransformer recordTransformer, DbSwitchRuntime runtime,
            cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read read,
            cn.iocoder.yudao.framework.dbswitch.data.service.MigrationTargetSqlAuthorizer authorizer) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.targetSqlAuthorizer = authorizer;
        if (authorizer != null) {
            cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService.validateAuthorizedConfiguration(properties);
            if (recordTransformer != null) {
                throw new IllegalArgumentException("Authorized table copy does not support row transformers");
            }
        }
        if (read != null && (!read.relation().schema().equals(td.getSchemaName()) || !read.relation().table()
                .equals(td.getTableName()))) {
            throw new IllegalArgumentException("Table read plan does not match the migration source");
        }
        this.tableRead = read;

        this.properties = properties;
        this.sourceProperties = properties.getSource().get(sourcePropertiesIndex);
        this.targetProperties = properties.getTarget();
        BatchLimits requested = targetProperties.getBatchLimits() == null ? BatchLimits.defaults() : targetProperties.getBatchLimits();
        long byteCeiling = Math.min(requested.maxBatchBytes(), runtime.options().maxCacheBytes());
        this.batchLimits = new BatchLimits(requested.maxRows(), byteCeiling,
                Math.min(requested.maxCellBytes(), byteCeiling), requested.maxBindParameters(),
                requested.maxBatchAgeMillis());
        this.maxCacheBytes = batchLimits.maxBatchBytes();
        RecordTransformDefinition definition = RecordTransformDefinition.resolve(
                targetProperties.getTransformDefinition(), recordTransformer);
        this.batchProgress = new TableBatchProgress.Collector(definition);
        this.sourceSchemaName = td.getSchemaName();
        this.sourceTableName = td.getTableName();
        this.sourceDataSource = sds;
        this.targetDataSource = tds;
        MapperConfig tableOverride = sourceProperties.getConfigMap() == null ? null : sourceProperties.getConfigMap()
                .get(sourceTableName);
        this.targetTableComment = tableOverride == null ? null : tableOverride.getTargetTableComment();
        this.dorisOptions = tableOverride == null ? null : tableOverride.getDorisOptions();
        this.recordTransformer = recordTransformer == null ? null : new FrozenRecordTransformer(definition,
                recordTransformer, batchLimits);

        if (MapperType.ALL.name().equals(sourceProperties.getMapperType())) {
            this.regexTableMapper = sourceProperties.getRegexTableMapper();
            this.sourcePrimaryKeys = sourceProperties.getSourcePrimaryKeys();
            this.increaseColumnName = sourceProperties.getIncreaseColumnName();
            this.timestampColumnName = sourceProperties.getTimestampColumn();
        } else {
            Map<String, MapperConfig> configMap = sourceProperties.getConfigMap();
            MapperConfig mapperConfig = configMap == null ? new MapperConfig() : configMap.getOrDefault(sourceTableName,
                    new MapperConfig());
            this.regexTableMapper = mapperConfig.getRegexTableMapper();
            this.sourcePrimaryKeys = mapperConfig.getSourcePrimaryKeys();
            this.increaseColumnName = mapperConfig.getIncreaseColumnName();
            this.timestampColumnName = mapperConfig.getTimestampColumn();
        }

        if (sourceProperties.getFetchSize() == null || sourceProperties.getFetchSize() < 1) {
            throw new IllegalArgumentException("Source fetch size must be positive");
        }
        fetchSize = Math.min(sourceProperties.getFetchSize(), batchLimits.maxRows());

        // 获取映射转换后新的表名
        this.targetSchemaName = properties.getTarget().getTargetSchema();
        this.targetTableName = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(
                sourceProperties, targetProperties, sourceTableName);

        this.tableNameMapString = String.format("%s.%s --> %s.%s",
                sourceSchemaName,
                SourceType.TABLE.getCode().equals(sourceProperties.getSourceType()) ? sourceTableName
                        : "SQL[" + cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan
                        .digest(sourceProperties.getSourceSql()) + "]",
                targetSchemaName, targetTableName);
    }

    /**
     * 请求中断本表迁移：置标记位，并取消正在全量读行的 Statement。
     * 取消失败只记日志。读行循环仍在检查点抛异常终止，写线程仍由 writeFuture.cancel(true) 结束。
     */
    public void interrupt() {
        this.interrupted = true;
        Statement statement = this.activeReadStatement;
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (Exception failure) {
            // 取消运行在迁移线程之外。失败只记日志，不能抛出，否则会盖住读行异常，也会让后续表的 interrupt 停掉。
            log.warn("Migration read cancellation was not acknowledged");
        }
    }

    /** 失败时保留已观测进度；仍活跃的工作线程之后可能继续发布终止事实。 */
    public DbSwitchTableResult partialResult() {
        return progressTarget;
    }

    /**
     * 中断检查：任务被中断时抛出异常（供比对器等的循环级检查回调使用）
     */
    private void assertNotInterrupted() {
        if (interrupted) {
            throw new RuntimeException("task is interrupted");
        }
    }

    /**
     * 执行单表迁移主流程（线程池调用入口）
     * <p>
     * 主要流程：
     * 1. 读取源端元数据：表模式读表注释/字段/主键，SQL 模式按查询 SQL 解析字段；
     * 2. 按列名映射规则生成目标端字段描述与主键列表（含大小写转换、是否同步默认值）；
     * 3. 校验字段映射合法性：禁止删光全部字段、删除主键字段、多列映射为同名列；
     * 4. 创建目标端批量写入器；MongoDB 作源端时强制删表重建（避免类型转换错误），
     *    MongoDB 作目标端时删集合后直接走全量覆盖同步；
     * 5. 配置了“不同步已存在的表”且目标表已存在时直接返回；
     * 6. 配置了删表重建（targetDrop=true）：先 drop 再生成并执行建表语句（可含索引），
     *    若配置只建表则到此返回，否则走全量覆盖同步；
     * 7. 未配置删表重建：目标表不存在则自动建表后全量同步；
     *    开启变化量同步（changeDataSync）时先为目标表补齐缺失字段，
     *    增量字段模式走 doTypedCursorSynchronize，
     *    两端主键一致时走 doIncreaseSynchronize，否则拒绝隐式扩大为全量同步
     *
     * @return 本表的同步结果（含源/目标表名、同步行数、字节数、消息等）
     */
    private DbSwitchTableResult prepareTable() {
        if (metadataPrepared) {
            return progressTarget;
        }
        assertNotInterrupted();
        boolean incrementalRequested = Boolean.TRUE.equals(targetProperties.getChangeDataSync());
        if (incrementalRequested && (Boolean.TRUE.equals(targetProperties.getTargetDrop())
                || Boolean.TRUE.equals(targetProperties.getTargetTruncateData()))) {
            throw new IllegalArgumentException("变化量同步禁止 DROP/TRUNCATE；全量替换须显式选择全量模式");
        }

        log.info("Begin Migrate table for {}", tableNameMapString);

        /*
         * this.sourceProductType =
         * DatabaseAwareUtils.getDatabaseTypeByDataSource(sourceDataSource);
         * this.targetProductType =
         * DatabaseAwareUtils.getDatabaseTypeByDataSource(targetDataSource);
         */
        this.sourceProductType = sourceProperties.getSourceProductType();
        this.targetProductType = targetProperties.getTargetProductType();
        this.sourceMetaDataService = runtime.metadata(sourceDataSource,
                sourceProductType);

        // 读取源表的表及字段元数据
        if (SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
            this.sourceTableRemarks = sourceMetaDataService
                    .getTableRemark(sourceSchemaName, sourceTableName);
            List<ColumnDescription> nativeColumns = runtime.reader(sourceDataSource, sourceProductType)
                    .nativeMigrationColumns(sourceSchemaName, sourceTableName);
            // 当两个产品都提供类型映射器时，根据原生事实规划跨产品结构。
            boolean crossProductNative = sourceProductType != targetProductType
                    && runtime.products().typeMappings().supports(sourceProductType, targetProductType);
            // 原生操作器定义不携带逻辑映射证据（Doris 为自身计划保留了一份）；
            // 跨产品计划需要读取器固定的事实。
            this.sourceColumnDescriptions = crossProductNative
                    ? sourceMetaDataService.queryTableColumnMetaWithNativeEvidence(sourceSchemaName, sourceTableName)
                    : !nativeColumns.isEmpty() ? nativeColumns
                    : sourceMetaDataService.queryTableColumnMeta(sourceSchemaName, sourceTableName);
            if (CollectionUtils.isEmpty(sourcePrimaryKeys)) {
                // 原生列定义传输协议，包括其中的标识字段。
                // 此处重新读取关系型元数据可能导致仅处理结构的原生计划被拒绝。
                this.sourcePrimaryKeys = !crossProductNative && sourceProductType == targetProductType
                        && !nativeColumns.isEmpty()
                        ? nativeColumns.stream().filter(ColumnDescription::isPk)
                        .map(ColumnDescription::getFieldName).toList()
                        : sourceMetaDataService.queryTablePrimaryKeys(sourceSchemaName, sourceTableName);
            }
        } else if (SourceType.SQL.getCode().equals(sourceProperties.getSourceType())) {
            this.sourceColumnDescriptions = sourceMetaDataService.querySqlColumnMeta(sourceProperties.getSourceSql());
        }
        // 自动匹配会显式带回源注释；无变化时仍复用产品原有原生计划。
        this.nativeTableCommentOverride = java.util.Objects.equals(targetTableComment, sourceTableRemarks)
                ? null : targetTableComment;
        if (targetTableComment != null) {
            this.sourceTableRemarks = targetTableComment;
        }
        // 增量字段
        this.increaseColumn = this.sourceColumnDescriptions.stream()
                .filter(item -> item.getFieldName().equals(this.increaseColumnName)).findFirst().orElse(null);

        // 预览和执行复用同一列映射规则，保持原生证据与默认值省略语义一致。
        var columnPlan = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings.resolve(
                sourceProperties, targetProperties, sourceTableName, sourceColumnDescriptions, sourcePrimaryKeys);
        this.targetColumnDescriptions = columnPlan.columns();
        this.targetPrimaryKeys = columnPlan.primaryKeys();
        if (dorisOptions != null) {
            if (targetProductType != ProductTypeEnum.DORIS || !SourceType.TABLE.getCode()
                    .equals(sourceProperties.getSourceType())) {
                throw new IllegalArgumentException(
                        "Doris table options require a physical table source and a Doris target");
            }
            dorisOptions.validateColumns(
                    columnPlan.retainedColumns().stream().map(ColumnDescription::getFieldName).toList());
            var custom = targetProperties.getCustomDdlMap();
            if (custom != null && StringUtil.isNotBlank(custom.get(targetTableName))) {
                throw new IllegalArgumentException("Custom DDL cannot be combined with structured Doris options");
            }
        }

        // 构建表的同步结果
        DbSwitchTableResult dbSwitchTableResult = new DbSwitchTableResult();
        this.progressTarget = dbSwitchTableResult;
        dbSwitchTableResult.setSourceSchemaName(sourceSchemaName);
        dbSwitchTableResult.setSourceTableName(
                SourceType.TABLE.getCode().equals(sourceProperties.getSourceType()) ? sourceTableName
                        : sourceProperties.getSourceSql());
        dbSwitchTableResult.setTargetSchemaName(targetSchemaName);
        dbSwitchTableResult.setTargetTableName(targetTableName);
        dbSwitchTableResult.setTableRemarks(sourceTableRemarks);
        dbSwitchTableResult.setSyncTime(new Date());

        // 打印表名与字段名的映射关系
        List<String> columnMapperPairs = new ArrayList<>();
        Map<String, String> mapChecker = new HashMap<>();
        for (int i = 0; i < sourceColumnDescriptions.size(); ++i) {
            String sourceColumnName = sourceColumnDescriptions.get(i).getFieldName();
            String targetColumnName = targetColumnDescriptions.get(i).getFieldName();
            if (StringUtils.hasLength(targetColumnName)) {
                columnMapperPairs.add(String.format("%s --> %s", sourceColumnName, targetColumnName));
                mapChecker.put(sourceColumnName, targetColumnName);
            } else {
                columnMapperPairs.add(String.format(
                        "%s --> %s",
                        sourceColumnName,
                        String.format("<!Field(%s) is Deleted>", (i + 1))));
            }
        }
        log.info("Mapping relation : \ntable mapper :\n\t{}  \ncolumn mapper :\n\t{} ",
                tableNameMapString, String.join("\n\t", columnMapperPairs));
        // 校验字段映射的合法性：禁止删光字段、删除主键、多列映射为同名列
        Set<String> valueSet = new HashSet<>(mapChecker.values());
        if (valueSet.size() <= 0) {
            throw new RuntimeException("字段映射配置有误，禁止通过映射将表所有的字段都删除!");
        }
        if (!valueSet.containsAll(this.targetPrimaryKeys)) {
            throw new RuntimeException("字段映射配置有误，禁止通过映射将表的主键字段删除!");
        }
        if (mapChecker.keySet().size() != valueSet.size()) {
            throw new RuntimeException("字段映射配置有误，禁止将多个字段映射到一个同名字段!");
        }

        mappedColumns = Map.copyOf(mapChecker);
        // 目标 DDL 或写入开始前，解析已配置的源端时间戳。
        getTimestampColumn();
        metadataPrepared = true;
        return dbSwitchTableResult;
    }

    /**
     * 根据当前输入构建并返回 {@code cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan}。
     */
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan sequenceTablePlan() {
        prepareTable();
        return new cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan(
                new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(sourceSchemaName,
                        sourceTableName),
                new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(targetSchemaName,
                        targetTableName), mappedColumns);
    }

    /**
     * 拒绝当前实现未支持的 {@code nativeTablePlan} 操作。
     */
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeMigrationScope.TablePlan nativeTablePlan() {
        prepareTable();
        if (nativeTableCommentOverride != null || dorisOptions != null) {
            throw new UnsupportedOperationException(
                    "Scoped native migration does not support per-table layout overrides");
        }
        if (!Boolean.TRUE.equals(targetProperties.getOnlyCreate())) {
            newFullWriter().preflightRuntimeWrite();
        }
        return new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeMigrationScope.TablePlan(
                new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(sourceSchemaName,
                        sourceTableName),
                new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(targetSchemaName,
                        targetTableName), mappedColumns,
                runtime.reader(sourceDataSource, sourceProductType)
                        .describeNativeTable(sourceSchemaName, sourceTableName));
    }

    /**
     * 绑定原生迁移。
     */
    public void bindNativeMigration(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeMigrationScope.TableBinding binding) {
        if (!metadataPrepared || nativeBinding != null || sequenceBinding != null) {
            throw new IllegalStateException("Native migration binding is out of order");
        }
        nativeBinding = java.util.Objects.requireNonNull(binding);
        progressTarget.setNativePlanDigest(binding.targetPlan().digest());
        refreshNativeRecoveryResources();
    }

    /** 资源级收尾后刷新，包括延迟发生且失败或结果未知的原生 DDL。 */
    public void refreshNativeRecoveryResources() {
        if (nativeBinding != null) {
            progressTarget.setNativeRecoveryResources(nativeBinding.targetPlan().recoveryResources());
        }
    }

    /**
     * 绑定序列传输。
     */
    public void bindSequenceTransfer(
            cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TableBinding binding) {
        if (!metadataPrepared || sequenceBinding != null) {
            throw new IllegalStateException("Sequence binding is out of order");
        }
        sequenceBinding = java.util.Objects.requireNonNull(binding);
    }

    /**
     * 生成创建当前迁移所需序列的 SQL 集合。
     */
    public List<String> sequenceCreateStatements() {
        if (sequenceBinding == null) {
            throw new IllegalStateException("Sequence scope is required");
        }
        return buildCreateTableStatements(runtime.metadata(targetDataSource, targetProductType));
    }

    /** 在任务组修改任何目标前，复用物理字段匹配和本地自增标识预检。 */
    public void preflightSequenceTarget() {
        if (sequenceBinding == null) {
            throw new IllegalStateException("Sequence scope is required");
        }
        var metadata = runtime.metadata(targetDataSource, targetProductType);
        boolean exists = targetTableExists(metadata);
        if (exists) {
            resolveTargetColumns(metadata, List.copyOf(mappedColumns.values()),
                    metadata.queryTableColumnMeta(targetSchemaName, targetTableName).stream()
                            .map(ColumnDescription::getFieldName).toList());
        }
        if (Boolean.TRUE.equals(targetProperties.getOnlyCreate())) {
            return;
        }
        var writer = newFullWriter();
        writer.preflightRuntimeWrite();
        writer.configureIdentityTransfer(identityTransferOptions());
        writer.preflightIdentityTransfer(targetSchemaName, targetTableName, exists);
        sequenceWriter = writer;
    }

    /**
     * 为全量数据搬运创建新的目标写入器。
     */
    private IDatabaseWriter newFullWriter() {
        if (sequenceWriter != null) {
            return sequenceWriter;
        }
        var writer = runtime.writer(targetDataSource, targetProductType,
                Boolean.TRUE.equals(properties.getTarget().getWriterEngineInsert())
                        ? WriteMode.INSERT : (targetProductType == ProductTypeEnum.POSTGRESQL
                        || targetProductType == ProductTypeEnum.GREENPLUM || targetProductType == ProductTypeEnum.UXDB)
                        ? WriteMode.COPY : WriteMode.INSERT);
        if (sequenceBinding != null) {
            writer.configureSequenceTransfer(sequenceBinding);
        }
        return writer;
    }

    /**
     * 根据给定键或索引读取当前容器中的值。
     */
    @Override
    public DbSwitchTableResult get() {
        var dbSwitchTableResult = prepareTable();
        if (targetSqlAuthorizer != null) {
            return doAuthorizedCreateNewSynchronize(dbSwitchTableResult);
        }
        validateDistributedKeys();
        validateExistingDorisOptions();
        boolean incrementalRequested = Boolean.TRUE.equals(targetProperties.getChangeDataSync());
        var mapChecker = mappedColumns;

        if (interrupted) {
            throw new RuntimeException("task is interrupted");
        }

        if (incrementalRequested && ChangeDataSyncType.INCREASE_COLUMN.name()
                .equals(targetProperties.getChangeDataSyncType())) {
            if (increaseColumn == null) {
                throw new IllegalStateException("配置的增量字段不存在，拒绝任何目标写入");
            }
            return doTypedCursorSynchronize(dbSwitchTableResult);
        }

        // 已有目标也必须经过保护自动创建和替换操作的同一套类型决策。
        // 确认列表为空时，所有已注册的跨产品映射器都必须拒绝 LOSSY 和 UNSUPPORTED。
        if (SourceType.TABLE.getCode()
                .equals(sourceProperties.getSourceType()) && sourceProductType != targetProductType
                && runtime.products().typeMappings().supports(sourceProductType, targetProductType)) {
            var mapping = new cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions(
                    runtime.products().typeMappings(),
                    cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement.acceptanceFor(
                            properties.getTypeMappingAcknowledgements(), targetProductType, targetSchemaName,
                            targetTableName));
            var retained = targetColumnDescriptions.stream()
                    .filter(column -> StringUtils.hasLength(column.getFieldName())).toList();
            var plan = cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.planCrossProductColumns(
                    runtime.products().metadata(targetProductType), retained, targetPrimaryKeys, mapping);
            dbSwitchTableResult.setTypeMappingPlan(plan);
            plan.requireAccepted();
            acceptedTypeMappingValues = cn.iocoder.yudao.framework.dbswitch.data.util.AcceptedTypeMappingValues.fromAccepted(
                    plan);
            if (acceptedTypeMappingValues.requiresTargetEvidence()) {
                var targetMetadata = runtime.metadata(targetDataSource, targetProductType);
                if (targetTableExists(targetMetadata)) {
                    acceptedTypeMappingValues.requireTargetColumns(
                            targetMetadata.queryTableColumnMetaWithNativeEvidence(targetSchemaName, targetTableName));
                }
            }
            if (targetProductType == ProductTypeEnum.SQLITE3) {
                try (Connection connection = targetDataSource.getConnection()) {
                    runtime.products().metadata(targetProductType).prepareDialect(connection);
                } catch (SQLException unavailable) {
                    throw new IllegalStateException("SQLite target dialect could not be verified");
                }
            }
        }

        if (sequenceBinding != null && Boolean.TRUE.equals(targetProperties.getOnlyCreate())) {
            sequenceBinding.tableCompleted();
            dbSwitchTableResult.setSuccessMsg("共享序列与全部依赖表结构已在同一事务建立并复核；未搬运数据");
            recordNoTransfer(dbSwitchTableResult, TableTransferSummary.Operation.DDL_ONLY);
            return dbSwitchTableResult;
        }
        IDatabaseWriter writer = newFullWriter();
        // 未证明的传输协议必须在首次目标 DDL/清表前拒绝；DIFF 在键预检与 prepareWrite 后执行。
        if (!incrementalRequested && !Boolean.TRUE.equals(targetProperties.getOnlyCreate())) {
            writer.preflightRuntimeWrite();
        }

        var identityOptions = Boolean.TRUE.equals(targetProperties.getOnlyCreate())
                ? cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions.disabled() : identityTransferOptions();
        if (identityOptions.preserveExplicitValues() && sequenceBinding == null) {
            writer.configureIdentityTransfer(identityOptions);
            writer.preflightIdentityTransfer(targetSchemaName, targetTableName,
                    targetTableExists(runtime.metadata(targetDataSource, targetProductType)));
        }
        if (sequenceBinding != null) {
            return doFullCoverSynchronize(writer, dbSwitchTableResult);
        }

        // 源端产品绝不能隐式授权对目标进行破坏性替换。
        String writeMode = properties.getTarget().getWriteMode();
        boolean effectiveTargetDrop = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.allowDrop(
                Boolean.TRUE.equals(properties.getTarget().getTargetDrop()),
                writeMode);
        if (cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.failIfTargetExists(writeMode)) {
            IMetaDataByDatasourceService existProbe = runtime.metadata(
                    targetDataSource, targetProductType);
            if (targetTableExists(existProbe)) {
                throw new IllegalStateException("CREATE_NEW/STRICT_COPY 拒绝覆盖已存在目标表 "
                        + targetSchemaName + "." + targetTableName);
            }
        }
        IDatabaseOperator nativeTarget = runtime.reader(targetDataSource, targetProductType);
        // SQL 传输与原生结构语义是独立的产品能力；对于提供类型映射器的跨产品
        // 源端，目标通过逻辑 DDL 路径规划，而不使用需要同产品物理事实
        // （例如分布策略）的原生计划。
        boolean crossProductLogical = SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())
                && sourceProductType != targetProductType
                && runtime.products().typeMappings().supports(sourceProductType, targetProductType);
        if (!targetProductType.isUseSql() || nativeTarget.supportsNativeTableDefinition() && !crossProductLogical) {
            if (incrementalRequested) {
                var nativeMetadata = runtime.metadata(targetDataSource, targetProductType);
                if (!targetTableExists(nativeMetadata)) {
                    throw new IllegalStateException("变化量同步要求已存在的原生目标");
                }
                if (nativeTarget.supportsNativeTableDefinition() && sourceProductType == targetProductType
                        && SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
                    var sourceDefinition = runtime.reader(sourceDataSource, sourceProductType)
                            .describeNativeTable(sourceSchemaName, sourceTableName);
                    // 仅做预检：DIFF 不得隐式创建或替换结构，也不得修改标签。
                    nativeTarget.prepareNativeTarget(sourceDefinition, targetSchemaName, targetTableName,
                                    Map.copyOf(mapChecker), false, nativeTableCommentOverride, dorisOptions)
                            .preflightDataTransfer();
                }
                requireDifferenceKeys(nativeMetadata);
                return doIncreaseSynchronize(writer, dbSwitchTableResult);
            }
            if (nativeTarget.supportsNativeTableDefinition()) {
                IMetaDataByDatasourceService nativeMetadata = runtime.metadata(targetDataSource, targetProductType);
                boolean nativeTargetExists = targetTableExists(nativeMetadata);
                if (!properties.getTarget().getSyncExist() && nativeTargetExists) {
                    dbSwitchTableResult.setSuccessMsg("由于设置了不同步已存在的原生目标，所以未同步表和数据");
                    recordNoTransfer(dbSwitchTableResult, TableTransferSummary.Operation.SKIPPED_EXISTING);
                    return dbSwitchTableResult;
                }
                if (!SourceType.TABLE.getCode()
                        .equals(sourceProperties.getSourceType()) || sourceProductType != targetProductType) {
                    if (nativeTargetExists && !effectiveTargetDrop && !Boolean.TRUE.equals(
                            properties.getTarget().getOnlyCreate())) {
                        return doFullCoverSynchronize(writer, dbSwitchTableResult);
                    }
                    throw new IllegalStateException("原生目标建表或重建要求同产品物理表来源；跨产品类型映射须显式适配");
                }
                var definition = nativeBinding == null ? runtime.reader(sourceDataSource, sourceProductType)
                        .describeNativeTable(sourceSchemaName, sourceTableName) : null;
                // 执行显式破坏性操作前，产品实现须验证全部原生属性
                // 和保留字段的完整映射；writer.prepare 绝不创建原生结构。
                var nativePlan = nativeBinding == null ? nativeTarget.prepareNativeTarget(definition, targetSchemaName,
                        targetTableName,
                        Map.copyOf(mapChecker), effectiveTargetDrop, nativeTableCommentOverride,
                        dorisOptions) : nativeBinding.targetPlan();
                assertNotInterrupted();
                dbSwitchTableResult.setNativePlanDigest(nativePlan.digest());
                dbSwitchTableResult.setNativeRecoveryResources(nativePlan.recoveryResources());
                if (nativePlan.requiresExclusiveDdlOwnership()
                        && !Boolean.TRUE.equals(targetProperties.getExclusiveTargetDdlOwnership())) {
                    throw new IllegalArgumentException(
                            "Native target replacement requires explicit exclusive DDL ownership");
                }
                if (!Boolean.TRUE.equals(targetProperties.getOnlyCreate())) {
                    nativePlan.preflightDataTransfer();
                    if (definition != null) {
                        writer.preflightNativeTarget(definition, Map.copyOf(mapChecker));
                    }
                }
                try {
                    nativePlan.execute();
                } finally {
                    dbSwitchTableResult.setNativeRecoveryResources(nativePlan.recoveryResources());
                }
                if (Boolean.TRUE.equals(properties.getTarget().getOnlyCreate())) {
                    if (nativeBinding != null) {
                        nativeBinding.tableCompleted();
                    }
                    dbSwitchTableResult.setSuccessMsg("原生目标结构已建立并复核；仅建结构，未搬运数据");
                    recordNoTransfer(dbSwitchTableResult, TableTransferSummary.Operation.DDL_ONLY);
                    return dbSwitchTableResult;
                }
                var transferred = doFullCoverSynchronize(writer, dbSwitchTableResult);
                if (nativeBinding != null) {
                    nativeBinding.tableCompleted();
                }
                return transferred;
            }
            if (cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.isExplicit(writeMode)) {
                throw new IllegalStateException("CREATE_NEW/STRICT_COPY 暂不支持非 SQL 目标产品");
            }
            // 无模式目标无需 DDL；只有显式请求时才允许删除。
            if (effectiveTargetDrop) {
                runtime.reader(targetDataSource, targetProductType)
                        .dropTable(targetSchemaName, targetTableName);
            }
            return doFullCoverSynchronize(writer, dbSwitchTableResult);
        }
        // zrx 如果不同步已存在的
        if (!properties.getTarget().getSyncExist()) {
            IMetaDataByDatasourceService metaDataByDatasourceServicee = runtime.metadata(
                    targetDataSource, targetProductType);
            if (targetTableExists(metaDataByDatasourceServicee)) {
                log.info("syncExist is false,table {}.{} has existed,do not sync,just return!", targetSchemaName,
                        targetTableName);
                dbSwitchTableResult.setSuccessMsg("由于设置了不同步已存在的表，所以未同步表和数据");
                recordNoTransfer(dbSwitchTableResult, TableTransferSummary.Operation.SKIPPED_EXISTING);
                return dbSwitchTableResult;
            }
        }

        if (effectiveTargetDrop) {
            /*
             * 如果配置了dbswitch.target.datasource-target-drop=true时，
             * <p>
             * 先执行drop table语句，然后执行create table语句
             */
            IMetaDataByDatasourceService targetDatasourceservice = runtime.metadata(targetDataSource,
                    targetProductType);
            List<String> createStatements = buildCreateTableStatements(targetDatasourceservice);
            boolean identityReplacement = identityOptions.preserveExplicitValues() && writer.prepareIdentityReplacement(
                    new cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityReplacement(targetSchemaName,
                            targetTableName,
                            cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityReplacement.Mode.RECREATE,
                            createStatements));
            // 显式判断表是否存在再删除：历史实现把 drop 的一切异常（含权限不足、网络故障）
            // 都当成"表不存在"吞掉，会把真实错误延迟到建表阶段并给出误导信息
            if (!identityReplacement && targetTableExists(targetDatasourceservice)) {
                log.info("Target Table {}.{} exists, drop it now !", targetSchemaName, targetTableName);
                if (targetProductType == ProductTypeEnum.POSTGRESQL) {
                    var replacement = new ArrayList<String>();
                    replacement.add(
                            "DROP TABLE " + cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                                    .qualifiedTableName(targetProductType, targetSchemaName, targetTableName));
                    replacement.addAll(createStatements);
                    createStatements = List.copyOf(replacement);
                } else {
                    runtime.reader(targetDataSource, targetProductType)
                            .dropTable(targetSchemaName, targetTableName);
                }
            } else if (!identityReplacement) {
                log.info("Target Table {}.{} does not exist, create it!", targetSchemaName, targetTableName);
            }

            // 生成建表语句并创建（优先使用用户自定义 DDL）
            if (!identityReplacement) {
                executeCreateTableStatements(createStatements);
            }

            // 如果只想创建表，这里直接返回
            if (null != properties.getTarget().getOnlyCreate()
                    && properties.getTarget().getOnlyCreate()) {
                dbSwitchTableResult.setSuccessMsg("由于设置了只创建表，所以未同步数据，已建表");
                recordNoTransfer(dbSwitchTableResult, TableTransferSummary.Operation.DDL_ONLY);
                return dbSwitchTableResult;
            }

            if (interrupted) {
                throw new RuntimeException("task is interrupted");
            }

            return doFullCoverSynchronize(writer, dbSwitchTableResult);
        } else {
            // 对于只想创建表的情况，不提供后续的变化量数据同步功能
            if (null != properties.getTarget().getOnlyCreate()
                    && properties.getTarget().getOnlyCreate()) {
                IMetaDataByDatasourceService createTarget = runtime.metadata(targetDataSource, targetProductType);
                if (!targetTableExists(createTarget)) {
                    executeCreateTable(createTarget);
                }
                dbSwitchTableResult.setSuccessMsg("由于设置了只创建表，所以未同步数据，已建表");
                recordNoTransfer(dbSwitchTableResult, TableTransferSummary.Operation.DDL_ONLY);
                return dbSwitchTableResult;
            }

            if (interrupted) {
                throw new RuntimeException("task is interrupted");
            }

            IMetaDataByDatasourceService metaDataByDatasourceService = runtime.metadata(
                    targetDataSource, targetProductType);

            if (!targetTableExists(metaDataByDatasourceService)) {
                if (incrementalRequested) {
                    throw new IllegalStateException("变化量同步目标表不存在，拒绝隐式建表并执行全量；请显式完成初始化");
                }
                // 当目标端不存在该表时，则生成建表语句并创建（优先使用用户自定义 DDL）
                executeCreateTable(metaDataByDatasourceService);

                if (interrupted) {
                    throw new RuntimeException("task is interrupted");
                }

                return doFullCoverSynchronize(writer, dbSwitchTableResult);
            }

            // 判断是否具备变化量同步的条件：（1）两端表结构一致，且都有一样的主键字段；(2)MySQL使用Innodb引擎；
            if (incrementalRequested) {
                requireDifferenceKeys(metaDataByDatasourceService);

                // MySQL 目标端要求 InnoDB 引擎才做变化量同步（对齐 dbswitch 2.0.2）：
                // MyISAM 等无事务引擎在增删改批量执行失败时无法回滚，存在一致性风险
                if (ProductTypeEnum.MYSQL.equals(targetProductType) || ProductTypeEnum.MARIADB.equals(
                        targetProductType)) {
                    try {
                        if (!DatabaseAwareUtils.isMysqlInnodbStorageEngine(
                                targetSchemaName, targetTableName, targetDataSource)) {
                            throw new IllegalStateException("变化量同步要求 InnoDB 目标");
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("无法确认 MySQL 目标表的 InnoDB 能力，拒绝变化量同步或隐式全量");
                    }
                }

                // DIFF 绝不隐式修改结构；产品准备步骤和通用比较器
                // 须在任何 DML 前验证物理字段和全部匹配键。
                return doIncreaseSynchronize(writer, dbSwitchTableResult);
            } else {
                return doFullCoverSynchronize(writer, dbSwitchTableResult);
            }
        }
    }

    /** 新表全量复制的闭合授权路径：先生成并授权完整写入计划，再执行返回的语句。 */
    private DbSwitchTableResult doAuthorizedCreateNewSynchronize(DbSwitchTableResult result) {
        if (interrupted) {
            throw new IllegalStateException("Migration was interrupted before target authorization");
        }
        requireOrdinaryAuthorizedSource();
        var metadata = runtime.metadata(targetDataSource, targetProductType);
        if (targetTableExists(metadata)) {
            throw new IllegalStateException("Authorized CREATE_NEW refuses an existing target table");
        }
        List<String> fields = targetColumnDescriptions.stream().map(ColumnDescription::getFieldName)
                .filter(StringUtils::hasLength).toList();
        IDatabaseWriter writer = newFullWriter();
        writer.preflightRuntimeWrite();
        List<String> createStatements = buildCreateTableStatements(metadata);
        if (createStatements.size() != 1) {
            throw new UnsupportedOperationException("Authorized CREATE_NEW requires exactly one ordinary CREATE TABLE");
        }
        var planned = cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan.parse(
                ProductTypeEnum.POSTGRESQL, targetSchemaName,
                List.of(createStatements.getFirst(), writer.planInsertSql(targetSchemaName, targetTableName, fields)));
        var objects = java.util.Set.of(targetTableName);
        var order = java.util.stream.IntStream.range(0, fields.size()).boxed().toList();
        if (planned.steps().size() != 2 || !"DDL".equals(planned.steps().get(0).operation())
                || !"DML".equals(planned.steps().get(1).operation())
                || planned.steps().stream().anyMatch(step -> !step.objectNames().equals(objects)
                || step.routineName() != null)
                || !planned.steps().get(0).parameterOrder().isEmpty()
                || !planned.steps().get(1).parameterOrder().equals(order)) {
            throw new IllegalArgumentException("Generated target plan is outside ordinary CREATE_NEW copy");
        }
        var authorized = targetSqlAuthorizer.authorize(targetTableName, fields, planned);
        if (!planned.equals(authorized)) {
            throw new IllegalArgumentException("Authorization changed the generated target SQL plan");
        }
        var insert = authorized.steps().get(1);
        writer.useAuthorizedInsertSql(targetSchemaName, targetTableName, fields, insert.sql(), insert.parameterOrder());
        if (interrupted) {
            throw new IllegalStateException("Migration was interrupted after target authorization");
        }
        executeCreateTableStatements(List.of(authorized.steps().get(0).sql()));
        return doFullCoverSynchronize(writer, result);
    }

    /** 原生目录必须证明没有隐藏写入或结构语义；未知事实不能当作不存在。 */
    private void requireOrdinaryAuthorizedSource() {
        if (StringUtils.hasText(sourceMetaDataService.getTableRemark(sourceSchemaName, sourceTableName))
                || StringUtils.hasText(targetTableComment)) {
            throw new UnsupportedOperationException("Authorized CREATE_NEW does not support table comments");
        }
        try (Connection connection = sourceDataSource.getConnection()) {
            var schema = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(connection,
                    ProductTypeEnum.POSTGRESQL, sourceSchemaName, sourceTableName,
                    Math.min(120, runtime.options().queryTimeoutSeconds()));
            var absent = cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT;
            if (!Boolean.TRUE.equals(schema.existence().value()) || schema.additionalObjects().state() != absent
                    || !schema.columns().stream().map(ColumnDescription::getFieldName).toList().equals(
                    sourceColumnDescriptions.stream().map(ColumnDescription::getFieldName).toList())) {
                throw new UnsupportedOperationException("Authorized CREATE_NEW requires a verified ordinary physical source table");
            }
            for (ColumnDescription column : schema.columns()) {
                var evidence = column.getMetadataEvidence();
                if (evidence.defaultExpression().state() != absent || evidence.identity().state() != absent
                        || evidence.generatedExpression().state() != absent || evidence.sequence().state() != absent
                        || StringUtils.hasText(column.getRemarks())) {
                    throw new UnsupportedOperationException("Authorized CREATE_NEW does not support defaults, generated columns or comments");
                }
            }
        } catch (SQLException unavailable) {
            throw new IllegalStateException("Authorized source table evidence is unavailable", unavailable);
        }
    }

    /**
     * 检查差异计算所需的键列已经明确声明。
     */
    private void requireDifferenceKeys(IMetaDataByDatasourceService metadata) {
        if (targetPrimaryKeys.isEmpty() || new HashSet<>(targetPrimaryKeys).size() != targetPrimaryKeys.size()) {
            throw new IllegalStateException("变化量同步必须指定互不重复的匹配键");
        }
        List<String> nativeKeys = metadata.queryTablePrimaryKeys(targetSchemaName, targetTableName);
        if (nativeKeys.isEmpty()) {
            if (!runtime.synchronizer(targetDataSource, targetProductType).supportsUnconstrainedMatchKeys()) {
                throw new IllegalStateException("该目标产品要求原生唯一匹配键");
            }
        } else if (!new HashSet<>(nativeKeys).equals(new HashSet<>(targetPrimaryKeys))) {
            throw new IllegalStateException("变化量同步的映射键必须与目标原生键一致");
        }
        if (!metadata.queryTableColumnName(targetSchemaName, targetTableName).containsAll(targetPrimaryKeys)) {
            throw new IllegalStateException("变化量同步匹配键不在目标物理字段中");
        }
    }

    /**
     * 执行覆盖同步：把源端数据整表读出并分批写入目标表（全量迁移）
     * <p>
     * 主要流程：
     * 1. 按下标对齐源/目标字段名（映射后字段名为空表示该字段被删除，跳过）；
     * 2. 校验所有显式保留的字段均能映射到目标物理列，禁止隐式丢列；
     * 3. 时序库目标端（TDengine/InfluxDB3）特殊处理：时间戳字段挪到首位，
     *    时间值必须由源字段或显式源查询提供；
     * 4. 配置了清空数据（targetTruncateData）时先 truncate 目标表；
     * 5. 流式读取源端数据（表模式整表查询 / SQL 模式按 SQL 查询），逐行装入缓存，
     *    行数达到 BATCH_SIZE 或字节数达到 maxCacheBytes 上限时批量写入目标端；
     * 6. 循环结束后写入剩余缓存，同步行数与字节数累计到结果对象
     *
     * @param writer 目的端的写入器
     */
    private DbSwitchTableResult doFullCoverSynchronize(IDatabaseWriter writer,
            DbSwitchTableResult dbSwitchTableResult) {
        writer.configureBatchLimits(batchLimits);
        this.progressTarget = dbSwitchTableResult;
        this.progressClosed = false;
        AtomicLong syncBytes = dbSwitchTableResult.getSyncBytes();
        AtomicLong syncCount = dbSwitchTableResult.getSyncCount();

        final int BATCH_SIZE = fetchSize;

        // 1. 按下标对齐源/目标字段名（目标字段名为空表示该字段被映射删除，跳过）
        List<String> sourceFieldsRaw = new ArrayList<>();
        List<String> targetFieldsRaw = new ArrayList<>();
        for (int i = 0; i < targetColumnDescriptions.size(); ++i) {
            ColumnDescription scd = sourceColumnDescriptions.get(i);
            ColumnDescription tcd = targetColumnDescriptions.get(i);
            if (!StringUtils.isEmpty(tcd.getFieldName())) {
                sourceFieldsRaw.add(scd.getFieldName());
                targetFieldsRaw.add(tcd.getFieldName());
            }
        }

        // 准备写入器或清空目标数据前，验证每个保留的映射。
        IMetaDataByDatasourceService targetMeta = runtime.metadata(writer.getDataSource(), targetProductType);
        if (acceptedTypeMappingValues != null && acceptedTypeMappingValues.requiresTargetEvidence()) {
            acceptedTypeMappingValues.requireTargetColumns(
                    targetMeta.queryTableColumnMetaWithNativeEvidence(targetSchemaName, targetTableName));
        }
        List<ColumnDescription> nativeTargetColumns = runtime.reader(writer.getDataSource(), targetProductType)
                .nativeMigrationColumns(targetSchemaName, targetTableName);
        var targetColumns = nativeTargetColumns.isEmpty()
                ? targetMeta.queryTableColumnMeta(targetSchemaName, targetTableName) : nativeTargetColumns;
        if (targetProductType == ProductTypeEnum.POSTGRESQL && !Boolean.TRUE.equals(
                targetProperties.getPreserveIdentityValues())
                && targetColumns.stream().anyMatch(column -> column.getMetadataEvidence().identity().value() != null
                || column.getMetadataEvidence().defaultExpression().value() != null
                && column.getMetadataEvidence().defaultExpression().value().contains("nextval("))) {
            throw new IllegalArgumentException(
                    "PostgreSQL sequence targets require explicit identity preservation before replacement or writes");
        }
        List<String> existTargetCols = targetColumns
                .stream()
                .map(ColumnDescription::getFieldName)
                .filter(StringUtils::hasLength)
                .toList();
        List<String> sourceFields = List.copyOf(sourceFieldsRaw);
        List<String> targetFields = resolveTargetColumns(targetMeta, targetFieldsRaw, existTargetCols);
        List<String> writeFields = new ArrayList<>(targetFields);
        List<Integer> sourceOrdinals = new ArrayList<>(targetFields.size());
        for (int i = 0; i < targetFields.size(); i++) {
            sourceOrdinals.add(i + 1);
        }
        // 时序排序必须显式指定；迁移时绝不能使用系统当前时间生成值。
        String timestampColumn = getTimestampColumn();
        if ((ProductTypeEnum.TDENGINE.equals(targetProductType)
                || ProductTypeEnum.INFLUXDB3.equals(targetProductType))
                && StringUtils.hasLength(timestampColumn)
                && indexOfIgnoreCase(existTargetCols, timestampColumn) >= 0) {
            int tsIndex = indexOfIgnoreCase(writeFields, timestampColumn);
            if (tsIndex > 0) {
                String tsField = writeFields.remove(tsIndex);
                Integer tsOrdinal = sourceOrdinals.remove(tsIndex);
                writeFields.add(0, tsField);
                sourceOrdinals.add(0, tsOrdinal);
            } else if (tsIndex < 0) {
                throw new IllegalStateException("时序迁移必须显式映射时间字段，禁止自动生成当前时间");
            }
        }
        // 没有匹配列属于配置失败，绝不能视为成功的空传输。
        if (targetFields.isEmpty()) {
            throw new IllegalStateException("目标表无可写入字段，必须提供完整的显式字段映射");
        }

        // 准备目的端的数据写入操作
        writer.prepareWrite(targetSchemaName, targetTableName, writeFields);

        // 请求的替换必须由产品实现执行，不能降级为追加。
        if (sequenceBinding == null && cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.allowTruncate(
                properties.getTarget().getTargetTruncateData(), properties.getTarget().getWriteMode())) {
            boolean identityReplacement = Boolean.TRUE.equals(targetProperties.getPreserveIdentityValues())
                    && writer.prepareIdentityReplacement(
                    new cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityReplacement(
                            targetSchemaName, targetTableName,
                            cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityReplacement.Mode.TRUNCATE, List.of()));
            if (!identityReplacement && !writer.truncatePreparedTarget(targetSchemaName, targetTableName)) {
                IDatabaseOperator targetOperator = runtime.reader(writer.getDataSource(), targetProductType);
                targetOperator.truncateTableData(targetSchemaName, targetTableName);
            }
        }

        // 查询源端数据并写入目的端
        IDatabaseOperator sourceOperator = runtime.reader(sourceDataSource, sourceProductType);
        sourceOperator.setFetchSize(BATCH_SIZE);

        // 读写流水线（对齐 dbswitch 2.0.2 的读写分离思想）：读线程组装批次后交给写线程执行，
        // 有界队列形成背压与双缓冲，读写并行以提升吞吐；可通过配置关闭退化为同步写
        TableTransferSummary transferSummary;
        try (StatementResultSet srs = SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())
                ? tableRead == null ? sourceOperator.queryTableData(sourceSchemaName, sourceTableName, sourceFields)
                : sourceOperator.queryTableData("SELECT * FROM " + tableRead.fromSql(), sourceFields)
                : sourceOperator.queryTableData(sourceProperties.getSourceSql(), sourceFields);
             ResultSet rs = srs.getResultset();
             PipelineBatchWriter batchWriter = new PipelineBatchWriter(
                     writer, writeFields, tableNameMapString, runtime.options().pipelineWriteEnabled())) {
            activeReadStatement = srs.getStatement();
            try {
                // 时序目标将时间列移到首位时，保留源端列序号。
                ResultSetMetaData rsMetaData = rs.getMetaData();
                int[] columnJdbcTypes = new int[writeFields.size()];
                for (int i = 0; i < writeFields.size(); i++) {
                    Integer ordinal = sourceOrdinals.get(i);
                    columnJdbcTypes[i] = rsMetaData.getColumnType(ordinal);
                }

                List<Object[]> cache = new ArrayList<>(BATCH_SIZE);
                long cacheBytes = 0;
                long batchStarted = System.nanoTime();
                // 流式读取源端行，不生成替代值，也不用 NULL 替换。
                while (rs.next()) {
                    if (interrupted) {
                        throw new RuntimeException("task is interrupted");
                    }
                    Object[] record = new Object[writeFields.size()];
                    for (int i = 0; i < writeFields.size(); i++) {
                        Integer ordinal = sourceOrdinals.get(i);
                        try {
                            record[i] = rs.getObject(ordinal);
                        } catch (Exception e) {
                            batchProgress.rejectedRead();
                            publishBatchProgress();
                            // 驱动异常可能包含源端值或连接细节，只保留序号。
                            throw new BatchRejectedException("Source field could not be read at ordinal "
                                    + ordinal + "; refusing NULL substitution");
                        }
                    }

                    record = prepareSourceRow(writeFields, record);

                    long rowBytes = BatchValueConverter.estimatedRowBytes(record);
                    if (rowBytes > maxCacheBytes) {
                        throw new BatchRejectedException("One row exceeds the batch byte limit");
                    }
                    if (!cache.isEmpty() && (rowBytes > maxCacheBytes - cacheBytes
                            || elapsedBatchMillis(batchStarted) >= batchLimits.maxBatchAgeMillis())) {
                        batchWriter.submit(cache, cacheBytes);
                        syncBytes.getAndAdd(cacheBytes);
                        cache = new ArrayList<>(BATCH_SIZE);
                        cacheBytes = 0;
                        batchStarted = System.nanoTime();
                    }
                    cache.add(record);
                    cacheBytes = Math.addExact(cacheBytes, rowBytes);
                    syncCount.getAndAdd(1);
                    batchProgress.read(rowBytes);
                    publishBatchProgress();
                    if (cache.size() >= BATCH_SIZE || cacheBytes >= maxCacheBytes) {
                        batchWriter.submit(cache, cacheBytes);
                        syncBytes.getAndAdd(cacheBytes);
                        cache = new ArrayList<>(BATCH_SIZE);
                        cacheBytes = 0;
                        batchStarted = System.nanoTime();
                    }
                }

                // 写入最后一批不足批次上限的剩余数据
                if (!cache.isEmpty()) {
                    batchWriter.submit(cache, cacheBytes);
                    syncBytes.getAndAdd(cacheBytes);
                }

                // 等待写线程完成全部批次（失败时在此抛出写入异常）
                batchWriter.finish();
                transferSummary = new TableTransferSummary(
                        SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())
                                ? TableTransferSummary.Operation.FULL_SCAN_TABLE : TableTransferSummary.Operation.SQL_QUERY,
                        syncCount.get(), batchWriter.acknowledgedRows.get(), sourceColumnDescriptions.size(),
                        sourceFields.size(), 0, recordTransformer != null
                        || acceptedTypeMappingValues != null && acceptedTypeMappingValues.requiresTargetEvidence(),
                        false,
                        hasCustomScripts(), batchWriter.commits.snapshot());
            } finally {
                // 先摘掉引用再关闭资源，避免 close 期间的 cancel 失败变成迁移异常。
                activeReadStatement = null;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            activeReadStatement = null;
            progressClosed = true;
            publishBatchProgress();
        }
        // try-with-resources 正常返回后发布；这里不是 JDBC 会话终止凭证（部分旧关闭器会吞异常）。
        dbSwitchTableResult.setTransferSummary(transferSummary);
        // 即使独立的序列对齐阶段失败，也保留已确认的行回执。
        assertNotInterrupted();
        writer.completeIdentityTransfer();
        // 返回表执行结果
        return dbSwitchTableResult;
    }

    /**
     * 准备源端行。
     */
    private Object[] prepareSourceRow(List<String> fields, Object[] record) {
        try {
            Object[] row = BatchValueConverter.convert(List.<Object[]>of(record), fields.size(), batchLimits)
                    .getFirst();
            if (recordTransformer != null) {
                row = recordTransformer.transform(sourceSchemaName,
                        SourceType.TABLE.getCode()
                                .equals(sourceProperties.getSourceType()) ? sourceTableName : sourceProperties.getSourceSql(),
                        fields, row);
            }
            if (acceptedTypeMappingValues != null) {
                row = acceptedTypeMappingValues.convert(fields, row);
            }
            return row;
        } catch (BatchRejectedException rejected) {
            batchProgress.rejectedRead();
            publishBatchProgress();
            throw rejected;
        }
    }

    /**
     * 返回当前批次已经经过的毫秒数。
     */
    private static long elapsedBatchMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /**
     * 发布批次进度。
     */
    private void publishBatchProgress() {
        DbSwitchTableResult result = progressTarget;
        if (result != null) {
            result.setBatchProgress(batchProgress.snapshot(progressClosed && activeWriters.get() == 0));
        }
    }

    /**
     * 记录缺少传输。
     */
    private void recordNoTransfer(DbSwitchTableResult result, TableTransferSummary.Operation operation) {
        result.setTransferSummary(new TableTransferSummary(operation, 0, 0, sourceColumnDescriptions.size(),
                0, 0, recordTransformer != null, false, hasCustomScripts()));
    }

    /**
     * 检查当前配置是否包含自定义前后置脚本。
     */
    private boolean hasCustomScripts() {
        var target = properties.getTarget();
        return StringUtils.hasText(sourceProperties.getBeforeSqlScripts())
                || StringUtils.hasText(sourceProperties.getAfterSqlScripts())
                || StringUtils.hasText(target.getBeforeSqlScripts()) || StringUtils.hasText(
                target.getAfterSqlScripts());
    }

    /**
     * 单表全量同步的"读写流水线"批量写入器
     * <p>
     * 移植自 dbswitch 2.0.2 的读写分离思想（MemChannel 生产者-消费者模型）的单表收敛版：
     * 读线程通过 {@link #submit} 把组装好的批次放入有界队列（容量 2，形成背压与双缓冲），
     * 专属写线程从队列消费并执行 {@link IDatabaseWriter#write}；写失败时记录异常并清空队列，
     * 读线程在下一次 submit/finish 时感知失败并终止。保持单写线程以保证单表内批次的写入顺序。
     * <p>
     * 关闭流水线（sync 模式）时 submit 直接同步写，行为与历史版本一致
     */
    private class PipelineBatchWriter implements AutoCloseable {

        private record PendingBatch(List<Object[]> rows, long bytes) {

        }

        private final PendingBatch poisonPill = new PendingBatch(List.of(), 0);

        private final IDatabaseWriter writer;

        private final List<String> writeFields;

        private final String tableNameMapString;

        private final boolean async;

        private final BlockingQueue<PendingBatch> queue;

        private final AtomicReference<Throwable> writeError = new AtomicReference<>();

        private final AtomicLong acknowledgedRows = new AtomicLong();

        private final BatchCommitSummary.Collector commits = new BatchCommitSummary.Collector();

        private final Future<?> writeFuture;

        private final CountDownLatch writerExited = new CountDownLatch(1);

        private volatile boolean closed;

        /**
         * 初始化 {@code PipelineBatchWriter}。
         */
        PipelineBatchWriter(IDatabaseWriter writer, List<String> writeFields,
                String tableNameMapString, boolean async) {
            this.writer = writer;
            this.writeFields = writeFields;
            this.tableNameMapString = tableNameMapString;
            this.async = async;
            if (async) {
                this.queue = new ArrayBlockingQueue<>(2);
                // FutureTask.done 会在 cancel 时提前执行，不能在 done 里递减。
                // 包裹 run 的 finally 即使任务启动前已取消也只登记一次真实退出。
                FutureTask<Void> task = new FutureTask<>(this::runWriteLoop, null) {
                    /**
                     * 运行异步批次任务，并在退出时更新活跃任务计数。
                     */
                    @Override
                    public void run() {
                        try {
                            super.run();
                        } finally {
                            activeWriters.decrementAndGet();
                            writerExited.countDown();
                            try {
                                publishBatchProgress();
                            } finally {
                                writerExitObserver.run();
                            }
                        }
                    }
                };
                this.writeFuture = task;
                activeWriters.incrementAndGet();
                try {
                    PIPELINE_WRITE_EXECUTOR.execute(task);
                } catch (RuntimeException | Error e) {
                    activeWriters.decrementAndGet();
                    writerExited.countDown();
                    throw e;
                }
            } else {
                this.queue = null;
                this.writeFuture = null;
            }
        }

        /**
         * 提交一个批次：异步模式放入队列（队列满时阻塞形成背压），同步模式直接写入
         */
        void submit(List<Object[]> batch, long batchBytes) {
            throwIfWriteFailed();
            if (!async) {
                long ret;
                try {
                    ret = writeAndAcknowledge(batch);
                } catch (RuntimeException | Error failure) {
                    writeError.compareAndSet(null, failure);
                    throw failure;
                }
                log.info("[FullCoverSync] handle table [{}] data count: {}, the batch bytes size: {}",
                        tableNameMapString, ret, BytesUnitUtils.bytesSizeToHuman(batchBytes));
                return;
            }
            enqueue(new PendingBatch(batch, batchBytes));
        }

        /**
         * 结束提交并等待写线程完成全部批次，写失败时抛出异常
         */
        void finish() {
            throwIfWriteFailed();
            if (!async) {
                return;
            }
            enqueue(poisonPill);
            try {
                while (true) {
                    throwIfWriteFailed();
                    try {
                        writeFuture.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException ignored) {
                        // handler.interrupt() 不一定中断调用线程，定期检查独立取消标记。
                    }
                }
                // Future 发布结果早于包装线程的 finally；正常完成也要等待退出登记。
                while (!writerExited.await(100, TimeUnit.MILLISECONDS)) {
                    throwIfWriteFailed();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("task is interrupted");
            } catch (ExecutionException e) {
                writeError.compareAndSet(null, e.getCause());
            }
            throwIfWriteFailed();
        }

        /**
         * 将当前批次加入待执行队列。
         */
        private void enqueue(PendingBatch batch) {
            throwIfWriteFailed();
            boolean accepted = false;
            batchProgress.reserve(batch.bytes());
            publishBatchProgress();
            try {
                while (!queue.offer(batch, 100, TimeUnit.MILLISECONDS)) {
                    throwIfWriteFailed();
                }
                accepted = true;
                throwIfWriteFailed();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("task is interrupted");
            } finally {
                if (!accepted || (closed || writeError.get() != null) && queue.remove(batch)) {
                    batchProgress.release(batch.bytes());
                }
                publishBatchProgress();
            }
        }

        /**
         * 丢弃尚未提交的待处理批次。
         */
        private void discardPendingBatches() {
            if (queue == null) {
                return;
            }
            PendingBatch pending;
            while ((pending = queue.poll()) != null) {
                batchProgress.release(pending.bytes());
            }
            publishBatchProgress();
        }

        /**
         * 清空队列让阻塞中的读线程尽快恢复并感知失败。
         */
        private void runWriteLoop() {
            while (!closed && !interrupted) {
                PendingBatch pending;
                try {
                    pending = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writeError.compareAndSet(null, new CancellationException("writer is interrupted"));
                    break;
                }
                if (pending == null) {
                    continue;
                }
                batchProgress.release(pending.bytes());
                publishBatchProgress();
                if (pending == poisonPill || closed || interrupted) {
                    break;
                }
                try {
                    long ret = writeAndAcknowledge(pending.rows());
                    log.info("[FullCoverSync] handle table [{}] data count: {}", tableNameMapString, ret);
                } catch (Throwable t) {
                    writeError.compareAndSet(null, t);
                    // 清空队列让阻塞中的读线程尽快恢复并感知失败
                    discardPendingBatches();
                    break;
                }
            }
        }

        /**
         * 写入当前批次并在确认结果后更新提交统计。
         */
        private long writeAndAcknowledge(List<Object[]> batch) {
            int expected = batch.size();
            batchProgress.submitted(expected);
            publishBatchProgress();
            try {
                var result = writer.writeWithReceipt(writeFields, batch);
                if (result == null || result.acknowledgedRows() != expected) {
                    throw new UnconfirmedWriteException();
                }
                long acknowledged = result.acknowledgedRows();
                acknowledgedRows.updateAndGet(previous -> Math.addExact(previous, acknowledged));
                commits.add(acknowledged, result.receipt());
                batchProgress.acknowledged(acknowledged, result.receipt() != null);
                return acknowledged;
            } catch (RuntimeException | Error failure) {
                if (failure instanceof cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException partial) {
                    if (partial.inputRows() != expected) {
                        var invalid = new UnconfirmedWriteException();
                        batchProgress.failed(expected, invalid);
                        throw invalid;
                    }
                    acknowledgedRows.updateAndGet(previous -> Math.addExact(previous, partial.acknowledgedRows()));
                }
                batchProgress.failed(expected, failure);
                throw failure;
            } finally {
                publishBatchProgress();
            }
        }

        /**
         * 检查异步写入失败状态并向当前线程传播异常。
         */
        private void throwIfWriteFailed() {
            if (closed || interrupted || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("task is interrupted");
            }
            Throwable t = writeError.get();
            if (t != null) {
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                throw new RuntimeException("[FullCoverSync] write table [" + tableNameMapString + "] failed", t);
            }
        }

        /**
         * 关闭 {@code MigrationHandler} 当前持有的资源。
         */
        @Override
        public void close() {
            closed = true;
            if (async && writeFuture != null && !writeFuture.isDone()) {
                // 读取阶段异常提前退出时，中断写线程避免其阻塞等待
                writeFuture.cancel(true);
            }
            discardPendingBatches();
        }
    }

    /**
     * 针对时序库目标端（TDengine/InfluxDB3）调整建表字段列表：
     * 确保显式映射的时间戳字段位于第一列；不创建没有来源的时间列。
     *
     * @param targetColumns 目标端字段描述列表
     * @return 调整后的字段列表（非时序库或未配置时间戳字段时原样返回）
     */
    private List<ColumnDescription> applyTimestampColumn(List<ColumnDescription> targetColumns) {
        if (!ProductTypeEnum.TDENGINE.equals(targetProductType)
                && !ProductTypeEnum.INFLUXDB3.equals(targetProductType)) {
            return targetColumns;
        }
        String timestampColumn = getTimestampColumn();
        if (!StringUtils.hasLength(timestampColumn)) {
            return targetColumns;
        }
        int idx = -1;
        for (int i = 0; i < targetColumns.size(); i++) {
            ColumnDescription cd = targetColumns.get(i);
            if (cd != null && StringUtils.hasLength(cd.getFieldName())
                    && cd.getFieldName().equalsIgnoreCase(timestampColumn)) {
                idx = i;
                break;
            }
        }
        if (idx == 0) {
            return targetColumns;
        }
        List<ColumnDescription> result = new ArrayList<>(targetColumns);
        if (idx > 0) {
            ColumnDescription ts = result.remove(idx);
            result.add(0, ts);
            return result;
        }
        throw new IllegalStateException("时序迁移必须显式映射时间字段，禁止创建无来源的时间列");
    }

    /**
     * 从已验证的列映射中获取目标时间戳字段名；未配置或非时序目标时返回 null。
     */
    private String getTimestampColumn() {
        if ((!ProductTypeEnum.TDENGINE.equals(targetProductType)
                && !ProductTypeEnum.INFLUXDB3.equals(targetProductType))
                || !StringUtils.hasLength(this.timestampColumnName)) {
            return null;
        }
        String name = mappedColumns == null ? null : mappedColumns.get(this.timestampColumnName);
        if (!StringUtils.hasLength(name)) {
            throw new IllegalArgumentException("时序时间字段必须属于未被排除的真实来源字段");
        }
        return name;
    }

    /**
     * 匹配实际物理表名；仅由目标驱动确认引号内标识符不区分大小写时折叠大小写。
     */
    private boolean targetTableExists(IMetaDataByDatasourceService metaDataService) {
        List<String> names = metaDataService.queryTableList(targetSchemaName).stream()
                .map(TableDescription::getTableName).filter(java.util.Objects::nonNull).toList();
        if (names.contains(targetTableName)) {
            return true;
        }
        if (names.stream().noneMatch(name -> name.equalsIgnoreCase(targetTableName))) {
            return false;
        }
        // PostgreSQL 和 Oracle 中，带引号的名称可以与仅大小写不同的名称共存。
        // MySQL 和 SQL Server 的标识符规则取决于服务器，而不只是产品枚举。
        try (Connection connection = metaDataService.getDataSource().getConnection()) {
            return !connection.getMetaData().supportsMixedCaseQuotedIdentifiers();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot establish target table identifier case rules");
        }
    }

    /**
     * 创建目标表：优先使用用户配置的自定义 DDL（customDdlMap，移植自 dbswitch 2.0.2），
     * 否则按源端元数据自动生成建表语句（可含索引语句）后执行
     */
    private void executeCreateTable(IMetaDataByDatasourceService targetMetaDataService) {
        executeCreateTableStatements(buildCreateTableStatements(targetMetaDataService));
    }

    /**
     * 拒绝当前实现未支持的 {@code buildCreateTableStatements} 操作。
     */
    private List<String> buildCreateTableStatements(IMetaDataByDatasourceService targetMetaDataService) {
        if (sourceProductType == ProductTypeEnum.POSTGRESQL && targetProductType == ProductTypeEnum.POSTGRESQL
                && !Boolean.TRUE.equals(targetProperties.getOnlyCreate())
                && !Boolean.TRUE.equals(targetProperties.getPreserveIdentityValues())
                && targetColumnDescriptions.stream().anyMatch(column -> column.getMetadataEvidence().identity().state()
                == cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.VALUE)) {
            throw new UnsupportedOperationException(
                    "Identity auto-create with data requires verified sequence-state synchronization; structure-only is supported");
        }
        List<String> sqlCreateTable;
        Map<String, String> customDdlMap = properties.getTarget().getCustomDdlMap();
        String customDdl = customDdlMap == null ? null : customDdlMap.get(targetTableName);
        if (sourceProductType == ProductTypeEnum.SQLSERVER && targetProductType == ProductTypeEnum.SQLSERVER
                && SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
            assertDistinctSqlServerPhysicalTable();
        }
        if (targetProductType == ProductTypeEnum.SQLSERVER && Boolean.TRUE.equals(
                targetProperties.getPreserveIdentityValues())
                && StringUtil.isNotBlank(customDdl)) {
            throw new IllegalArgumentException("Identity replacement requires component-generated structural DDL");
        }
        if (StringUtil.isNotBlank(customDdl)) {
            log.info("Using custom DDL for table {}", tableNameMapString);
            sqlCreateTable = new ArrayList<>();
            sqlCreateTable.add(customDdl.trim());
        } else {
            if (sourceProductType == ProductTypeEnum.SQLITE3 && sourceProductType != targetProductType
                    && SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
                try (Connection connection = sourceDataSource.getConnection()) {
                    var schema = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(
                            connection, sourceProductType, sourceSchemaName, sourceTableName,
                            Math.min(120, runtime.options().queryTimeoutSeconds()));
                    if (schema.additionalObjects()
                            .state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT) {
                        throw new UnsupportedOperationException(
                                "SQLite additional schema objects require an explicit cross-product reconstruction plan");
                    }
                } catch (SQLException unavailable) {
                    throw new IllegalStateException("SQLite cross-product reconstruction evidence is unavailable");
                }
            }
            if (sourceProductType == ProductTypeEnum.SQLSERVER
                    && SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
                // 此方法同时承担 DROP 前的检查和仅结构规划的边界。
                // 仅有列元数据不能证明约束、策略或索引不存在。
                try (Connection connection = sourceDataSource.getConnection()) {
                    var schema = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(
                            connection, sourceProductType, sourceSchemaName, sourceTableName,
                            Math.min(120, runtime.options().queryTimeoutSeconds()));
                    if (schema.additionalObjects()
                            .state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT) {
                        throw new UnsupportedOperationException(
                                "SQL Server additional schema objects require an explicit reconstruction plan");
                    }
                } catch (SQLException unavailable) {
                    throw new IllegalStateException("SQL Server native table reconstruction evidence is unavailable");
                }
            }
            List<ColumnDescription> targetColumns = targetColumnDescriptions.stream()
                    .filter(column -> StringUtils.hasLength(column.getFieldName()))
                    .collect(Collectors.toList());
            targetColumns = applyTimestampColumn(targetColumns);
            if (sequenceBinding != null) {
                targetColumns = sequenceBinding.columnsForCreate(targetColumns);
            }
            if (!properties.getTypeMappingAcknowledgements().isEmpty() && sourceProductType != targetProductType) {
                var mapping = new cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions(
                        runtime.products().typeMappings(),
                        cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement.acceptanceFor(
                                properties.getTypeMappingAcknowledgements(), targetProductType, targetSchemaName,
                                targetTableName));
                sqlCreateTable = targetMetaDataService.getDDLCreateTableSQL(targetProductType, targetColumns,
                        targetPrimaryKeys, targetSchemaName, targetTableName, sourceTableRemarks,
                        properties.getTarget().getCreateTableAutoIncrement(), getTblProperties(), mapping);
            } else {
                sqlCreateTable = sequenceBinding != null && !sequenceBinding.identitySequenceNames().isEmpty()
                        ? cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.getDDLCreateTableSQL(
                        runtime.products().metadata(targetProductType), targetColumns, targetPrimaryKeys,
                        targetSchemaName, targetTableName,
                        sourceTableRemarks, properties.getTarget().getCreateTableAutoIncrement(), getTblProperties(),
                        sequenceBinding.identitySequenceNames())
                        : targetMetaDataService.getDDLCreateTableSQL(
                        targetProductType,
                        targetColumns,
                        targetPrimaryKeys,
                        targetSchemaName,
                        targetTableName,
                        sourceTableRemarks,
                        properties.getTarget().getCreateTableAutoIncrement(),
                        getTblProperties());
            }
            if (Boolean.TRUE.equals(properties.getTarget().getIndexCreate())) {
                sqlCreateTable = new ArrayList<>(sqlCreateTable);
                sqlCreateTable.addAll(sourceIndexStatements(targetColumns));
            }
        }
        if (targetProductType == ProductTypeEnum.POSTGRESQL) {
            var plan = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(targetProductType,
                    String.join(";\n", sqlCreateTable));
            if (plan.steps().stream().anyMatch(
                    step -> step.kind() != cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier.Kind.DDL)) {
                throw new IllegalArgumentException("Table creation plan must contain only DDL");
            }
            if (!Boolean.TRUE.equals(targetProperties.getOnlyCreate())
                    && !Boolean.TRUE.equals(targetProperties.getWriterEngineInsert())) {
                newFullWriter().preflightTargetDefinition(targetSchemaName, targetTableName, sqlCreateTable,
                        targetColumnDescriptions.stream().map(ColumnDescription::getFieldName)
                                .filter(StringUtils::hasLength).toList());
            }
        }
        if ((targetProductType == ProductTypeEnum.DORIS || targetProductType == ProductTypeEnum.STARROCKS)
                && !Boolean.TRUE.equals(targetProperties.getOnlyCreate())) {
            newFullWriter().preflightTargetDefinition(targetSchemaName, targetTableName, sqlCreateTable,
                    targetColumnDescriptions.stream().map(ColumnDescription::getFieldName)
                            .filter(StringUtils::hasLength).toList());
        }
        if ((targetProductType == ProductTypeEnum.UXDB || targetProductType == ProductTypeEnum.GREENPLUM)
                && !Boolean.TRUE.equals(targetProperties.getOnlyCreate())
                && !Boolean.TRUE.equals(targetProperties.getWriterEngineInsert())) {
            newFullWriter().preflightTargetDefinition(targetSchemaName, targetTableName, sqlCreateTable,
                    targetColumnDescriptions.stream().map(ColumnDescription::getFieldName)
                            .filter(StringUtils::hasLength).toList());
        }
        return List.copyOf(sqlCreateTable);
    }

    /**
     * 按源表索引结构生成目标 CREATE INDEX（仅表来源）。无法原样重建的索引整个跳过并记录原因，不删键、不改唯一性；
     * 目标产品在本路径不建二级索引时不读取源索引。
     */
    private List<String> sourceIndexStatements(List<ColumnDescription> createdColumns) {
        if (!SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
            return List.of();
        }
        var target = runtime.products().metadata(targetProductType);
        if (target.requiresDialectConnection()) {
            try (Connection connection = targetDataSource.getConnection()) {
                target.prepareDialect(connection);
            } catch (SQLException unavailable) {
                throw new IllegalStateException("Target dialect could not be verified for index DDL");
            }
        }
        if (createdColumns.stream().noneMatch(column -> target.canCreateIndex(column.getMetaData()))) {
            log.info("Target {} does not rebuild secondary indexes for {}", targetProductType, tableNameMapString);
            return List.of();
        }
        var mapped = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationIndexes.map(
                sourceMetaDataService.queryTableIndexes(sourceSchemaName, sourceTableName), mappedColumns);
        var plan = cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.getCreateIndexSqlList(target,
                sourceProductType, createdColumns, targetPrimaryKeys, targetSchemaName, targetTableName,
                mapped.indexes());
        mapped.skipped().forEach(reason -> log.warn("Index not rebuilt for {}: {}", tableNameMapString, reason));
        for (var skipped : plan.skipped()) {
            if (skipped.redundant()) {
                log.info("Index {} not rebuilt for {}: {}", skipped.indexName(), tableNameMapString, skipped.reason());
            } else {
                log.warn("Index {} not rebuilt for {}: {}", skipped.indexName(), tableNameMapString, skipped.reason());
            }
        }
        return plan.statements();
    }

    /**
     * 依序执行当前目标建表语句。
     */
    private void executeCreateTableStatements(List<String> sqlCreateTable) {
        if (targetProductType == ProductTypeEnum.POSTGRESQL) {
            var plan = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(targetProductType,
                    String.join(";\n", sqlCreateTable));
            var executor = new cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl(
                    targetProductType, runtime.products(), (url, user, password) -> {
                var connection = targetDataSource.getConnection();
                if (!connection.getAutoCommit()) {
                    connection.close();
                    throw new SQLException("DDL requires an owned connection");
                }
                return connection;
            });
            var result = executor.executePlan(null, null, null, plan, List.of(), 1, 1, null,
                    ignored -> assertNotInterrupted());
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                if (result.getTransactionOutcome() != cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult.TransactionOutcome.ROLLED_BACK) {
                    throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
                }
                throw new IllegalStateException("PostgreSQL table creation transaction rolled back");
            }
            return;
        }
        JdbcTemplate targetJdbcTemplate = new JdbcTemplate(targetDataSource);
        for (int index = 0; index < sqlCreateTable.size(); index++) {
            String sql = sqlCreateTable.get(index);
            targetJdbcTemplate.execute(sql);
            log.info("DDL step completed ordinal={} digest={}", index + 1,
                    cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.digest(sql));
        }
    }

    /**
     * 根据当前输入构建并返回 {@code cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions}。
     */
    private cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions identityTransferOptions() {
        boolean preserve = Boolean.TRUE.equals(targetProperties.getPreserveIdentityValues());
        if (!preserve) {
            if (!Boolean.TRUE.equals(targetProperties.getOnlyCreate())
                    && (targetProductType == ProductTypeEnum.POSTGRESQL || targetProductType == ProductTypeEnum.SQLSERVER)
                    && targetColumnDescriptions.stream()
                    .anyMatch(column -> column.getMetadataEvidence().identity().value() != null)) {
                throw new IllegalArgumentException(
                        "Identity data migration requires explicit preservation and exclusive target ownership");
            }
            return cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions.disabled();
        }
        if (targetProductType == ProductTypeEnum.SQLSERVER) {
            if (sourceProductType != ProductTypeEnum.SQLSERVER || !SourceType.TABLE.getCode()
                    .equals(sourceProperties.getSourceType())
                    || !Boolean.TRUE.equals(sourceProperties.getIdentitySourceQuiesced())) {
                throw new IllegalArgumentException(
                        "SQL Server identity preservation requires an explicitly quiesced physical-table source");
            }
            if (hasCustomScripts() || recordTransformer != null) {
                throw new IllegalArgumentException("Identity preservation cannot use scripts or row transformations");
            }
            assertDistinctSqlServerPhysicalTable();
            try (Connection connection = sourceDataSource.getConnection()) {
                var observed = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.readSqlServerIdentityTable(
                        connection, sourceSchemaName, sourceTableName,
                        Math.min(120, runtime.options().queryTimeoutSeconds()));
                Map<String, cn.iocoder.yudao.framework.dbswitch.core.model.SqlServerIdentityMetadata> mapped = new java.util.LinkedHashMap<>();
                for (var entry : observed.columns().entrySet()) {
                    String destination = mappedColumns.get(entry.getKey());
                    if (!StringUtils.hasText(destination)) {
                        throw new IllegalArgumentException("Identity mapping must retain all native identity columns");
                    }
                    mapped.put(destination, entry.getValue());
                }
                return new cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions(true,
                        Boolean.TRUE.equals(targetProperties.getExclusiveTargetOwnership()), Map.of(), mapped);
            } catch (SQLException unavailable) {
                throw new IllegalStateException("Source SQL Server identity observation failed");
            }
        }
        if (sourceProductType != ProductTypeEnum.POSTGRESQL || !SourceType.TABLE.getCode()
                .equals(sourceProperties.getSourceType())) {
            throw new IllegalArgumentException("Identity preservation requires a PostgreSQL physical table source");
        }
        Map<String, cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata> sequences = new java.util.LinkedHashMap<>();
        for (var column : targetColumnDescriptions) {
            if (!StringUtils.hasLength(column.getFieldName())) {
                continue;
            }
            if (sequenceBinding != null && sequenceBinding.managesColumn(column.getFieldName())) {
                continue;
            }
            var facts = column.getMetadataEvidence();
            boolean sequenceColumn = facts.identity().value() != null
                    || facts.defaultExpression().value() != null && facts.defaultExpression().value()
                    .contains("nextval(");
            if (!sequenceColumn) {
                continue;
            }
            if (facts.sequence().value() == null) {
                throw new IllegalArgumentException("Sequence ownership and state are unverified");
            }
            sequences.put(column.getFieldName(), facts.sequence().value());
        }
        return new cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions(true,
                Boolean.TRUE.equals(targetProperties.getExclusiveTargetOwnership()), sequences);
    }

    /**
     * 检查 SQL Server 源表与目标表为不同物理对象。
     */
    private void assertDistinctSqlServerPhysicalTable() {
        if (!targetTableExists(runtime.metadata(targetDataSource, targetProductType))) {
            return;
        }
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            var original = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.readSqlServerIdentityTable(
                    source, sourceSchemaName, sourceTableName, Math.min(120, runtime.options().queryTimeoutSeconds()));
            var destination = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.readSqlServerIdentityTable(
                    target, targetSchemaName, targetTableName, Math.min(120, runtime.options().queryTimeoutSeconds()));
            if (original.samePhysicalTable(destination)) {
                throw new IllegalArgumentException("Source and target must be distinct physical tables");
            }
        } catch (SQLException unavailable) {
            throw new IllegalStateException("SQL Server source/target physical identity unavailable");
        }
    }

    /**
     * 获取变化量同步操作选项（null 时回退为全部执行，保证旧任务 JSON 配置反序列化后的兼容性）
     */
    private SyncOptionEnum resolveSyncOption() {
        SyncOptionEnum option = properties.getTarget().getTargetSyncOption();
        return option == null ? SyncOptionEnum.INSERT_UPDATE_DELETE : option;
    }

    /**
     * 忽略大小写查找字符串在列表中的下标，找不到返回 -1
     */
    private int indexOfIgnoreCase(List<String> values, String target) {
        if (values == null || values.isEmpty() || !StringUtils.hasLength(target)) {
            return -1;
        }
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            if (v != null && v.equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    /** 任何目标写入前，按照物理 JDBC 标识符语义解析保留列。 */
    private List<String> resolveTargetColumns(IMetaDataByDatasourceService metadata,
            List<String> requested, List<String> physical) {
        List<String> resolved = new ArrayList<>(requested.size());
        Boolean caseInsensitive = null;
        for (String name : requested) {
            if (physical.contains(name)) {
                resolved.add(name);
                continue;
            }
            List<String> candidates = physical.stream().filter(name::equalsIgnoreCase).toList();
            if (candidates.size() != 1 || !targetProductType.isUseSql()) {
                throw new IllegalStateException("目标缺少显式映射的物理列，禁止静默丢弃源字段");
            }
            if (caseInsensitive == null) {
                try (var connection = metadata.getDataSource().getConnection()) {
                    caseInsensitive = !connection.getMetaData().supportsMixedCaseQuotedIdentifiers();
                } catch (SQLException failure) {
                    throw new IllegalStateException("无法验证目标字段的标识符规则，拒绝有歧义的映射");
                }
            }
            if (!caseInsensitive) {
                throw new IllegalStateException("引号内区分大小写的目标字段必须使用精确名称映射");
            }
            resolved.add(candidates.getFirst());
        }
        if (new HashSet<>(resolved).size() != resolved.size()) {
            throw new IllegalStateException("多个源字段映射到同一个物理目标列");
        }
        return resolved;
    }

    /**
     * 变化量同步：基于主键比对源端与目标端数据，只同步差异部分（增量同步）
     * <p>
     * 主要流程：
     * 1. 按下标对齐源/目标字段名，并记录“源列名 -> 目标列名”映射；
     * 2. 时序库目标端必须显式映射时间字段，禁止在重放时生成不同的时间键；
     * 3. 组装比对任务参数：目标端视为旧数据（old），源端视为新数据（new）；
     * 4. 创建增量同步器并预生成 INSERT/UPDATE/DELETE 语句模板（prepare）；
     * 5. 交由 ChangeCalculatorService 对两端按主键排序的结果集做归并比对，
     *    回调 handle() 中把记录按 INSERT/UPDATE/DELETE 分类缓存，
     *    任一缓存达到批次上限或字节数超限时按 删除 → 插入 → 更新 的顺序批量执行；
     * 6. 比对结束时（destroy 回调）刷出剩余缓存并输出增删改统计
     *
     * @param writer 目的端的写入器
     */
    private DbSwitchTableResult doIncreaseSynchronize(IDatabaseWriter writer, DbSwitchTableResult dbSwitchTableResult) {
        if (acceptedTypeMappingValues != null && acceptedTypeMappingValues.requiresTargetEvidence()) {
            acceptedTypeMappingValues.requireTargetColumns(runtime.metadata(writer.getDataSource(), targetProductType)
                    .queryTableColumnMetaWithNativeEvidence(targetSchemaName, targetTableName));
        }
        writer.configureBatchLimits(batchLimits);
        this.progressTarget = dbSwitchTableResult;
        this.progressClosed = false;
        final int BATCH_SIZE = fetchSize;

        AtomicLong syncCount = dbSwitchTableResult.getSyncCount();
        AtomicLong syncBytes = dbSwitchTableResult.getSyncBytes();

        List<String> sourceFields = new ArrayList<>();
        List<String> targetFields = new ArrayList<>();
        Map<String, String> columnNameMaps = new HashMap<>();
        for (int i = 0; i < targetColumnDescriptions.size(); ++i) {
            ColumnDescription scd = sourceColumnDescriptions.get(i);
            ColumnDescription tcd = targetColumnDescriptions.get(i);
            if (!StringUtils.isEmpty(tcd.getFieldName())) {
                sourceFields.add(scd.getFieldName());
                targetFields.add(tcd.getFieldName());
                columnNameMaps.put(scd.getFieldName(), tcd.getFieldName());
            }
        }

        // 按系统当前时间生成的键无法在重放时识别同一源端行。
        String timestampColumn = getTimestampColumn();
        if ((ProductTypeEnum.TDENGINE.equals(targetProductType)
                || ProductTypeEnum.INFLUXDB3.equals(targetProductType))
                && StringUtils.hasLength(timestampColumn)
                && indexOfIgnoreCase(targetFields, timestampColumn) < 0) {
            throw new IllegalStateException("时序同步必须显式映射稳定时间键，禁止自动生成当前时间");
        }

        final List<String> finalSyncFields = List.copyOf(targetFields);

        TaskParamEntity.TaskParamEntityBuilder taskBuilder = TaskParamEntity.builder();
        // target相当于老数据，source相当于要同步的新数据
        taskBuilder.oldProductType(targetProductType);
        taskBuilder.oldDataSource(writer.getDataSource());
        taskBuilder.oldSchemaName(targetSchemaName);
        taskBuilder.oldTableName(targetTableName);
        taskBuilder.newProductType(sourceProductType);
        taskBuilder.newDataSource(sourceDataSource);
        taskBuilder.newSchemaName(sourceSchemaName);
        taskBuilder.newTableName(sourceTableName);
        taskBuilder.sourceType(sourceProperties.getSourceType());
        taskBuilder.sourceSql(sourceProperties.getSourceSql());
        taskBuilder.sourcePrimaryKeys(sourcePrimaryKeys);
        taskBuilder.fieldColumns(sourceFields);
        taskBuilder.columnsMap(columnNameMaps);
        taskBuilder.transformer(acceptedTypeMappingValues == null ? recordTransformer
                : acceptedTypeMappingValues.forSourceRows(recordTransformer, columnNameMaps));

        TaskParamEntity param = taskBuilder.build();

        IDatabaseSynchronize synchronizer = runtime.synchronizer(writer.getDataSource(), targetProductType);
        synchronizer.configureBatchLimits(batchLimits);
        var groupRewriteBinding = targetProperties.groupRewriteBinding(targetSchemaName, targetTableName);
        if (groupRewriteBinding != null) {
            groupRewriteBinding.checkOwnership();
            synchronizer.bindGroupRewrite(groupRewriteBinding);
        } else if (targetProperties.getGroupRewriteBindingProvider() != null) {
            synchronizer.bindGroupRewriteProvider(targetProperties.getGroupRewriteBindingProvider());
        }
        if (targetProperties.getTableRewriteBindingProvider() != null) {
            synchronizer.bindTableRewriteProvider(targetProperties.getTableRewriteBindingProvider());
        }
        try {
            synchronizer.prepare(targetSchemaName, targetTableName, finalSyncFields, targetPrimaryKeys);
        } finally {
            dbSwitchTableResult.setNativeRecoveryResources(synchronizer.recoveryResources());
        }
        var wholeDifference = synchronizer.wholeTableDifference();

        IDatabaseChangeCaculator calculator = new ChangeCalculatorService(runtime.products(), batchLimits,
                runtime.options());
        calculator.setFetchSize(fetchSize);
        calculator.setRecordIdentical(false);
        calculator.setCheckJdbcType(false);
        // 循环级中断检查：大段"无变化"记录不经过行处理回调，必须由比对器在每次迭代检查中断
        calculator.setInterruptCheck(this::assertNotInterrupted);

        // 执行实际的变化同步过程
        try {
            calculator.executeCalculate(param, new IDatabaseRowHandler() {

                private long countInsert = 0;

                private long countUpdate = 0;

                private long countDelete = 0;

                private long countTotal = 0;

                private long cacheBytes = 0;

                private long batchStarted = System.nanoTime();

                private final List<Object[]> cacheInsert = new LinkedList<>();

                private final List<Object[]> cacheUpdate = new LinkedList<>();

                private final List<Object[]> cacheDelete = new LinkedList<>();

                /**
                 * 按当前值类型将数据编码到目标输出端。
                 */
                @Override
                public void handle(List<String> fields, Object[] record, RecordChangeTypeEnum flag) {
                    if (interrupted) {
                        throw new RuntimeException("task is interrupted");
                    }
                    record = BatchValueConverter.convert(List.<Object[]>of(record), fields.size(), batchLimits)
                            .getFirst();
                    long bytes = BatchValueConverter.estimatedRowBytes(record);
                    if (bytes > maxCacheBytes) {
                        throw new BatchRejectedException("One change row exceeds the batch byte limit");
                    }
                    if (cacheBytes > 0 && (bytes > maxCacheBytes - cacheBytes
                            || elapsedBatchMillis(batchStarted) >= batchLimits.maxBatchAgeMillis())) {
                        checkFull(fields, true);
                    }
                    // 按 targetSyncOption 过滤操作类别（移植自 dbswitch 2.0.2）
                    SyncOptionEnum syncOption = resolveSyncOption();
                    if (flag == RecordChangeTypeEnum.VALUE_INSERT) {
                        if (!syncOption.callInsert()) {
                            return;
                        }
                        cacheInsert.add(record);
                        countInsert++;
                    } else if (flag == RecordChangeTypeEnum.VALUE_CHANGED) {
                        if (!syncOption.callUpdate()) {
                            return;
                        }
                        cacheUpdate.add(record);
                        countUpdate++;
                    } else {
                        if (!syncOption.callDelete()) {
                            return;
                        }
                        cacheDelete.add(record);
                        countDelete++;
                    }

                    cacheBytes = Math.addExact(cacheBytes, bytes);
                    batchProgress.read(bytes);
                    batchProgress.reserve(bytes);
                    publishBatchProgress();
                    syncBytes.getAndAdd(bytes);
                    countTotal++;
                    syncCount.getAndAdd(1);
                    checkFull(fields);
                }

                /**
                 * 检测缓存是否已满，如果已满执行同步操作
                 *
                 * @param fields 同步的字段列表
                 */
                private void checkFull(List<String> fields) {
                    checkFull(fields, false);
                }

                /**
                 * 检查当前全量迁移配置及目标条件。
                 */
                private void checkFull(List<String> fields, boolean force) {
                    if (force || cacheInsert.size() >= BATCH_SIZE || cacheUpdate.size() >= BATCH_SIZE
                            || cacheDelete.size() >= BATCH_SIZE || cacheBytes >= maxCacheBytes
                            || elapsedBatchMillis(batchStarted) >= batchLimits.maxBatchAgeMillis()) {
                        if (cacheDelete.size() > 0) {
                            doDelete(fields);
                        }

                        if (cacheInsert.size() > 0) {
                            doInsert(fields);
                        }

                        if (cacheUpdate.size() > 0) {
                            doUpdate(fields);
                        }

                        log.info("[IncreaseSync] Handle table [{}] data one batch size: {}",
                                tableNameMapString, BytesUnitUtils.bytesSizeToHuman(cacheBytes));
                        cacheBytes = 0;
                        batchStarted = System.nanoTime();
                    }
                }

                /**
                 * 关闭当前组件并销毁其持有的运行资源。
                 */
                @Override
                public void destroy(List<String> fields) {
                    if (cacheDelete.size() > 0) {
                        doDelete(fields);
                    }

                    if (cacheInsert.size() > 0) {
                        doInsert(fields);
                    }

                    if (cacheUpdate.size() > 0) {
                        doUpdate(fields);
                    }

                    log.info("[IncreaseSync] Handle table [{}] total count: {}, Insert:{},Update:{},Delete:{} ",
                            tableNameMapString, countTotal, countInsert, countUpdate, countDelete);
                }

                /**
                 * 通过当前产品实现应用数据插入操作。
                 */
                private void doInsert(List<String> fields) {
                    long ret = wholeDifference == null ? executeDifferenceBatch(cacheInsert,
                            synchronizer::executeInsert)
                            : stageWholeDifference(wholeDifference,
                            cn.iocoder.yudao.framework.dbswitch.dbsynch.WholeTableDifference.Change.INSERT,
                            cacheInsert);
                    log.info("[IncreaseSync] Handle table [{}] data Insert count: {}", tableNameMapString, ret);
                    cacheInsert.clear();
                }

                /**
                 * 通过当前产品实现应用数据更新操作。
                 */
                private void doUpdate(List<String> fields) {
                    long ret = wholeDifference == null ? executeDifferenceBatch(cacheUpdate,
                            synchronizer::executeUpdate)
                            : stageWholeDifference(wholeDifference,
                            cn.iocoder.yudao.framework.dbswitch.dbsynch.WholeTableDifference.Change.UPDATE,
                            cacheUpdate);
                    log.info("[IncreaseSync] Handle table [{}] data Update count: {}", tableNameMapString, ret);
                    cacheUpdate.clear();
                }

                /**
                 * 通过当前产品实现应用数据删除操作。
                 */
                private void doDelete(List<String> fields) {
                    long ret = wholeDifference == null ? executeDifferenceBatch(cacheDelete,
                            synchronizer::executeDelete)
                            : stageWholeDifference(wholeDifference,
                            cn.iocoder.yudao.framework.dbswitch.dbsynch.WholeTableDifference.Change.DELETE,
                            cacheDelete);
                    log.info("[IncreaseSync] Handle table [{}] data Delete count: {}", tableNameMapString, ret);
                    cacheDelete.clear();
                }

            });
            if (wholeDifference != null) {
                try {
                    dbSwitchTableResult.setNativeRecoveryResources(wholeDifference.recoveryResources());
                    var complete = wholeDifference.seal(); // 首次修改目标数据或 DDL 前，持久保存完整镜像
                    dbSwitchTableResult.setNativePlanDigest(complete.digest());
                    long expected = complete.changeCount();
                    if (expected > 0) {
                        assertNotInterrupted();
                        batchProgress.submitted(expected);
                        publishBatchProgress();
                        activeWriters.incrementAndGet();
                        try {
                            long acknowledged = complete.execute();
                            if (acknowledged != expected) {
                                throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
                            }
                            batchProgress.acknowledged(acknowledged, false);
                        } catch (RuntimeException failure) {
                            batchProgress.failed(expected, failure);
                            throw failure;
                        } finally {
                            activeWriters.decrementAndGet();
                            publishBatchProgress();
                        }
                    }
                } finally {
                    dbSwitchTableResult.setNativeRecoveryResources(wholeDifference.recoveryResources());
                }
            }
        } finally {
            // 同步变更批次的生命周期不能超出此方法；未提交的记录会释放，
            // 已提交但结果未确认的记录仍在各自的进度分区中可见。
            batchProgress.release(batchProgress.snapshot(false).bufferedBytes());
            progressClosed = true;
            publishBatchProgress();
        }

        return dbSwitchTableResult;
    }

    /** 暂存阶段释放普通批次缓冲，但绝不报告已提交或已确认的目标行。 */
    private long stageWholeDifference(cn.iocoder.yudao.framework.dbswitch.dbsynch.WholeTableDifference plan,
            cn.iocoder.yudao.framework.dbswitch.dbsynch.WholeTableDifference.Change change, List<Object[]> rows) {
        assertNotInterrupted();
        long bytes = rows.stream().mapToLong(BatchValueConverter::estimatedRowBytes).reduce(0, Math::addExact);
        try {
            plan.stage(change, rows);
            return 0;
        } finally {
            batchProgress.release(bytes);
            publishBatchProgress();
        }
    }

    /**
     * 此 SPI 不提供事务提交凭据。
     */
    private long executeDifferenceBatch(List<Object[]> records,
            java.util.function.ToLongFunction<List<Object[]>> execution) {
        assertNotInterrupted();
        long expected = records.size();
        long bytes = records.stream().mapToLong(BatchValueConverter::estimatedRowBytes).reduce(0, Math::addExact);
        batchProgress.submitted(expected);
        publishBatchProgress();
        activeWriters.incrementAndGet();
        try {
            long acknowledged = execution.applyAsLong(records);
            if (acknowledged != expected) {
                throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
            }
            batchProgress.acknowledged(acknowledged, false); // 此 SPI 不提供事务提交凭据。
            return acknowledged;
        } catch (RuntimeException failure) {
            if (failure instanceof cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException partial
                    && partial.inputRows() != expected) {
                var invalid = new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
                batchProgress.failed(expected, invalid);
                throw invalid;
            }
            batchProgress.failed(expected, failure);
            throw failure;
        } finally {
            batchProgress.release(bytes);
            activeWriters.decrementAndGet();
            publishBatchProgress();
        }
    }

    /**
     * PostgreSQL 类型化增量：同一只读快照固定上界，按水位与完整主键原生排序读取。
     * 目标经唯一键验证后执行 UPSERT；只有收到提交凭据才 CAS 推进已应用元组。
     * 跨快照恢复保守重读最后水位组，拒绝旧字符串 MAX、结构漂移及不匹配的键比较语义。
     */
    private DbSwitchTableResult doTypedCursorSynchronize(DbSwitchTableResult result) {
        if (!SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())
                || !"MONOTONIC_WATERMARK_V1".equals(sourceProperties.getCursorWatermarkContract())
                || recordTransformer != null || hasCustomScripts() || Boolean.TRUE.equals(
                targetProperties.getOnlyCreate())
                || !Boolean.TRUE.equals(targetProperties.getSyncExist())
                || !resolveSyncOption().callInsert() || !resolveSyncOption().callUpdate()) {
            throw new UnsupportedOperationException(
                    "Typed cursor requires a verified table, monotonic watermark contract and native UPSERT");
        }
        var bindings = targetProperties.getCursorRunBindings();
        var binding = bindings == null ? null : bindings.get(sourceTableName);
        if (binding == null) {
            throw new UnsupportedOperationException(
                    "Typed cursor requires trusted versioned checkpoint ownership; legacy string watermarks cannot resume");
        }
        List<String> fields = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < targetColumnDescriptions.size(); i++) {
            String target = targetColumnDescriptions.get(i).getFieldName();
            if (StringUtils.hasLength(target)) {
                fields.add(sourceColumnDescriptions.get(i).getFieldName());
                targets.add(target);
            }
        }
        try {
            cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorTransfer.execute(runtime, sourceDataSource,
                    targetDataSource,
                    sourceProductType, targetProductType,
                    sourceSchemaName, sourceTableName, targetSchemaName, targetTableName, fields, targets,
                    sourcePrimaryKeys, targetPrimaryKeys, increaseColumnName, batchLimits, fetchSize, binding,
                    result, this::assertNotInterrupted);
            return result;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Typed cursor transfer failed");
        }
    }

    /**
     * 返回当前表的产品专属属性集合。
     */
    public Map<String, String> getTblProperties() {
        /*
         * Map<String, String> ret = new HashMap<>();
         * if (ProductTypeEnum.HIVE.equals(targetProductType)) {
         * // hive.sql.database.type: MYSQL, POSTGRES, ORACLE, DERBY, DB2
         * final List<ProductTypeEnum> supportedProductTypes =
         * Arrays.asList(ProductTypeEnum.MYSQL, ProductTypeEnum.ORACLE,
         * ProductTypeEnum.DB2, ProductTypeEnum.POSTGRESQL);
         * ExamineUtils.check(supportedProductTypes.contains(sourceProductType),
         * "Unsupported data from %s to Hive", sourceProductType.name());
         *
         * String querySql;
         * if (SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
         * String fullTableName =
         * sourceProductType.quoteSchemaTableName(sourceSchemaName, sourceTableName);
         * List<String> columnNames =
         * sourceColumnDescriptions.stream().map(ColumnDescription::getFieldName)
         * .collect(Collectors.toList());
         * querySql = String.format("SELECT %s FROM %s",
         * columnNames.stream()
         * .map(s -> sourceProductType.quoteName(s))
         * .collect(Collectors.joining(",")),
         * fullTableName);
         * } else {
         * querySql = sourceProperties.getSourceSql();
         * }
         * String databaseType = sourceProductType.name().toUpperCase();
         * if (ProductTypeEnum.POSTGRESQL == sourceProductType) {
         * databaseType = "POSTGRES";
         * } else if (ProductTypeEnum.SQLSERVER == sourceProductType) {
         * databaseType = "MSSQL";
         * }
         * ret.put("hive.sql.database.type", databaseType);
         * ret.put("hive.sql.jdbc.driver", sourceProductType.getDriveClassName());
         * ret.put("hive.sql.jdbc.url", sourceDataSource.getJdbcUrl());
         * ret.put("hive.sql.dbcp.username", sourceDataSource.getUsername());
         * ret.put("hive.sql.dbcp.password", sourceDataSource.getPassword());
         * ret.put("hive.sql.query", querySql);
         * ret.put("hive.sql.jdbc.read-write", "read");
         * ret.put("hive.sql.jdbc.fetch.size", "2000");
         * ret.put("hive.sql.dbcp.maxActive", "1");
         * }
         * return ret;
         */
        Map<String, String> ret = new HashMap<>();
        if (dorisOptions != null) {
            ret.put(cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY,
                    dorisOptions.toJson());
        }
        if (!targetProperties.getDistributedKeys().isEmpty()) {
            ret.put("greenplum.distributedKeys",
                    cn.hutool.json.JSONUtil.toJsonStr(targetProperties.getDistributedKeys()));
        }
        if (ProductTypeEnum.MONGODB.equals(sourceProductType) && ProductTypeEnum.DORIS.equals(targetProductType)) {
            ret.put("doris.preferUniqueKey", "true");
        }
        return ret;
    }

    /**
     * 校验已有 Doris 表属性与本次写入配置一致。
     */
    private void validateExistingDorisOptions() {
        if (dorisOptions == null) {
            return;
        }
        boolean replacing = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.allowDrop(
                Boolean.TRUE.equals(targetProperties.getTargetDrop()), targetProperties.getWriteMode());
        var metadata = runtime.metadata(targetDataSource, targetProductType);
        if (!replacing && targetTableExists(metadata)) {
            runtime.reader(targetDataSource, targetProductType)
                    .validateExistingTableOptions(targetSchemaName, targetTableName, targetTableComment, dorisOptions);
        }
    }

    /** 目标 DDL 或截断前解析显式选项，绝不能忽略请求的分布策略。 */
    private void validateDistributedKeys() {
        var keys = targetProperties.getDistributedKeys();
        if (keys.isEmpty()) {
            return;
        }
        if (targetProductType != ProductTypeEnum.GREENPLUM || sourceProductType == targetProductType
                || Boolean.TRUE.equals(targetProperties.getChangeDataSync())
                || !runtime.products().typeMappings().supports(sourceProductType, targetProductType)
                || !SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
            throw new UnsupportedOperationException(
                    "Custom distribution requires generated cross-product Greenplum CREATE; native clones retain their native policy");
        }
        var custom = targetProperties.getCustomDdlMap();
        if (custom != null && StringUtil.isNotBlank(custom.get(targetTableName))) {
            throw new IllegalArgumentException("Custom DDL must carry its own distribution policy");
        }
        var names = targetColumnDescriptions.stream().map(ColumnDescription::getFieldName).collect(Collectors.toSet());
        if (!names.containsAll(keys) || (!targetPrimaryKeys.isEmpty() && !new HashSet<>(targetPrimaryKeys).containsAll(
                keys))) {
            throw new IllegalArgumentException(
                    "Distribution columns must exist and be a subset of the primary key when one is present");
        }
        boolean dropping = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.allowDrop(
                Boolean.TRUE.equals(targetProperties.getTargetDrop()), targetProperties.getWriteMode());
        if (!dropping && targetTableExists(runtime.metadata(targetDataSource, targetProductType))) {
            throw new IllegalArgumentException(
                    "Custom distribution cannot alter an existing target without explicit replacement");
        }
    }
}
