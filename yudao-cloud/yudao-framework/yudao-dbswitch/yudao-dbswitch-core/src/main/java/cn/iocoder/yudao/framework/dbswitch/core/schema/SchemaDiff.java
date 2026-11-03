package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;

import java.sql.SQLFeatureNotSupportedException;
import java.util.*;
import java.util.regex.Pattern;

/** 保守的同产品列和主键差异比较。不自动重命名、DROP、重建约束或执行 DDL。 */
public final class SchemaDiff {

    /**
     * 已声明允许空值且无默认值的 ADD COLUMN 生成器、并自行对原始名称加引号的产品。
     * PostgreSQL 在自身方言中对原始名称加引号。SQL Server 2000 不在此范围内：
     * 其旧版 ADD 直接拼接传入的 schema 和表名，不为它们加引号。
     */
    private static final Set<ProductTypeEnum> NULLABLE_ADD_PRODUCTS = EnumSet.of(
            ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB,
            ProductTypeEnum.GBASE8A, ProductTypeEnum.ORACLE, ProductTypeEnum.DM, ProductTypeEnum.KINGBASE,
            ProductTypeEnum.GREENPLUM, ProductTypeEnum.HIGHGO, ProductTypeEnum.UXDB,
            ProductTypeEnum.OPENGAUSS, ProductTypeEnum.SYBASE, ProductTypeEnum.SQLSERVER,
            ProductTypeEnum.OCEANBASE, ProductTypeEnum.DORIS, ProductTypeEnum.STARROCKS,
            ProductTypeEnum.CLICKHOUSE, ProductTypeEnum.TDENGINE, ProductTypeEnum.HIVE,
            ProductTypeEnum.OSCAR, ProductTypeEnum.SQLITE3, ProductTypeEnum.DB2);

    /**
     * 模型驱动的加法式变更。现有原生事实保持不变；仅可通过现有方言生成器
     * 追加明确允许空值、非键且非生成列。
     * 在 DDL 后完成原生观测前，目标指纹未知，不伪造来源信息。
     * 通过准入的产品（包括 PostgreSQL）自行对原始 schema 和表名加引号。
     * 只有 PostgreSQL 的 DROP COLUMN 逆向操作已实际执行；{@link DdlPlan.Step} 允许逆向操作留空。
     */
    public DdlPlan planAddColumns(cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase database,
            String schemaName, String tableName, List<ColumnDescription> additions,
            TableSchemaMetadata actual) {
        ProductTypeEnum product = database.getDatabaseType();
        List<DdlPlan.Step> steps = new ArrayList<>();
        List<DdlPlan.Diagnostic> diagnostics = new ArrayList<>();
        if (!NULLABLE_ADD_PRODUCTS.contains(product) || !actual.provenance().nativeProduct(product)
                || !Boolean.TRUE.equals(actual.existence().value())
                || actual.additionalObjects().state() != MetadataValue.State.ABSENT) {
            diagnostics.add(new DdlPlan.Diagnostic("UNVERIFIED_ADDITIVE_TARGET", tableName));
        } else {
            Set<String> names = new HashSet<>(index(actual.columns()).keySet());
            for (ColumnDescription column : additions) {
                String name = column.getFieldName();
                if (!names.add(name) || !column.isNullable() || column.isPk() || column.isAutoIncrement()
                        || column.getDefaultValue() != null) {
                    diagnostics.add(new DdlPlan.Diagnostic("ADD_REQUIRES_NEW_NULLABLE_NON_KEY_COLUMN", name));
                    continue;
                }
                try {
                    if (column.getMetaData().getType() == ColumnMetaData.TYPE_NONE) {
                        throw new UnsupportedOperationException("Unknown column type cannot generate ADD DDL");
                    }
                    if (product == ProductTypeEnum.POSTGRESQL) {
                        String sql = database.getAddColumnSql(schemaName, tableName, column);
                        String reverse = "ALTER TABLE " + RestrictedSelectGuard.qualifiedTableName(product, schemaName,
                                tableName)
                                + " DROP COLUMN " + RestrictedSelectGuard.quotePhysicalIdentifier(product, name);
                        add(steps, product, DdlPlan.Kind.ADD_COLUMN, name, sql, reverse,
                                DdlPlan.RollbackCompleteness.STRUCTURE_ONLY_DATA_NOT_RESTORED);
                    } else {
                        String sql = database.getAddColumnSql(schemaName, tableName, column);
                        addWithoutReverse(steps, product, DdlPlan.Kind.ADD_COLUMN, name, sql,
                                DdlPlan.RollbackCompleteness.STRUCTURE_ONLY_DATA_NOT_RESTORED);
                    }
                } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
                    diagnostics.add(new DdlPlan.Diagnostic("UNSUPPORTED_ADDITIVE_COLUMN", name));
                } catch (Exception unsupported) {
                    // 接口未声明此受检拒绝异常；方言重写仍将其抛出。
                    if (!(unsupported instanceof SQLFeatureNotSupportedException)) {
                        throw propagate(unsupported);
                    }
                    diagnostics.add(new DdlPlan.Diagnostic("UNSUPPORTED_ADDITIVE_COLUMN", name));
                }
            }
        }
        return new DdlPlan(SchemaFingerprint.of(actual), null, diagnostics.isEmpty() ? steps : List.of(), diagnostics);
    }

    /**
     * 保留当前失败信息并向调用方传播异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }

    /**
     * 返回计划。
     */
    public DdlPlan plan(cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase database, String schemaName,
            String tableName,
            TableSchemaMetadata desired, TableSchemaMetadata actual) {
        ProductTypeEnum product = database.getDatabaseType();
        List<DdlPlan.Step> steps = new ArrayList<>();
        List<DdlPlan.Diagnostic> diagnostics = new ArrayList<>();
        String relation = RestrictedSelectGuard.qualifiedTableName(product, schemaName, tableName);
        if (!desired.provenance().nativeProduct(product) || !actual.provenance().nativeProduct(product)
                || !Objects.equals(desired.provenance().serverVersion(), actual.provenance().serverVersion())) {
            diagnostics.add(new DdlPlan.Diagnostic("UNVERIFIED_PRODUCT_OR_SERVER_VERSION", tableName));
            return result(desired, actual, steps, diagnostics);
        }
        if (actual.existence().state() != MetadataValue.State.VALUE) {
            diagnostics.add(new DdlPlan.Diagnostic("UNKNOWN_TABLE_EXISTENCE", tableName));
            return result(desired, actual, steps, diagnostics);
        }
        if (desired.additionalObjects().state() != MetadataValue.State.ABSENT
                || actual.additionalObjects().state() != MetadataValue.State.ABSENT) {
            diagnostics.add(new DdlPlan.Diagnostic("UNSUPPORTED_OR_UNKNOWN_ADDITIONAL_OBJECTS", tableName));
            return result(desired, actual, steps, diagnostics);
        }
        if (!actual.existence().value()) {
            try {
                String sql = GenerateSqlUtils.getDDLCreateTableSQL(database, desired, schemaName, tableName);
                add(steps, product, DdlPlan.Kind.CREATE_TABLE, tableName, sql, "DROP TABLE " + relation,
                        DdlPlan.RollbackCompleteness.STRUCTURE_ONLY_DATA_NOT_RESTORED);
            } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
                diagnostics.add(new DdlPlan.Diagnostic("INCOMPLETE_OR_UNSUPPORTED_CREATE", tableName));
            }
            return result(desired, actual, steps, diagnostics);
        }
        if (!desired.primaryKeys().equals(actual.primaryKeys())) {
            diagnostics.add(new DdlPlan.Diagnostic("PRIMARY_KEY_CHANGE_REQUIRES_DEPENDENCY_PLAN", tableName));
        }
        Map<String, ColumnDescription> before = index(actual.columns());
        Map<String, ColumnDescription> after = index(desired.columns());
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                diagnostics.add(new DdlPlan.Diagnostic("DROP_OR_RENAME_REQUIRES_EXPLICIT_PLAN", name));
            }
        }
        for (ColumnDescription column : desired.columns()) {
            String name = column.getFieldName();
            String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(product, name);
            ColumnDescription old = before.get(name);
            try {
                String definition = NativeColumnSql.definition(product, column);
                if (old == null) {
                    if (!Boolean.TRUE.equals(column.getMetadataEvidence().nullable().value())
                            || column.getMetadataEvidence().identity().state() != MetadataValue.State.ABSENT) {
                        diagnostics.add(new DdlPlan.Diagnostic("ADD_REQUIRES_NULLABLE_NON_IDENTITY_COLUMN", name));
                        continue;
                    }
                    add(steps, product, DdlPlan.Kind.ADD_COLUMN, name,
                            "ALTER TABLE " + relation + " ADD COLUMN " + definition,
                            "ALTER TABLE " + relation + " DROP COLUMN " + quoted,
                            DdlPlan.RollbackCompleteness.STRUCTURE_ONLY_DATA_NOT_RESTORED);
                    continue;
                }
                NativeColumnSql.definition(product, old); // 未知的旧事实不能作为破坏性差异的依据。
                ColumnMetadataEvidence want = column.getMetadataEvidence();
                ColumnMetadataEvidence have = old.getMetadataEvidence();
                if (!Objects.equals(want.nullable(), have.nullable()) && !Boolean.TRUE.equals(
                        want.nullable().value())) {
                    diagnostics.add(new DdlPlan.Diagnostic("NOT_NULL_REQUIRES_DATA_VALIDATION", name));
                    continue;
                }
                if (!Objects.equals(want.identity(), have.identity()) || !Objects.equals(want.charset(), have.charset())
                        || !Objects.equals(want.collation(), have.collation())) {
                    diagnostics.add(new DdlPlan.Diagnostic("UNSUPPORTED_IDENTITY_OR_CHARACTER_SEMANTICS_CHANGE", name));
                    continue;
                }
                if (!Objects.equals(want.nativeType(), have.nativeType())) {
                    if (!safeWiden(have.nativeType().value(), want.nativeType().value())) {
                        diagnostics.add(new DdlPlan.Diagnostic("TYPE_NARROWING_OR_UNVERIFIED_CONVERSION", name));
                        continue;
                    }
                    String sql = product == ProductTypeEnum.POSTGRESQL
                            ? "ALTER TABLE " + relation + " ALTER COLUMN " + quoted + " TYPE " + want.nativeType()
                            .value()
                            : "ALTER TABLE " + relation + " MODIFY COLUMN " + definition;
                    String reverse = product == ProductTypeEnum.POSTGRESQL
                            ? "ALTER TABLE " + relation + " ALTER COLUMN " + quoted + " TYPE " + have.nativeType()
                            .value()
                            : "ALTER TABLE " + relation + " MODIFY COLUMN " + NativeColumnSql.definition(product, old);
                    add(steps, product, DdlPlan.Kind.WIDEN_TYPE, name, sql, reverse,
                            DdlPlan.RollbackCompleteness.CONDITIONAL_STRUCTURE);
                    if (product == ProductTypeEnum.MYSQL) {
                        continue; // 完整列定义已包含这些属性。
                    }
                }
                if (!Objects.equals(want.defaultExpression(), have.defaultExpression())) {
                    boolean drop = want.defaultExpression().state() == MetadataValue.State.ABSENT;
                    String prefix = "ALTER TABLE " + relation + " ALTER COLUMN " + quoted;
                    add(steps, product, drop ? DdlPlan.Kind.DROP_DEFAULT : DdlPlan.Kind.SET_DEFAULT, name,
                            prefix + defaultClause(want.defaultExpression()),
                            prefix + defaultClause(have.defaultExpression()),
                            DdlPlan.RollbackCompleteness.STRUCTURE_ONLY_DATA_NOT_RESTORED);
                }
                if (!Objects.equals(want.nullable(), have.nullable())) {
                    if (!Boolean.TRUE.equals(want.nullable().value())) {
                        diagnostics.add(new DdlPlan.Diagnostic("NOT_NULL_REQUIRES_DATA_VALIDATION", name));
                    } else {
                        String sql = product == ProductTypeEnum.POSTGRESQL
                                ? "ALTER TABLE " + relation + " ALTER COLUMN " + quoted + " DROP NOT NULL"
                                : "ALTER TABLE " + relation + " MODIFY COLUMN " + definition;
                        String reverse = product == ProductTypeEnum.POSTGRESQL
                                ? "ALTER TABLE " + relation + " ALTER COLUMN " + quoted + " SET NOT NULL"
                                : "ALTER TABLE " + relation + " MODIFY COLUMN " + NativeColumnSql.definition(product,
                                old);
                        add(steps, product, DdlPlan.Kind.RELAX_NULLABILITY, name, sql, reverse,
                                DdlPlan.RollbackCompleteness.CONDITIONAL_STRUCTURE);
                    }
                }
            } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
                diagnostics.add(new DdlPlan.Diagnostic("INCOMPLETE_OR_UNSUPPORTED_COLUMN", name));
            }
        }
        return result(desired, actual, steps, diagnostics);
    }

    /**
     * 返回结果。
     */
    private static DdlPlan result(TableSchemaMetadata desired, TableSchemaMetadata actual,
            List<DdlPlan.Step> steps, List<DdlPlan.Diagnostic> diagnostics) {
        // 被阻止的预览绝不暴露部分可执行前缀。
        return new DdlPlan(SchemaFingerprint.of(actual), SchemaFingerprint.of(desired),
                diagnostics.isEmpty() ? steps : List.of(), diagnostics);
    }

    /**
     * 返回索引。
     */
    private static Map<String, ColumnDescription> index(List<ColumnDescription> columns) {
        Map<String, ColumnDescription> result = new LinkedHashMap<>();
        for (ColumnDescription column : columns) {
            if (result.putIfAbsent(column.getFieldName(), column) != null) {
                throw new IllegalArgumentException("Duplicate exact column names");
            }
        }
        return result;
    }

    /**
     * 返回列默认值子句的可观测状态。
     */
    private static String defaultClause(MetadataValue<String> value) {
        return value.state() == MetadataValue.State.ABSENT ? " DROP DEFAULT" : " SET DEFAULT " + value.value();
    }

    /**
     * 根据当前输入构建并返回 {@code java.math.BigInteger}。
     */
    private static boolean safeWiden(String oldType, String newType) {
        Pattern pattern = Pattern.compile(
                "(?i)(character varying|varchar|numeric|decimal)\\((\\d+)(?:,\\s*(-?\\d+))?\\)");
        var old = pattern.matcher(oldType);
        var next = pattern.matcher(newType);
        if (!old.matches() || !next.matches() || !old.group(1).equalsIgnoreCase(next.group(1))
                || !Objects.equals(old.group(3), next.group(3))) {
            return false;
        }
        return new java.math.BigInteger(next.group(2)).compareTo(new java.math.BigInteger(old.group(2))) > 0;
    }

    /**
     * 将当前值加入集合或累计统计。
     */
    private static void add(List<DdlPlan.Step> steps, ProductTypeEnum product, DdlPlan.Kind kind, String object,
            String sql, String reverse, DdlPlan.RollbackCompleteness rollback) {
        if (SqlScriptPlan.parse(product, sql).steps().size() != 1 || SqlScriptPlan.parse(product, reverse).steps()
                .size() != 1) {
            throw new IllegalArgumentException("DDL step must contain one statement");
        }
        append(steps, product, kind, object, sql, reverse, rollback);
    }

    /** {@link DdlPlan.Step} 允许逆向操作留空；此处不凭空生成 DROP 语句。 */
    private static void addWithoutReverse(List<DdlPlan.Step> steps, ProductTypeEnum product, DdlPlan.Kind kind,
            String object, String sql, DdlPlan.RollbackCompleteness rollback) {
        if (sql == null || sql.isBlank() || sql.indexOf(';') >= 0
                || (SqlStatementClassifier.supports(product) && SqlScriptPlan.parse(product, sql).steps()
                .size() != 1)) {
            throw new IllegalArgumentException("DDL step must contain one statement");
        }
        append(steps, product, kind, object, sql, "", rollback);
    }

    /**
     * 将给定内容追加到当前缓冲区，并更新累计大小。
     */
    private static void append(List<DdlPlan.Step> steps, ProductTypeEnum product, DdlPlan.Kind kind, String object,
            String sql, String reverse, DdlPlan.RollbackCompleteness rollback) {
        EnumSet<DdlPlan.Risk> risks = EnumSet.of(DdlPlan.Risk.LOCK, DdlPlan.Risk.REVERSE_CAN_LOSE_DATA);
        if (kind == DdlPlan.Kind.WIDEN_TYPE || kind == DdlPlan.Kind.ADD_COLUMN) {
            risks.add(DdlPlan.Risk.TABLE_REWRITE);
        }
        if (product == ProductTypeEnum.MYSQL) {
            risks.add(DdlPlan.Risk.IMPLICIT_COMMIT);
        }
        String id = String.format(java.util.Locale.ROOT, "step-%04d", steps.size() + 1);
        List<String> dependencies = steps.isEmpty() ? List.of() : List.of(steps.getLast().id());
        steps.add(new DdlPlan.Step(id, kind, object, sql, dependencies, risks,
                product == ProductTypeEnum.POSTGRESQL ? DdlPlan.Transactionality.TRANSACTIONAL : DdlPlan.Transactionality.IMPLICIT_COMMIT,
                reverse, rollback));
    }
}
