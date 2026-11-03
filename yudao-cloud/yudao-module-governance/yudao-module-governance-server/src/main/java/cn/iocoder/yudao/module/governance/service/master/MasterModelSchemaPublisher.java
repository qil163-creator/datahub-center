package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.MetadataReadOptions;
import cn.iocoder.yudao.framework.dbswitch.core.schema.PostgresSchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaObservation;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronization;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 主数据模型字段映射与发布结果翻译；原生观测、差异、标识符、SQL 和锁由 DB Switch 执行。 */
@Component
public class MasterModelSchemaPublisher {

    public enum Status {APPLIED, NOT_APPLIED, UNKNOWN}

    /** 只返回固定诊断码，不包含厂商异常、SQL、对象名称或连接信息。 */
    public record Result(Status status, List<String> diagnostics) {

        /**
         * 使用给定状态、诊断初始化主数据模型模式发布器。
         */
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static final MetadataReadOptions READ_OPTIONS =
            new MetadataReadOptions(MetadataReadOptions.Freshness.REQUIRE_FRESH, 30);

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    /**
     * 同步主数据模型模式发布器结构或关联状态。
     */
    public Result synchronize(MasterDatabaseResolver.ConnectionInfo source, String tableName,
            String tableComment, List<MasterColumnDO> desiredColumns) {
        return publish(source, tableName, tableComment, desiredColumns, false);
    }

    /** 核查只接受已一致的目标，不继续执行非空结构变更计划。 */
    public Result verify(MasterDatabaseResolver.ConnectionInfo source, String tableName,
            String tableComment, List<MasterColumnDO> desiredColumns) {
        return publish(source, tableName, tableComment, desiredColumns, true);
    }

    /**
     * 校验并发布主数据模型模式发布器。
     */
    private Result publish(MasterDatabaseResolver.ConnectionInfo source, String tableName,
            String tableComment, List<MasterColumnDO> desiredColumns, boolean verifyOnly) {
        if (source == null || source.getProductType() != ProductTypeEnum.POSTGRESQL) {
            return new Result(Status.NOT_APPLIED, List.of("UNSUPPORTED_SCHEMA_SYNCHRONIZATION_PRODUCT"));
        }
        if (desiredColumns == null || desiredColumns.isEmpty() || desiredColumns.size() > 200
                || desiredColumns.stream().anyMatch(java.util.Objects::isNull)) {
            return new Result(Status.NOT_APPLIED, List.of("INVALID_MODEL_COLUMNS"));
        }
        Status status = Status.NOT_APPLIED;
        List<String> diagnostics = new ArrayList<>();
        Connection connection = null;
        boolean transactional = false;
        boolean commitSent = false;
        String failureCode = "SCHEMA_CONNECTION_FAILED";
        try {
            connection = dbSwitchRuntime.openConnection(source.getProductType(), null,
                    source.getJdbcUrl(), source.getUsername(), source.getPassword());
            var metadata = connection.getMetaData();
            failureCode = "SCHEMA_TRANSACTION_UNSUPPORTED";
            if (!metadata.supportsTransactions() || !metadata.supportsDataDefinitionAndDataManipulationTransactions()
                    || metadata.dataDefinitionCausesTransactionCommit() || metadata.dataDefinitionIgnoredInTransactions()
                    || connection.isReadOnly() || !connection.getAutoCommit()) {
                diagnostics.add(failureCode);
            } else {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                transactional = true;
                String schema = source.getSchema();
                if (schema == null || schema.isBlank()) {
                    schema = connection.getSchema();
                }
                var database = dbSwitchRuntime.products().metadata(ProductTypeEnum.POSTGRESQL);
                failureCode = "SCHEMA_OBSERVATION_FAILED";
                SchemaObservation original = PostgresSchemaChanges.observe(connection, database, schema, tableName,
                        READ_OPTIONS);
                if (!Boolean.TRUE.equals(original.metadata().existence().value())) {
                    diagnostics.add("SCHEMA_TARGET_NOT_FOUND");
                } else {
                    failureCode = "SCHEMA_DEFAULT_OR_MODEL_DEFINITION_UNSUPPORTED";
                    List<ColumnDescription> desired = mapColumns(desiredColumns, original);
                    failureCode = "SCHEMA_PLAN_FAILED";
                    var plan = SchemaSynchronization.plan(connection, database, schema, tableName, tableComment,
                            desired, READ_OPTIONS);
                    if (!plan.ready()) {
                        diagnostics.addAll(
                                plan.diagnostics().stream().map(SchemaSynchronization.Diagnostic::code).toList());
                    } else if (verifyOnly && !plan.statements().isEmpty()) {
                        diagnostics.add("SCHEMA_TARGET_NOT_MATCHED");
                    } else {
                        // 空计划也进入组件的关系锁和原始观测校验，不能直接宣布已发布。
                        SqlScriptPlan script = plan.statements().isEmpty()
                                ? new SqlScriptPlan(ProductTypeEnum.POSTGRESQL, SqlScriptPlan.digest(""), List.of())
                                : SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                                String.join(";\n", plan.statements()));
                        failureCode = "SCHEMA_EXECUTION_OR_FRESHNESS_FAILED";
                        PostgresSchemaChanges.execute(connection, database, script, original.precondition(),
                                READ_OPTIONS, ordinal -> {
                                });
                        failureCode = "SCHEMA_FINAL_VERIFICATION_FAILED";
                        var verified = SchemaSynchronization.plan(connection, database, schema, tableName, tableComment,
                                desired, READ_OPTIONS);
                        if (!verified.ready() || !verified.statements().isEmpty()) {
                            diagnostics.add(failureCode);
                        } else {
                            failureCode = "SCHEMA_COMMIT_UNCONFIRMED";
                            commitSent = true;
                            connection.commit();
                            transactional = false;
                            status = Status.APPLIED;
                        }
                    }
                }
                if (transactional) {
                    connection.rollback();
                    transactional = false;
                }
            }
        } catch (SQLException | RuntimeException failure) {
            boolean rolledBack = !transactional;
            if (transactional) {
                try {
                    connection.rollback();
                    rolledBack = true;
                } catch (SQLException | RuntimeException rollbackFailure) {
                    diagnostics.add("SCHEMA_ROLLBACK_UNCONFIRMED");
                }
            }
            status = commitSent || !rolledBack ? Status.UNKNOWN : Status.NOT_APPLIED;
            diagnostics.add(failureCode);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException | RuntimeException cleanupFailure) {
                    diagnostics.add("SCHEMA_CONNECTION_CLEANUP_UNCONFIRMED");
                }
            }
        }
        return new Result(status, diagnostics);
    }

    /**
     * 映射主数据模型模式发布器的字段。
     */
    private static List<ColumnDescription> mapColumns(List<MasterColumnDO> columns, SchemaObservation original) {
        Map<String, ColumnDescription> existing = original.metadata().columns().stream()
                .collect(Collectors.toMap(ColumnDescription::getFieldName, Function.identity()));
        return columns.stream().map(column -> {
            ColumnDescription desired = column.toColumnDescription();
            ColumnDescription old = existing.get(column.getName());
            if (old != null) {
                MetadataValue<String> value = old.getMetadataEvidence().defaultExpression();
                if (!value.known()) {
                    throw new UnsupportedOperationException("Default semantics are unverified");
                }
                // 本页面没有默认值编辑能力，保留可由组件完整验证的原默认值；不私自解析厂商 cast 或函数。
                desired.setDefaultValue(value.state() == MetadataValue.State.VALUE
                        ? SchemaSynchronization.defaultExpression(ProductTypeEnum.POSTGRESQL, value.value()) : null);
            }
            return desired;
        }).toList();
    }
}
