package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.util.DorisDdlBuilder;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.core.schema.MetadataReadOptions;
import cn.iocoder.yudao.framework.dbswitch.core.schema.PostgresSchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaPrecondition;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronization;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.physical.DorisExtVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

/**
 * 物理模型 DDL 执行器
 *
 * 把「读实库结构」「生成 DDL」「执行 DDL」「删表」这几件与外部数据库打交道的事集中在一处，
 * 由部署服务编排，模型服务本身不再直接碰实库。
 *
 * DDL 由 dbswitch 的 {@link GenerateSqlUtils} 按目标库方言生成，
 * Doris 走 {@link DorisDdlBuilder}（表模型/Key/分区/分桶）。
 *
 * @author Li
 */
@Slf4j
@Component
public class ModelDdlExecutor {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private ModelingDatabaseResolver databaseResolver;

    /**
     * 解析连接。
     *
     * @param databaseId 数据源编号
     * @return 连接信息
     */
    public ModelingDatabaseResolver.ConnectionInfo resolveConnection(Long databaseId) {
        return databaseResolver.resolve(databaseId);
    }

    /**
     * 目标表是否存在
     */
    public boolean tableExist(ModelingDatabaseResolver.ConnectionInfo conn, String tableName) {
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        return metaDataService.tableExist(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                conn.getSchema(), tableName);
    }

    /**
     * 读取目标表实际字段结构
     */
    public List<ModelFieldItem> importFields(Long databaseId, String tableName) {
        return databaseResolver.importFields(databaseId, tableName);
    }

    /**
     * 使用服务端解析出的连接做一次受控实库观察。标识符按目标库方言引用，数据探测最多读取一行。
     */
    public TableObservation observeTable(ModelingDatabaseResolver.ConnectionInfo conn,
            Long datasourceId, String tableName) {
        try (Connection connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            ModelJdbcTableObserver.Observation observed =
                    ModelJdbcTableObserver.observe(connection, conn.getSchema(), tableName);
            SchemaPrecondition nativePrecondition = null;
            if (conn.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL) {
                var nativeObservation = PostgresSchemaChanges.observe(connection,
                        dbSwitchRuntime.products().metadata(conn.getProductType()), conn.getSchema(), tableName,
                        MetadataReadOptions.fresh());
                if (observed.tableExists() != Boolean.TRUE.equals(nativeObservation.metadata().existence().value())
                        || observed.hasData() != nativeObservation.hasData()) {
                    throw new IllegalStateException("Target changed during schema observation");
                }
                var confirmed = ModelJdbcTableObserver.observe(connection, conn.getSchema(), tableName);
                if (observed.tableExists() != confirmed.tableExists() || observed.hasData() != confirmed.hasData()
                        || !java.util.Objects.equals(observed.schemaHash(), confirmed.schemaHash())) {
                    throw new IllegalStateException("Column evidence changed during native schema observation");
                }
                nativePrecondition = nativeObservation.precondition();
            }
            if (!observed.tableExists()) {
                return new TableObservation(false, false, null, observed.observedAt(), List.of(), nativePrecondition);
            }
            return new TableObservation(true, observed.hasData(), observed.schemaHash(), observed.observedAt(),
                    toObservedFields(observed.columns()), nativePrecondition);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to observe target table on the resolved connection", ex);
        }
    }

    /**
     * 转换为观测字段。
     *
     * @param columns 列集合
     * @return 转换为观测字段结果集合
     */
    private List<ModelFieldItem> toObservedFields(
            List<ModelJdbcSchemaCanonical.ColumnSnapshot> columns) {
        List<ModelFieldItem> fields = new ArrayList<>(columns.size());
        int sort = 1;
        for (ModelJdbcSchemaCanonical.ColumnSnapshot column : columns) {
            ModelFieldItem field = new ModelFieldItem();
            field.setFieldName(column.columnName());
            field.setCnName(column.columnName());
            field.setDataType(column.canonicalTypeName());
            if ("decimal".equals(SqlTypeSemantics.canonicalName(column.canonicalTypeName()))) {
                field.setDataPrecision(column.columnSize());
                field.setDataScale(column.decimalDigits());
            } else if (Set.of("varchar", "char", "nvarchar", "nchar")
                    .contains(SqlTypeSemantics.canonicalName(column.canonicalTypeName()))) {
                field.setDataLength(column.columnSize());
            }
            field.setNullableFlag(column.nullable());
            field.setPkFlag(column.primaryKey());
            field.setSort(sort++);
            fields.add(field);
        }
        return List.copyOf(fields);
    }

    /** 单次受控探测的值对象；比较时刻意不比较 observedAt。 */
    public record TableObservation(boolean tableExists, boolean hasData, String observedSchemaHash,
                                   Instant observedAt, List<ModelFieldItem> fields,
                                   SchemaPrecondition nativePrecondition) {

        /**
         * 创建表观测实例。
         *
         * @param tableExists 表存在性
         * @param hasData 包含数据
         * @param observedSchemaHash 观测结构摘要
         * @param observedAt 观测时间
         * @param fields 字段定义
         */
        public TableObservation(boolean tableExists, boolean hasData, String observedSchemaHash,
                Instant observedAt, List<ModelFieldItem> fields) {
            this(tableExists, hasData, observedSchemaHash, observedAt, fields, null);
        }

        /**
         * 判断是否一致数据源状态条件。
         *
         * @param other 待比较对象
         * @return 判断结果
         */
        public boolean sameDatabaseState(TableObservation other) {
            return other != null && tableExists == other.tableExists && hasData == other.hasData
                    && java.util.Objects.equals(observedSchemaHash, other.observedSchemaHash)
                    && java.util.Objects.equals(nativePrecondition, other.nativePrecondition);
        }
    }

    /**
     * 生成建表 DDL（可能多条：建表 + 注释）
     */
    /**
     * 生成建表 DDL。数据源上下文取 ConnectionInfo（含 productType/schema）。
     * dorisExt 可选；仅 Doris 且非空时走 Doris 专用建表。
     */
    public List<String> buildCreateTableDdl(String tableName, ModelingDatabaseResolver.ConnectionInfo conn,
            List<ModelFieldItem> fields) {
        return buildCreateTableDdl(tableName, tableName, fields, conn, null);
    }

    /**
     * 生成建表 DDL。数据源上下文取 ConnectionInfo（含 productType/schema）。
     *  dorisExt 可选；仅 Doris 且非空时走 Doris 专用建表。
     *
     * @param tableName 表名
     * @param tableComment 表注释
     * @param fields 字段定义
     * @param conn 已解析的数据源连接信息
     * @param dorisExt Doris扩展
     * @return 生成建表 DDL。数据源上下文取 ConnectionInfo（含 productType/schema）结果集合
     */
    public List<String> buildCreateTableDdl(String tableName, String tableComment,
            List<ModelFieldItem> fields,
            ModelingDatabaseResolver.ConnectionInfo conn,
            DorisExtVO dorisExt) {
        String name = StrUtil.blankToDefault(tableName, "");
        String comment = StrUtil.blankToDefault(tableComment, name);
        if ("DORIS".equals(conn.getProductType().name()) && dorisExt != null) {
            return List.of(DorisDdlBuilder.buildCreateTable(toDorisDefinition(name, comment, fields, dorisExt)));
        }
        if (conn.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL
                || conn.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL) {
            var plan = SchemaSynchronization.createPlan(dbSwitchRuntime.products().metadata(conn.getProductType()),
                    conn.getSchema(), name, comment, convertList(fields, this::toPublishColumnDescription));
            if (!plan.ready()) {
                throw new IllegalArgumentException("建表结构未获数据库组件支持："
                        + plan.diagnostics().stream().map(SchemaSynchronization.Diagnostic::code).toList());
            }
            return plan.statements();
        }
        List<ColumnDescription> columnDescriptions = convertList(fields, this::toColumnDescription);
        List<String> pkNames = fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.getPkFlag()))
                .map(ModelFieldItem::getFieldName)
                .toList();
        return GenerateSqlUtils.getDDLCreateTableSQL(
                dbSwitchRuntime.products().metadata(conn.getProductType()), columnDescriptions, pkNames,
                conn.getSchema(), name, comment, false, null);
    }

    /** 模型增量发布：完整结构同步由 DB Switch 规划，业务侧只映射字段。 */
    public List<String> buildSynchronizeTableDdl(String tableName, String tableComment,
            ModelingDatabaseResolver.ConnectionInfo conn,
            List<ModelFieldItem> fields) {
        var database = dbSwitchRuntime.products().metadata(conn.getProductType());
        try (var connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            var plan = SchemaSynchronization.plan(connection, database, conn.getSchema(), tableName,
                    tableComment, convertList(fields, this::toPublishColumnDescription));
            if (!plan.ready()) {
                throw new IllegalArgumentException("增量发布结构变更未获数据库组件支持："
                        + plan.diagnostics().stream().map(SchemaSynchronization.Diagnostic::code).toList());
            }
            return plan.statements();
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException("读取增量发布目标结构失败", failed);
        }
    }

    /**
     * 校验已核验结构执行。
     *
     * @param conn 已解析的数据源连接信息
     */
    public void requireVerifiedSchemaExecution(ModelingDatabaseResolver.ConnectionInfo conn) {
        if (conn.getProductType() != cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL) {
            throw new UnsupportedOperationException("Locked model schema changes are verified only for PostgreSQL");
        }
    }

    /** 冻结证据、整批计划、锁内复核和执行后观察共用同一外部事务。 */
    public void executeDdl(ModelingDatabaseResolver.ConnectionInfo conn, List<String> ddlList,
            TableObservation observation, java.util.function.IntConsumer beforeStep) {
        requireVerifiedSchemaExecution(conn);
        if (observation == null || observation.nativePrecondition() == null) {
            throw new IllegalArgumentException("Model DDL requires a frozen native schema precondition");
        }
        var plan = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(conn.getProductType(),
                String.join(";\n", List.copyOf(ddlList)));
        boolean commitSent = false;
        boolean committed = false;
        try (var connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                PostgresSchemaChanges.execute(connection, dbSwitchRuntime.products().metadata(conn.getProductType()),
                        plan, observation.nativePrecondition(), MetadataReadOptions.fresh(), beforeStep);
                commitSent = true;
                connection.commit();
                committed = true;
            } catch (Exception failed) {
                if (!commitSent) {
                    try {
                        connection.rollback();
                    } catch (java.sql.SQLException uncertain) {
                        throw new DdlExecutionException(DdlOutcome.UNKNOWN);
                    }
                    throw new DdlExecutionException(DdlOutcome.ROLLED_BACK);
                }
                throw new DdlExecutionException(DdlOutcome.UNKNOWN);
            }
        } catch (java.sql.SQLException failed) {
            throw new DdlExecutionException(
                    committed ? DdlOutcome.COMMITTED : commitSent ? DdlOutcome.UNKNOWN : DdlOutcome.NOT_STARTED);
        }
    }

    /** 保留组件证明的外部事务结果，业务恢复不得从供应商错误文本推断。 */
    public enum DdlOutcome {COMMITTED, ROLLED_BACK, NOT_STARTED, UNKNOWN}

    public static final class DdlExecutionException extends IllegalStateException {

        private final DdlOutcome outcome;

        /**
         * 创建DDL执行实例。
         *
         * @param outcome 结果
         */
        public DdlExecutionException(DdlOutcome outcome) {
            super("Model DDL transaction outcome=" + outcome);
            this.outcome = outcome;
        }

        /**
         * 返回DDL执行的结果。
         *
         * @return DDL结果
         */
        public DdlOutcome outcome() {
            return outcome;
        }
    }

    /**
     * 获取模型DDL的物理目标键。
     *
     * @param conn 已解析的数据源连接信息
     * @param tableName 表名
     * @return 模型DDL的物理目标键文本
     */
    public String physicalTargetKey(ModelingDatabaseResolver.ConnectionInfo conn, String tableName) {
        requireVerifiedSchemaExecution(conn);
        try (var connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            return PostgresSchemaChanges.physicalTargetFingerprint(connection,
                    dbSwitchRuntime.products().metadata(conn.getProductType()), conn.getSchema(), tableName,
                    MetadataReadOptions.fresh());
        } catch (java.sql.SQLException failed) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.PHYSICAL_MODEL_TABLE_META_FAILED,
                    "物理目标控制身份或执行权限不可核验");
        }
    }

    /** 原生完整结构匹配也在持锁连接中委托组件，不打开第二个连接等待自己持有的关系锁。 */
    public void withLockedExpectedObservation(ModelingDatabaseResolver.ConnectionInfo conn, String tableName,
            String tableComment, List<ModelFieldItem> fields,
            java.util.function.BiConsumer<TableObservation, Boolean> callback) {
        requireVerifiedSchemaExecution(conn);
        try (var connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                var nativeObservation = PostgresSchemaChanges.lockAndObserve(connection,
                        dbSwitchRuntime.products().metadata(conn.getProductType()), conn.getSchema(), tableName,
                        MetadataReadOptions.fresh());
                var observed = ModelJdbcTableObserver.observe(connection, conn.getSchema(), tableName);
                boolean matches = false;
                if (fields != null && observed.tableExists()) {
                    var expected = SchemaSynchronization.plan(connection,
                            dbSwitchRuntime.products().metadata(conn.getProductType()),
                            conn.getSchema(), tableName, tableComment,
                            convertList(fields, this::toPublishColumnDescription));
                    matches = expected.ready() && expected.statements().isEmpty();
                }
                callback.accept(new TableObservation(observed.tableExists(), observed.hasData(), observed.schemaHash(),
                                observed.observedAt(), toObservedFields(observed.columns()), nativeObservation.precondition()),
                        matches);
            } finally {
                connection.rollback();
            }
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException("目标结构对账未完成，继续保留未知占用");
        }
    }

    /**
     * 按序执行 DDL
     *
     * 多条 DDL 可能部分成功（例如建表成功、注释失败），异常由调用方按部署语义处置：
     * 不在这里补偿删表，因为提交成功但响应超时的情况下表可能已经建好，盲目删表会丢数据。
     */
    public void executeDdl(ModelingDatabaseResolver.ConnectionInfo conn, List<String> ddlList) {
        if (ddlList.isEmpty()) {
            return;
        }
        var plan = parseDdl(conn, ddlList);
        var metadata = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        var result = metadata.executePlan(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                plan, List.of(),
                conn.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL ? 1 : 0,
                1, null, ignored -> {
                });
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException("Model DDL execution did not finish successfully; transaction outcome="
                    + result.getTransactionOutcome());
        }
    }

    /** 在重建删表前证明完整建表脚本属于执行器当前支持的 DDL。 */
    public void validateDdl(ModelingDatabaseResolver.ConnectionInfo conn, List<String> ddlList) {
        parseDdl(conn, ddlList);
    }

    /**
     * 解析DDL。
     *
     * @param conn 已解析的数据源连接信息
     * @param ddlList DDL列表
     * @return SQL脚本计划
     */
    private cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan parseDdl(
            ModelingDatabaseResolver.ConnectionInfo conn, List<String> ddlList) {
        var plan = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(
                conn.getProductType(), String.join(";\n", List.copyOf(ddlList)));
        if (plan.steps().stream().anyMatch(step -> step.kind()
                != cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier.Kind.DDL)) {
            throw new IllegalArgumentException("Model deployment only accepts a complete DDL plan");
        }
        return plan;
    }

    /**
     * 删除物理表，失败只记日志
     */
    public void dropTableQuietly(ModelingDatabaseResolver.ConnectionInfo conn, String tableName) {
        try {
            dropTable(conn, tableName);
        } catch (Exception ex) {
            log.warn("[dropTableQuietly][删除物理表({}) 失败：{}]", tableName, ex.getMessage());
        }
    }

    /** 重建发布必须感知删除失败，避免继续执行后续建表。 */
    public void dropTable(ModelingDatabaseResolver.ConnectionInfo conn, String tableName) {
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        metaDataService.dropTable(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                conn.getSchema(), tableName);
    }

    /**
     * 校验 Doris 建模配置，返回错误列表
     */
    public List<String> validateDorisExt(String tableName, String tableComment,
            List<ModelFieldItem> fields, DorisExtVO dorisExt) {
        if (dorisExt == null) {
            return List.of();
        }
        return DorisDdlBuilder.validate(toDorisDefinition(
                StrUtil.blankToDefault(tableName, ""),
                StrUtil.blankToDefault(tableComment, tableName),
                fields, dorisExt));
    }

    /**
     * 解析Doris扩展。
     *
     * @param dorisExtJson Doris扩展JSON
     * @return Doris扩展
     */
    public DorisExtVO parseDorisExt(String dorisExtJson) {
        if (StrUtil.isBlank(dorisExtJson)) {
            return null;
        }
        return JsonUtils.parseObject(dorisExtJson, DorisExtVO.class);
    }

    /**
     * 模型字段转 dbswitch 列描述（valType 为 dbswitch 归一化通用类型）
     */
    private ColumnDescription toColumnDescription(ModelFieldItem field) {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(field.getFieldName());
        description.setLabelName(field.getFieldName());
        SqlTypeSemantics.applyDeclaredType(description, field.getDataType(), field.getDataLength(),
                field.getDataPrecision(), field.getDataScale());
        description.setNullable(!Boolean.FALSE.equals(field.getNullableFlag())
                && !Boolean.TRUE.equals(field.getPkFlag()));
        description.setRemarks(commentOf(field));
        description.setPk(Boolean.TRUE.equals(field.getPkFlag()));
        description.setAutoIncrement(false);
        return description;
    }

    /**
     * 转换为发布列描述。
     *
     * @param field 字段
     * @return 列描述
     */
    private ColumnDescription toPublishColumnDescription(ModelFieldItem field) {
        ColumnDescription description = toColumnDescription(field);
        if (field.getDataLength() == null
                && Set.of("date", "time", "timestamp").contains(SqlTypeSemantics.canonicalName(field.getDataType()))) {
            description.setLength(0);
        }
        description.setDefaultValue(StrUtil.emptyToNull(field.getDefaultValue()));
        return description;
    }


    /**
     * 获取模型DDL的注释对应。
     *
     * @param field 字段
     * @return 模型DDL的注释对应文本
     */
    private String commentOf(ModelFieldItem field) {
        return StrUtil.blankToDefault(field.getComment(), StrUtil.nullToEmpty(field.getCnName()));
    }

    /**
     * 建模配置（DorisExtVO + 模型字段）转 dbswitch 通用建表定义
     */
    private DorisTableDefinition toDorisDefinition(String tableName, String tableComment,
            List<ModelFieldItem> fields,
            DorisExtVO ext) {
        for (ModelFieldItem field : fields) {
            if ("decimal".equals(SqlTypeSemantics.canonicalName(field.getDataType()))
                    && (field.getDataPrecision() == null || field.getDataPrecision() <= 0
                    || field.getDataScale() == null || field.getDataScale() < 0
                    || field.getDataScale() > field.getDataPrecision())) {
                throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_FIELD_DECIMAL_UNCONFIRMED,
                        field.getFieldName());
            }
        }
        List<DorisTableDefinition.Column> columns = convertList(fields,
                field -> DorisTableDefinition.Column.builder()
                        .name(field.getFieldName())
                        .typeName(field.getDataType())
                        .length("decimal".equals(SqlTypeSemantics.canonicalName(field.getDataType()))
                                ? field.getDataPrecision() : field.getDataLength())
                        .precision(field.getDataScale())
                        .nullable(!Boolean.FALSE.equals(field.getNullableFlag())
                                && !Boolean.TRUE.equals(field.getPkFlag()))
                        .comment(commentOf(field))
                        .aggType(field.getAggType())
                        .build());
        return DorisTableDefinition.builder()
                .tableName(tableName)
                .tableComment(StrUtil.blankToDefault(tableComment, tableName))
                .columns(columns)
                .tableModel(ext.getTableModel())
                .keyColumns(ext.getKeyColumns())
                .partitionMode(ext.getPartitionMode())
                .partitionColumns(ext.getPartitionColumns())
                .partitionExpr(ext.getPartitionExpr())
                .distributionType(ext.getDistributionType())
                .distributionColumns(ext.getDistributionColumns())
                .bucketMode(ext.getBucketMode())
                .bucketCount(ext.getBucketCount())
                .replicationNum(ext.getReplicationNum())
                .enableMow(ext.getEnableMow())
                .sequenceColumn(ext.getSequenceColumn())
                .build();
    }

}
