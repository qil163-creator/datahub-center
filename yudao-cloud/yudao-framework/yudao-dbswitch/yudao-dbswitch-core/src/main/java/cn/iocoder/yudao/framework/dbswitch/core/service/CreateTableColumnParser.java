package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumericLiteralExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLTextLiteralExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.parser.ParserException;

import java.util.*;

/**
 * 把一段建表 DDL 解析为字段定义：字段名、类型、长度/精度/小数位、可空、主键、默认值与注释。
 *
 * <p>只做文本解析，不连接数据库、不执行 SQL。输入必须恰好包含一条 CREATE TABLE，另外只允许针对同一张表的
 * {@code COMMENT ON COLUMN/TABLE}；其他语句一律拒绝。已验证方言：MySQL、MariaDB、Doris、PostgreSQL、Oracle，
 * 其余产品抛出 {@link UnsupportedOperationException}。类型名保留 DDL 原文（大写），不做跨库类型转换。
 * 线程安全：无状态静态方法。
 */
public final class CreateTableColumnParser {

    /** 输入长度上限。 */
    public static final int MAX_SQL_LENGTH = 100_000;

    /** 字段数量上限。 */
    public static final int MAX_COLUMNS = 1_000;

    private static final Map<ProductTypeEnum, DbType> DIALECTS = Map.of(
            ProductTypeEnum.MYSQL, DbType.mysql,
            ProductTypeEnum.MARIADB, DbType.mysql,
            ProductTypeEnum.DORIS, DbType.doris,
            ProductTypeEnum.POSTGRESQL, DbType.postgresql,
            ProductTypeEnum.ORACLE, DbType.oracle);

    private static final Set<String> DECIMAL_TYPES = Set.of("decimal", "numeric", "number", "dec");

    private static final Set<String> CHARACTER_TYPES = Set.of("char", "varchar", "varchar2", "nchar", "nvarchar",
            "nvarchar2", "character", "character varying", "bpchar", "binary", "varbinary");

    /**
     * 解析出的字段。
     *
     * @param name         字段名（去引号，保留原大小写）
     * @param typeName     类型名（大写，不含参数）
     * @param length       字符类型长度，其余类型为空
     * @param precision    定点数总精度，其余类型为空
     * @param scale        定点数小数位，其余类型为空
     * @param nullable     是否可空；主键与 NOT NULL 为否
     * @param primaryKey   是否主键（Doris 为 UNIQUE KEY 列）
     * @param defaultValue 默认值文本，字符串常量去引号
     * @param comment      字段注释
     */
    public record Column(String name, String typeName, Integer length, Integer precision, Integer scale,
                         boolean nullable, boolean primaryKey, String defaultValue, String comment) {
    }

    /**
     * 解析结果。
     *
     * @param schemaName   DDL 中的 schema，可为空
     * @param tableName    表名
     * @param tableComment 表注释，可为空
     * @param columns      字段，按 DDL 顺序
     */
    public record Result(String schemaName, String tableName, String tableComment, List<Column> columns) {

        /** 复制不可变集合。 */
        public Result {
            columns = List.copyOf(columns);
        }
    }

    private CreateTableColumnParser() {
    }

    /**
     * 当前产品的建表语句是否已验证可解析。
     */
    public static boolean supports(ProductTypeEnum product) {
        return product != null && DIALECTS.containsKey(product);
    }

    /**
     * 解析建表语句。
     *
     * @param product 建表语句所属方言
     * @param sql     DDL 文本
     * @return 解析结果
     * @throws UnsupportedOperationException 方言未验证
     * @throws IllegalArgumentException      语句为空、过长、无法解析，或包含建表与注释以外的语句
     */
    public static Result parse(ProductTypeEnum product, String sql) {
        if (!supports(product)) {
            throw new UnsupportedOperationException("尚未验证该数据库方言的建表语句解析：" + product);
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("请输入建表语句");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new IllegalArgumentException("建表语句超过 " + MAX_SQL_LENGTH + " 个字符");
        }
        DbType dialect = DIALECTS.get(product);
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(sql, dialect);
        } catch (ParserException | IllegalStateException | IllegalArgumentException failed) {
            throw new IllegalArgumentException("无法解析：" + abbreviate(failed.getMessage()));
        }
        SQLCreateTableStatement create = null;
        List<SQLCommentStatement> comments = new ArrayList<>();
        for (SQLStatement statement : statements) {
            if (statement instanceof SQLCreateTableStatement table) {
                if (create != null) {
                    throw new IllegalArgumentException("一次只能解析一条 CREATE TABLE 语句");
                }
                create = table;
            } else if (statement instanceof SQLCommentStatement comment) {
                comments.add(comment);
            } else {
                throw new IllegalArgumentException("只允许 CREATE TABLE 与 COMMENT ON 语句");
            }
        }
        if (create == null) {
            throw new IllegalArgumentException("未找到 CREATE TABLE 语句");
        }
        String tableName = name(create.getTableName());
        String schemaName = create.getSchema() == null ? null : name(create.getSchema());

        Set<String> primaryKeys = new HashSet<>();
        create.getPrimaryKeyNames().forEach(key -> primaryKeys.add(key(name(key))));
        if (create.getUnique() != null && product == ProductTypeEnum.DORIS) {
            create.getUnique().getColumns().forEach(item -> primaryKeys.add(key(exprName(item.getExpr()))));
        }
        List<SQLColumnDefinition> definitions = new ArrayList<>();
        for (SQLTableElement element : create.getTableElementList()) {
            if (element instanceof SQLColumnDefinition column) {
                definitions.add(column);
            } else if (element instanceof SQLPrimaryKey primaryKey) {
                primaryKey.getColumns().forEach(item -> primaryKeys.add(key(exprName(item.getExpr()))));
            }
        }
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("建表语句中没有字段定义");
        }
        if (definitions.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("字段数量超过 " + MAX_COLUMNS);
        }

        Map<String, String> columnComments = new HashMap<>();
        String tableComment = text(create.getComment());
        for (SQLCommentStatement comment : comments) {
            String commentText = text(comment.getComment());
            SQLExpr target = comment.getOn() == null ? null : comment.getOn().getExpr();
            if (comment.getType() == SQLCommentStatement.Type.TABLE) {
                requireSameTable(target, tableName);
                tableComment = commentText;
            } else if (comment.getType() == SQLCommentStatement.Type.COLUMN
                    && target instanceof SQLPropertyExpr property) {
                requireSameTable(property.getOwner(), tableName);
                columnComments.put(key(name(property.getName())), commentText);
            } else {
                throw new IllegalArgumentException("只支持 COMMENT ON TABLE 与 COMMENT ON COLUMN");
            }
        }

        List<Column> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SQLColumnDefinition definition : definitions) {
            String columnName = name(definition.getName().getSimpleName());
            if (!seen.add(key(columnName))) {
                throw new IllegalArgumentException("字段 " + columnName + " 重复");
            }
            boolean primaryKey = primaryKeys.contains(key(columnName))
                    || definition.getConstraints().stream().anyMatch(SQLColumnPrimaryKey.class::isInstance);
            boolean notNull = definition.getConstraints().stream().anyMatch(SQLNotNullConstraint.class::isInstance);
            SQLDataType dataType = definition.getDataType();
            if (dataType == null || dataType.getName() == null) {
                throw new IllegalArgumentException("字段 " + columnName + " 缺少数据类型");
            }
            String typeName = dataType.getName().trim();
            String base = typeName.toLowerCase(Locale.ROOT);
            List<Integer> arguments = integers(dataType.getArguments());
            Integer length = null;
            Integer precision = null;
            Integer scale = null;
            if (DECIMAL_TYPES.contains(base) && !arguments.isEmpty()) {
                precision = arguments.get(0);
                scale = arguments.size() > 1 ? arguments.get(1) : 0;
            } else if (CHARACTER_TYPES.contains(base) && !arguments.isEmpty()) {
                length = arguments.get(0);
            }
            String comment = text(definition.getComment());
            String fromCommentOn = columnComments.get(key(columnName));
            if (fromCommentOn != null) {
                comment = fromCommentOn;
            }
            columns.add(new Column(columnName, typeName.toUpperCase(Locale.ROOT), length, precision, scale,
                    !primaryKey && !notNull, primaryKey, defaultValue(definition.getDefaultExpr(), dialect),
                    comment));
        }
        return new Result(schemaName, tableName, tableComment, columns);
    }

    private static void requireSameTable(SQLExpr owner, String tableName) {
        String ownerName = exprName(owner);
        if (!key(ownerName).equals(key(tableName))) {
            throw new IllegalArgumentException("注释语句针对的表与建表语句不一致：" + ownerName);
        }
    }

    /** 取表达式最后一段名称：a.b.c → c。 */
    private static String exprName(SQLExpr expr) {
        if (expr instanceof SQLPropertyExpr property) {
            return name(property.getName());
        }
        if (expr instanceof SQLIdentifierExpr identifier) {
            return name(identifier.getName());
        }
        if (expr == null) {
            throw new IllegalArgumentException("缺少对象名称");
        }
        return name(expr.toString());
    }

    private static String name(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("对象名称不能为空");
        }
        String normalized = SQLUtils.normalize(raw.trim());
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("对象名称不能为空");
        }
        return normalized;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static List<Integer> integers(List<SQLExpr> arguments) {
        List<Integer> result = new ArrayList<>();
        if (arguments == null) {
            return result;
        }
        for (SQLExpr argument : arguments) {
            if (argument instanceof SQLIntegerExpr integer) {
                result.add(integer.getNumber().intValue());
            } else if (argument instanceof SQLNumericLiteralExpr numeric) {
                result.add(numeric.getNumber().intValue());
            } else {
                break;
            }
        }
        return result;
    }

    private static String text(SQLExpr expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof SQLTextLiteralExpr literal) {
            return literal.getText();
        }
        return SQLUtils.normalize(expr.toString());
    }

    private static String defaultValue(SQLExpr expr, DbType dialect) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof SQLTextLiteralExpr literal) {
            return literal.getText();
        }
        if (expr instanceof SQLIdentifierExpr identifier) {
            return SQLUtils.normalize(identifier.getName());
        }
        return SQLUtils.toSQLString(expr, dialect);
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "语法错误";
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }
}
