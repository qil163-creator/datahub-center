package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 解析单条受限 DML，供业务在授权对象上执行修复。
 * <p>
 * 当前仅验证 PostgreSQL / MySQL / MariaDB 的解析路径。本类不执行 SQL，不替代对象授权、
 * 参数绑定或事务行数回滚。
 */
public final class RestrictedDmlGuard {

    /**
     * 初始化 {@code RestrictedDmlGuard}。
     */
    private RestrictedDmlGuard() {
    }

    /**
     * 判断是否支持事务行上限。
     */
    public static boolean supportsTransactionalRowLimit(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL
                || product == ProductTypeEnum.MYSQL
                || product == ProductTypeEnum.MARIADB;
    }

    /**
     * 返回当前解析。
     */
    public static RestrictedDmlPlan parse(ProductTypeEnum product, String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 100_000) {
            throw new IllegalArgumentException("修复 SQL 为空或超过长度限制");
        }
        DbType dialect = dialect(product);
        List<SQLStatement> statements = SQLUtils.parseStatements(sql, dialect);
        if (statements.size() != 1) {
            throw new IllegalArgumentException("修复 SQL 仅允许单条 UPDATE/DELETE/INSERT");
        }
        SQLStatement statement = statements.get(0);
        RestrictedDmlPlan.Kind kind = statementKind(statement);
        Set<String> tables = collectTables(statement, dialect);
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("修复 SQL 无法解析目标表");
        }
        if (tables.size() != 1) {
            throw new IllegalArgumentException("修复 DML 只允许操作一张已授权表");
        }
        String normalized = SQLUtils.toSQLString(statement, dialect).strip();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return new RestrictedDmlPlan(kind, tables, normalized);
    }

    /**
     * 根据解析结果确定当前 SQL 语句种类。
     */
    private static RestrictedDmlPlan.Kind statementKind(SQLStatement statement) {
        RestrictedDmlPlan.Kind kind;
        if (statement instanceof SQLUpdateStatement) {
            kind = RestrictedDmlPlan.Kind.UPDATE;
        } else if (statement instanceof SQLDeleteStatement) {
            kind = RestrictedDmlPlan.Kind.DELETE;
        } else if (statement instanceof SQLInsertStatement insert) {
            if (insert.getQuery() != null) {
                throw new IllegalArgumentException("修复 SQL 不允许 INSERT ... SELECT");
            }
            kind = RestrictedDmlPlan.Kind.INSERT;
        } else {
            throw new IllegalArgumentException("修复 SQL 仅允许 UPDATE/DELETE/INSERT");
        }
        return kind;
    }

    /**
     * 检查当前 SQL 引用的表位于调用方授权范围内。
     */
    public static void assertAuthorizedTable(RestrictedDmlPlan plan, String authorizedTable) {
        String expected = simpleName(authorizedTable);
        if (expected == null || expected.isBlank()) {
            throw new IllegalArgumentException("修复目标表未授权");
        }
        String actual = plan.requireSingleTable();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("修复 SQL 目标表与授权对象不一致");
        }
    }

    /**
     * 将每个物理表引用绑定到精确元数据标识。schema/table 参数
     * 是原始元数据名称，不是 SQL 片段。PostgreSQL 带引号的大小写保留；未加引号的名称
     * 折叠为小写。MySQL 系名称精确比较，绝不推断服务器的大小写折叠设置。
     * 返回的 SQL 为所有引用添加完整限定名，包括原本未限定的子查询，
     * 避免 search_path、临时表或默认 schema 变化导致操作重定向。
     * 现有 parse/assertAuthorizedTable 调用方保留其旧契约。
     */
    public static RestrictedDmlPlan bindAuthorizedTable(AbstractDatabase database, String sql,
            String schema, String table) {
        if (schema == null || schema.isBlank() || table == null || table.isBlank()) {
            throw new IllegalArgumentException("修复必须绑定明确的物理 schema 和表");
        }
        ProductTypeEnum product = database.getDatabaseType();
        if (sql == null || sql.isBlank() || sql.length() > 100_000) {
            throw new IllegalArgumentException("修复 SQL 为空或超过长度限制");
        }
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(sql, dialect(product));
        } catch (com.alibaba.druid.sql.parser.ParserException ex) {
            throw new IllegalArgumentException("修复 SQL 无法解析", ex);
        }
        if (statements.size() != 1) {
            throw new IllegalArgumentException("修复 SQL 仅允许单条 DML");
        }
        SQLStatement statement = statements.getFirst();
        RestrictedDmlPlan.Kind kind = statementKind(statement);
        java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger();
        statement.accept(new SQLASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLJoinTableSource source) {
                throw new IllegalArgumentException("修复 SQL 不允许 JOIN");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLExprTableSource source) {
                references.incrementAndGet();
                SQLExpr reference = source.getExpr();
                String actualSchema = schema;
                String actualTable;
                if (reference instanceof SQLIdentifierExpr identifier) {
                    actualTable = physicalIdentifier(identifier.getName(), product);
                } else if (reference instanceof SQLPropertyExpr qualified
                        && qualified.getOwner() instanceof SQLIdentifierExpr owner) {
                    actualSchema = physicalIdentifier(owner.getName(), product);
                    actualTable = physicalIdentifier(qualified.getName(), product);
                } else {
                    throw new IllegalArgumentException("修复表引用必须是可确定的 schema.table");
                }
                if (!schema.equals(actualSchema) || !table.equals(actualTable)) {
                    throw new IllegalArgumentException("修复 SQL 的物理 schema 或表与批准对象不一致");
                }
                source.setExpr(new SQLPropertyExpr(new SQLIdentifierExpr(database.quoteIdentifier(schema)),
                        database.quoteIdentifier(table)));
                return false;
            }
        });
        if (references.get() == 0) {
            throw new IllegalArgumentException("修复 SQL 未绑定任何目标表");
        }
        String normalized = SQLUtils.toSQLString(statement, dialect(product)).strip();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return new RestrictedDmlPlan(kind, Set.of(table), normalized);
    }

    /**
     * 检查并生成物理数据库对象的标识符。
     */
    private static String physicalIdentifier(String name, ProductTypeEnum product) {
        if (name.length() >= 2 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
            return name.substring(1, name.length() - 1).replace("\"\"", "\"");
        }
        if (name.length() >= 2 && name.charAt(0) == '`' && name.charAt(name.length() - 1) == '`') {
            if (product == ProductTypeEnum.POSTGRESQL) {
                throw new IllegalArgumentException("PostgreSQL 标识符引号无效");
            }
            return name.substring(1, name.length() - 1).replace("``", "`");
        }
        return product == ProductTypeEnum.POSTGRESQL ? name.toLowerCase(Locale.ROOT) : name;
    }

    /**
     * 收集当前 SQL 语法树引用的表集合。
     */
    private static Set<String> collectTables(SQLStatement statement, DbType dialect) {
        statement.accept(new SQLASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLJoinTableSource x) {
                throw new IllegalArgumentException("修复 SQL 不允许 JOIN");
            }
        });
        SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(dialect);
        statement.accept(visitor);
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        if (visitor.getTables() != null) {
            for (TableStat.Name name : visitor.getTables().keySet()) {
                tables.add(simpleName(name.getName()));
            }
        }
        tables.removeIf(item -> item == null || item.isBlank());
        return tables;
    }

    /**
     * 提取当前对象不包含限定前缀的名称。
     */
    static String simpleName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("`") && trimmed.endsWith("`"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0) {
            trimmed = trimmed.substring(dot + 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * 解析当前产品对应的数据库方言。
     */
    private static DbType dialect(ProductTypeEnum product) {
        if (product == ProductTypeEnum.POSTGRESQL) {
            return DbType.postgresql;
        }
        if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.MARIADB) {
            return DbType.mysql;
        }
        throw new UnsupportedOperationException("数据库产品尚未验证受限修复 DML：" + product);
    }
}
