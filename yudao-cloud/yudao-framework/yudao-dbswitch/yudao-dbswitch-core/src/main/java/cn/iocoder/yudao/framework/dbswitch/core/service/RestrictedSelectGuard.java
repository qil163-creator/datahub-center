package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLExprImpl;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLLiteralExpr;
import com.alibaba.druid.sql.ast.expr.SQLNotExpr;
import com.alibaba.druid.sql.ast.expr.SQLNullExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectOrderByItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oscar.ast.stmt.OscarSelectQueryBlock;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.NotAllowCommentException;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGExprParser;
import com.alibaba.druid.sql.dialect.clickhouse.ast.CKSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlLexer;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlExprParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 解析单条受限 SELECT，供门户预览/即席查询在授权前判定形状。
 * <p>
 * 受限投影只启用已逐产品真实库验证的方言，不能按协议族外推。
 * 本类不执行 SQL，不替代对象授权、
 * 参数绑定、行级策略或脱敏。无法证明安全的 JOIN、CTE、子查询、UNION、写语句一律拒绝。
 */
public final class RestrictedSelectGuard {

    /** 受限投影当前支持的过滤运算符；未列出的运算符一律拒绝，不做部分检查。 */
    private static final Set<SQLBinaryOperator> SUPPORTED_OPERATORS = EnumSet.of(
            SQLBinaryOperator.Equality, SQLBinaryOperator.LessThan, SQLBinaryOperator.LessThanOrEqual,
            SQLBinaryOperator.GreaterThan, SQLBinaryOperator.GreaterThanOrEqual,
            SQLBinaryOperator.NotEqual, SQLBinaryOperator.LessThanOrGreater,
            SQLBinaryOperator.Is, SQLBinaryOperator.IsNot, SQLBinaryOperator.Like, SQLBinaryOperator.NotLike,
            SQLBinaryOperator.BooleanAnd, SQLBinaryOperator.BooleanOr);

    /**
     * 初始化 {@code RestrictedSelectGuard}。
     */
    private RestrictedSelectGuard() {
    }

    /**
     * 当前精确关系绑定与行条件合并的独立产品能力；不从普通 SELECT 方言支持推断。
     * openGauss 7.0 原生探针已证明普通物理名称、精确列与分组条件契约，限普通标识符。
     * TiDB 仍须原生 catalog 规范化大小写别名，不能把用户拼写直接当作策略键。
     */
    public static boolean supportsExactBinding(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.MYSQL
                || product == ProductTypeEnum.MARIADB || product == ProductTypeEnum.OPENGAUSS;
    }

    /**
     * 返回当前解析。
     */
    public static RestrictedSelectPlan parse(ProductTypeEnum product, String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 20_000) {
            throw new IllegalArgumentException("查询 SQL 为空或超过长度限制");
        }
        DbType dialect = dialect(product);
        List<SQLStatement> statements;
        try {
            statements = parseStatements(sql, dialect);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("查询 SQL 无法解析");
        }
        if (statements.size() != 1) {
            throw new IllegalArgumentException("仅允许单条 SELECT");
        }
        SQLStatement statement = statements.get(0);
        if (!(statement instanceof SQLSelectStatement selectStatement)) {
            throw new IllegalArgumentException("仅允许 SELECT，不允许写语句或 DDL");
        }
        rejectUnsafeNodes(statement);
        SQLSelect select = selectStatement.getSelect();
        if (select == null) {
            throw new IllegalArgumentException("无法解析 SELECT");
        }
        if (select.getWithSubQuery() != null) {
            throw new IllegalArgumentException("不允许 CTE/WITH");
        }
        SQLSelectQuery query = select.getQuery();
        if (query instanceof SQLUnionQuery) {
            throw new IllegalArgumentException("不允许 UNION");
        }
        if (!(query instanceof SQLSelectQueryBlock block)) {
            throw new IllegalArgumentException("仅允许单层 SELECT");
        }
        if (block.getInto() != null) {
            throw new IllegalArgumentException("不允许 SELECT INTO");
        }
        if (block.isForUpdate()) {
            throw new IllegalArgumentException("不允许 FOR UPDATE");
        }
        rejectUnsupportedQueryClauses(block);
        if (!(block.getFrom() instanceof SQLExprTableSource tableSource)) {
            throw new IllegalArgumentException("仅允许单表查询");
        }
        requireSupportedTableName(tableSource);
        rejectUnsafeTableSource(tableSource);
        String tableName = physicalName(product, rawTableName(tableSource));
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("无法解析目标表");
        }
        String schemaName = physicalName(product, rawSchemaName(tableSource));
        boolean wildcard = false;
        Set<String> columns = new LinkedHashSet<>();
        List<SQLSelectItem> items = block.getSelectList();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("SELECT 列表为空");
        }
        for (SQLSelectItem item : items) {
            SQLExpr expr = item.getExpr();
            if (expr instanceof SQLAllColumnExpr) {
                wildcard = true;
                continue;
            }
            if (expr instanceof SQLPropertyExpr property && "*".equals(property.getName())) {
                wildcard = true;
                continue;
            }
            RestrictedSelectColumn reference = columnReference(expr);
            String column = reference == null ? null : physicalName(product,
                    reference.name(), reference.quoted());
            if (column == null) {
                throw new IllegalArgumentException("仅允许列引用或 *，不允许表达式/函数");
            }
            columns.add(column);
        }
        if (wildcard && !columns.isEmpty()) {
            throw new IllegalArgumentException("不允许同时使用 * 与列清单");
        }
        String normalized = SQLUtils.toSQLString(statement, dialect).strip();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return new RestrictedSelectPlan(schemaName, tableName, wildcard, columns, normalized);
    }

    /**
     * 为授权与执行固定同一个物理对象。未限定的表使用数据源配置的物理 schema，
     * 不依赖连接池 search_path；未知 schema 或未验证的产品不得猜测。
     * 此接口只做解析/引用，不访问数据库、不授予权限，也不接管连接与事务。
     */
    public static RestrictedSelectPlan parseBound(ProductTypeEnum product, String sql, String defaultSchema) {
        if (product == ProductTypeEnum.TIDB) {
            throw new UnsupportedOperationException("TiDB 必须使用原生 catalog 关系绑定，不能采信调用方名称");
        }
        // PostgreSQL/openGauss 用双引号。MySQL 8.4 与 MariaDB 11.4 的库名就是 schema，输出只用反引号。
        // 不带引号的库名和表名按原文保留，不折叠。lower_case_table_names=0 时大小写是不同对象。
        // 未限定表名使用调用方给出的库，不使用连接当前库。不猜测 ANSI_QUOTES。
        // 其他产品仍拒绝，不能从协议族外推。
        if (!supportsExactBinding(product)) {
            throw new UnsupportedOperationException("数据库产品尚未验证精确表定位：" + product);
        }
        RestrictedSelectPlan plan = parse(product, sql);
        String schema = plan.getSchemaName() == null ? defaultSchema : plan.getSchemaName();
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("未限定表名必须配置明确的 schema");
        }
        if (product == ProductTypeEnum.OPENGAUSS) {
            quotePhysicalIdentifier(product, schema);
            quotePhysicalIdentifier(product, plan.getTableName());
        }
        SQLSelectQueryBlock block = singleTableQueryBlock(sql, dialect(product));
        SQLExprTableSource source = (SQLExprTableSource) block.getFrom();
        source.setExpr(new SQLPropertyExpr(new SQLIdentifierExpr(quote(product, schema)),
                quote(product, plan.getTableName())));
        return new RestrictedSelectPlan(schema, plan.getTableName(), plan.isWildcard(),
                plan.getSourceColumns(), render(product, block));
    }

    /**
     * 将 TiDB 查询绑定到公共原生目录 API 观测到的名称。调用方负责该观测的
     * 真实性、新鲜度和授权；此方法只检查
     * 查询名称是否为该关系的别名，再替换 AST 中的 FROM 关系。
     * 不启用 parseBound，也不允许仅凭调用方名称进行精确授权。
     */
    public static RestrictedSelectPlan bindToCatalogRelation(
            String sql, String defaultSchema, CatalogRelationIdentity relation) {
        if (relation == null || relation.product() != ProductTypeEnum.TIDB) {
            throw new UnsupportedOperationException("当前仅接受已验证的 TiDB 原生 catalog 关系");
        }
        if (!relation.schemaName().matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !relation.tableName().matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !relation.objectId().matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("TiDB catalog 关系必须使用普通名称与原生表 ID");
        }
        ProductTypeEnum product = relation.product();
        RestrictedSelectPlan plan = parse(product, sql);
        String schema = plan.getSchemaName() == null ? defaultSchema : plan.getSchemaName();
        if (schema == null || !schema.equalsIgnoreCase(relation.schemaName())
                || !plan.getTableName().equalsIgnoreCase(relation.tableName())) {
            throw new IllegalArgumentException("查询与原生 catalog 关系不一致");
        }
        SQLSelectQueryBlock block = singleTableQueryBlock(sql, dialect(product));
        SQLExprTableSource source = (SQLExprTableSource) block.getFrom();
        source.setExpr(new SQLPropertyExpr(new SQLIdentifierExpr(quote(product, relation.schemaName())),
                quote(product, relation.tableName())));
        return new RestrictedSelectPlan(relation.schemaName(), relation.tableName(), plan.isWildcard(),
                plan.getSourceColumns(), render(product, block));
    }

    /** 解析单独的关系引用，不接受别名、分号、查询子句或额外语句。 */
    public static RestrictedSelectPlan parseTableReference(ProductTypeEnum product, String reference,
            String defaultSchema) {
        if (product == ProductTypeEnum.TIDB) {
            throw new UnsupportedOperationException("TiDB 必须解析并复验原生 catalog 关系");
        }
        // 授权接口只放行已经用真实库证明“限定名命中调用方指定的库”的产品。
        // parseRelationReference 里的 Doris 只证明关系名语法，不在这里放行。
        if (!supportsExactBinding(product)) {
            throw new UnsupportedOperationException("数据库产品尚未验证精确表定位：" + product);
        }
        return parseRelationReference(product, reference, defaultSchema);
    }

    /**
     * 仅解析单个关系名语法并安全引用。MySQL/Doris 大小写及实际对象仍须在读取连接上确认；
     * 本方法不赋予 parseBound、精确元数据或授权执行能力。
     */
    public static RestrictedSelectPlan parseRelationReference(ProductTypeEnum product, String reference,
            String defaultSchema) {
        if (product == ProductTypeEnum.TIDB) {
            throw new UnsupportedOperationException("TiDB 关系名语法仅用于后续原生 catalog 解析");
        }
        if (!supportsExactBinding(product) && product != ProductTypeEnum.DORIS) {
            throw new UnsupportedOperationException("数据库产品尚未验证精确表定位：" + product);
        }
        return parseRelationName(product, reference, defaultSchema);
    }

    /**
     * 仅解析 TiDB 关系名称语法，用于下一次原生目录查找。结果包含
     * 调用方名称，绝不是规范标识或授权键。通用 relation 和
     * parseBound API 仍拒绝调用，必须使用 resolveCatalogRelation 和 bindToCatalogRelation。
     */
    public static RestrictedSelectPlan parseCatalogRelationReference(String reference, String defaultSchema) {
        return parseRelationName(ProductTypeEnum.TIDB, reference, defaultSchema);
    }

    /**
     * 解析关系名称。
     */
    private static RestrictedSelectPlan parseRelationName(ProductTypeEnum product, String reference,
            String defaultSchema) {
        if (reference == null || reference.isBlank() || reference.length() > 2048) {
            throw new IllegalArgumentException("表引用为空或超过长度限制");
        }
        SQLName name;
        try {
            if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.DORIS || product == ProductTypeEnum.MARIADB
                    || product == ProductTypeEnum.TIDB) {
                var lexer = new MySqlLexer(reference, SQLParserFeature.KeepNameQuotes);
                lexer.setAllowComment(false);
                lexer.nextToken();
                name = new MySqlExprParser(lexer).name();
                if (lexer.token() != Token.EOF || lexer.getCommentCount() != 0) {
                    throw new IllegalArgumentException("表引用包含额外内容");
                }
            } else {
                var lexer = new ExactPostgresLexer(reference);
                lexer.nextToken();
                name = new PGExprParser(lexer).name();
                if (lexer.token() != Token.EOF) {
                    throw new IllegalArgumentException("表引用包含额外内容");
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("表引用无法解析");
        }
        var source = new SQLExprTableSource(name);
        requireSupportedTableName(source);
        if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.DORIS || product == ProductTypeEnum.MARIADB
                || product == ProductTypeEnum.TIDB) {
            // 不猜测连接的 ANSI_QUOTES；当前接受普通名称与反引号，不把字符串当作标识符。
            validateMysqlReferenceName(rawSchemaName(source));
            validateMysqlReferenceName(rawTableName(source));
        }
        String schema = physicalName(product, rawSchemaName(source));
        schema = schema == null ? defaultSchema : schema;
        String table = physicalName(product, rawTableName(source));
        return new RestrictedSelectPlan(schema, table, true, Set.of(),
                "SELECT * FROM " + qualifiedTableName(product, schema, table));
    }

    /** 物理名称到可直接用于 SQL 的限定名；不把名称当作 SQL 再解析或折叠。 */
    public static String qualifiedTableName(ProductTypeEnum product, String schema, String table) {
        return quotePhysicalIdentifier(product, schema) + "." + quotePhysicalIdentifier(product, table);
    }

    /** 已验证产品的单个物理标识符安全引用，不处理 SQL 表达式。 */
    public static String quotePhysicalIdentifier(ProductTypeEnum product, String name) {
        if (product == ProductTypeEnum.ORACLE) {
            // 仅精确名称；不折叠大小写，也不扩展 parseBound 或关系标识支持范围。
            if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.indexOf('"') >= 0
                    || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128
                    || name.chars().anyMatch(ch -> Character.isSurrogate((char) ch))) {
                throw new IllegalArgumentException("Invalid Oracle physical identifier");
            }
            return "\"" + name + "\"";
        }
        if (product == ProductTypeEnum.SQLSERVER) {
            if (name == null || name.isBlank() || name.length() > 128 || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid SQL Server physical identifier");
            }
            return "[" + name.replace("]", "]]") + "]";
        }
        if (product == ProductTypeEnum.SQLSERVER2000) {
            // 通过 TCP 连接的 8.00.760 SP3。QUOTED_IDENTIFIER 关闭时，方括号仍表示标识符。
            // ] 双写转义，拒绝 129 个字符。单个空格合法，空名称不合法。
            // 不属于 parseBound。
            if (name == null || name.isEmpty() || name.length() > 128 || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid SQL Server 2000 physical identifier");
            }
            return "[" + name.replace("]", "]]") + "]";
        }
        if (product == ProductTypeEnum.SYBASE) {
            // 仅支持 ASE 16 普通名称；方括号形式与已验证的 SybaseCursorMetadata 加引号方式一致。
            if (name == null || name.isBlank() || name.length() > 30 || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid Sybase physical identifier");
            }
            return "[" + name.replace("]", "]]") + "]";
        }
        if (product == ProductTypeEnum.DB2) {
            if (name == null || name.isBlank() || name.length() > 128 || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid DB2 physical identifier");
            }
            return "\"" + name.replace("\"", "\"\"") + "\"";
        }
        if (product == ProductTypeEnum.CLICKHOUSE || product == ProductTypeEnum.OPENGAUSS || product == ProductTypeEnum.STARROCKS
                || product == ProductTypeEnum.OCEANBASE || product == ProductTypeEnum.TDENGINE
                || product == ProductTypeEnum.INFLUXDB3 || product == ProductTypeEnum.HIVE || product == ProductTypeEnum.GREENPLUM
                || product == ProductTypeEnum.HIGHGO || product == ProductTypeEnum.KINGBASE
                || product == ProductTypeEnum.UXDB) {
            // 此分支仅证明原生标识及键适配器使用的普通名称，不提供 #17 查询绑定。
            // Greenplum 7.1.0 和 HighGo 6.0.4.2 将未加引号名称存为小写，双引号名称按原样存储。
            // KingbaseES V008R006C009B0014 在 database_mode=oracle 和 enable_ci=on 时也按此方式存储，
            // 但比较不区分大小写。UXsinoDB 2.1.0.3 同样将未加引号名称存为小写，
            // 双引号名称按原样存储。对给定普通名称加引号，不在此处折叠大小写。
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
                throw new IllegalArgumentException("Ordinary identifier required for verified projection");
            }
            return product == ProductTypeEnum.OPENGAUSS || product == ProductTypeEnum.INFLUXDB3
                    || product == ProductTypeEnum.GREENPLUM || product == ProductTypeEnum.HIGHGO
                    || product == ProductTypeEnum.KINGBASE || product == ProductTypeEnum.UXDB
                    ? "\"" + name + "\"" : "`" + name + "`";
        }
        if (product == ProductTypeEnum.DM) {
            // 仅支持 CASE_SENSITIVE=1 的普通名称。未加引号名称存为大写，所以带引号
            // 形式必须使用对应名称。此处不提供 parseBound 或 #17 查询绑定支持。
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
                throw new IllegalArgumentException("Ordinary identifier required for verified projection");
            }
            return "\"" + name.toUpperCase(Locale.ROOT) + "\"";
        }
        if (product == ProductTypeEnum.OSCAR) {
            // 仅支持 ShenTong V8.0.251217 普通名称。未加引号名称存为大写。
            // 带引号的小写名称不匹配该列。此处不提供 parseBound。
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
                throw new IllegalArgumentException("Ordinary identifier required for verified projection");
            }
            return "\"" + name.toUpperCase(Locale.ROOT) + "\"";
        }
        if (product == ProductTypeEnum.SQLITE3) {
            // sqlite-jdbc 3.31.1 文件探测。双引号保留给定名称，内嵌
            // 引号双写，与 DatabaseSqliteImpl.quoteIdentifier 规则相同。不提供 parseBound。
            if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid SQLite identifier");
            }
            return "\"" + name.replace("\"", "\"\"") + "\"";
        }
        if (product != ProductTypeEnum.POSTGRESQL && product != ProductTypeEnum.MYSQL
                && product != ProductTypeEnum.DORIS && product != ProductTypeEnum.MARIADB
                && product != ProductTypeEnum.TIDB && product != ProductTypeEnum.GBASE8A) {
            throw new UnsupportedOperationException("数据库产品尚未验证精确表定位：" + product);
        }
        if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.DORIS || product == ProductTypeEnum.MARIADB
                || product == ProductTypeEnum.TIDB || product == ProductTypeEnum.GBASE8A) {
            if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.length() > 64
                    || name.chars().anyMatch(ch -> Character.isSurrogate((char) ch))) {
                throw new IllegalArgumentException("MySQL 物理标识符为空、包含非法字符或超过长度限制");
            }
        } else {
            validatePhysicalIdentifier(name);
        }
        return quote(product, name);
    }

    /**
     * 检查 MySQL 模式名和表名是否符合受限引用规则。
     */
    private static void validateMysqlReferenceName(String raw) {
        // 反引号的闭合/转义已由 lexer 验证；不要再用递归正则解析一遍标识符。
        if (raw != null && !(raw.startsWith("`") && raw.endsWith("`"))
                && !raw.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("MySQL 表引用必须使用普通标识符或反引号");
        }
    }

    /**
     * 校验物理标识符。
     */
    private static void validatePhysicalIdentifier(String name) {
        // PostgreSQL 的标准标识符上限为 63 UTF-8 字节。拒绝服务端截断，避免授权检查另一名称。
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0
                || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("物理标识符为空、包含 NUL 或超过长度限制");
        }
    }

    /**
     * 在数据库执行前把受限 SELECT 限制到给定列集合：检查全部被引用列（输出、过滤、排序），
     * 并按目标产品方言重建明确投影的 SQL。通配符在这里展开，不依赖返回结果后再删列。
     *
     * @param product         目标数据库产品；未逐库验证的产品直接拒绝
     * @param sql             调用方提交的 SQL
     * @param schemaName      授权绑定的 schema，必须与 SQL 中的限定一致
     * @param tableName       授权绑定的表
     * @param physicalColumns 该表的物理列（来自元数据，保留原始大小写）
     * @param allowedColumns  允许访问的列，必须是物理列的子集且非空
     * @throws IllegalArgumentException      SQL 形态不受支持、绑定不一致或引用了不允许的列
     * @throws UnsupportedOperationException 数据库产品尚未验证
     */
    public static RestrictedSelectProjection restrictProjection(
            ProductTypeEnum product, String sql, String schemaName, String tableName,
            List<String> physicalColumns, Set<String> allowedColumns) {
        DbType dialect = dialect(product);
        if (schemaName == null || schemaName.isBlank() || tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("授权绑定的 schema 与表名不能为空");
        }
        if (physicalColumns == null || physicalColumns.isEmpty()) {
            throw new IllegalArgumentException("缺少目标表的物理列元数据");
        }
        Set<String> physical = new LinkedHashSet<>(physicalColumns);
        if (physical.size() != physicalColumns.size()) {
            throw new IllegalArgumentException("物理列元数据存在重复列名");
        }
        if (product != ProductTypeEnum.SYBASE
                && (!product.isLikePostgres() || product == ProductTypeEnum.KINGBASE)) {
            // 列名大小写不敏感：仅大小写不同的元数据无法唯一解析，直接拒绝。
            // 金仓 V008R006C009B0014 在默认大小写不敏感比较下，带引号也不区分大小写，服务器拒绝这种列。
            // SQL Server 2000 8.00.760 的 CI 排序同样拒绝这种列。
            // Sybase ASE 16.0 SP02 的 bin_iso_1 区分大小写，Id 和 id 可以同时存在，不在这里拒绝。
            Set<String> folded = new LinkedHashSet<>();
            for (String column : physicalColumns) {
                if (!folded.add(column.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("物理列元数据存在仅大小写不同的列名");
                }
            }
        }
        if (allowedColumns == null || allowedColumns.isEmpty() || !physical.containsAll(allowedColumns)) {
            throw new IllegalArgumentException("授权列与物理元数据不一致");
        }
        SQLSelectQueryBlock block = singleTableQueryBlock(sql, dialect);
        SQLExprTableSource tableSource = (SQLExprTableSource) block.getFrom();
        requireSupportedTableName(tableSource);
        rejectUnsafeTableSource(tableSource);
        String parsedSchema = rawSchemaName(tableSource);
        if (parsedSchema != null && !matchesRelationName(product, schemaName, parsedSchema)) {
            throw new IllegalArgumentException("查询 schema 与授权对象不一致");
        }
        String parsedTable = rawTableName(tableSource);
        if (parsedTable == null || !matchesRelationName(product, tableName, parsedTable)) {
            throw new IllegalArgumentException("查询目标表与授权对象不一致");
        }
        Set<String> qualifiers = new LinkedHashSet<>();
        qualifiers.add(tableName);
        if (tableSource.getAlias() != null) {
            String alias = unquote(tableSource.getAlias());
            if (alias != null && !alias.isBlank()) {
                qualifiers.add(alias);
            }
        }
        rejectUnsupportedProjectionSyntax(block);

        // 输出列与别名：别名不得遮蔽物理列，避免过滤/排序被解释成别名而跳过授权检查。
        Set<String> selected = new LinkedHashSet<>();
        boolean wildcard = false;
        for (SQLSelectItem item : block.getSelectList()) {
            SQLExpr expr = item.getExpr();
            if (expr instanceof SQLAllColumnExpr
                    || (expr instanceof SQLPropertyExpr property && "*".equals(property.getName()))) {
                if (expr instanceof SQLPropertyExpr property) {
                    requireQualifier(product, qualifiers, rawQualifier(property.getOwner()));
                }
                wildcard = true;
                continue;
            }
            RestrictedSelectColumn column = columnReference(expr);
            if (column == null) {
                throw new IllegalArgumentException("仅允许列引用或 *，不允许表达式/函数");
            }
            requireQualifier(product, qualifiers, column.qualifier());
            if (item.getAlias() != null && resolve(product, physical, item.getAlias()) != null) {
                throw new IllegalArgumentException("输出别名不能与物理列同名");
            }
            selected.add(requireAllowed(product, physical, allowedColumns, column));
        }
        if (wildcard && !selected.isEmpty()) {
            throw new IllegalArgumentException("不允许同时使用 * 与列清单");
        }
        if (wildcard) {
            for (String column : physicalColumns) {
                if (allowedColumns.contains(column)) {
                    selected.add(column);
                }
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("投影后没有可返回的授权列");
        }

        // 过滤与排序中的列同样受限，并同样用元数据列名重建，不把请求字符串直接拼进 SQL。
        Set<String> referenced = new LinkedHashSet<>(selected);
        if (block.getWhere() != null) {
            block.setWhere(rewriteCondition(product, physical, allowedColumns, qualifiers, referenced,
                    block.getWhere()));
        }
        if (block.getOrderBy() != null) {
            for (SQLSelectOrderByItem item : block.getOrderBy().getItems()) {
                RestrictedSelectColumn column = columnReference(item.getExpr());
                requireQualifier(product, qualifiers, column.qualifier());
                String resolved = requireAllowed(product, physical, allowedColumns, column);
                referenced.add(resolved);
                item.setExpr(new SQLIdentifierExpr(quote(product, resolved)));
            }
        }

        List<SQLSelectItem> projection = new ArrayList<>();
        for (String column : selected) {
            projection.add(new SQLSelectItem(new SQLIdentifierExpr(quote(product, column))));
        }
        block.getSelectList().clear();
        block.getSelectList().addAll(projection);
        tableSource.setExpr(new SQLIdentifierExpr(quote(product, schemaName) + "." + quote(product, tableName)));
        tableSource.setAlias(null);
        String restricted = render(product, block);
        if (restricted.endsWith(";")) {
            restricted = restricted.substring(0, restricted.length() - 1);
        }
        return new RestrictedSelectProjection(restricted, selected, referenced);
    }

    /**
     * 在已校验的用户投影上合并调用方提供的可信行条件。先校验所有用户引用，不能借策略
     * 提升用户的列权限；策略可以读取未输出的物理列，但不能改变投影、表、排序或行数上限。
     * 条件只支持与受限查询相同的有界基本谓词，不支持参数、子查询、函数或额外查询子句。
     * 调用方拥有策略来源、角色/租户授权与返回前复验，本组件不决定业务授权。
     */
    public static RestrictedSelectProjection restrictProjectionWithRowFilters(
            ProductTypeEnum product, String sql, String schemaName, String tableName,
            List<String> physicalColumns, Set<String> allowedColumns, List<String> rowFilters) {
        if (product == ProductTypeEnum.TIDB) {
            throw new UnsupportedOperationException("TiDB 行条件必须使用原生 catalog 身份绑定的接口");
        }
        if (!supportsExactBinding(product)) {
            throw new UnsupportedOperationException("数据库产品尚未验证受限行条件合并：" + product);
        }
        if (product == ProductTypeEnum.OPENGAUSS) {
            quotePhysicalIdentifier(product, schemaName);
            quotePhysicalIdentifier(product, tableName);
        }
        return mergeRowFilters(product, sql, schemaName, tableName, physicalColumns, allowedColumns, rowFilters);
    }

    /**
     * 基于原生目录的 TiDB 行条件 API。调用方负责授权，必须在其数据源上校验
     * 观测，并将列绑定到同一对象 ID。此方法不能
     * 开放业务路由，通用 TiDB 行条件入口会显式拒绝。
     */
    public static RestrictedSelectProjection restrictCatalogProjectionWithRowFilters(
            String sql, String defaultSchema, CatalogRelationIdentity relation,
            List<String> physicalColumns, Set<String> allowedColumns, List<String> rowFilters) {
        RestrictedSelectPlan bound = bindToCatalogRelation(sql, defaultSchema, relation);
        return mergeRowFilters(relation.product(), bound.getNormalizedSql(), relation.schemaName(),
                relation.tableName(), physicalColumns, allowedColumns, rowFilters);
    }

    /**
     * 将调用方数据范围与当前查询条件合并。
     */
    private static RestrictedSelectProjection mergeRowFilters(
            ProductTypeEnum product, String sql, String schemaName, String tableName,
            List<String> physicalColumns, Set<String> allowedColumns, List<String> rowFilters) {
        RestrictedSelectProjection user = restrictProjection(product, sql, schemaName, tableName, physicalColumns,
                allowedColumns);
        if (rowFilters == null) {
            throw new IllegalArgumentException("行条件清单不能为 null");
        }
        if (rowFilters.isEmpty()) {
            return user;
        }
        DbType dialect = dialect(product);
        SQLSelectQueryBlock block = singleTableQueryBlock(user.sql(), dialect);
        Set<String> references = new LinkedHashSet<>(user.referencedColumns());
        String column = user.selectedColumns().iterator().next();
        for (String filter : rowFilters) {
            if (filter == null || filter.isBlank() || filter.length() > 4000) {
                throw new IllegalArgumentException("行条件为空或超过长度限制");
            }
            String probe = "SELECT " + quote(product, column) + " FROM "
                    + qualifiedTableName(product, schemaName, tableName) + " WHERE (" + filter + ")";
            SQLSelectQueryBlock raw = singleTableQueryBlock(probe, dialect);
            if (raw.getWhere() == null || raw.getOrderBy() != null || raw.getLimit() != null) {
                throw new IllegalArgumentException("行条件不能包含查询子句");
            }
            final boolean[] parameter = {false};
            raw.getWhere().accept(new SQLASTVisitorAdapter() {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(SQLVariantRefExpr expr) {
                    parameter[0] = true;
                    return false;
                }
            });
            if (parameter[0]) {
                throw new IllegalArgumentException("行条件不能包含未绑定参数");
            }
            RestrictedSelectProjection policy = restrictProjection(product, probe, schemaName, tableName,
                    physicalColumns, new LinkedHashSet<>(physicalColumns));
            references.addAll(policy.referencedColumns());
            SQLExpr condition = singleTableQueryBlock(policy.sql(), dialect).getWhere();
            ((SQLExprImpl) condition).setParenthesized(true);
            SQLExpr userCondition = block.getWhere();
            if (userCondition != null) {
                // Druid 的查询块渲染会平铺 BooleanAnd；必须显式保留两侧的分组，
                // 否则用户的 OR 条件可能跳过最后追加的行策略。
                ((SQLExprImpl) userCondition).setParenthesized(true);
            }
            block.setWhere(userCondition == null ? condition
                    : new SQLBinaryOpExpr(userCondition, SQLBinaryOperator.BooleanAnd, condition, dialect));
            if (render(product, block).length() > 20_000) {
                throw new IllegalArgumentException("合并后的 SQL 超过长度限制");
            }
        }
        return new RestrictedSelectProjection(render(product, block), user.selectedColumns(), references);
    }

    /**
     * 解析只引用一个目标表的查询块。
     */
    private static SQLSelectQueryBlock singleTableQueryBlock(String sql, DbType dialect) {
        if (sql == null || sql.isBlank() || sql.length() > 20_000) {
            throw new IllegalArgumentException("查询 SQL 为空或超过长度限制");
        }
        List<SQLStatement> statements;
        try {
            statements = parseStatements(sql, dialect);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("查询 SQL 无法解析");
        }
        if (statements.size() != 1) {
            throw new IllegalArgumentException("仅允许单条 SELECT");
        }
        SQLStatement statement = statements.get(0);
        if (!(statement instanceof SQLSelectStatement selectStatement)) {
            throw new IllegalArgumentException("仅允许 SELECT，不允许写语句或 DDL");
        }
        rejectUnsafeNodes(statement);
        SQLSelect select = selectStatement.getSelect();
        if (select == null || select.getWithSubQuery() != null) {
            throw new IllegalArgumentException("无法解析 SELECT 或不允许 CTE/WITH");
        }
        if (!(select.getQuery() instanceof SQLSelectQueryBlock block)) {
            throw new IllegalArgumentException("仅允许单层 SELECT");
        }
        if (block.getInto() != null || block.isForUpdate()) {
            throw new IllegalArgumentException("不允许 SELECT INTO 或 FOR UPDATE");
        }
        if (!(block.getFrom() instanceof SQLExprTableSource)) {
            throw new IllegalArgumentException("仅允许单表查询");
        }
        if (select.getOrderBy() != null && block.getOrderBy() == null) {
            block.setOrderBy(select.getOrderBy());
        }
        if (block.getSelectList() == null || block.getSelectList().isEmpty()) {
            throw new IllegalArgumentException("SELECT 列表为空");
        }
        return block;
    }

    /**
     * 同包命名参数适配器使用的窄重写契约：投影/物理列规范化、可信无参数行条件合并，
     * 以及 LIMIT/OFFSET 格式化。参数身份由谓词和槽位决定，不依赖渲染后的出现顺序。
     * 不用于任意 SQL 改写；无法建立同一角色的参数必须拒绝。
     */
    static java.util.Map<String, Integer> parameterRoles(ProductTypeEnum product, String sql) {
        if (!supportsExactBinding(product)) {
            throw new UnsupportedOperationException("SQL parameter rewrite product is unverified");
        }
        SQLSelectQueryBlock block = singleTableQueryBlock(sql, dialect(product));
        rejectUnsupportedProjectionSyntax(block);
        var roles = new java.util.LinkedHashMap<String, Integer>();
        var occurrences = new java.util.HashMap<String, Integer>();
        record Condition(SQLExpr expression, String prefix) {

        }
        var pending = new java.util.ArrayDeque<Condition>();
        if (block.getWhere() != null) {
            pending.push(new Condition(block.getWhere(), ""));
        }
        while (!pending.isEmpty()) {
            var condition = pending.pop();
            SQLExpr expr = condition.expression();
            if (expr instanceof SQLBinaryOpExpr binary && (binary.getOperator() == SQLBinaryOperator.BooleanAnd
                    || binary.getOperator() == SQLBinaryOperator.BooleanOr)) {
                pending.push(new Condition(binary.getRight(), condition.prefix()));
                pending.push(new Condition(binary.getLeft(), condition.prefix()));
            } else if (expr instanceof SQLNotExpr not) {
                pending.push(new Condition(not.getExpr(), condition.prefix() + "NOT/"));
            } else {
                var parameters = parameters(expr);
                if (parameters.isEmpty()) {
                    continue;
                }
                String identity = SqlScriptPlan.digest(condition.prefix() + parameterExpressionIdentity(product, expr));
                int occurrence = occurrences.merge(identity, 1, Integer::sum);
                for (int slot = 0; slot < parameters.size(); slot++) {
                    roles.put("WHERE/" + identity + "/" + occurrence + "/" + slot, parameters.get(slot).getIndex());
                }
            }
        }
        if (block.getLimit() != null) {
            addParameterRole(roles, "LIMIT_ROW_COUNT", block.getLimit().getRowCount());
            addParameterRole(roles, "LIMIT_OFFSET", block.getLimit().getOffset());
        }
        var parameters = parameters(block);
        if (parameters.size() != roles.size() || !new java.util.HashSet<>(roles.values()).equals(
                java.util.stream.IntStream.range(0, parameters.size()).boxed()
                        .collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("SQL parameter roles are unsupported or incomplete");
        }
        return java.util.Map.copyOf(roles);
    }

    /**
     * 返回参数。
     */
    private static List<SQLVariantRefExpr> parameters(com.alibaba.druid.sql.ast.SQLObject object) {
        var result = new ArrayList<SQLVariantRefExpr>();
        object.accept(new SQLASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLVariantRefExpr parameter) {
                if (!"?".equals(parameter.getName())) {
                    throw new IllegalArgumentException("SQL parameter role requires JDBC placeholders");
                }
                result.add(parameter);
                return false;
            }
        });
        return result;
    }

    /**
     * 记录当前参数在受限语句中的使用角色。
     */
    private static void addParameterRole(java.util.Map<String, Integer> roles, String role, SQLExpr expr) {
        if (expr instanceof SQLVariantRefExpr parameter) {
            if (!"?".equals(parameter.getName())) {
                throw new IllegalArgumentException("SQL parameter role requires JDBC placeholders");
            }
            roles.put(role, parameter.getIndex());
        } else if (expr != null && !parameters(expr).isEmpty()) {
            throw new IllegalArgumentException("SQL pagination parameter must occupy one scalar slot");
        }
    }

    /**
     * 为当前参数表达式计算稳定身份信息。
     */
    private static String parameterExpressionIdentity(ProductTypeEnum product, SQLExpr expr) {
        var column = columnReference(expr);
        if (column != null) {
            return "COLUMN(" + physicalName(product, column.name(), column.quoted()) + ")";
        }
        if (expr instanceof SQLVariantRefExpr) {
            return "PARAMETER";
        }
        if (expr instanceof SQLLiteralExpr || expr instanceof SQLNullExpr) {
            return "LITERAL(" + expr + ")";
        }
        if (expr instanceof SQLBinaryOpExpr binary) {
            return binary.getOperator().name() + "("
                    + parameterExpressionIdentity(product, binary.getLeft()) + ","
                    + parameterExpressionIdentity(product, binary.getRight()) + ")";
        }
        if (expr instanceof SQLBetweenExpr between) {
            return "BETWEEN(" + between.isNot() + ","
                    + parameterExpressionIdentity(product, between.getTestExpr()) + ","
                    + parameterExpressionIdentity(product, between.getBeginExpr()) + ","
                    + parameterExpressionIdentity(product, between.getEndExpr()) + ")";
        }
        if (expr instanceof SQLInListExpr in) {
            return "IN(" + in.isNot() + ","
                    + parameterExpressionIdentity(product, in.getExpr()) + ","
                    + in.getTargetList().stream().map(item -> parameterExpressionIdentity(product, item))
                    .collect(java.util.stream.Collectors.joining(",")) + ")";
        }
        throw new IllegalArgumentException("SQL parameter expression identity is unsupported");
    }

    /** 初期只接受列、字面值、参数、基本比较与布尔条件和列排序；其余语法一律拒绝，不做部分检查。 */
    private static void rejectUnsupportedProjectionSyntax(SQLSelectQueryBlock block) {
        rejectUnsupportedQueryClauses(block);
        if (block.getGroupBy() != null) {
            throw new IllegalArgumentException("不允许 GROUP BY/HAVING");
        }
        if (block.getDistionOption() != 0 || block.isDistinct()) {
            throw new IllegalArgumentException("不允许 DISTINCT");
        }
        if (block instanceof PGSelectQueryBlock pg
                && (pg.getDistinctOn() != null && !pg.getDistinctOn().isEmpty()
                || pg.getForClause() != null || pg.getIntoOption() != null)) {
            throw new IllegalArgumentException("不允许 DISTINCT ON / FOR / INTO 子句");
        }
        if (block instanceof CKSelectQueryBlock clickHouse
                && (clickHouse.isFinal() || clickHouse.getPreWhere() != null || clickHouse.getSample() != null
                || clickHouse.getFormat() != null || clickHouse.isWithTotals() || clickHouse.isWithFill()
                || clickHouse.isWithTies()
                || (clickHouse.getSettings() != null && !clickHouse.getSettings().isEmpty()))) {
            throw new IllegalArgumentException(
                    "不允许 ClickHouse FINAL、PREWHERE、SAMPLE、FORMAT、SETTINGS 或 WITH TOTALS/FILL/TIES");
        }
        if (block.getHints() != null && !block.getHints().isEmpty()) {
            throw new IllegalArgumentException("不允许查询 HINT");
        }
        if (block.getWhere() != null) {
            rejectUnsupportedCondition(block.getWhere());
        }
        if (block.getOrderBy() != null) {
            for (SQLSelectOrderByItem item : block.getOrderBy().getItems()) {
                if (columnReference(item.getExpr()) == null) {
                    throw new IllegalArgumentException("排序仅允许列引用，不允许表达式或位置序号");
                }
            }
        }
    }

    /**
     * 拒绝当前受限 SQL 路径未支持的条件表达式。
     */
    private static void rejectUnsupportedCondition(SQLExpr expr) {
        if (columnReference(expr) != null || expr instanceof SQLLiteralExpr || expr instanceof SQLVariantRefExpr) {
            return;
        }
        if (expr instanceof SQLBinaryOpExpr binary) {
            if (!SUPPORTED_OPERATORS.contains(binary.getOperator())) {
                throw new IllegalArgumentException("不支持的过滤运算符：" + binary.getOperator().name);
            }
            rejectUnsupportedCondition(binary.getLeft());
            rejectUnsupportedCondition(binary.getRight());
            return;
        }
        if (expr instanceof SQLNotExpr not) {
            rejectUnsupportedCondition(not.getExpr());
            return;
        }
        if (expr instanceof SQLBetweenExpr between) {
            rejectUnsupportedCondition(between.getTestExpr());
            rejectUnsupportedCondition(between.getBeginExpr());
            rejectUnsupportedCondition(between.getEndExpr());
            return;
        }
        if (expr instanceof SQLInListExpr in) {
            rejectUnsupportedCondition(in.getExpr());
            in.getTargetList().forEach(RestrictedSelectGuard::rejectUnsupportedCondition);
            return;
        }
        if (expr instanceof SQLNullExpr) {
            return;
        }
        throw new IllegalArgumentException("过滤条件仅允许列、字面值、参数与基本比较/布尔组合");
    }

    /** 校验并重建过滤条件：列引用替换为元数据列名的方言引用，字面值与参数保持原样。 */
    private static SQLExpr rewriteCondition(ProductTypeEnum product, Set<String> physical, Set<String> allowed,
            Set<String> qualifiers, Set<String> referenced, SQLExpr expr) {
        RestrictedSelectColumn column = columnReference(expr);
        if (column != null) {
            requireQualifier(product, qualifiers, column.qualifier());
            String resolved = requireAllowed(product, physical, allowed, column);
            referenced.add(resolved);
            return new SQLIdentifierExpr(quote(product, resolved));
        }
        if (expr instanceof SQLLiteralExpr || expr instanceof SQLVariantRefExpr || expr instanceof SQLNullExpr) {
            return expr;
        }
        if (expr instanceof SQLBinaryOpExpr binary) {
            if (!SUPPORTED_OPERATORS.contains(binary.getOperator())) {
                throw new IllegalArgumentException("不支持的过滤运算符：" + binary.getOperator().name);
            }
            binary.setLeft(rewriteCondition(product, physical, allowed, qualifiers, referenced, binary.getLeft()));
            binary.setRight(rewriteCondition(product, physical, allowed, qualifiers, referenced, binary.getRight()));
            return binary;
        }
        if (expr instanceof SQLNotExpr not) {
            not.setExpr(rewriteCondition(product, physical, allowed, qualifiers, referenced, not.getExpr()));
            return not;
        }
        if (expr instanceof SQLBetweenExpr between) {
            between.setTestExpr(rewriteCondition(product, physical, allowed, qualifiers, referenced,
                    between.getTestExpr()));
            between.setBeginExpr(rewriteCondition(product, physical, allowed, qualifiers, referenced,
                    between.getBeginExpr()));
            between.setEndExpr(rewriteCondition(product, physical, allowed, qualifiers, referenced,
                    between.getEndExpr()));
            return between;
        }
        if (expr instanceof SQLInListExpr in) {
            in.setExpr(rewriteCondition(product, physical, allowed, qualifiers, referenced, in.getExpr()));
            List<SQLExpr> targets = in.getTargetList();
            for (int index = 0; index < targets.size(); index++) {
                targets.set(index, rewriteCondition(product, physical, allowed, qualifiers, referenced,
                        targets.get(index)));
            }
            return in;
        }
        throw new IllegalArgumentException("过滤条件仅允许列、字面值、参数与基本比较/布尔组合");
    }

    /**
     * 检查对象引用包含所需的限定名称。
     */
    private static void requireQualifier(ProductTypeEnum product, Set<String> qualifiers, String qualifier) {
        if (qualifier != null && resolve(product, qualifiers, qualifier) == null) {
            throw new IllegalArgumentException("未知的表限定符：" + qualifier);
        }
    }

    /**
     * 校验并取得允许。
     */
    private static String requireAllowed(ProductTypeEnum product, Set<String> physical, Set<String> allowed,
            RestrictedSelectColumn column) {
        String resolved = column == null ? null : resolve(product, physical, column.name(), column.quoted());
        if (resolved == null) {
            throw new IllegalArgumentException("引用了未知列：" + (column == null ? null : column.name()));
        }
        if (!allowed.contains(resolved)) {
            throw new IllegalArgumentException("引用了未授权列：" + resolved);
        }
        return resolved;
    }

    /**
     * 关系名与列名具有独立的大小写规则。Doris 4.0.8 case 0 的库表名按物理拼写精确匹配，
     * 带反引号时仅解码转义；列名及别名继续使用既有匹配规则。
     * 其他产品保持原有行为，此检查不开放 parseBound 或精确元数据能力。
     */
    private static boolean matchesRelationName(ProductTypeEnum product, String expected, String raw) {
        if (product == ProductTypeEnum.DORIS) {
            return expected.equals(unquote(raw));
        }
        return resolve(product, Set.of(expected), raw) != null;
    }

    /**
     * 按目标产品的标识符规则把 SQL 中的标识符解析到唯一的元数据名称。
     *
     * <p>PostgreSQL：带引号按原文精确匹配，不带引号折叠为小写后精确匹配；不做全局小写化。
     * 金仓 V008R006C009B0014（默认 DB_MODE=oracle，字符串比较不区分大小写）：带引号和不带引号都大小写不敏感，命中多个候选则拒绝。
     * MySQL/MariaDB/Hive：列名大小写不敏感，忽略大小写匹配，命中多个候选视为歧义并拒绝。
     * Hive 4.1 带反引号或不带引号都不区分大小写，建表后元数据记成小写。
     *
     * @return 匹配到的元数据名称；无法唯一确定时返回 null
     */
    private static String resolve(ProductTypeEnum product, Set<String> candidates, String raw) {
        return resolve(product, candidates, unquote(raw), isQuoted(raw));
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    private static String resolve(ProductTypeEnum product, Set<String> candidates, String name, boolean quoted) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (product.isLikePostgres() && product != ProductTypeEnum.KINGBASE) {
            // PostgreSQL、openGauss、Greenplum、瀚高、优炫：不带引号折叠为小写，带引号按原文。
            // 金仓不走这里。它的带引号标识符也不区分大小写，精确匹配会把合法的 "Id" 拒掉。
            String resolved = quoted ? name : name.toLowerCase(Locale.ROOT);
            return candidates.contains(resolved) ? resolved : null;
        }
        if (product == ProductTypeEnum.CLICKHOUSE || product == ProductTypeEnum.SYBASE) {
            // ClickHouse 未加引号的库、表、列名也区分大小写，且不会折叠。只接受与元数据完全相同的拼写。
            // Sybase ASE 16.0 SP02 PL02 的 bin_iso_1 同样不折叠：未加引号的 ID 不命中 id，带引号也按原文。
            return candidates.contains(name) ? name : null;
        }
        if (product == ProductTypeEnum.SQLITE3 || product == ProductTypeEnum.INFLUXDB3) {
            // 带引号按原文匹配；不带引号大小写不敏感，但命中多个候选时拒绝。
            if (candidates.contains(name)) {
                return name;
            }
            if (quoted) {
                return null;
            }
        }
        if (product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DB2
                || product == ProductTypeEnum.DM || product == ProductTypeEnum.OSCAR) {
            // 不带引号折叠为大写；带引号按原文，不接受另一种大小写。
            // 达梦 CASE_SENSITIVE=1、神通 V8.0.251217 与此相同：未加引号的 id 命中 ID，"Id" 不命中 ID。
            String resolved = quoted ? name : name.toUpperCase(Locale.ROOT);
            return candidates.contains(resolved) ? resolved : null;
        }
        // SQL Server 2022 默认排序规则，以及 SQL Server 2000 8.00.760 的
        // SQL_Latin1_General_CP1_CI_AS：带引号和不带引号都大小写不敏感，命中多个候选则拒绝。
        if (candidates.contains(name)) {
            return name;
        }
        String matched = null;
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(name)) {
                if (matched != null) {
                    return null; // 元数据本身存在大小写歧义时拒绝
                }
                matched = candidate;
            }
        }
        return matched;
    }

    /**
     * 根据当前输入构建并返回 {@code RestrictedSelectColumn}。
     */
    private static RestrictedSelectColumn columnReference(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr identifier) {
            return new RestrictedSelectColumn(null, unquote(identifier.getName()), isQuoted(identifier.getName()));
        }
        if (expr instanceof SQLPropertyExpr property && !"*".equals(property.getName())) {
            return new RestrictedSelectColumn(rawQualifier(property.getOwner()), unquote(property.getName()),
                    isQuoted(property.getName()));
        }
        return null;
    }

    /**
     * 提取语法节点中原始的限定名称。
     */
    private static String rawQualifier(SQLExpr owner) {
        if (owner instanceof SQLName name) {
            return name.getSimpleName();
        }
        return owner == null ? null : owner.toString();
    }

    /**
     * 提取语法节点中原始的表名。
     */
    private static String rawTableName(SQLExprTableSource tableSource) {
        if (tableSource.getExpr() instanceof SQLName name) {
            return name.getSimpleName();
        }
        return tableSource.getName() == null ? null : tableSource.getName().getSimpleName();
    }

    /**
     * 提取语法节点中原始的模式名。
     */
    private static String rawSchemaName(SQLExprTableSource tableSource) {
        if (tableSource.getExpr() instanceof SQLPropertyExpr property
                && property.getOwner() instanceof SQLIdentifierExpr owner) {
            return owner.getName();
        }
        return null;
    }

    /** AST 必须已经把 schema/table 分开；拒绝跨 catalog、多段或非普通关系名。 */
    private static void requireSupportedTableName(SQLExprTableSource source) {
        SQLExpr expr = source.getExpr();
        if (!(expr instanceof SQLIdentifierExpr)
                && !(expr instanceof SQLPropertyExpr property
                && property.getOwner() instanceof SQLIdentifierExpr)) {
            throw new IllegalArgumentException("只允许 table 或 schema.table，不支持跨 catalog 定位");
        }
    }

    /**
     * 按数据库产品及引用状态还原物理标识符，PostgreSQL 未引用名称统一转小写。
     */
    private static String physicalName(ProductTypeEnum product, String raw) {
        return physicalName(product, unquote(raw), isQuoted(raw));
    }

    /**
     * 按数据库产品及引用状态还原物理标识符，PostgreSQL 未引用名称统一转小写。
     */
    private static String physicalName(ProductTypeEnum product, String name, boolean quoted) {
        return name == null || quoted || !product.isLikePostgres()
                ? name : name.toLowerCase(Locale.ROOT);
    }

    /** 解码单个 AST 标识符，不拆点、不删除内部引号或空格。 */
    private static String unquote(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("`") && trimmed.endsWith("`"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            String closing = trimmed.substring(trimmed.length() - 1);
            return trimmed.substring(1, trimmed.length() - 1).replace(closing + closing, closing);
        }
        return trimmed;
    }

    /**
     * 判断引用。
     */
    private static boolean isQuoted(String name) {
        if (name == null || name.length() < 2) {
            return false;
        }
        String trimmed = name.trim();
        return (trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("`") && trimmed.endsWith("`"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    /** 跳过单引号字符串和反引号标识符；其余位置出现双引号即拒绝。 */
    private static void rejectClickHouseDoubleQuotes(String sql) {
        boolean singleQuoted = false;
        boolean backtick = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (backtick) {
                if (current == '`' && i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                    i++;
                } else if (current == '`') {
                    backtick = false;
                }
                continue;
            }
            if (singleQuoted) {
                if (current == '\\' && i + 1 < sql.length()) {
                    i++;
                } else if (current == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                } else if (current == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (current == '`') {
                backtick = true;
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                throw new IllegalArgumentException("ClickHouse 双引号标识符不能安全解析，请改用反引号");
            }
        }
    }

    /**
     * 将当前结构定义渲染为目标 SQL 或表达式。
     */
    private static String render(ProductTypeEnum product, SQLSelectQueryBlock block) {
        String sql = SQLUtils.toSQLString(block, dialect(product)).strip();
        if (product == ProductTypeEnum.TDENGINE) {
            // Druid 的 taos 输出把列名写成双引号，而 TDengine 把双引号当字符串。改成反引号后才是列引用。
            sql = backtickDoubleQuotedIdentifiers(sql);
        }
        return sql;
    }

    /**
     * 将字符串字面量外的双引号标识符改写为反引号形式，供 TDengine SQL 渲染使用。
     */
    private static String backtickDoubleQuotedIdentifiers(String sql) {
        StringBuilder rendered = new StringBuilder(sql.length());
        boolean singleQuoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (singleQuoted) {
                rendered.append(current);
                if (current == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    rendered.append(sql.charAt(++i));
                } else if (current == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (current == '\'') {
                singleQuoted = true;
                rendered.append(current);
                continue;
            }
            if (current == '"') {
                int end = sql.indexOf('"', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("TDengine 标识符引号未闭合");
                }
                rendered.append('`').append(sql, i + 1, end).append('`');
                i = end;
                continue;
            }
            rendered.append(current);
        }
        return rendered.toString();
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    private static String quote(ProductTypeEnum product, String identifier) {
        // OceanBase 枚举未固定引号：已验证的是 CE MySQL 模式，用反引号。Oracle 模式不在本次范围。
        String quote = product == ProductTypeEnum.OCEANBASE ? "`" : product.getQuote();
        if (quote == null || quote.isEmpty()) {
            throw new UnsupportedOperationException("数据库产品缺少标识符引用定义：" + product);
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    /**
     * 拒绝当前受限 SQL 协议禁止的语法节点。
     */
    private static void rejectUnsafeNodes(SQLStatement statement) {
        statement.accept(new SQLASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLWithSubqueryClause x) {
                throw new IllegalArgumentException("不允许 CTE/WITH");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLJoinTableSource x) {
                throw new IllegalArgumentException("不允许 JOIN");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLSubqueryTableSource x) {
                throw new IllegalArgumentException("不允许子查询");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLUnionQuery x) {
                throw new IllegalArgumentException("不允许 UNION");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLQueryExpr x) {
                throw new IllegalArgumentException("不允许子查询");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLInSubQueryExpr x) {
                throw new IllegalArgumentException("不允许子查询");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLExistsExpr x) {
                throw new IllegalArgumentException("不允许 EXISTS 子查询");
            }
        });
    }

    /**
     * 解析当前产品对应的数据库方言。
     */
    private static DbType dialect(ProductTypeEnum product) {
        // 只启用已逐库做真实差异测试的产品。不能按协议族外推。
        // PostgreSQL 家族：PostgreSQL、openGauss、Greenplum 7.1.0、KingbaseES V008R006C009B0014、瀚高 6.0.4.2、优炫 2.1.0.3。
        // MySQL 家族：MySQL、MariaDB、TiDB、StarRocks、Doris、GBase 8a 8.6.2.43。
        // 另有独立方言：ClickHouse、SQLite、SQL Server 2022、SQL Server 2000 8.00.760、
        // Sybase ASE 16.0 SP02 PL02、TDengine、Oracle 23.26、DB2 12.1、InfluxDB 3。
        // MongoDB 与 Elasticsearch 的产品驱动不执行改写后的 SELECT，仍默认拒绝。
        // 达梦只按本次实例证明：dminit CASE_SENSITIVE=1，不带引号折叠为大写，双引号按原文。
        // OceanBase 只启用已验证的 CE MySQL 模式。Hive 只启用 4.1.0 普通文本表上已验证的反引号投影。
        // Greenplum 只按 7.1.0 单容器普通表证明：不带引号折叠为小写，双引号按原文，
        // standard_conforming_strings 的 on/off 会改变反斜杠。Greengage 等分支不是本产品。
        // 金仓只按默认镜像证明：DB_MODE=oracle，字符串比较不区分大小写。带引号和不带引号都大小写不敏感。
        // ENABLE_CI=no 和其他 DB_MODE 未证明。
        // 瀚高只按 6.0.4.2 本次实例证明：不带引号折叠为小写，双引号按原文。反引号在服务器上也按原文，
        // postgresql 词法会先把它收成双引号标识符再做授权。standard_conforming_strings 的 on/off 会改变反斜杠。
        // 优炫只按 2.1.0.3 本次实例证明：不带引号折叠为小写，双引号按原文。同一张表可以同时有 Id 和 id。
        // 服务器会剥掉反引号，里面的文本按未加引号解析；postgresql 词法先收成双引号再授权。
        if (product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.OPENGAUSS
                || product == ProductTypeEnum.GREENPLUM || product == ProductTypeEnum.KINGBASE
                || product == ProductTypeEnum.HIGHGO || product == ProductTypeEnum.UXDB) {
            return DbType.postgresql;
        }
        if (product == ProductTypeEnum.CLICKHOUSE) {
            return DbType.clickhouse;
        }
        if (product == ProductTypeEnum.SQLITE3) {
            return DbType.sqlite;
        }
        if (product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000
                || product == ProductTypeEnum.SYBASE) {
            // SQL Server 2000 只按 8.00.760 SP3 本次实例证明：排序 SQL_Latin1_General_CP1_CI_AS。
            // 不带引号，以及 QUOTED_IDENTIFIER ON 下的双引号，都不区分大小写。仅大小写不同的列被服务器拒绝。
            // OFF 时双引号是字符串，不在本次放行的会话里。方括号始终是标识符。反引号是语法错误。
            // 反斜杠不是转义。TOP 可执行，LIMIT 不可。一次插入多行 VALUES 不可用。
            // 模块里的 mssql-jdbc 13.4 拒绝服务器主版本 8。本次用同一类名的 JDBC Driver 3.0.1301.101。
            // Sybase ASE 16.0 SP02 PL02：quoted_identifier on 时双引号是标识符，方括号始终是标识符。
            // 不用 Druid sybase：它拒绝 TOP，却会输出服务器不执行的 LIMIT。
            return DbType.sqlserver;
        }
        if (product == ProductTypeEnum.TDENGINE) {
            return DbType.taosdata;
        }
        if (product == ProductTypeEnum.ORACLE) {
            return DbType.oracle;
        }
        if (product == ProductTypeEnum.DM) {
            // 达梦 V8 在 CASE_SENSITIVE=1 下双引号是标识符。不用 oracle 方言，避免把达梦语句交给另一套解析器。
            return DbType.dm;
        }
        if (product == ProductTypeEnum.OSCAR) {
            // 神通 V8.0.251217：不带引号折叠为大写，双引号按原文。用 oscar 方言，不交给 Oracle 解析器。
            return DbType.oscar;
        }
        if (product == ProductTypeEnum.DB2) {
            return DbType.db2;
        }
        if (product == ProductTypeEnum.INFLUXDB3) {
            // InfluxDB 3 SQL 使用双引号标识符。词法沿用 PostgreSQL 精确词法，解析规则本身不按 PostgreSQL 折叠。
            return DbType.postgresql;
        }
        if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.MARIADB
                || product == ProductTypeEnum.TIDB || product == ProductTypeEnum.STARROCKS
                || product == ProductTypeEnum.DORIS || product == ProductTypeEnum.OCEANBASE
                || product == ProductTypeEnum.HIVE || product == ProductTypeEnum.GBASE8A) {
            // Hive 4.1.0 反引号是标识符。不用 Druid 的 hive 方言：它把双引号收成字符串，
            // 并且会留下 SORT BY / CLUSTER BY / DISTRIBUTE BY，而这些子句里的列没有被授权检查。
            // MySQL 词法把双引号当标识符。column/none 下服务器把双引号当字符串，拒绝是收紧；
            // standard 下双引号是标识符，拒绝才是对的。none 把反引号改成正则，不在本次放行的会话里。
            // GBase 8a 8.6.2.43-R7-free：lower_case_table_names=1，列名大小写不敏感。
            // 默认 sql_mode 含 ANSI_QUOTES，双引号是标识符；关掉后双引号是字符串。NO_BACKSLASH_ESCAPES 会生效。
            // 不用 Druid 的 gbase 方言：它把反引号重写成双引号，ANSI_QUOTES 关闭时这些列名会变成字符串。
            return DbType.mysql;
        }
        throw new UnsupportedOperationException("数据库产品尚未验证受限查询：" + product);
    }

    /**
     * 解析语句集合。
     */
    static List<SQLStatement> parseStatements(String sql, DbType dialect) {
        rejectUnsafeLexemes(dialect, sql);
        if (dialect != DbType.postgresql) {
            return SQLUtils.parseStatements(sql, dialect, SQLParserFeature.KeepNameQuotes);
        }
        var lexer = new ExactPostgresLexer(sql);
        lexer.nextToken();
        var parser = new SupportedPostgresStatementParser(lexer);
        List<SQLStatement> statements = parser.parseStatementList();
        if (lexer.token() != Token.EOF) {
            throw new IllegalArgumentException("查询 SQL 存在未解析内容");
        }
        return statements;
    }

    /**
     * 词法层拦截无法在 AST 上稳妥判定的越权载体：任何注释/优化器提示、会话或系统变量、
     * PostgreSQL 美元引用字符串，以及含单引号或反斜杠的字符串字面值——其跨会话模式重渲染
     * 无法证明安全（已在真实库差异测试确认 PostgreSQL {@code standard_conforming_strings=off}
     * 下可借反斜杠越权）。仅保留 {@code ?} 与 PostgreSQL {@code $n} 占位符。双引号/反引号
     * 标识符走词法别名分支而非字符串字面值，不受本检查影响。
     */
    private static void rejectUnsafeLexemes(DbType dialect, String sql) {
        if (dialect == DbType.clickhouse) {
            // 服务器把双引号当标识符，Druid 的 clickhouse 解析器却当成字符串。拒绝后不会把未授权列改写成字面值。
            rejectClickHouseDoubleQuotes(sql);
        }
        Lexer lexer = dialect == DbType.postgresql
                ? new ExactPostgresLexer(sql)
                : new MySqlLexer(sql, SQLParserFeature.KeepNameQuotes);
        lexer.setAllowComment(false);
        try {
            lexer.nextToken();
            for (int guard = 0; lexer.token() != Token.EOF; guard++) {
                if (guard > 200_000) {
                    throw new IllegalArgumentException("查询 SQL 词法元素过多");
                }
                Token token = lexer.token();
                if (token == Token.HINT) {
                    throw new IllegalArgumentException("不允许查询 HINT 或优化器提示");
                }
                if (token == Token.LINE_COMMENT || token == Token.MULTI_LINE_COMMENT) {
                    throw new IllegalArgumentException("不允许 SQL 注释");
                }
                if (token == Token.VARIANT) {
                    String name = lexer.stringVal();
                    if (name == null || !name.matches("\\$\\d+")) {
                        throw new IllegalArgumentException(
                                "不允许会话/系统变量或美元引用字符串，仅支持 ? 与 $n 占位符");
                    }
                }
                if (token == Token.LITERAL_CHARS) {
                    String value = lexer.stringVal();
                    if (value != null && (value.indexOf('\'') >= 0 || value.indexOf('\\') >= 0)) {
                        throw new IllegalArgumentException("字符串字面值不允许包含单引号或反斜杠");
                    }
                }
                lexer.nextToken();
            }
        } catch (NotAllowCommentException ex) {
            throw new IllegalArgumentException("不允许 SQL 注释");
        }
        if (lexer.getCommentCount() != 0) {
            throw new IllegalArgumentException("不允许 SQL 注释");
        }
    }

    /** 拒绝无法证明安全的查询修饰、锁定与会话级子句（MySQL/MariaDB 修饰、PostgreSQL FOR/锁）。 */
    private static void rejectUnsupportedQueryClauses(SQLSelectQueryBlock block) {
        if (block.isForShare()) {
            throw new IllegalArgumentException("不允许 FOR SHARE");
        }
        if (block instanceof PGSelectQueryBlock pg && pg.getForClause() != null) {
            throw new IllegalArgumentException("不允许 FOR UPDATE/SHARE 子句");
        }
        if (block instanceof OscarSelectQueryBlock oscar
                && (oscar.getForClause() != null
                || oscar.getIntoOptionTemp() != null
                || oscar.getIntoOptionLocal() != null
                || (oscar.getDistinctOn() != null && !oscar.getDistinctOn().isEmpty()))) {
            // 神通解析器把 FOR SHARE 放在自己的 ForClause 上，不走 isForShare()。
            throw new IllegalArgumentException("不允许 DISTINCT ON / FOR / INTO 子句");
        }
        if (block instanceof MySqlSelectQueryBlock mysql) {
            if (mysql.isLockInShareMode()) {
                throw new IllegalArgumentException("不允许 LOCK IN SHARE MODE");
            }
            if (mysql.isHignPriority()) {
                throw new IllegalArgumentException("不允许 HIGH_PRIORITY");
            }
            if (mysql.isStraightJoin()) {
                throw new IllegalArgumentException("不允许 STRAIGHT_JOIN");
            }
            if (mysql.isCalcFoundRows()) {
                throw new IllegalArgumentException("不允许 SQL_CALC_FOUND_ROWS");
            }
            if (mysql.getCache() != null) {
                throw new IllegalArgumentException("不允许 SQL_CACHE/SQL_NO_CACHE");
            }
            if (mysql.isSmallResult() || mysql.isBigResult() || mysql.isBufferResult()) {
                throw new IllegalArgumentException("不允许 SQL_SMALL/BIG/BUFFER_RESULT");
            }
            if (mysql.getForcePartition() != null) {
                throw new IllegalArgumentException("不允许 PARTITION 选择");
            }
            if (mysql.getProcedureName() != null) {
                throw new IllegalArgumentException("不允许 PROCEDURE");
            }
        }
    }

    /** 拒绝表级采样、分区选择与索引提示（PostgreSQL TABLESAMPLE、MySQL PARTITION 与 USE/FORCE/IGNORE INDEX）。 */
    private static void rejectUnsafeTableSource(SQLExprTableSource source) {
        if (source.getSampling() != null) {
            throw new IllegalArgumentException("不允许 TABLESAMPLE");
        }
        if (source.getPartitions() != null && !source.getPartitions().isEmpty()) {
            throw new IllegalArgumentException("不允许 PARTITION 选择");
        }
        if (source.getHintsSize() > 0) {
            throw new IllegalArgumentException("不允许索引提示（USE/FORCE/IGNORE INDEX）");
        }
    }

}
