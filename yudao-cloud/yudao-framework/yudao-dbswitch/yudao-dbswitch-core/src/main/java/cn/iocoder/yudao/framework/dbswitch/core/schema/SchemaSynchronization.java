package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 显式启用的完整普通表同步预览：ADD/MODIFY/DROP、主键及注释。
 * 仅借用连接读取新鲜目录，不执行、提交、回滚、关闭或修改连接状态。
 * DROP 会丢失数据；类型转换、NOT NULL 和主键变更可能因现有数据失败。
 * 调用方负责授权、并发保护、执行及结果核验；MySQL DDL 具有隐式提交语义。
 * 保守的 {@link SchemaDiff} 契约保持不变。生成列、标识生成及额外依赖显式拒绝。
 */
public final class SchemaSynchronization {

    /**
     * 初始化 {@code SchemaSynchronization}。
     */
    private SchemaSynchronization() {
    }

    public record Diagnostic(String code, String objectName) {

    }

    public record Plan(ProductTypeEnum product, List<String> statements, List<Diagnostic> diagnostics) {

        /**
         * 校验构造参数并初始化 {@code Plan}。
         */
        public Plan {
            statements = List.copyOf(statements);
            diagnostics = List.copyOf(diagnostics);
            if (!diagnostics.isEmpty() && !statements.isEmpty()) {
                throw new IllegalArgumentException("A rejected synchronization cannot expose partial SQL");
            }
        }

        /**
         * 检查当前资源是否已经准备就绪。
         */
        public boolean ready() {
            return diagnostics.isEmpty();
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "SchemaSynchronization.Plan[product=" + product + ", statements=" + statements.size()
                    + ", diagnostics=" + diagnostics.size() + "]";
        }
    }

    /** 主键顺序取自输入列顺序；默认值为有限的 SQL 字面量或当前时间表达式。 */
    public static Plan plan(Connection connection, AbstractDatabase database, String schema, String table,
            String tableComment, List<ColumnDescription> desiredColumns) throws SQLException {
        return plan(connection, database, schema, table, tableComment, desiredColumns, MetadataReadOptions.fresh());
    }

    /**
     * 返回计划。
     */
    public static Plan plan(Connection connection, AbstractDatabase database, String schema, String table,
            String tableComment, List<ColumnDescription> desiredColumns,
            MetadataReadOptions options) throws SQLException {
        ProductTypeEnum product = database.getDatabaseType();
        if (product == ProductTypeEnum.DORIS) {
            Objects.requireNonNull(options);
            return database.planTableSynchronization(connection, schema, table, tableComment,
                    desiredColumns.stream().map(ColumnDescription::copy).toList(), options.timeoutSeconds());
        }
        if (product != ProductTypeEnum.POSTGRESQL && product != ProductTypeEnum.MYSQL) {
            return rejected(product, "UNSUPPORTED_FULL_SYNCHRONIZATION_PRODUCT", table);
        }
        RestrictedSelectGuard.qualifiedTableName(product, schema, table);
        Objects.requireNonNull(options);
        List<ColumnDescription> desired = desiredColumns.stream().map(ColumnDescription::copy).toList();
        TableSchemaMetadata actual = NativeSchemaReader.read(connection, product, schema, table,
                options.timeoutSeconds());
        TableFacts facts = readTableFacts(connection, product, schema, table, options.timeoutSeconds());
        return planObserved(database, schema, table, tableComment, desired, actual, facts);
    }

    /**
     * 显式普通列 CREATE 策略。复用旧建表组装器，但先移除默认值；
     * 再以与同步一致的默认表达式及注释补齐语句。旧生成器调用语义保持不变。
     * 只生成计划；MySQL 多步 DDL 的隐式提交及中途失败由调用方处理。
     */
    public static Plan createPlan(AbstractDatabase database, String schema, String table, String tableComment,
            List<ColumnDescription> desiredColumns) {
        return createPlan(database, schema, table, tableComment, desiredColumns, Map.of());
    }

    /** 原生数仓表使用显式结构化属性；其他产品不接受未声明的表属性。 */
    public static Plan createPlan(AbstractDatabase database, String schema, String table, String tableComment,
            List<ColumnDescription> desiredColumns, Map<String, String> properties) {
        ProductTypeEnum product = database.getDatabaseType();
        Objects.requireNonNull(properties);
        if (product == ProductTypeEnum.DORIS) {
            return database.planCreateTableSynchronization(schema, table, tableComment,
                    desiredColumns.stream().map(ColumnDescription::copy).toList(), Map.copyOf(properties));
        }
        if (!properties.isEmpty()) {
            return rejected(product, "UNSUPPORTED_CREATE_TABLE_PROPERTIES", table);
        }
        if (product != ProductTypeEnum.POSTGRESQL && product != ProductTypeEnum.MYSQL) {
            return rejected(product, "UNSUPPORTED_FULL_SYNCHRONIZATION_PRODUCT", table);
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(product, schema, table);
        if (desiredColumns.isEmpty() || desiredColumns.size() > 2000) {
            return rejected(product, "INVALID_CREATE_COLUMN_COUNT", table);
        }
        try {
            validateComments(product, tableComment, desiredColumns);
            List<String> keys = desiredColumns.stream().filter(ColumnDescription::isPk)
                    .map(ColumnDescription::getFieldName).toList();
            Map<String, DesiredColumn> desired = new LinkedHashMap<>();
            List<ColumnDescription> withoutDefaults = new ArrayList<>();
            for (ColumnDescription column : desiredColumns) {
                DesiredColumn normalized = desiredColumn(database, keys, column);
                if (desired.putIfAbsent(normalized.name(), normalized) != null) {
                    return rejected(product, "DUPLICATE_COLUMN_NAME", normalized.name());
                }
                ColumnDescription portable = column.copy();
                portable.setDbType(product);
                portable.setDefaultValue(null);
                portable.setRemarks(null);
                portable.setNullable(normalized.nullable());
                withoutDefaults.add(portable);
            }
            if (product == ProductTypeEnum.MYSQL && desired.keySet().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).distinct().count() != desired.size()) {
                return rejected(product, "DUPLICATE_COLUMN_NAME", table);
            }
            List<String> statements = new ArrayList<>();
            statements.add(
                    GenerateSqlUtils.getDDLCreateTableSQL(database, withoutDefaults, keys, schema, table, false));
            for (DesiredColumn column : desired.values()) {
                String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(product, column.name());
                boolean mysqlCompleteDefinition = product == ProductTypeEnum.MYSQL && (!column.comment().isEmpty()
                        || !column.nullable() && column.type()
                        .matches("(?i)(date|time|timestamp|datetime)(?:\\(\\d+\\))?"));
                if (mysqlCompleteDefinition) {
                    statements.add("ALTER TABLE " + relation + " MODIFY COLUMN " + definition(product, column, null));
                } else if (column.defaultExpression() != null) {
                    statements.add(
                            "ALTER TABLE " + relation + " ALTER COLUMN " + quoted + " SET DEFAULT " + column.defaultExpression());
                }
                if (product == ProductTypeEnum.POSTGRESQL && !column.comment().isEmpty()) {
                    statements.add("COMMENT ON COLUMN " + relation + "." + quoted + " IS " + postgresLiteral(
                            column.comment()));
                }
            }
            if (!text(tableComment).isEmpty()) {
                statements.add(product == ProductTypeEnum.POSTGRESQL
                        ? "COMMENT ON TABLE " + relation + " IS " + postgresLiteral(tableComment)
                        : "ALTER TABLE " + relation + " COMMENT = " + GenerateSqlUtils.quoteMysqlComment(tableComment,
                        2048));
            }
            return validatedPlan(product, table, statements);
        } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
            return rejected(product, "UNSUPPORTED_OR_INVALID_CREATE_DEFINITION", table);
        }
    }

    record TableFacts(String primaryKeyName, String comment, boolean dependencyVisible, boolean incomingReference,
                      boolean unsupportedColumnAttributes) {

        /**
         * 使用给定参数初始化 {@code TableFacts}。
         */
        TableFacts(String primaryKeyName, String comment, boolean dependencyVisible, boolean incomingReference) {
            this(primaryKeyName, comment, dependencyVisible, incomingReference, false);
        }
    }

    /**
     * 先移除旧默认值，避免 TYPE 转换沿用不兼容的默认表达式。
     */
    static Plan planObserved(AbstractDatabase database, String schema, String table, String tableComment,
            List<ColumnDescription> desiredColumns, TableSchemaMetadata actual, TableFacts facts) {
        ProductTypeEnum product = database.getDatabaseType();
        if (product != ProductTypeEnum.POSTGRESQL && product != ProductTypeEnum.MYSQL) {
            return rejected(product, "UNSUPPORTED_FULL_SYNCHRONIZATION_PRODUCT", table);
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(product, schema, table);
        try {
            validateComments(product, tableComment, desiredColumns);
        } catch (IllegalArgumentException unsupported) {
            return rejected(product, "UNSUPPORTED_COMMENT_LITERAL", table);
        }
        if (!actual.provenance().nativeProduct(product) || !Boolean.TRUE.equals(actual.existence().value())
                || desiredColumns.isEmpty() || desiredColumns.size() > 2000) {
            return rejected(product, "UNVERIFIED_FULL_SYNCHRONIZATION_TARGET", table);
        }
        if (actual.additionalObjects().state() != MetadataValue.State.ABSENT
                && !(product == ProductTypeEnum.MYSQL && actual.additionalObjects()
                .state() == MetadataValue.State.UNKNOWN
                && facts.dependencyVisible())) {
            return rejected(product, "UNSUPPORTED_OR_UNKNOWN_TABLE_DEPENDENCIES", table);
        }
        if (facts.incomingReference()) {
            return rejected(product, "INCOMING_FOREIGN_KEY_REQUIRES_DEPENDENCY_PLAN", table);
        }
        if (facts.unsupportedColumnAttributes()) {
            return rejected(product, "COLUMN_ATTRIBUTES_REQUIRE_EXPLICIT_PLAN", table);
        }

        Map<String, ColumnDescription> before = new LinkedHashMap<>();
        for (ColumnDescription old : actual.columns()) {
            var evidence = old.getMetadataEvidence();
            if (evidence.nativeType().state() != MetadataValue.State.VALUE
                    || !NativeColumnSql.supportedType(product, evidence.nativeType().value())
                    || evidence.nullable().state() != MetadataValue.State.VALUE
                    || evidence.defaultExpression().state() == MetadataValue.State.UNKNOWN
                    || evidence.defaultExpression().state() == MetadataValue.State.UNSUPPORTED
                    || evidence.identity().state() != MetadataValue.State.ABSENT
                    || evidence.generatedExpression().state() != MetadataValue.State.ABSENT
                    || product == ProductTypeEnum.POSTGRESQL && evidence.sequence()
                    .state() != MetadataValue.State.ABSENT
                    || evidence.sequence().state() == MetadataValue.State.VALUE
                    || evidence.sequence().state() == MetadataValue.State.UNSUPPORTED) {
                return rejected(product, "UNSUPPORTED_OR_UNKNOWN_COLUMN_SEMANTICS", old.getFieldName());
            }
            if (before.putIfAbsent(columnKey(product, old.getFieldName()), old) != null) {
                return rejected(product, "AMBIGUOUS_NATIVE_COLUMN_NAME", old.getFieldName());
            }
        }
        Map<String, DesiredColumn> after = new LinkedHashMap<>();
        List<String> keys = desiredColumns.stream().filter(ColumnDescription::isPk).map(ColumnDescription::getFieldName)
                .toList();
        for (ColumnDescription column : desiredColumns) {
            try {
                String name = column.getFieldName();
                DesiredColumn desired = desiredColumn(database, keys, column);
                if (after.putIfAbsent(columnKey(product, name), desired) != null) {
                    return rejected(product, "DUPLICATE_COLUMN_NAME", name);
                }
            } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
                return rejected(product, "UNSUPPORTED_OR_INVALID_COLUMN_DEFINITION", column.getFieldName());
            }
        }
        if (product == ProductTypeEnum.MYSQL && after.keySet().stream().map(value -> value.toLowerCase(Locale.ROOT))
                .distinct().count() != after.size()) {
            return rejected(product, "DUPLICATE_COLUMN_NAME", table);
        }

        List<String> statements = new ArrayList<>();
        boolean keyChanged = !actual.primaryKeys().stream().map(name -> columnKey(product, name)).toList()
                .equals(keys.stream().map(name -> columnKey(product, name)).toList());
        if (keyChanged && !actual.primaryKeys().isEmpty()) {
            if (facts.primaryKeyName() == null || facts.primaryKeyName().isBlank()) {
                return rejected(product, "PRIMARY_KEY_NAME_NOT_OBSERVED", table);
            }
            statements.add("ALTER TABLE " + relation + (product == ProductTypeEnum.MYSQL ? " DROP PRIMARY KEY"
                    : " DROP CONSTRAINT " + RestrictedSelectGuard.quotePhysicalIdentifier(product,
                    facts.primaryKeyName())));
        }
        for (DesiredColumn desired : after.values()) {
            ColumnDescription old = before.get(columnKey(product, desired.name()));
            String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(product, desired.name());
            if (old == null) {
                statements.add("ALTER TABLE " + relation + " ADD COLUMN " + definition(product, desired, null));
            } else {
                var evidence = old.getMetadataEvidence();
                boolean typeChanged = !canonicalType(product, desired.type()).equals(
                        canonicalType(product, evidence.nativeType().value()));
                boolean defaultChanged = !canonicalDefault(product, desired.defaultExpression(), desired.type()).equals(
                        canonicalDefault(product, evidence.defaultExpression().value(), desired.type()));
                boolean nullableChanged = desired.nullable() != Boolean.TRUE.equals(evidence.nullable().value());
                boolean commentChanged = !desired.comment().equals(text(old.getRemarks()));
                if (product == ProductTypeEnum.MYSQL) {
                    if (typeChanged || defaultChanged || nullableChanged || commentChanged) {
                        statements.add(
                                "ALTER TABLE " + relation + " MODIFY COLUMN " + definition(product, desired, old));
                    }
                } else {
                    String prefix = "ALTER TABLE " + relation + " ALTER COLUMN " + quoted;
                    // 先移除旧默认值，避免 TYPE 转换沿用不兼容的默认表达式。
                    if (typeChanged && evidence.defaultExpression().state() == MetadataValue.State.VALUE) {
                        statements.add(prefix + " DROP DEFAULT");
                    }
                    if (typeChanged) {
                        if (evidence.collation().state() != MetadataValue.State.ABSENT) {
                            return rejected(product, "TYPE_CHANGE_REQUIRES_COLLATION_PLAN", desired.name());
                        }
                        statements.add(prefix + " TYPE " + desired.type());
                    }
                    if (defaultChanged || typeChanged && desired.defaultExpression() != null) {
                        statements.add(
                                prefix + (desired.defaultExpression() == null ? " DROP DEFAULT" : " SET DEFAULT " + desired.defaultExpression()));
                    }
                    if (nullableChanged) {
                        statements.add(prefix + (desired.nullable() ? " DROP NOT NULL" : " SET NOT NULL"));
                    }
                }
            }
            if (product == ProductTypeEnum.POSTGRESQL && (old == null || !desired.comment()
                    .equals(text(old.getRemarks())))) {
                statements.add(
                        "COMMENT ON COLUMN " + relation + "." + quoted + " IS " + postgresLiteral(desired.comment()));
            }
        }
        for (var old : before.entrySet()) {
            if (!after.containsKey(old.getKey())) {
                statements.add(
                        "ALTER TABLE " + relation + " DROP COLUMN " + RestrictedSelectGuard.quotePhysicalIdentifier(
                                product, old.getValue().getFieldName()));
            }
        }
        if (keyChanged && !keys.isEmpty()) {
            statements.add("ALTER TABLE " + relation + " ADD PRIMARY KEY (" + keys.stream()
                    .map(name -> RestrictedSelectGuard.quotePhysicalIdentifier(product, name))
                    .collect(Collectors.joining(", ")) + ")");
        }
        if (!text(tableComment).equals(text(facts.comment()))) {
            statements.add(product == ProductTypeEnum.POSTGRESQL
                    ? "COMMENT ON TABLE " + relation + " IS " + postgresLiteral(text(tableComment))
                    : "ALTER TABLE " + relation + " COMMENT = " + GenerateSqlUtils.quoteMysqlComment(text(tableComment),
                    2048));
        }
        return validatedPlan(product, table, statements);
    }

    /**
     * 根据当前输入构建并返回 {@code Plan}。
     */
    private static Plan validatedPlan(ProductTypeEnum product, String table, List<String> statements) {
        try {
            for (String sql : statements) {
                var parsed = SqlScriptPlan.parse(product, sql);
                if (parsed.steps().size() != 1 || parsed.steps().getFirst().kind() != SqlStatementClassifier.Kind.DDL) {
                    return rejected(product, "UNVERIFIED_SYNCHRONIZATION_STATEMENT", table);
                }
            }
            if (!statements.isEmpty()) {
                var batch = SqlScriptPlan.parse(product, String.join(";\n", statements));
                if (batch.steps().size() != statements.size()
                        || batch.steps().stream().anyMatch(step -> step.kind() != SqlStatementClassifier.Kind.DDL)) {
                    return rejected(product, "UNVERIFIED_SYNCHRONIZATION_BATCH", table);
                }
            }
        } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
            return rejected(product, "UNVERIFIED_SYNCHRONIZATION_STATEMENT", table);
        }
        return new Plan(product, statements, List.of());
    }

    private record DesiredColumn(String name, String type, boolean nullable, String defaultExpression, String comment) {

    }

    /**
     * 拒绝当前实现未支持的 {@code desiredColumn} 操作。
     */
    private static DesiredColumn desiredColumn(AbstractDatabase database, List<String> keys, ColumnDescription column) {
        ProductTypeEnum product = database.getDatabaseType();
        if (column.isAutoIncrement() || column.getMetadataEvidence().nativeType()
                .state() == MetadataValue.State.VALUE) {
            throw new UnsupportedOperationException("Synchronization accepts ordinary portable column definitions");
        }
        String name = column.getFieldName();
        String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(product, name);
        ColumnDescription portable = column.copy();
        portable.setDbType(product);
        ColumnMetaData metadata = portable.getMetaData();
        if (metadata.getType() == ColumnMetaData.TYPE_NONE) {
            throw new UnsupportedOperationException("Unknown portable type");
        }
        if ((metadata.getSqlType() == java.sql.Types.DECIMAL || metadata.getSqlType() == java.sql.Types.NUMERIC)
                && metadata.getLength() > 0) {
            validateDecimalParameters(product, metadata.getLength(), metadata.getPrecision());
        }
        metadata.setDefaultValue(null);
        metadata.setNullable(true);
        String definition = database.getFieldDefinition(metadata, keys, false, false, false).trim();
        if (!definition.startsWith(quoted + " ")) {
            throw new UnsupportedOperationException("Unverified column rendering");
        }
        String mappedType = definition.substring(quoted.length()).trim();
        var numericParameters = java.util.regex.Pattern.compile("(?:decimal|numeric)\\(([0-9]+)(?:,([0-9]+))?\\)")
                .matcher(canonicalType(product, mappedType));
        if (numericParameters.matches()) {
            validateDecimalParameters(product, Integer.parseInt(numericParameters.group(1)),
                    numericParameters.group(2) == null ? 0 : Integer.parseInt(numericParameters.group(2)));
        }
        return new DesiredColumn(name, mappedType, column.isNullable() && !column.isPk(),
                defaultExpression(product, portable), text(column.getRemarks()));
    }

    /**
     * 拒绝当前实现未支持的 {@code validateDecimalParameters} 操作。
     */
    private static void validateDecimalParameters(ProductTypeEnum product, int precision, int scale) {
        int maximumPrecision = product == ProductTypeEnum.MYSQL ? 65 : 1000;
        if (precision < 1 || precision > maximumPrecision || scale < 0 || scale > precision
                || product == ProductTypeEnum.MYSQL && scale > 30) {
            throw new UnsupportedOperationException("Decimal parameters exceed the verified database product range");
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code definition} 操作。
     */
    private static String definition(ProductTypeEnum product, DesiredColumn desired, ColumnDescription old) {
        StringBuilder sql = new StringBuilder(
                RestrictedSelectGuard.quotePhysicalIdentifier(product, desired.name())).append(' ')
                .append(desired.type());
        // MODIFY 必须显式保留已有字符语义，不能退回表默认字符集。
        if (product == ProductTypeEnum.MYSQL && old != null && characterType(desired.type())) {
            var facts = old.getMetadataEvidence();
            for (var item : List.of(Map.entry(" CHARACTER SET ", facts.charset()),
                    Map.entry(" COLLATE ", facts.collation()))) {
                if (item.getValue().state() == MetadataValue.State.VALUE) {
                    String value = item.getValue().value();
                    if (!value.matches("[A-Za-z0-9_]+")) {
                        throw new IllegalArgumentException("Unverified character semantics");
                    }
                    sql.append(item.getKey()).append(value);
                } else if (item.getValue().state() != MetadataValue.State.ABSENT) {
                    throw new UnsupportedOperationException("Unknown character semantics");
                }
            }
        }
        sql.append(desired.nullable() ? " NULL" : " NOT NULL");
        if (desired.defaultExpression() != null) {
            sql.append(" DEFAULT ").append(desired.defaultExpression());
        }
        if (product == ProductTypeEnum.MYSQL) {
            sql.append(" COMMENT ").append(GenerateSqlUtils.quoteMysqlComment(desired.comment(), 1024));
        }
        return sql.toString();
    }

    /** 不接受任意函数、注释、反斜杠、类型 cast 或多语句。字符串字面量使用 SQL 双单引号转义。 */
    public static String defaultExpression(ProductTypeEnum product, String expression) {
        if (expression == null || expression.isBlank() || "NULL".equalsIgnoreCase(expression.trim())) {
            return null;
        }
        String value = expression.trim();
        if (value.length() > 4096 || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsupported default expression");
        }
        if (value.matches("'([^']|'')*'")) {
            return value;
        }
        if (value.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")) {
            return value;
        }
        if (value.matches("(?i)TRUE|FALSE")) {
            return value.toUpperCase(Locale.ROOT);
        }
        if (value.matches("(?i)CURRENT_TIMESTAMP(?:\\([0-6]?\\))?")) {
            return value.toUpperCase(Locale.ROOT);
        }
        if (product == ProductTypeEnum.POSTGRESQL && value.matches("(?i)CURRENT_DATE|CURRENT_TIME|now\\(\\)")) {
            return value.toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("Unsupported default expression");
    }

    /** 普通文本按字段类型成为字面量；数值和 SQL 表达式不接受任意函数或语句。 */
    public static String defaultExpression(ProductTypeEnum product, ColumnDescription column) {
        String expression = column.getDefaultValue();
        if (expression == null || expression.isBlank() || "NULL".equalsIgnoreCase(expression.trim())) {
            return null;
        }
        ColumnDescription portable = column.copy();
        portable.setDbType(product);
        int type = portable.getMetaData().getType();
        String value = expression.trim();
        if (value.startsWith("'")) {
            return defaultExpression(product, value);
        }
        if (type == ColumnMetaData.TYPE_STRING) {
            if (expression.length() > 4096 || !expression.matches("[\\p{L}\\p{N}_ '.:@/+\\-]+") || expression.contains(
                    "--")) {
                throw new IllegalArgumentException("Unsupported plain text default");
            }
            return "'" + expression.replace("'", "''") + "'";
        }
        if ((type == ColumnMetaData.TYPE_DATE || type == ColumnMetaData.TYPE_TIMESTAMP || type == ColumnMetaData.TYPE_TIME)
                && value.matches(
                "(?:[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?)?|[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?)")) {
            return "'" + value + "'";
        }
        return defaultExpression(product, value);
    }

    /**
     * 将当前类型名称及属性整理为规范表示。
     */
    private static String canonicalType(ProductTypeEnum product, String type) {
        String value = type.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ").replaceAll("\\s*([(),])\\s*", "$1");
        if (product == ProductTypeEnum.POSTGRESQL) {
            value = value.replace("varchar", "character varying")
                    .replace("decimal", "numeric").replace("timestamp without time zone", "timestamp")
                    .replace("time without time zone", "time");
        } else {
            value = value.replaceFirst("^integer$", "int");
        }
        return value;
    }

    /**
     * 根据当前输入构建并返回 {@code java.math.BigDecimal}。
     */
    private static String canonicalDefault(ProductTypeEnum product, String expression, String type) {
        if (expression == null) {
            return "";
        }
        String value = expression.trim();
        // PostgreSQL 目录会为字面量追加类型 cast，MySQL 会给数值字面量添加单引号。
        if (product == ProductTypeEnum.POSTGRESQL) {
            value = value.replaceFirst(
                    "(?i)::(?:text|character varying|smallint|integer|bigint|numeric|boolean|date|timestamp without time zone|time without time zone)$",
                    "");
        }
        boolean character = characterType(type) || type.toLowerCase(Locale.ROOT).startsWith("character");
        if (!character && value.matches("'[-+]?[0-9]+(?:\\.[0-9]+)?'")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!character && value.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")) {
            return new java.math.BigDecimal(value).stripTrailingZeros().toString();
        }
        if (value.matches("(?i)TRUE|FALSE")) {
            return value.equalsIgnoreCase("TRUE") ? "1" : "0";
        }
        if (value.matches("(?i)NULL")) {
            return "";
        }
        if (value.matches("(?i)now\\(\\)|CURRENT_TIMESTAMP(?:\\(\\))?")) {
            return "CURRENT_TIMESTAMP";
        }
        return value.startsWith("'") ? value : value.toUpperCase(Locale.ROOT);
    }

    /**
     * 规范化字符类型名称的大小写。
     */
    private static boolean characterType(String type) {
        return type.toLowerCase(Locale.ROOT).matches("(?:char|varchar|text|tinytext|mediumtext|longtext)(?:\\(.*\\))?");
    }

    /**
     * 根据字段信息生成用于定位或匹配的键。
     */
    private static String columnKey(ProductTypeEnum product, String name) {
        return product == ProductTypeEnum.MYSQL ? name.toLowerCase(Locale.ROOT) : name;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * 按 PostgreSQL 语法生成值字面量。
     */
    private static String postgresLiteral(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Unverified SQL literal");
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 校验注释集合。
     */
    private static void validateComments(ProductTypeEnum product, String tableComment,
            List<ColumnDescription> columns) {
        if (product == ProductTypeEnum.POSTGRESQL) {
            postgresLiteral(text(tableComment));
            columns.forEach(column -> postgresLiteral(text(column.getRemarks())));
        } else {
            GenerateSqlUtils.quoteMysqlComment(text(tableComment), 2048);
            columns.forEach(column -> GenerateSqlUtils.quoteMysqlComment(text(column.getRemarks()), 1024));
        }
    }

    /**
     * 累加当前查询准入拒绝计数。
     */
    private static Plan rejected(ProductTypeEnum product, String code, String object) {
        return new Plan(product, List.of(), List.of(new Diagnostic(code, object)));
    }

    /**
     * 读取表事实。
     */
    private static TableFacts readTableFacts(Connection connection, ProductTypeEnum product, String schema,
            String table, int timeout) throws SQLException {
        String sql = product == ProductTypeEnum.POSTGRESQL ? """
                SELECT p.conname,pg_catalog.obj_description(c.oid,'pg_class'),
                  EXISTS(SELECT 1 FROM pg_catalog.pg_constraint incoming WHERE incoming.confrelid=c.oid AND incoming.contype='f')
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_catalog.pg_constraint p ON p.conrelid=c.oid AND p.contype='p'
                WHERE n.nspname=? AND c.relname=? AND c.relkind='r'
                """ : """
                SELECT CASE WHEN EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS p
                  WHERE p.TABLE_SCHEMA=t.TABLE_SCHEMA AND p.TABLE_NAME=t.TABLE_NAME AND p.CONSTRAINT_TYPE='PRIMARY KEY') THEN 'PRIMARY' END,
                  t.TABLE_COMMENT,EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE incoming
                    WHERE incoming.REFERENCED_TABLE_SCHEMA=t.TABLE_SCHEMA AND incoming.REFERENCED_TABLE_NAME=t.TABLE_NAME),
                  EXISTS(SELECT 1 FROM information_schema.COLUMNS a WHERE a.TABLE_SCHEMA=t.TABLE_SCHEMA AND a.TABLE_NAME=t.TABLE_NAME
                    AND (a.EXTRA LIKE '%on update%' OR a.EXTRA LIKE '%INVISIBLE%'))
                FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME=? AND t.TABLE_TYPE='BASE TABLE'
                """;
        String primaryKey, comment;
        boolean incoming, unsupportedAttributes;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("A native ordinary table is required for synchronization");
                }
                primaryKey = rows.getString(1);
                comment = rows.getString(2);
                incoming = rows.getBoolean(3);
                unsupportedAttributes = product == ProductTypeEnum.MYSQL && rows.getBoolean(4);
                if (rows.next()) {
                    throw new SQLException("Ambiguous native table observation");
                }
            }
        }
        boolean visible = product == ProductTypeEnum.POSTGRESQL || mysqlTriggerVisible(connection, schema, table,
                timeout);
        return new TableFacts(primaryKey, comment, visible, incoming, unsupportedAttributes);
    }

    /**
     * 检查 MySQL 触发器信息在当前权限下是否可见。
     */
    private static boolean mysqlTriggerVisible(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        String grantee = "CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',1)),'@',QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',-1)))";
        String sql = "SELECT EXISTS(SELECT 1 FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=" + grantee + " AND PRIVILEGE_TYPE='TRIGGER'"
                + " UNION ALL SELECT 1 FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=" + grantee + " AND PRIVILEGE_TYPE='TRIGGER' AND TABLE_SCHEMA=?"
                + " UNION ALL SELECT 1 FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=" + grantee + " AND PRIVILEGE_TYPE='TRIGGER' AND TABLE_SCHEMA=? AND TABLE_NAME=?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, schema);
            statement.setString(3, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Missing trigger privilege observation");
                }
                return rows.getBoolean(1);
            }
        }
    }
}
