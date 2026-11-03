package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.*;

import java.util.*;

/**
 * 不执行 SQL 的 PostgreSQL DML 限制，输入不可变。领域角色和授权由调用方负责。
 * 仅支持标量 VALUES/SET 和简单谓词；函数、别名、子查询、CTE、
 * RETURNING 和冲突子句均默认拒绝。参数保留原始顺序和数量。
 */
public final class RestrictedDmlAuthorization {

    /**
     * 初始化 {@code RestrictedDmlAuthorization}。
     */
    private RestrictedDmlAuthorization() {
    }

    public record Shape(String operation, String schemaName, String tableName, Set<String> writeColumns,
                        Set<String> filterColumns, int parameterCount) {

    }

    /** 单项授权必须覆盖整个语句。独立满足要求的授权范围按 OR 合并。 */
    public record Grant(Set<String> operations, Set<String> writeColumns, Set<String> filterColumns,
                        Map<String, String> rowScope, boolean unrestrictedRows) {

    }

    public record Authorized(String sql, Shape shape) {

    }

    private record Parsed(SQLStatement statement, SQLExprTableSource source, Shape shape,
                          List<Map<String, SQLExpr>> insertRows, SQLExpr where) {

    }

    /**
     * 解析受限 DML 语句并返回引用对象、字段及条件的结构描述。
     */
    public static Shape inspect(ProductTypeEnum product, String sql, String defaultSchema) {
        return parse(product, sql, defaultSchema).shape();
    }

    /**
     * 检查当前输入操作是否满足授权约束。
     */
    public static Authorized authorize(ProductTypeEnum product, String sql, String defaultSchema,
            String schema, String table, Set<String> physicalColumns,
            List<String> parameterValues, List<Grant> grants) {
        Parsed parsed = parse(product, sql, defaultSchema);
        Shape shape = parsed.shape();
        if (!Objects.equals(schema, shape.schemaName()) || !Objects.equals(table, shape.tableName())) {
            throw denied("SQL 目标与授权的精确模式或表不一致");
        }
        if (physicalColumns == null || !physicalColumns.containsAll(shape.writeColumns())
                || !physicalColumns.containsAll(shape.filterColumns())) {
            throw denied("SQL 包含非物理列");
        }
        if (parameterValues == null || parameterValues.size() != shape.parameterCount()) {
            throw denied("写参数数量与 SQL 不一致");
        }
        if (grants == null || grants.isEmpty() || grants.size() > 200) {
            throw denied("没有明确写授权");
        }
        List<Grant> eligible = new ArrayList<>();
        for (Grant grant : grants) {
            validateGrant(grant, physicalColumns);
            if (!grant.operations().contains(shape.operation())
                    || !grant.writeColumns().containsAll(shape.writeColumns())
                    || !grant.filterColumns().containsAll(shape.filterColumns())) {
                continue;
            }
            if ("UPDATE".equals(shape.operation())
                    && !Collections.disjoint(shape.writeColumns(), grant.rowScope().keySet())) {
                continue;
            }
            eligible.add(grant);
        }
        if (eligible.isEmpty()) {
            throw denied("没有角色同时授权本次操作、写列、过滤列及行范围");
        }
        if ("INSERT".equals(shape.operation())) {
            for (Map<String, SQLExpr> row : parsed.insertRows()) {
                boolean allowed = eligible.stream().anyMatch(grant -> grant.unrestrictedRows()
                        || matches(row, grant.rowScope(), parameterValues));
                if (!allowed) {
                    throw denied("INSERT 行不在明确写入范围内，范围列必须显式提供");
                }
            }
        } else if (eligible.stream().noneMatch(Grant::unrestrictedRows)) {
            SQLExpr combined = null;
            for (Grant grant : eligible) {
                SQLExpr scope = null;
                for (Map.Entry<String, String> entry : new TreeMap<>(grant.rowScope()).entrySet()) {
                    SQLExpr comparison = new SQLBinaryOpExpr(new SQLIdentifierExpr(quote(entry.getKey())),
                            entry.getValue() == null ? SQLBinaryOperator.Is : SQLBinaryOperator.Equality,
                            entry.getValue() == null ? new SQLNullExpr() : new SQLCharExpr(entry.getValue()),
                            DbType.postgresql);
                    scope = and(scope, comparison);
                }
                combined = combined == null ? scope : new SQLBinaryOpExpr(combined, SQLBinaryOperator.BooleanOr, scope,
                        DbType.postgresql);
            }
            SQLExpr restricted = and(parsed.where(), combined);
            if (parsed.statement() instanceof SQLUpdateStatement update) {
                update.setWhere(restricted);
            } else {
                ((SQLDeleteStatement) parsed.statement()).setWhere(restricted);
            }
        }
        parsed.source().setExpr(new SQLPropertyExpr(new SQLIdentifierExpr(quote(schema)), quote(table)));
        String rendered = SQLUtils.toSQLString(parsed.statement(), DbType.postgresql).strip();
        if (rendered.endsWith(";")) {
            rendered = rendered.substring(0, rendered.length() - 1);
        }
        return new Authorized(rendered, shape);
    }

    /**
     * 检查当前操作所需数据库权限已经具备。
     */
    public static void validateGrant(Grant grant, Set<String> physical) {
        if (grant == null || grant.operations() == null || grant.operations().isEmpty()
                || !Set.of("INSERT", "UPDATE", "DELETE").containsAll(grant.operations())
                || grant.writeColumns() == null || grant.filterColumns() == null || grant.rowScope() == null
                || physical == null || !physical.containsAll(grant.writeColumns())
                || !physical.containsAll(grant.filterColumns()) || !physical.containsAll(grant.rowScope().keySet())
                || (grant.unrestrictedRows() == !grant.rowScope().isEmpty())
                || grant.rowScope().size() > 20 || grant.rowScope().values().stream().anyMatch(v -> v != null
                && (v.length() > 1000 || v.indexOf('\'') >= 0 || v.indexOf('\\') >= 0 || v.indexOf('\0') >= 0))) {
            throw denied("写策略必须明确操作、物理列，以及等值行范围或显式全表范围");
        }
    }

    /**
     * 返回当前匹配结果。
     */
    private static boolean matches(Map<String, SQLExpr> row, Map<String, String> scope, List<String> parameters) {
        for (var entry : scope.entrySet()) {
            if (!row.containsKey(entry.getKey()) || !Objects.equals(entry.getValue(),
                    scalarText(row.get(entry.getKey()), parameters))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将当前标量值转换为文本表示。
     */
    private static String scalarText(SQLExpr value, List<String> parameters) {
        if (value instanceof SQLVariantRefExpr variable) {
            return parameters.get(variable.getIndex());
        }
        if (value instanceof SQLNullExpr) {
            return null;
        }
        if (value instanceof SQLCharExpr literal) {
            return literal.getText();
        }
        if (value instanceof SQLIntegerExpr literal) {
            return literal.getNumber().toString();
        }
        if (value instanceof SQLNumberExpr literal) {
            return literal.getNumber().toString();
        }
        if (value instanceof SQLBooleanExpr literal) {
            return Boolean.toString(literal.getValue());
        }
        throw denied("仅支持简单参数或字面值");
    }

    /**
     * 返回当前解析。
     */
    private static Parsed parse(ProductTypeEnum product, String sql, String defaultSchema) {
        if (product != ProductTypeEnum.POSTGRESQL) {
            throw denied("精确写权限仅开放已验证的 PostgreSQL 产品");
        }
        if (sql == null || sql.isBlank() || sql.length() > 20000) {
            throw denied("写 SQL 为空或超过上限");
        }
        List<SQLStatement> statements;
        try {
            statements = RestrictedSelectGuard.parseStatements(sql, DbType.postgresql);
        } catch (RuntimeException ex) {
            throw denied("写 SQL 无法按受控语法解析");
        }
        if (statements.size() != 1) {
            throw denied("授权只接受单条写 SQL");
        }
        SQLStatement statement = statements.getFirst();
        SQLTableSource target;
        SQLExpr where = null;
        String operation;
        Set<String> writes = new LinkedHashSet<>();
        Set<String> filters = new LinkedHashSet<>();
        Set<Integer> parameters = new HashSet<>();
        List<Map<String, SQLExpr>> rows = new ArrayList<>();
        if (statement instanceof PGInsertStatement insert) {
            if (insert.getWith() != null || insert.getQuery() != null || insert.getReturning() != null
                    || insert.isDefaultValues() || insert.isUpsert() || insert.isOnConflictDoNothing()
                    || nonempty(insert.getOnConflictTarget()) || nonempty(insert.getOnConflictUpdateSetItems())
                    || insert.getOnConflictConstraint() != null || insert.getOnConflictWhere() != null
                    || insert.getOnConflictUpdateWhere() != null || insert.getColumns().isEmpty()
                    || insert.getValuesList().isEmpty() || insert.getValuesList().size() > 1000) {
                throw denied("INSERT 只支持明确列清单及 VALUES，禁止冲突处理、查询和 RETURNING");
            }
            operation = "INSERT";
            target = insert.getTableSource();
            List<String> columns = new ArrayList<>();
            for (SQLExpr column : insert.getColumns()) {
                String name = column(column);
                if (!writes.add(name)) {
                    throw denied("写入列重复");
                }
                columns.add(name);
            }
            for (var values : insert.getValuesList()) {
                if (values.getValues().size() != columns.size()) {
                    throw denied("INSERT 列和值数量不一致");
                }
                Map<String, SQLExpr> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    SQLExpr value = values.getValues().get(i);
                    scalar(value, parameters);
                    row.put(columns.get(i), value);
                }
                rows.add(row);
            }
        } else if (statement instanceof PGUpdateStatement update) {
            if (update.getWith() != null || update.getFrom() != null || update.getLimit() != null
                    || update.getOrderBy() != null || nonempty(update.getReturning())
                    || nonempty(update.getPartitions()) || update.getItems().isEmpty()) {
                throw denied("UPDATE 只支持单表 SET 和 WHERE");
            }
            // 已授权的父关系不得静默更新具有独立所有权的继承表。
            update.setOnly(true);
            operation = "UPDATE";
            target = update.getTableSource();
            where = update.getWhere();
            for (var item : update.getItems()) {
                if (!writes.add(column(item.getColumn()))) {
                    throw denied("更新列重复");
                }
                scalar(item.getValue(), parameters);
            }
        } else if (statement instanceof PGDeleteStatement delete) {
            if (delete.getWith() != null || delete.getFrom() != null || delete.getUsing() != null || delete.isReturning()) {
                throw denied("DELETE 只支持单表及 WHERE");
            }
            delete.setOnly(true);
            operation = "DELETE";
            target = delete.getTableSource();
            where = delete.getWhere();
        } else {
            throw denied("只允许 INSERT、UPDATE 或 DELETE");
        }
        if (!(target instanceof SQLExprTableSource source) || source.getAlias() != null
                || (source.getHints() != null && !source.getHints().isEmpty())) {
            throw denied("不支持别名、JOIN 或表提示");
        }
        var binding = RestrictedSelectGuard.parseTableReference(product,
                SQLUtils.toSQLString(source.getExpr(), DbType.postgresql), defaultSchema);
        predicate(where, filters, parameters);
        int count = parameters.size();
        for (int i = 0; i < count; i++) {
            if (!parameters.contains(i)) {
                throw denied("参数序号不连续");
            }
        }
        return new Parsed(statement, source, new Shape(operation, binding.getSchemaName(), binding.getTableName(),
                Set.copyOf(writes), Set.copyOf(filters), count), rows, where);
    }

    /**
     * 返回谓词。
     */
    private static void predicate(SQLExpr expression, Set<String> columns, Set<Integer> parameters) {
        if (expression == null) {
            return;
        }
        if (expression instanceof SQLBinaryOpExpr binary) {
            if (!Set.of(SQLBinaryOperator.BooleanAnd, SQLBinaryOperator.BooleanOr, SQLBinaryOperator.Equality,
                    SQLBinaryOperator.NotEqual, SQLBinaryOperator.LessThanOrGreater, SQLBinaryOperator.LessThan,
                    SQLBinaryOperator.LessThanOrEqual, SQLBinaryOperator.GreaterThan,
                    SQLBinaryOperator.GreaterThanOrEqual,
                    SQLBinaryOperator.Is, SQLBinaryOperator.IsNot).contains(binary.getOperator())) {
                throw denied("过滤运算符未开放");
            }
            predicate(binary.getLeft(), columns, parameters);
            predicate(binary.getRight(), columns, parameters);
        } else if (expression instanceof SQLIdentifierExpr) {
            columns.add(column(expression));
        } else if (expression instanceof SQLInListExpr in && !in.isNot()) {
            columns.add(column(in.getExpr()));
            if (in.getTargetList().isEmpty() || in.getTargetList().size() > 1000) {
                throw denied("IN 参数超限");
            }
            for (SQLExpr value : in.getTargetList()) {
                scalar(value, parameters);
            }
        } else {
            scalar(expression, parameters);
        }
    }

    /**
     * 读取或转换当前标量值。
     */
    private static void scalar(SQLExpr value, Set<Integer> parameters) {
        if (value instanceof SQLVariantRefExpr variable) {
            if (!"?".equals(variable.getName()) || variable.getIndex() < 0 || !parameters.add(variable.getIndex())) {
                throw denied("只支持位置参数且每个位置只能出现一次");
            }
        } else if (!(value instanceof SQLCharExpr || value instanceof SQLIntegerExpr || value instanceof SQLNumberExpr
                || value instanceof SQLNullExpr || value instanceof SQLBooleanExpr)) {
            throw denied("写值仅支持参数/字面值，过滤只支持简单列比较；不允许函数或子查询");
        }
    }

    /**
     * 返回列。
     */
    private static String column(SQLExpr value) {
        if (!(value instanceof SQLIdentifierExpr identifier)) {
            throw denied("列必须使用无别名的物理列名");
        }
        String raw = identifier.getName();
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
        }
        if (!raw.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw denied("列标识符不合法");
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    /**
     * 按DM方言转义并引用标识符。
     */
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * 判断输入文本是否非空。
     */
    private static boolean nonempty(Collection<?> value) {
        return value != null && !value.isEmpty();
    }

    /**
     * 返回当前及。
     */
    private static SQLExpr and(SQLExpr left, SQLExpr right) {
        return left == null ? right : new SQLBinaryOpExpr(left, SQLBinaryOperator.BooleanAnd, right, DbType.postgresql);
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalArgumentException}。
     */
    private static IllegalArgumentException denied(String message) {
        return new IllegalArgumentException(message);
    }
}
