package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

/**
 * 现有 CREATE 生成器中的同产品原生列分支，仅支持已验证的内建类型。
 * 跨产品列由 {@link cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMappings} 规划。
 */
public final class NativeColumnSql {

    /**
     * 初始化 {@code NativeColumnSql}。
     */
    private NativeColumnSql() {
    }

    /**
     * 返回定义。
     */
    public static String definition(ProductTypeEnum target, ColumnDescription column) {
        return definition(target, column, null, null);
    }

    /** 限定后的目标名称仅由考虑依赖的 CREATE 语句列表提供。 */
    public static String definition(ProductTypeEnum target, ColumnDescription column, String schema, String table) {
        return definition(target, column, schema, table, null);
    }

    /** 依赖所有者可以在创建所属表前，预留精确的标识生成序列名称。 */
    public static String definition(ProductTypeEnum target, ColumnDescription column, String schema, String table,
            TableReadPlan.Relation identitySequenceName) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (identitySequenceName != null && (target != ProductTypeEnum.POSTGRESQL || facts.identity()
                .state() != MetadataValue.State.VALUE
                || facts.sequence().value() == null || !java.util.Objects.equals(schema,
                identitySequenceName.schema()))) {
            throw new IllegalArgumentException(
                    "Named identity sequence requires matching PostgreSQL dependency evidence");
        }
        if (!facts.provenance().nativeProduct(target)) {
            throw new UnsupportedOperationException("Native column provenance does not match target");
        }
        requireKnown(facts.nativeType(), "native type");
        requireKnown(facts.nullable(), "nullable");
        requireKnown(facts.defaultExpression(), "default");
        requireKnown(facts.identity(), "identity");
        requireKnown(facts.generatedExpression(), "generated expression");
        requireKnown(facts.charset(), "charset");
        requireKnown(facts.collation(), "collation");
        String type = facts.nativeType().value();
        if (type == null || facts.nullable().value() == null || !supportedType(target, type)) {
            throw new UnsupportedOperationException("Native column type is not verified");
        }
        if (target == ProductTypeEnum.CLICKHOUSE && (facts.defaultExpression().state() != MetadataValue.State.ABSENT
                || facts.identity().state() != MetadataValue.State.ABSENT || column.isAutoIncrement()
                || facts.nullable().value() != type.startsWith("Nullable(")
                || column.isNullable() != facts.nullable().value())) {
            throw new UnsupportedOperationException(
                    "ClickHouse CREATE requires ordinary columns with consistent native nullability");
        }
        if (facts.generatedExpression().state() == MetadataValue.State.VALUE) {
            throw new UnsupportedOperationException("Generated columns require dependency-aware reconstruction");
        }
        StringBuilder sql = new StringBuilder(
                RestrictedSelectGuard.quotePhysicalIdentifier(target, column.getFieldName()))
                .append(' ').append(type);
        if (facts.charset().state() == MetadataValue.State.VALUE) {
            if ((target != ProductTypeEnum.MYSQL && target != ProductTypeEnum.TIDB) || !facts.charset().value()
                    .matches("[A-Za-z0-9_]+")) {
                throw new UnsupportedOperationException("Native character set is not verified");
            }
            sql.append(" CHARACTER SET ").append(facts.charset().value());
        }
        if (facts.collation().state() == MetadataValue.State.VALUE) {
            String collation = facts.collation().value();
            if (target == ProductTypeEnum.MYSQL || target == ProductTypeEnum.TIDB) {
                if (!collation.matches("[A-Za-z0-9_]+")) {
                    throw new UnsupportedOperationException("Collation is not verified");
                }
            } else if (!collation.matches(
                    "(?:[A-Za-z_][A-Za-z0-9_]*|\"(?:[^\"]|\"\")+\")(?:\\.(?:[A-Za-z_][A-Za-z0-9_]*|\"(?:[^\"]|\"\")+\"))?")) {
                throw new UnsupportedOperationException("Collation is not verified");
            }
            sql.append(" COLLATE ").append(collation);
        }
        if (facts.identity().state() == MetadataValue.State.VALUE) {
            String identity = facts.identity().value();
            if (target == ProductTypeEnum.POSTGRESQL && facts.sequence().state() == MetadataValue.State.UNSUPPORTED) {
                throw new UnsupportedOperationException(
                        "Shared identity sequences require a multi-table dependency plan");
            }
            var sequence = facts.sequence().value();
            if (target == ProductTypeEnum.POSTGRESQL && sequence != null) {
                String expected = "GENERATED " + (sequence.kind() == ColumnSequenceMetadata.Kind.IDENTITY_ALWAYS ? "ALWAYS" : "BY DEFAULT")
                        + " AS IDENTITY (" + sequence.optionsSql() + ")";
                if (sequence.kind() == ColumnSequenceMetadata.Kind.OWNED_DEFAULT || !type.equals(sequence.dataType())
                        || !identity.equals(expected)) {
                    throw new IllegalArgumentException("Identity column and sequence catalog observations disagree");
                }
            }
            if ((target == ProductTypeEnum.MYSQL || target == ProductTypeEnum.TIDB) && "AUTO_INCREMENT".equals(
                    identity)) {
                sql.append(" AUTO_INCREMENT");
            } else if (target == ProductTypeEnum.SQLSERVER) {
                var match = java.util.regex.Pattern.compile(
                        "IDENTITY\\((-?[0-9]{1,38}),(-?[0-9]{1,38})\\)( NOT FOR REPLICATION)?").matcher(identity);
                if (!match.matches()) {
                    throw new UnsupportedOperationException("SQL Server identity definition is unverified");
                }
                var seed = new java.math.BigInteger(match.group(1));
                var increment = new java.math.BigInteger(match.group(2));
                var definition = new SqlServerIdentityMetadata(type, seed, increment, seed, false,
                        match.group(3) != null);
                sql.append(' ').append(definition.definition());
            } else if (target == ProductTypeEnum.POSTGRESQL && identity.matches(
                    "GENERATED (ALWAYS|BY DEFAULT) AS IDENTITY \\(START WITH -?\\d+ INCREMENT BY -?\\d+ MINVALUE -?\\d+ MAXVALUE -?\\d+ CACHE \\d+ (NO CYCLE|CYCLE)\\)")) {
                sql.append(' ')
                        .append(identitySequenceName == null ? identity : identity.substring(0, identity.length() - 1)
                                + " SEQUENCE NAME " + RestrictedSelectGuard.qualifiedTableName(target,
                                identitySequenceName.schema(), identitySequenceName.table()) + ")");
            } else {
                throw new UnsupportedOperationException("Identity strategy is not verified");
            }
        }
        if (facts.defaultExpression().state() == MetadataValue.State.VALUE) {
            String expression = facts.defaultExpression().value();
            if (target == ProductTypeEnum.SQLSERVER && !sqlServerDefault(expression)) {
                throw new UnsupportedOperationException(
                        "SQL Server default requires a verified literal or builtin expression");
            }
            if (facts.identity().state() == MetadataValue.State.VALUE) {
                throw new UnsupportedOperationException("Identity cannot carry a separate default");
            }
            if (expression.toLowerCase(java.util.Locale.ROOT).contains("nextval(")) {
                var sequence = facts.sequence().value();
                if (target != ProductTypeEnum.POSTGRESQL || schema == null || table == null || sequence == null
                        || sequence.kind() != ColumnSequenceMetadata.Kind.OWNED_DEFAULT
                        || !expression.matches("nextval\\('(?:[^']|'')+'::regclass\\)")) {
                    throw new UnsupportedOperationException(
                            "Sequence defaults require an explicit sequence dependency plan");
                }
                String destination = RestrictedSelectGuard.qualifiedTableName(target, schema,
                        sequenceName(schema, table, column.getFieldName()));
                expression = "nextval('" + destination.replace("'", "''") + "'::regclass)";
            }
            sql.append(" DEFAULT ").append(expression);
        }
        if (!facts.nullable().value()) {
            sql.append(" NOT NULL");
        }
        return sql.toString();
    }

    /**
     * 返回序列名。
     */
    public static String sequenceName(String schema, String table, String column) {
        return "dbswitch_seq_" + cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan
                .digest(schema.length() + ":" + schema + table.length() + ":" + table + column.length() + ":" + column)
                .substring(0, 32);
    }

    /**
     * 判断当前类型是否属于此路径已声明的支持范围。
     */
    public static boolean supportedType(ProductTypeEnum target, String type) {
        if (target == ProductTypeEnum.CLICKHOUSE) {
            return clickHouseType(type);
        }
        if (target == ProductTypeEnum.SQLSERVER) {
            return sqlServerType(type);
        }
        if (target == ProductTypeEnum.POSTGRESQL) {
            return type.matches(
                    "(?i)(?:smallint|integer|bigint|boolean|text|bytea|jsonb?|uuid|date|real|double precision"
                            + "|(?:numeric|decimal)(?:\\(\\d+(?:,\\s*-?\\d+)?\\))?"
                            + "|(?:character varying|varchar|character|char)(?:\\(\\d+\\))?"
                            + "|(?:timestamp|time)(?:\\(\\d+\\))?(?: with(?:out)? time zone)?)(?:\\[\\])*");
        }
        return (target == ProductTypeEnum.MYSQL || target == ProductTypeEnum.TIDB) && type.matches(
                "(?i)(?:(?:tinyint|smallint|mediumint|int|bigint)(?:\\(\\d+\\))?(?: unsigned)?"
                        + "|(?:decimal|numeric)\\(\\d+,\\s*\\d+\\)(?: unsigned)?|(?:char|varchar|binary|varbinary)\\(\\d+\\)"
                        + "|(?:tiny|medium|long)?(?:text|blob)|json|date|(?:datetime|timestamp|time)(?:\\(\\d+\\))?|float|double|boolean|bit\\(\\d+\\))");
    }

    /**
     * 要求当前元数据事实已经得到明确观测。
     */
    private static void requireKnown(MetadataValue<?> value, String property) {
        if (!value.known()) {
            throw new IllegalArgumentException("Incomplete column metadata: " + property);
        }
    }

    /**
     * 根据列属性生成 SQL Server 类型声明。
     */
    private static boolean sqlServerType(String type) {
        if (type.matches("tinyint|smallint|int|bigint|bit|real|money|smallmoney|date|datetime|smalldatetime")) {
            return true;
        }
        var decimal = java.util.regex.Pattern.compile("(?:decimal|numeric)\\(([0-9]+),([0-9]+)\\)").matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1)), scale = Integer.parseInt(decimal.group(2));
            return precision >= 1 && precision <= 38 && scale <= precision;
        }
        var sized = java.util.regex.Pattern.compile("(n?char|n?varchar|binary|varbinary)\\((max|[0-9]+)\\)")
                .matcher(type);
        if (sized.matches()) {
            String base = sized.group(1), size = sized.group(2);
            if ("max".equals(size)) {
                return base.equals("varchar") || base.equals("nvarchar") || base.equals("varbinary");
            }
            long length = Long.parseLong(size);
            return length >= 1 && length <= (base.startsWith("n") ? 4000 : 8000);
        }
        if (type.matches("(?:datetime2|time)\\([0-7]\\)")) {
            return true;
        }
        return type.equals("float(24)") || type.equals("float(53)");
    }

    /** 固定的 25.8 普通标量语法；不接受时区字面量、表达式或嵌套包装。 */
    private static boolean clickHouseType(String type) {
        if (type == null || type.length() > 64) {
            return false;
        }
        if (type.startsWith("Nullable(") && type.endsWith(")")) {
            type = type.substring(9, type.length() - 1);
        }
        if (type.matches("(?:U?Int(?:8|16|32|64)|Bool|String|UUID|Date|Date32|DateTime|Float32|Float64)")) {
            return true;
        }
        if (type.matches("DateTime64\\([0-9]\\)")) {
            return true;
        }
        var decimal = java.util.regex.Pattern.compile("Decimal\\(([0-9]{1,2}),\\s*([0-9]{1,2})\\)").matcher(type);
        if (!decimal.matches()) {
            return false;
        }
        int precision = Integer.parseInt(decimal.group(1)), scale = Integer.parseInt(decimal.group(2));
        return precision >= 1 && precision <= 76 && scale <= precision;
    }

    /** 目录文本在通过此有限表达式子集证明可安全重放前，一直作为数据处理。 */
    private static boolean sqlServerDefault(String expression) {
        if (expression == null || expression.length() > 4000) {
            return false;
        }
        String value = expression.trim();
        for (int depth = 0; value.startsWith("(") && value.endsWith(")"); depth++) {
            if (depth >= 32) {
                return false;
            }
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.matches(
                "(?is)(?:NULL|[-+]?[0-9]+(?:\\.[0-9]+)?|0x[0-9a-f]*|N?'(?:[^']|'')*'|CURRENT_TIMESTAMP|(?:getdate|sysdatetime|sysutcdatetime)\\(\\))");
    }
}
