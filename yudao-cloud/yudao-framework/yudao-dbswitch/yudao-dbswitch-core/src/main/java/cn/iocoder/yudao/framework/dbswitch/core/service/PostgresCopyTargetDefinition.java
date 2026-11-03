package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeColumnSql;
import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.SQLDataTypeImpl;
import com.alibaba.druid.sql.ast.SQLArrayDataType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNullExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 替换 PostgreSQL 二进制 COPY 目标前，对实际 CREATE 计划进行纯检查。
 * 这里只检查有限的原生列编码，不检查逻辑或 JDBC 类型兼容性，也不判断 DDL
 * 授权。实际驱动及服务器、关系标识和原生列事实仍是运行时
 * 前提。使用现有脚本及标识解析器，不执行或保留 SQL。
 */
public final class PostgresCopyTargetDefinition {

    // 这些是 PostgresLogicalTypeMapper 输出的源整数值域。
    // 标量编码器不允许任意谓词、函数或表级 CHECK。
    private static final Set<String> INTEGER_CHECK_DOMAINS = Set.of(
            "smallint:-128:127", "smallint:0:255", "integer:0:65535",
            "integer:-8388608:8388607", "integer:0:16777215", "bigint:0:4294967295",
            "numeric:0:18446744073709551615");

    /**
     * 初始化 {@code PostgresCopyTargetDefinition}。
     */
    private PostgresCopyTargetDefinition() {
    }

    /**
     * 检查 COPY 目标的建表计划、字段投影及目标名称满足允许的 PostgreSQL 定义。
     */
    public static void validate(String schema, String table, List<String> statements, List<String> writeFields) {
        validate(schema, table, statements, writeFields, false);
    }

    /** GP 7 heap/hash COPY 共用结构解析器，但有限编码值域更窄。 */
    public static void validateGreenplum(String schema, String table, List<String> statements,
            List<String> writeFields) {
        validate(schema, table, statements, writeFields, true);
    }

    /**
     * 检查 COPY 目标的建表计划、字段投影及目标名称满足允许的 PostgreSQL 定义。
     */
    private static void validate(String schema, String table, List<String> statements, List<String> writeFields,
            boolean greenplum) {
        try {
            if (schema == null || schema.isBlank() || table == null || table.isBlank()
                    || statements == null || statements.isEmpty() || statements.size() > 500
                    || statements.stream().anyMatch(sql -> sql == null || sql.isBlank())
                    || writeFields == null || writeFields.isEmpty() || writeFields.size() > 1600
                    || writeFields.stream().anyMatch(name -> name == null || name.isBlank())) {
                throw invalid();
            }
            var retained = new LinkedHashSet<>(writeFields);
            if (retained.size() != writeFields.size()) {
                throw invalid();
            }
            String script = String.join(";\n", statements);
            var plan = greenplum ? SqlScriptPlan.parseMigrationHook(ProductTypeEnum.GREENPLUM, script)
                    : SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, script);
            // 还要检查原始完整 AST。渲染器可能省略方言属性
            // （Druid 的 PG 索引访问器省略 COLLATE）；若只检查渲染后的 SQL，
            // 会因静默丢失该属性而允许未验证的调用方计划。
            var ast = greenplum ? SupportedSqlParser.parseGreenplumHook(script)
                    : SupportedSqlParser.parse(ProductTypeEnum.POSTGRESQL, script);
            if (ast.size() != plan.steps().size()) {
                throw invalid();
            }
            Set<String> columns = null;
            Set<String> distribution = Set.of();
            var sequences = new LinkedHashMap<String, SupportedPostgresStatementParser.SequenceCreate>();
            var sequenceColumns = new LinkedHashMap<String, String>();
            Set<String> ownedSequences = new LinkedHashSet<>();
            for (var step : plan.steps()) {
                if (step.kind() != SqlStatementClassifier.Kind.DDL || step.parameterCount() != 0) {
                    throw invalid();
                }
                var statement = ast.get(step.ordinal() - 1);
                if (greenplum && !(statement instanceof SQLCreateTableStatement)
                        && !(statement instanceof SQLCommentStatement) && !(statement instanceof SQLCreateIndexStatement)) {
                    throw invalid();
                }
                if (columns == null && statement instanceof SupportedPostgresStatementParser.SequenceCreate sequence) {
                    String name = sequenceName(schema, sequence.getName());
                    // 序列选项是结构 AST 事实，不授予修改现有
                    // 序列的权限。仅允许新建、不循环且依赖范围闭合于此目标的序列。
                    if (sequence.definition == null || sequence.definition.cycle()
                            || sequences.putIfAbsent(name, sequence) != null) {
                        throw invalid();
                    }
                } else if (statement instanceof SQLCreateTableStatement create) {
                    if (columns != null) {
                        throw invalid();
                    }
                    if (greenplum) {
                        distribution = validateGreenplumTable(create);
                    }
                    columns = validateTable(schema, table, create, sequences, sequenceColumns);
                } else if (columns != null && statement instanceof SupportedPostgresStatementParser.SequenceAlter sequence) {
                    validateOwnership(schema, table, sequence, sequenceColumns, ownedSequences);
                } else if (columns != null && statement instanceof SQLCommentStatement comment) {
                    validateComment(schema, table, columns, comment);
                } else if (columns != null && statement instanceof SQLCreateIndexStatement index) {
                    validateIndex(schema, table, columns, index);
                    if (greenplum && "unique".equalsIgnoreCase(index.getType())) {
                        requireDistributionInUnique(distribution, index.getItems());
                    }
                } else {
                    throw invalid();
                }
            }
            if (columns == null || !columns.containsAll(retained)
                    || !sequences.keySet().equals(sequenceColumns.keySet())
                    || !sequences.keySet().equals(ownedSequences)) {
                throw invalid();
            }
        } catch (RuntimeException rejected) {
            // 解析器诊断和调用方 SQL 可能包含行值、URL 或其他敏感信息。
            throw invalid();
        }
    }

    /**
     * 检查 Greenplum 目标表是否满足当前路径的结构要求。
     */
    private static Set<String> validateGreenplumTable(SQLCreateTableStatement create) {
        Object attribute = create.getAttribute(SupportedPostgresStatementParser.GREENPLUM_DISTRIBUTION_KEYS);
        if (!(attribute instanceof List<?> keys) || keys.isEmpty() || keys.size() > 1024
                || keys.stream().anyMatch(value -> !(value instanceof String))) {
            throw invalid();
        }
        Set<String> distribution = keys.stream().map(String.class::cast).collect(java.util.stream.Collectors.toSet());
        int count = 0;
        for (var element : create.getTableElementList()) {
            if (element instanceof SQLColumnDefinition column) {
                if (++count > 1024 || column.getIdentity() != null) {
                    throw invalid();
                }
                var value = column.getDefaultExpr();
                if (value != null && !(value instanceof SQLIntegerExpr) && !(value instanceof com.alibaba.druid.sql.ast.expr.SQLNumberExpr)
                        && !(value instanceof SQLCharExpr) && !(value instanceof SQLNullExpr)
                        && !(value instanceof com.alibaba.druid.sql.ast.expr.SQLBooleanExpr)) {
                    throw invalid();
                }
                var type = column.getDataType();
                if (!(type instanceof SQLDataTypeImpl) || type instanceof SQLArrayDataType
                        || Boolean.TRUE.equals(type.getWithTimeZone()) || type.isWithLocalTimeZone()) {
                    throw invalid();
                }
                String name = type.getName().toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
                if (name.startsWith("pg_catalog.")) {
                    name = name.substring("pg_catalog.".length());
                }
                if (!Set.of("smallint", "int2", "integer", "int", "int4", "bigint", "int8", "numeric", "decimal",
                                "real", "float4", "double precision", "float8", "float", "boolean", "bool", "text", "bytea",
                                "date",
                                "char", "character", "bpchar", "varchar", "character varying", "time", "timestamp")
                        .contains(name)) {
                    throw invalid();
                }
                if (Set.of("numeric", "decimal").contains(name) && type.getArguments().size() == 2) {
                    requireInteger(type.getArguments().getFirst(), 1, 1000);
                    requireInteger(type.getArguments().get(1), 0,
                            Integer.parseInt(integerLiteral(type.getArguments().getFirst())));
                }
                for (var constraint : column.getConstraints()) {
                    if ((constraint instanceof SQLColumnPrimaryKey || constraint instanceof SQLColumnUniqueKey)
                            && !distribution.equals(Set.of(identifier(column.getName())))) {
                        throw invalid();
                    }
                }
            } else if (element instanceof SQLUniqueConstraint unique) {
                requireDistributionInUnique(distribution, unique.getColumns());
            }
        }
        return distribution;
    }

    /**
     * 检查唯一键包含当前分布策略要求的键列。
     */
    private static void requireDistributionInUnique(Set<String> distribution, List<SQLSelectOrderByItem> columns) {
        Set<String> names = columns.stream().map(item -> identifier(item.getExpr()))
                .collect(java.util.stream.Collectors.toSet());
        if (!names.containsAll(distribution)) {
            throw invalid();
        }
    }

    /**
     * 校验表。
     */
    private static Set<String> validateTable(String schema, String table, SQLCreateTableStatement create,
            Map<String, SupportedPostgresStatementParser.SequenceCreate> sequences,
            Map<String, String> sequenceColumns) {
        requireRelation(schema, table, create.getTableSource());
        if (create.isTemporary() || create.isExternal() || create.isIfNotExists() || create.isReplace()
                || create.isEnabled(SQLCreateTableStatement.Feature.Unlogged)
                || create.getSelect() != null || create.getLike() != null || create.getInherits() != null
                || create.getPartitioning() != null || create.getPartitionOf() != null
                || create.getLocalPartitioning() != null || !create.getPartitionColumns().isEmpty()
                || !create.getTableOptions().isEmpty() || create.getTablespace() != null) {
            throw invalid();
        }
        Set<String> columns = new LinkedHashSet<>();
        for (var element : create.getTableElementList()) {
            if (element instanceof SQLColumnDefinition column) {
                String name = identifier(column.getName());
                if (!columns.add(name) || column.getGeneratedAlwaysAs() != null || column.getAsExpr() != null
                        || column.isStored() || column.isVirtual() || column.getOnUpdate() != null
                        || column.getComment() != null) {
                    throw invalid();
                }
                requireNativeType(column.getDataType());
                validateSequenceDefault(schema, table, name, column, sequences, sequenceColumns);
                boolean checked = false;
                for (var constraint : column.getConstraints()) {
                    if (constraint instanceof SQLColumnCheck check) {
                        if (checked) {
                            throw invalid();
                        }
                        if ("text".equalsIgnoreCase(column.getDataType().getName())) {
                            validateGeneratedOctetTextCheck(name, column, check);
                        } else {
                            validateGeneratedNumericCheck(name, column.getDataType(), check);
                        }
                        checked = true;
                    } else if (!(constraint instanceof SQLNotNullConstraint) && !(constraint instanceof SQLNullConstraint)
                            && !(constraint instanceof SQLColumnPrimaryKey) && !(constraint instanceof SQLColumnUniqueKey)) {
                        throw invalid();
                    }
                }
            } else if (!(element instanceof SQLPrimaryKey) && !(element instanceof SQLUnique)) {
                throw invalid();
            }
        }
        if (columns.isEmpty() || columns.size() > 1600) {
            throw invalid();
        }
        for (var element : create.getTableElementList()) {
            if (element instanceof SQLUniqueConstraint constraint) {
                requireIndexColumns(columns, constraint.getColumns());
            }
        }
        return columns;
    }

    /**
     * 检查生成的文本字节数约束与列定义一致。
     */
    private static void validateGeneratedOctetTextCheck(String name, SQLColumnDefinition column, SQLColumnCheck check) {
        // 只准入 mapper 固定生成的同列 NULL / UTF8 / 字节上限形状；不遍历或执行未知表达式。
        if (check.getName() != null || check.getEnable() != null || check.getValidate() != null
                || check.getRely() != null || check.getEnforced() != null || check.getComment() != null
                || check.getHints() != null && !check.getHints().isEmpty()
                || !(column.getCollateExpr() instanceof SQLCharExpr collation) || !"\"C\"".equals(collation.getText())
                || !(check.getExpr() instanceof SQLBinaryOpExpr either) || either.getOperator() != SQLBinaryOperator.BooleanOr
                || !(either.getLeft() instanceof SQLBinaryOpExpr absent) || absent.getOperator() != SQLBinaryOperator.Is
                || !name.equals(identifier(absent.getLeft())) || !(absent.getRight() instanceof SQLNullExpr)
                || !(either.getRight() instanceof SQLBinaryOpExpr both) || both.getOperator() != SQLBinaryOperator.BooleanAnd
                || !(both.getLeft() instanceof SQLBinaryOpExpr encoding) || encoding.getOperator() != SQLBinaryOperator.Equality
                || !"server_encoding".equals(characterLiteral(builtinArgument(encoding.getLeft(), "current_setting")))
                || !"UTF8".equals(characterLiteral(encoding.getRight()))
                || !(both.getRight() instanceof SQLBinaryOpExpr bound) || bound.getOperator() != SQLBinaryOperator.LessThanOrEqual
                || !name.equals(identifier(builtinArgument(bound.getLeft(), "octet_length")))) {
            throw invalid();
        }
        requireInteger(bound.getRight(), 1, (1 << 30) - 1);
    }

    /**
     * 校验并解析当前内建函数允许的参数。
     */
    private static SQLExpr builtinArgument(SQLExpr expression, String name) {
        if (!(expression instanceof SQLMethodInvokeExpr function) || expression.getClass() != SQLMethodInvokeExpr.class
                || !name.equals(function.getMethodName())
                || !(function.getOwner() instanceof SQLIdentifierExpr owner) || !"pg_catalog".equals(owner.getName())
                || owner.getCollate() != null || function.getArguments().size() != 1 || function.getFrom() != null
                || function.getUsing() != null || function.getFor() != null || function.getAs() != null
                || function.getContent() != null || function.getTrimOption() != null) {
            throw invalid();
        }
        return function.getArguments().getFirst();
    }

    /**
     * SQL 值或 dollar-string 不扩大固定常量模板；只接受标准字符字面量的已解析值。
     */
    private static String characterLiteral(SQLExpr expression) {
        // SQL 值或 dollar-string 不扩大固定常量模板；只接受标准字符字面量的已解析值。
        if (expression == null || expression.getClass() != SQLCharExpr.class) {
            throw invalid();
        }
        return ((SQLCharExpr) expression).getText();
    }

    /**
     * 检查生成的数值约束与声明值域一致。
     */
    private static void validateGeneratedNumericCheck(String column, SQLDataType type, SQLColumnCheck check) {
        if (check.getName() != null || check.getEnable() != null || check.getValidate() != null
                || check.getRely() != null || check.getEnforced() != null || check.getComment() != null
                || check.getHints() != null && !check.getHints().isEmpty()) {
            throw invalid();
        }
        String name = type.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("pg_catalog.")) {
            name = name.substring("pg_catalog.".length());
        }
        name = switch (name) {
            case "int2" -> "smallint";
            case "int", "int4" -> "integer";
            case "int8" -> "bigint";
            case "decimal" -> "numeric";
            default -> name;
        };
        if (check.getExpr() instanceof SQLBetweenExpr between && !between.isNot()
                && column.equals(identifier(between.getTestExpr()))) {
            String minimum = integerLiteral(between.getBeginExpr()), maximum = integerLiteral(between.getEndExpr());
            if (!INTEGER_CHECK_DOMAINS.contains(name + ':' + minimum + ':' + maximum)) {
                throw invalid();
            }
            if ("numeric".equals(name) && (type.getArguments().size() != 2
                    || !"20".equals(integerLiteral(type.getArguments().getFirst()))
                    || !"0".equals(integerLiteral(type.getArguments().get(1))))) {
                throw invalid();
            }
            return;
        }
        // 无符号固定精度十进制数使用目标 typmod 的 precision/scale
        // 及一个生成的下界。仅允许精确的“列 >= 整数零”条件。
        if ("numeric".equals(name) && type.getArguments().size() == 2
                && check.getExpr() instanceof SQLBinaryOpExpr binary
                && binary.getOperator() == SQLBinaryOperator.GreaterThanOrEqual
                && column.equals(identifier(binary.getLeft())) && "0".equals(integerLiteral(binary.getRight()))) {
            return;
        }
        throw invalid();
    }

    /**
     * 根据当前输入构建并返回 {@code java.math.BigInteger}。
     */
    private static String integerLiteral(SQLExpr expression) {
        if (!(expression instanceof SQLIntegerExpr integer)) {
            throw invalid();
        }
        return new java.math.BigInteger(integer.getNumber().toString()).toString();
    }

    /**
     * 校验序列默认。
     */
    private static void validateSequenceDefault(String schema, String table, String name, SQLColumnDefinition column,
            Map<String, SupportedPostgresStatementParser.SequenceCreate> sequences,
            Map<String, String> sequenceColumns) {
        SQLExpr expression = column.getDefaultExpr();
        if (expression == null) {
            return;
        }
        boolean[] sequenceOperation = {false};
        expression.accept(new SQLASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLMethodInvokeExpr method) {
                String function = method.getMethodName().replace("\"", "").toLowerCase(Locale.ROOT);
                if (Set.of("nextval", "currval", "setval").contains(function)) {
                    sequenceOperation[0] = true;
                }
                return true;
            }
        });
        if (!sequenceOperation[0]) {
            return;
        }
        // 不接受嵌套调用、调用方函数所有者、隐式名称解析，或
        // 已有及共享序列。将字面量与组件实际生成器比较。
        if (column.getIdentity() != null || !(expression instanceof SQLMethodInvokeExpr next)
                || !"nextval".equalsIgnoreCase(next.getMethodName()) || next.getOwner() != null
                || next.getArguments().size() != 1 || next.getFrom() != null || next.getUsing() != null
                || next.getFor() != null || next.getAs() != null || next.getContent() != null
                || !(next.getArguments()
                .getFirst() instanceof SQLCastExpr cast) || cast.isTry() || cast.getFormat() != null
                || !(cast.getExpr() instanceof SQLCharExpr literal)
                || !(cast.getDataType() instanceof SQLDataTypeImpl castType) || !castType.getArguments().isEmpty()
                || !Set.of("regclass", "pg_catalog.regclass").contains(castType.getName().toLowerCase(Locale.ROOT))) {
            throw invalid();
        }
        String destination = NativeColumnSql.sequenceName(schema, table, name);
        if (!literal.getText()
                .equals(RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, destination))) {
            throw invalid();
        }
        var sequence = sequences.get(destination);
        String nativeType = column.getDataType().getName().toLowerCase(Locale.ROOT);
        if (nativeType.startsWith("pg_catalog.")) {
            nativeType = nativeType.substring("pg_catalog.".length());
        }
        String integerType = switch (nativeType) {
            case "smallint", "int2" -> "smallint";
            case "integer", "int", "int4" -> "integer";
            case "bigint", "int8" -> "bigint";
            default -> throw invalid();
        };
        if (sequence == null || !integerType.equals(sequence.definition.dataType())
                || sequenceColumns.putIfAbsent(destination, name) != null) {
            throw invalid();
        }
    }

    /**
     * 校验所有权。
     */
    private static void validateOwnership(String schema, String table,
            SupportedPostgresStatementParser.SequenceAlter sequence, Map<String, String> sequenceColumns,
            Set<String> ownedSequences) {
        String name = sequenceName(schema, sequence.getName());
        if (!(sequence.ownedBy instanceof SQLPropertyExpr owner)) {
            throw invalid();
        }
        requireRelation(schema, table, new SQLExprTableSource(owner.getOwner()));
        if (!physicalName(owner.getName()).equals(sequenceColumns.get(name)) || !ownedSequences.add(name)) {
            throw invalid();
        }
    }

    /**
     * 返回序列名。
     */
    private static String sequenceName(String schema, SQLExpr expression) {
        if (!(expression instanceof SQLPropertyExpr property)
                || !(property.getOwner() instanceof SQLIdentifierExpr owner)
                || !schema.equals(physicalName(owner.getName()))) {
            throw invalid();
        }
        return physicalName(property.getName());
    }

    /**
     * 校验并取得原生类型。
     */
    private static void requireNativeType(SQLDataType type) {
        if (type instanceof SQLArrayDataType array) {
            // 声明维度不约束运行时值。仅此单层、
            // 无界 CREATE 语法已得到验证；已脱离驱动的源值另行检查。
            if (!array.getArguments().isEmpty() || Boolean.TRUE.equals(array.getWithTimeZone())
                    || array.isWithLocalTimeZone() || !(array.getComponentType() instanceof SQLDataTypeImpl element)
                    || !element.getArguments().isEmpty() || Boolean.TRUE.equals(element.getWithTimeZone())
                    || element.isWithLocalTimeZone()) {
                throw invalid();
            }
            String name = element.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("pg_catalog.")) {
                name = name.substring("pg_catalog.".length());
            }
            if (!Set.of("smallint", "int2", "integer", "int", "int4", "bigint", "int8",
                    "boolean", "bool", "text", "numeric", "decimal").contains(name)) {
                throw invalid();
            }
            return;
        }
        // 记录或映射类型也可能暴露元素名称；它们不能据此获得标量编码能力。
        if (!(type instanceof SQLDataTypeImpl) || Boolean.TRUE.equals(type.getWithTimeZone())
                || type.isWithLocalTimeZone()) {
            throw invalid();
        }
        String name = type.getName().toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
        if (name.startsWith("pg_catalog.")) {
            name = name.substring("pg_catalog.".length());
        }
        var arguments = type.getArguments();
        switch (name) {
            case "smallint", "int2", "integer", "int", "int4", "bigint", "int8",
                 "smallserial", "serial2", "serial", "serial4", "bigserial", "serial8",
                 "boolean", "bool", "real", "float4", "double precision", "float8",
                 "text", "bytea", "date", "uuid", "jsonb" -> {
                if (!arguments.isEmpty()) {
                    throw invalid();
                }
            }
            case "numeric", "decimal" -> {
                if (arguments.size() > 2) {
                    throw invalid();
                }
                if (!arguments.isEmpty()) {
                    requireInteger(arguments.getFirst(), 1, 1000);
                }
                if (arguments.size() == 2) {
                    requireInteger(arguments.get(1), -1000, 1000);
                }
            }
            case "char", "character", "bpchar", "varchar", "character varying" -> {
                if (arguments.size() > 1) {
                    throw invalid();
                }
                if (!arguments.isEmpty()) {
                    requireInteger(arguments.getFirst(), 1, 10_485_760);
                }
            }
            case "time", "timestamp" -> {
                if (arguments.size() > 1) {
                    throw invalid();
                }
                if (!arguments.isEmpty()) {
                    requireInteger(arguments.getFirst(), 0, 6);
                }
            }
            case "float" -> {
                if (arguments.size() > 1) {
                    throw invalid();
                }
                if (!arguments.isEmpty()) {
                    requireInteger(arguments.getFirst(), 1, 53);
                }
            }
            default -> throw invalid();
        }
    }

    /**
     * 校验并取得整数。
     */
    private static void requireInteger(SQLExpr expression, int minimum, int maximum) {
        if (!(expression instanceof SQLIntegerExpr integer)) {
            throw invalid();
        }
        var value = new java.math.BigInteger(integer.getNumber().toString());
        if (value.compareTo(java.math.BigInteger.valueOf(minimum)) < 0
                || value.compareTo(java.math.BigInteger.valueOf(maximum)) > 0) {
            throw invalid();
        }
    }

    /**
     * 校验注释。
     */
    private static void validateComment(String schema, String table, Set<String> columns, SQLCommentStatement comment) {
        if (!(comment.getComment() instanceof SQLCharExpr) && !(comment.getComment() instanceof SQLNullExpr)) {
            throw invalid();
        }
        if (comment.getType() == SQLCommentStatement.Type.TABLE) {
            requireRelation(schema, table, comment.getOn());
        } else if (comment.getType() == SQLCommentStatement.Type.COLUMN
                && comment.getOn().getExpr() instanceof SQLPropertyExpr property) {
            requireRelation(schema, table, new SQLExprTableSource(property.getOwner()));
            if (!columns.contains(physicalName(property.getName()))) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
    }

    /**
     * 校验索引。
     */
    private static void validateIndex(String schema, String table, Set<String> columns, SQLCreateIndexStatement index) {
        requireRelation(schema, table, index.getTable());
        if (index.isConcurrently() || index.isIfNotExists() || index.getTablespace() != null
                || !index.getOptions().isEmpty() || !index.getCovering().isEmpty()
                || index.getUsing() != null && !"btree".equalsIgnoreCase(index.getUsing())
                || index.getType() != null && !"unique".equalsIgnoreCase(index.getType())) {
            throw invalid();
        }
        identifier(index.getName());
        requireIndexColumns(columns, index.getItems());
    }

    /**
     * 校验并取得索引列集合。
     */
    private static void requireIndexColumns(Set<String> columns, List<SQLSelectOrderByItem> items) {
        if (items.isEmpty() || items.size() > columns.size()) {
            throw invalid();
        }
        Set<String> names = new LinkedHashSet<>();
        for (var item : items) {
            if (item.getCollate() != null || item.getOpclass() != null) {
                throw invalid();
            }
            String name = identifier(item.getExpr());
            if (!columns.contains(name) || !names.add(name)) {
                throw invalid();
            }
        }
    }

    /**
     * 校验并取得数据库关系。
     */
    private static void requireRelation(String schema, String table, SQLTableSource source) {
        if (!(source instanceof SQLExprTableSource relation) || relation.getAlias() != null
                || !(relation.getExpr() instanceof SQLPropertyExpr property)
                || !(property.getOwner() instanceof SQLIdentifierExpr owner)
                || !schema.equals(physicalName(owner.getName())) || !table.equals(physicalName(property.getName()))) {
            throw invalid();
        }
    }

    /**
     * 检查或转换当前数据库标识符。
     */
    private static String identifier(SQLExpr expression) {
        if (!(expression instanceof SQLIdentifierExpr identifier) || identifier.getCollate() != null) {
            throw invalid();
        }
        return physicalName(identifier.getName());
    }

    /**
     * 根据元数据或映射取得对象的物理名称。
     */
    private static String physicalName(String spelling) {
        if (spelling == null || spelling.isEmpty()) {
            throw invalid();
        }
        String name;
        if (spelling.startsWith("\"") && spelling.endsWith("\"")) {
            name = spelling.substring(1, spelling.length() - 1).replace("\"\"", "\"");
        } else {
            if (!spelling.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                throw invalid();
            }
            name = spelling.toLowerCase(Locale.ROOT);
        }
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            throw invalid();
        }
        return name;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("PostgreSQL WRITE_COPY planned native target definition is not verified");
    }
}
