package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronization;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.core.util.DorisDdlBuilder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

/** Doris 普通列建模规划；保留原生键模型、分区、分桶和索引，不借用 MySQL 主键目录。 */
public final class DorisSchemaSynchronization {

    private DorisSchemaSynchronization() {
    }

    /** 仅生成 DDL；输入经类型映射和原生表值模型校验，失败不暴露部分 SQL。 */
    public static SchemaSynchronization.Plan create(AbstractDatabase database, String schema, String table,
            String comment, List<ColumnDescription> columns, Map<String, String> properties) {
        try {
            return planned(table, List.of(desired(database, schema, table, comment, columns, properties).ddl(schema, table)));
        } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
            return rejected(table, "DORIS_CREATE_DEFINITION_UNSUPPORTED");
        }
    }

    /** 借用连接读取 SHOW CREATE 等原生事实；不执行、提交或改变调用方连接。 */
    public static SchemaSynchronization.Plan plan(Connection connection, AbstractDatabase database, String schema,
            String table, String comment, List<ColumnDescription> columns, int timeout) throws SQLException {
        return observed(database, schema, table, comment, columns,
                DorisJdbcSupport.inspect(connection, schema, table, timeout).table());
    }

    /** 读取产品版本及 SHOW CREATE，不使用 MySQL 兼容主键或生成列表。 */
    public static TableSchemaMetadata readMetadata(Connection connection, String schema, String table, int timeout) throws SQLException {
        String version;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SHOW VARIABLES LIKE 'version_comment'")) {
                if (!rows.next()) throw new SQLException("Doris native version is missing");
                var match = java.util.regex.Pattern.compile("^doris version (doris-4\\.0\\.[0-9]+(?:[-+][^\\s]*)?)$")
                        .matcher(rows.getString(2));
                if (!match.matches() || rows.next()) throw new SQLException("Doris native version is unverified");
                version = match.group(1);
            }
        }
        var nativeTable = DorisJdbcSupport.inspect(connection, schema, table, timeout).table();
        return metadata(nativeTable, new MetadataProvenance(ProductTypeEnum.DORIS, "Doris", version,
                "DORIS_CATALOG_V1", java.time.Instant.now()));
    }

    /** 将原生数仓模型投影到通用事实，DUPLICATE 的排序键不表示唯一主键。 */
    static TableSchemaMetadata metadata(Table table, MetadataProvenance source) {
        List<String> primary = table.unique() ? table.keys() : List.of();
        List<ColumnDescription> columns = table.columns().stream().map(column -> {
            var description = column.description();
            description.setPk(primary.contains(column.name()));
            boolean decimal = column.kind().equals("decimal") || column.kind().equals("largeint");
            boolean character = column.kind().equals("varchar") || column.kind().equals("char");
            description.setMetadataEvidence(new ColumnMetadataEvidence(source, MetadataValue.value(column.type()),
                    column.defaultSql() == null || column.defaultSql().equals("NULL") ? MetadataValue.absent()
                            : MetadataValue.value(column.defaultSql()), MetadataValue.value(column.nullable()),
                    decimal ? MetadataValue.value(description.getPrecisionSize()) : MetadataValue.absent(),
                    decimal ? MetadataValue.value(column.scale()) : MetadataValue.absent(),
                    character ? MetadataValue.value((long) column.precision()) : MetadataValue.absent(),
                    MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
            return description;
        }).toList();
        return new TableSchemaMetadata(columns, primary, source, MetadataValue.value(true),
                MetadataValue.value(List.of("DORIS_NATIVE_LAYOUT:" + SqlScriptPlan.digest(table.ddl("_schema", "_table")))));
    }

    /** 目录与逻辑列分别标准化后比较；键与依赖变化必须使用另行审核的重建计划。 */
    static SchemaSynchronization.Plan observed(AbstractDatabase database, String schema, String table, String comment,
            List<ColumnDescription> columns, Table actual) {
        try {
            Table desired = desired(database, schema, table, comment, columns, Map.of());
            List<String> requestedKeys = columns.stream().filter(ColumnDescription::isPk)
                    .map(ColumnDescription::getFieldName).toList();
            if ((!requestedKeys.isEmpty() || actual.unique()) && !requestedKeys.equals(actual.keys())) {
                return rejected(table, "DORIS_KEY_CHANGE_REQUIRES_REBUILD");
            }
            Map<String, Column> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            actual.columns().forEach(column -> before.put(column.name(), column));
            desired.columns().forEach(column -> after.put(column.name(), column));
            for (String name : after.keySet()) {
                // Doris 列名不区分大小写，大小写改名不能转成同名 ADD/DROP。
                if (!before.containsKey(name) && before.keySet().stream().anyMatch(name::equalsIgnoreCase)) {
                    return rejected(name, "DORIS_COLUMN_RENAME_REQUIRES_EXPLICIT_PLAN");
                }
            }
            Set<String> protectedColumns = new HashSet<>(actual.keys());
            protectedColumns.addAll(actual.distribution());
            if (actual.partitioning() != null) protectedColumns.addAll(actual.partitioning().columns());
            actual.indexes().forEach(index -> protectedColumns.addAll(index.columns()));
            String sequence = actual.properties().get("function_column.sequence_col");
            if (sequence != null) protectedColumns.add(sequence);
            String bloom = actual.properties().get("bloom_filter_columns");
            if (bloom != null) protectedColumns.addAll(Arrays.asList(bloom.split(",\\s*")));
            List<String> clauses = new ArrayList<>(), comments = new ArrayList<>();
            String relation = relation(schema, table);
            for (Column target : desired.columns()) {
                Column previous = before.get(target.name());
                if (previous == null) {
                    if (actual.model().equals("AGGREGATE")) {
                        return rejected(target.name(), "DORIS_AGGREGATION_REQUIRED_FOR_NEW_COLUMN");
                    }
                    clauses.add("ADD COLUMN " + definition(target));
                    continue;
                }
                boolean changed = !previous.type().equals(target.type()) || previous.nullable() != target.nullable()
                        || !sameDefault(previous.defaultSql(), target.defaultSql());
                if (changed) {
                    // 4.0 原生 MODIFY 不接受默认值变更或 NULL 收紧；预览阶段拒绝，避免提交无效任务。
                    if (!sameDefault(previous.defaultSql(), target.defaultSql())) {
                        return rejected(target.name(), "DORIS_DEFAULT_CHANGE_REQUIRES_REBUILD");
                    }
                    if (previous.nullable() && !target.nullable()) {
                        return rejected(target.name(), "DORIS_NULLABILITY_TIGHTENING_REQUIRES_REBUILD");
                    }
                    if (Set.of("char", "varchar").contains(previous.kind())
                            && Set.of("char", "varchar").contains(target.kind())
                            && target.precision() < previous.precision()) {
                        return rejected(target.name(), "DORIS_STRING_NARROWING_REQUIRES_REBUILD");
                    }
                    if (!losslessTypeChange(previous, target)) {
                        return rejected(target.name(), "DORIS_TYPE_CHANGE_REQUIRES_EXPLICIT_MIGRATION");
                    }
                    if (protectedColumns.contains(target.name()) || actual.model().equals("AGGREGATE")) {
                        return rejected(target.name(), "DORIS_DEPENDENT_COLUMN_CHANGE_REQUIRES_REBUILD");
                    }
                    clauses.add("MODIFY COLUMN " + definition(new Column(target.name(), target.type(), target.nullable(),
                            target.defaultSql(), previous.comment(), previous.aggregation())));
                }
                if (!previous.comment().equals(target.comment())) {
                    comments.add("ALTER TABLE " + relation + " MODIFY COLUMN " + quote(target.name())
                            + " COMMENT " + columnCommentLiteral(target.comment()));
                }
            }
            for (Column previous : actual.columns()) {
                if (!after.containsKey(previous.name())) {
                    if (protectedColumns.contains(previous.name())) {
                        return rejected(previous.name(), "DORIS_DEPENDENT_COLUMN_DROP_REQUIRES_REBUILD");
                    }
                    clauses.add("DROP COLUMN " + quote(previous.name()));
                }
            }
            List<String> statements = new ArrayList<>();
            // 同一表的重型变更合并提交；执行方须确认异步任务结束后再验收结构。
            if (!clauses.isEmpty()) statements.add("ALTER TABLE " + relation + " " + String.join(", ", clauses));
            statements.addAll(comments);
            if (!actual.comment().equals(Objects.requireNonNullElse(comment, ""))) {
                statements.add("ALTER TABLE " + relation + " MODIFY COMMENT " + literal(Objects.requireNonNullElse(comment, "")));
            }
            return planned(table, statements);
        } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
            return rejected(table, "DORIS_SYNCHRONIZATION_DEFINITION_UNSUPPORTED");
        }
    }

    /** 复用既有类型及表布局生成器，只覆盖已验证的默认字面量和注释。 */
    static Table desired(AbstractDatabase database, String schema, String table, String comment,
            List<ColumnDescription> columns, Map<String, String> properties) {
        if (database.getDatabaseType() != ProductTypeEnum.DORIS || columns.isEmpty() || columns.size() > 1024
                || columns.stream().map(column -> column.getFieldName().toLowerCase(Locale.ROOT)).distinct().count() != columns.size()
                || !Set.of(DorisCreateTableOptions.TABLE_PROPERTY, "doris.preferUniqueKey", "olap.preferUniqueKey",
                "olap.replicationNum").containsAll(properties.keySet())) {
            throw new IllegalArgumentException("Unsupported Doris table definition");
        }
        List<String> keys = columns.stream().filter(ColumnDescription::isPk).map(ColumnDescription::getFieldName).toList();
        Map<String, String> options = new HashMap<>(properties);
        if (!keys.isEmpty() && !options.containsKey("olap.preferUniqueKey")) {
            options.putIfAbsent("doris.preferUniqueKey", "true");
        }
        if (!options.containsKey(DorisCreateTableOptions.TABLE_PROPERTY)) {
            String distribution = keys.isEmpty() ? columns.getFirst().getFieldName() : keys.getFirst();
            int replicas = Integer.parseInt(options.getOrDefault("olap.replicationNum", "1"));
            options.put(DorisCreateTableOptions.TABLE_PROPERTY,
                    new DorisCreateTableOptions(null, "HASH", List.of(distribution), "FIXED", 10, replicas).toJson());
        }
        List<Column> complete = new ArrayList<>();
        for (ColumnDescription column : columns) {
            if (column.isAutoIncrement() || column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE) {
                throw new UnsupportedOperationException("Ordinary portable columns are required");
            }
            var metadata = column.getMetaData();
            String logicalType = switch (metadata.getType()) {
                case ColumnMetaData.TYPE_INTEGER -> metadata.getLength() <= 2 ? "tinyint"
                        : metadata.getLength() <= 4 ? "smallint" : metadata.getLength() <= 9 ? "int" : "bigint";
                case ColumnMetaData.TYPE_BIGNUMBER -> "decimal";
                case ColumnMetaData.TYPE_NUMBER -> "double";
                case ColumnMetaData.TYPE_STRING -> metadata.getLength() > 0 && metadata.getLength() <= 65_533 / 4 ? "varchar" : "text";
                case ColumnMetaData.TYPE_DATE -> "date";
                case ColumnMetaData.TYPE_TIMESTAMP -> "timestamp";
                case ColumnMetaData.TYPE_BOOLEAN -> "boolean";
                default -> throw new UnsupportedOperationException("Doris portable column type requires an explicit mapping");
            };
            var portable = DorisTableDefinition.Column.builder().name(column.getFieldName()).typeName(logicalType)
                    .length(metadata.getLength()).precision(metadata.getPrecision()).nullable(column.isNullable())
                    .comment(column.getRemarks()).build();
            String type = DorisDdlBuilder.dorisColumnType(portable);
            if (logicalType.equals("varchar")) {
                // 逻辑长度以字符计，Doris VARCHAR 以字节计；补充字符需要完整 UTF-8 四字节空间。
                type = "varchar(" + (metadata.getLength() * 4) + ")";
            }
            if (metadata.getType() == ColumnMetaData.TYPE_TIMESTAMP) {
                if (metadata.getSqlType() == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
                    throw new UnsupportedOperationException("Timezone timestamps require an explicit Doris mapping");
                }
                int scale = column.getLength() == null ? 0 : column.getLength();
                if (scale < 0 || scale > 6) throw new IllegalArgumentException("Doris timestamp scale must be 0..6");
                type = "datetime(" + scale + ")";
            }
            if (column.isPk() && Set.of("string", "float", "double", "json").contains(DorisNativeSchema.type(type))) {
                throw new UnsupportedOperationException("Doris key type requires an explicit mapping");
            }
            complete.add(new Column(column.getFieldName(), type,
                    column.isNullable() && !column.isPk(), defaultLiteral(column),
                    Objects.requireNonNullElse(column.getRemarks(), "")));
        }
        List<Column> ordered = new ArrayList<>();
        for (String key : keys) complete.stream().filter(column -> column.name().equals(key)).findFirst().ifPresent(ordered::add);
        complete.stream().filter(column -> !keys.contains(column.name())).forEach(ordered::add);
        boolean unique = !keys.isEmpty() && Boolean.parseBoolean(options.getOrDefault("olap.preferUniqueKey",
                options.getOrDefault("doris.preferUniqueKey", "false")));
        List<String> tableKeys = keys.isEmpty() ? List.of(complete.getFirst().name()) : keys;
        if (complete.stream().anyMatch(column -> tableKeys.contains(column.name())
                && Set.of("string", "float", "double", "json").contains(column.type()))) {
            throw new UnsupportedOperationException("Doris key type requires an explicit mapping");
        }
        Map<String, String> nativeProperties = unique ? Map.of("enable_unique_key_merge_on_write", "true") : Map.of();
        var shape = new Table(ordered, unique ? "UNIQUE" : "DUPLICATE", tableKeys,
                List.of(tableKeys.getFirst()), 10, nativeProperties, Objects.requireNonNullElse(comment, ""));
        return DorisTableLayouts.apply(shape, comment,
                DorisCreateTableOptions.fromJson(options.get(DorisCreateTableOptions.TABLE_PROPERTY)));
    }

    private static String defaultLiteral(ColumnDescription column) {
        String value = SchemaSynchronization.defaultExpression(ProductTypeEnum.DORIS, column);
        if (value == null) return null;
        if (value.startsWith("'") && value.endsWith("'")) {
            return literal(value.substring(1, value.length() - 1).replace("''", "'"));
        }
        if (value.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")) return literal(value);
        if (value.equals("TRUE") || value.equals("FALSE")) return literal(value.equals("TRUE") ? "1" : "0");
        throw new UnsupportedOperationException("Doris default expression requires an explicit native strategy");
    }

    private static String definition(Column column) {
        return quote(column.name()) + " " + column.type() + (column.aggregation() == null ? "" : " " + column.aggregation())
                + (column.nullable() ? " NULL" : " NOT NULL")
                + (column.defaultSql() == null ? "" : " DEFAULT " + column.defaultSql())
                + " COMMENT " + columnCommentLiteral(column.comment());
    }

    private static boolean sameDefault(String left, String right) {
        return Objects.equals(left == null || left.equals("NULL") ? null : left,
                right == null || right.equals("NULL") ? null : right);
    }

    /** 普通 ALTER 只接受可由类型本身证明的扩容；值域收窄、舍入和跨域解析须独立验数迁移。 */
    private static boolean losslessTypeChange(Column before, Column after) {
        if (before.type().equals(after.type())) return true;
        List<String> integers = List.of("tinyint", "smallint", "int", "bigint", "largeint");
        int sourceInteger = integers.indexOf(before.kind()), targetInteger = integers.indexOf(after.kind());
        if (sourceInteger >= 0 && targetInteger >= 0) return sourceInteger < targetInteger;
        if (sourceInteger >= 0 && after.kind().equals("double")) return sourceInteger <= 2;
        if (before.kind().equals("decimal") && after.kind().equals("decimal")) {
            return after.scale() >= before.scale()
                    && after.precision() - after.scale() >= before.precision() - before.scale();
        }
        if (before.kind().equals("float") && after.kind().equals("double")) return true;
        if (Set.of("char", "varchar").contains(before.kind())) {
            return after.kind().equals("string")
                    || after.kind().equals("varchar") && after.precision() >= before.precision();
        }
        if (before.kind().equals("date") && after.kind().equals("datetime")) return true;
        return before.kind().equals("datetime") && after.kind().equals("datetime") && after.scale() >= before.scale();
    }

    private static SchemaSynchronization.Plan planned(String table, List<String> statements) {
        // 每个标识符、类型与字面量均由上述原生值模型生成，不接收任意 SQL 输入。
        return new SchemaSynchronization.Plan(ProductTypeEnum.DORIS, statements, List.of());
    }

    private static SchemaSynchronization.Plan rejected(String object, String code) {
        return new SchemaSynchronization.Plan(ProductTypeEnum.DORIS, List.of(),
                List.of(new SchemaSynchronization.Diagnostic(code, object)));
    }
}
