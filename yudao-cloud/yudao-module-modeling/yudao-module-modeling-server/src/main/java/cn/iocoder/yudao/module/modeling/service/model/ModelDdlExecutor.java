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
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaObservation;
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
        requireVerifiedSchemaExecution(conn);
        try (Connection connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            return toObservation(SchemaChanges.observe(connection,
                    dbSwitchRuntime.products().metadata(conn.getProductType()), conn.getSchema(), tableName,
                    MetadataReadOptions.fresh()));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to observe target table on the resolved connection", ex);
        }
    }

    /** 原生结构的唯一事实来源由 DB Switch 提供，业务侧只映射展示字段。 */
    private TableObservation toObservation(SchemaObservation observed) {
        List<ModelFieldItem> fields = new ArrayList<>();
        int sort = 1;
        for (ColumnDescription column : observed.metadata().columns()) {
            ModelFieldItem field = new ModelFieldItem();
            field.setFieldName(column.getFieldName());
            field.setCnName(column.getFieldName());
            field.setDataType(column.getFieldTypeName());
            field.setDataPrecision(column.getPrecisionSize());
            field.setDataScale(column.getScaleSize());
            var length = column.getMetadataEvidence().characterLength().value();
            if (length != null && length <= Integer.MAX_VALUE) field.setDataLength(length.intValue());
            field.setNullableFlag(column.isNullable());
            field.setPkFlag(observed.metadata().primaryKeys().contains(column.getFieldName()));
            field.setComment(column.getRemarks());
            field.setSort(sort++);
            fields.add(field);
        }
        var precondition = observed.precondition();
        return new TableObservation(precondition.tableExists(), observed.hasData(),
                precondition.tableExists() ? precondition.schemaFingerprint() : null,
                Instant.now(), List.copyOf(fields), precondition);
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
                    && (nativePrecondition != null && other.nativePrecondition != null
                    ? nativePrecondition.equals(other.nativePrecondition)
                    : java.util.Objects.equals(observedSchemaHash, other.observedSchemaHash)
                    && java.util.Objects.equals(nativePrecondition, other.nativePrecondition));
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
        if ("DORIS".equals(conn.getProductType().name())) {
            if (dorisExt != null) {
                // 先保留领域校验错误语义，再把普通列适配到组件。
                toDorisDefinition(name, comment, fields, dorisExt);
            }
            var columns = convertList(fields, this::toPublishColumnDescription);
            java.util.Map<String, String> properties = new java.util.HashMap<>();
            if (dorisExt != null) {
                // 旧配置入口继续保留精确数值验证；受控部署不接受未定义边界的自动分区和聚合语义。
                if (!Set.of("DUPLICATE", "UNIQUE").contains(dorisExt.getTableModel())
                        || StrUtil.isNotBlank(dorisExt.getPartitionMode()) && !"NONE".equals(dorisExt.getPartitionMode())
                        || StrUtil.isNotBlank(dorisExt.getSequenceColumn()) || Boolean.FALSE.equals(dorisExt.getEnableMow())) {
                    throw new IllegalArgumentException("当前受控发布只支持普通 Doris 明细表或 Merge-on-Write 主键表");
                }
                var keys = dorisExt.getKeyColumns() == null ? List.<String>of() : dorisExt.getKeyColumns();
                if (keys.isEmpty() || !fields.stream().map(ModelFieldItem::getFieldName).toList().containsAll(keys)) {
                    throw new IllegalArgumentException("Doris Key 列必须来自当前模型字段");
                }
                var frozenKeys = fields.stream().filter(field -> Boolean.TRUE.equals(field.getPkFlag()))
                        .map(ModelFieldItem::getFieldName).toList();
                if (!frozenKeys.equals(keys)) {
                    throw new IllegalArgumentException("Doris Key 配置必须与当前模型的键列及顺序一致，请先同步模型字段");
                }
                properties.put("doris.preferUniqueKey", Boolean.toString("UNIQUE".equals(dorisExt.getTableModel())));
                var options = new cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions(null,
                        StrUtil.blankToDefault(dorisExt.getDistributionType(), "HASH"),
                        dorisExt.getDistributionColumns() == null ? keys : dorisExt.getDistributionColumns(),
                        StrUtil.blankToDefault(dorisExt.getBucketMode(), "AUTO"), dorisExt.getBucketCount(),
                        dorisExt.getReplicationNum() == null ? 1 : dorisExt.getReplicationNum());
                properties.put(cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY,
                        options.toJson());
            }
            var plan = SchemaSynchronization.createPlan(dbSwitchRuntime.products().metadata(conn.getProductType()),
                    conn.getSchema(), name, comment, columns, properties);
            if (!plan.ready()) throw new IllegalArgumentException("Doris 建表定义未获组件支持：" + plan.diagnostics());
            return plan.statements();
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
        if (!SchemaChanges.supports(conn.getProductType())) {
            throw new UnsupportedOperationException("Frozen model schema changes are unsupported for this product");
        }
    }

    /** 由组件管理外部事务差异；Doris 执行中断或异步任务未结束时保持未知结果。 */
    public void executeDdl(ModelingDatabaseResolver.ConnectionInfo conn, List<String> ddlList,
            TableObservation observation, java.util.function.IntConsumer beforeStep) {
        requireVerifiedSchemaExecution(conn);
        if (observation == null || observation.nativePrecondition() == null) {
            throw new IllegalArgumentException("Model DDL requires a frozen native schema precondition");
        }
        var database = dbSwitchRuntime.products().metadata(conn.getProductType());
        var expected = observation.nativePrecondition();
        var plan = SchemaChanges.plan(database, expected.schemaName(), expected.tableName(), ddlList);
        boolean entered = false;
        try (var connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            entered = true;
            var result = SchemaChanges.execute(connection, database, plan, expected, MetadataReadOptions.fresh(), beforeStep);
            if (result.outcome() != SchemaChanges.Outcome.COMMITTED) {
                throw new DdlExecutionException(DdlOutcome.valueOf(result.outcome().name()));
            }
        } catch (java.sql.SQLException failed) {
            throw new DdlExecutionException(entered ? DdlOutcome.UNKNOWN : DdlOutcome.NOT_STARTED);
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
            return SchemaChanges.physicalTargetFingerprint(connection,
                    dbSwitchRuntime.products().metadata(conn.getProductType()), conn.getSchema(), tableName,
                    MetadataReadOptions.fresh());
        } catch (java.sql.SQLException failed) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.PHYSICAL_MODEL_TABLE_META_FAILED,
                    "物理目标控制身份或执行权限不可核验");
        }
    }

    /** 原生结构对账委托组件；Doris 由持久目标占用及两次观测保护，不冒充关系锁。 */
    public void withLockedExpectedObservation(ModelingDatabaseResolver.ConnectionInfo conn, String tableName,
            String tableComment, List<ModelFieldItem> fields,
            java.util.function.BiConsumer<TableObservation, Boolean> callback) {
        withLockedExpectedObservation(conn, tableName, tableComment, fields, null, callback);
    }

    /** 首次创建还需核对该任务冻结的键模型、布局和属性，不能只比较字段。 */
    public void withLockedExpectedObservation(ModelingDatabaseResolver.ConnectionInfo conn, String tableName,
            String tableComment, List<ModelFieldItem> fields, String frozenCreateSql,
            java.util.function.BiConsumer<TableObservation, Boolean> callback) {
        requireVerifiedSchemaExecution(conn);
        try (var connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            var database = dbSwitchRuntime.products().metadata(conn.getProductType());
            SchemaChanges.withGuardedObservation(connection, database, conn.getSchema(), tableName,
                    MetadataReadOptions.fresh(), observed -> {
                        boolean matches = false;
                        if (fields != null && Boolean.TRUE.equals(observed.metadata().existence().value())) {
                            try {
                                var expected = SchemaSynchronization.plan(connection, database, conn.getSchema(),
                                        tableName, tableComment, convertList(fields, this::toPublishColumnDescription));
                                matches = expected.ready() && expected.statements().isEmpty();
                                if (matches && frozenCreateSql != null) {
                                    matches = !frozenCreateSql.isBlank() && database.matchesModelingCreate(connection,
                                            conn.getSchema(), tableName, frozenCreateSql, MetadataReadOptions.fresh().timeoutSeconds());
                                }
                                if (conn.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DORIS) {
                                    // 对账查询本身也可能与外部改表交错；决策前再次确认原生证据未变化。
                                    database.awaitSchemaChange(connection, conn.getSchema(), tableName,
                                            MetadataReadOptions.fresh().timeoutSeconds());
                                    var confirmed = SchemaChanges.observe(connection, database, conn.getSchema(), tableName,
                                            MetadataReadOptions.fresh());
                                    if (!observed.sameState(confirmed)) {
                                        throw new IllegalStateException("核验期间目标结构发生变化，继续保留未知占用");
                                    }
                                }
                            } catch (java.sql.SQLException failed) {
                                throw new IllegalStateException("目标结构对账失败，继续保留未知占用");
                            }
                        }
                        callback.accept(toObservation(observed), matches);
                    });
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

    /** 部署专用校验同时限定物理目标，避免已冻结 SQL 跨表执行。 */
    public void validateDdl(ModelingDatabaseResolver.ConnectionInfo conn, String tableName, List<String> ddlList) {
        SchemaChanges.plan(dbSwitchRuntime.products().metadata(conn.getProductType()), conn.getSchema(), tableName, ddlList);
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
