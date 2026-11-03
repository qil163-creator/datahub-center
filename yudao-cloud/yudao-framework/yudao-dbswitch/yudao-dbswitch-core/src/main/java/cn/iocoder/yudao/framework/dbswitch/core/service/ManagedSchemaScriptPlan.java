package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 显式受管 schema 脚本的窄语法计划。授权由调用方负责；不扩展只读/普通 DML 接口。
 * 所有关系与过程名都限定到一个物理 schema，拒绝权限/会话/事务指令、外部语言、
 * DB link、未验证函数和运行时动态 SQL。Oracle/DM 的 EXECUTE IMMEDIATE 仅接受
 * 可以递归规划的常量 DDL；DM 使用 Oracle 兼容语法，不声明厂商执行验证。
 */
public record ManagedSchemaScriptPlan(ProductTypeEnum product, String schemaName, List<Step> steps) {

    private static final Pattern MARKER = Pattern.compile(":__dbswitch_binding_([0-9]+)__");

    private static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "MIN", "MAX", "AVG", "COALESCE",
            "NVL", "NULLIF", "ROUND", "ABS", "LOWER", "UPPER", "LENGTH", "SUBSTR", "SUBSTRING",
            "TO_DATE", "TO_TIMESTAMP", "TO_CHAR", "NOW", "CURRENT_TIMESTAMP");

    private static final Pattern PG_PROCEDURE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(OR\\s+REPLACE\\s+)?PROCEDURE\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)"
                    + "\\s*\\(([^()]*)\\)\\s+LANGUAGE\\s+SQL\\s+AS\\s+(\\$[A-Za-z0-9_]*\\$)(.*?)\\4\\s*;?\\s*$");

    private static final Pattern PG_ARGUMENTS = Pattern.compile(
            "(?i)\\s*(?:IN\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s+(?:INT|INTEGER|BIGINT|TEXT|BOOLEAN|DATE|TIMESTAMP|VARCHAR)\\s*");

    /**
     * 初始化 {@code ManagedSchemaScriptPlan}。
     */
    public ManagedSchemaScriptPlan {
        steps = List.copyOf(steps);
    }

    public record Step(String sql, String operation, Set<String> objectNames, List<Integer> parameterOrder,
                       String routineName) {

        /**
         * 初始化 {@code Step}。
         */
        public Step {
            objectNames = Set.copyOf(objectNames);
            parameterOrder = List.copyOf(parameterOrder);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "ManagedSchemaScriptStep[operation=" + operation + "]";
        }
    }

    /**
     * 返回当前支持。
     */
    public static boolean supports(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.MYSQL
                || product == ProductTypeEnum.MARIADB || product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM;
    }

    /** 严格解析类别供调用方分流；不以关键词猜测 WITH/过程/写入能力。 */
    public static boolean requiresSchemaAuthorization(ProductTypeEnum product, List<String> statements) {
        if (!supports(product) || statements == null || statements.isEmpty() || statements.size() > 500) {
            throw invalid();
        }
        boolean required = false;
        for (String sql : statements) {
            if (sql == null) {
                throw invalid();
            }
            SqlScriptInput.readStatements(product, new StringReader(sql), "/");
            if (product == ProductTypeEnum.POSTGRESQL && PG_PROCEDURE.matcher(sql).matches()) {
                required = true;
                continue;
            }
            var parser = SQLParserUtils.createSQLStatementParser(sql, dialect(product));
            var parsed = parser.parseStatementList();
            if (parser.getLexer().token() != Token.EOF || parsed.size() != 1 || !allowed(parsed.getFirst(), false)) {
                throw invalid();
            }
            required |= Set.of("DDL", "PROCEDURE", "BLOCK", "CALL").contains(operation(parsed.getFirst()));
        }
        return required;
    }

    /** 无对象、无结果数据的 SQL 执行器结束探针；不需要业务再造方言分支。 */
    public static String completionQuery(ProductTypeEnum product) {
        if (!supports(product) && !SqlStatementClassifier.supports(product)) {
            throw new UnsupportedOperationException("SQL completion query product is unverified");
        }
        return product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    /** 输入已由公开分段器分段，参数必须是 JDBC ?；返回逐段完整的原参数索引排列。 */
    public static ManagedSchemaScriptPlan parse(ProductTypeEnum product, String schema, List<String> statements) {
        if (!supports(product)) {
            throw new UnsupportedOperationException("Managed schema script product is unverified");
        }
        if (schema == null || schema.isBlank() || statements == null || statements.isEmpty() || statements.size() > 500) {
            throw invalid();
        }
        quote(product, schema);
        int total = 0;
        var steps = new ArrayList<Step>();
        var verifiedRoutines = new HashMap<String, List<SQLDataType>>();
        try {
            for (String sql : statements) {
                if (sql == null || (total += sql.length()) > 100000) {
                    throw invalid();
                }
                var source = SqlScriptInput.readStatements(product, new StringReader(sql), "/");
                if (source.statements().size() != 1) {
                    throw invalid();
                }
                Step step = plan(product, schema, sql, 0);
                if ("CALL".equals(step.operation())) {
                    var types = verifiedRoutines.get(step.routineName());
                    if (types == null) {
                        throw invalid();
                    }
                    step = plan(product, schema, sql, 0, types);
                }
                if ("PROCEDURE".equals(step.operation())) {
                    verifiedRoutines.put(step.routineName(), routineTypes(product, sql));
                }
                steps.add(step);
            }
            if (steps.stream()
                    .noneMatch(step -> Set.of("DDL", "PROCEDURE", "BLOCK", "CALL").contains(step.operation()))) {
                throw new IllegalArgumentException("Schema script must contain an explicit DDL or procedure operation");
            }
            return new ManagedSchemaScriptPlan(product, schema, steps);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 显式高权限调用方的 MySQL 追加写入窄入口；旧 parse 仍要求 DDL，不隐式提升普通 DML。
     * 仅接受显式列与参数化 VALUES，拒绝查询来源、忽略冲突、更新冲突、分区和其他写入变体。
     */
    public static ManagedSchemaScriptPlan parseInsertOnly(ProductTypeEnum product, String schema,
            List<String> statements) {
        if (product != ProductTypeEnum.MYSQL) {
            throw new UnsupportedOperationException("Insert-only scripts are only verified for MySQL");
        }
        if (schema == null || schema.isBlank() || statements == null || statements.isEmpty() || statements.size() > 500) {
            throw invalid();
        }
        quote(product, schema);
        int total = 0, parameterCount = 0;
        var steps = new ArrayList<Step>();
        try {
            for (String sql : statements) {
                if (sql == null || (total += sql.length()) > 100000) {
                    throw invalid();
                }
                var source = SqlScriptInput.readStatements(product, new StringReader(sql), "/");
                if (source.statements().size() != 1) {
                    throw invalid();
                }
                var parser = SQLParserUtils.createSQLStatementParser(sql, DbType.mysql);
                var parsed = parser.parseStatementList();
                if (parser.getLexer().token() != Token.EOF || parsed.size() != 1
                        || !(parsed.getFirst() instanceof com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement insert)
                        || insert.getQuery() != null || insert.getWith() != null || insert.getColumns().isEmpty()
                        || insert.getValuesList().isEmpty() || !insert.getDuplicateKeyUpdate().isEmpty()
                        || insert.isIgnore() || insert.isUpsert() || insert.isOverwrite() || insert.isDelayed()
                        || insert.isHighPriority() || insert.isLowPriority() || insert.isRollbackOnFail()
                        || insert.isFulltextDictionary() || insert.isIfNotExists() || insert.getHint() != null
                        || insert.getHintsSize() != 0 || insert.getAlias() != null
                        || (insert.getPartitions() != null && !insert.getPartitions().isEmpty())) {
                    throw invalid();
                }
                var names = new HashSet<String>();
                for (SQLExpr column : insert.getColumns()) {
                    if (!(column instanceof SQLIdentifierExpr identifier)) {
                        throw invalid();
                    }
                    String name = physical(product, identifier.getName());
                    quote(product, name);
                    if (!names.add(name.toLowerCase(Locale.ROOT))) {
                        throw invalid();
                    }
                }
                for (var row : insert.getValuesList()) {
                    if (row.getValues().size() != insert.getColumns().size()) {
                        throw invalid();
                    }
                    for (SQLExpr value : row.getValues()) {
                        if (!(value instanceof SQLVariantRefExpr parameter) || !"?".equals(parameter.getName())) {
                            throw invalid();
                        }
                    }
                    if ((parameterCount += row.getValues().size()) > 5000) {
                        throw invalid();
                    }
                }
                steps.add(plan(product, schema, sql, 0));
            }
            return new ManagedSchemaScriptPlan(product, schema, steps);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 返回计划。
     */
    private static Step plan(ProductTypeEnum product, String schema, String sql, int depth) {
        return plan(product, schema, sql, depth, null);
    }

    /**
     * 返回计划。
     */
    private static Step plan(ProductTypeEnum product, String schema, String sql, int depth,
            List<SQLDataType> callTypes) {
        if (depth > 4) {
            throw invalid();
        }
        if (product == ProductTypeEnum.POSTGRESQL) {
            var procedure = PG_PROCEDURE.matcher(sql);
            if (procedure.matches()) {
                if (!procedure.group(3).isBlank()) {
                    for (String argument : procedure.group(3).split(",", -1)) {
                        if (!PG_ARGUMENTS.matcher(argument).matches()) {
                            throw invalid();
                        }
                    }
                }
                var name = SQLUtils.toSQLExpr(procedure.group(2), DbType.postgresql);
                if (!(name instanceof SQLName)) {
                    throw invalid();
                }
                var objects = new LinkedHashSet<String>();
                SQLName qualified = bindName(product, schema, (SQLName) name, objects);
                var body = new ArrayList<String>();
                for (String segment : SqlScriptPlan.splitStatements(product, procedure.group(5), ";")) {
                    var step = plan(product, schema, segment, depth + 1);
                    if (!Set.of("QUERY", "DML").contains(step.operation()) || !step.parameterOrder().isEmpty()) {
                        throw invalid();
                    }
                    objects.addAll(step.objectNames());
                    body.add(step.sql());
                }
                String rendered = "CREATE " + (procedure.group(1) == null ? "" : "OR REPLACE ") + "PROCEDURE "
                        + qualified + "(" + procedure.group(3) + ") LANGUAGE SQL AS " + procedure.group(4)
                        + String.join(";\n", body) + ";" + procedure.group(4);
                return new Step(rendered, "PROCEDURE", objects, List.of(),
                        physical(product, qualified.getSimpleName()));
            }
        }
        DbType dialect = dialect(product);
        var parser = SQLParserUtils.createSQLStatementParser(sql, dialect);
        var parsed = parser.parseStatementList();
        if (parser.getLexer().token() != Token.EOF || parsed.size() != 1) {
            throw invalid();
        }
        SQLStatement statement = parsed.getFirst();
        if (statement instanceof SQLCallStatement call && callTypes != null) {
            if (call.getParameters().size() != callTypes.size()) {
                throw invalid();
            }
            for (int index = 0; index < callTypes.size(); index++) {
                call.getParameters()
                        .set(index, new SQLCastExpr(call.getParameters().get(index), callTypes.get(index).clone()));
            }
        }
        var objects = new LinkedHashSet<String>();
        var parameters = new ArrayList<SQLVariantRefExpr>();
        audit(product, schema, statement, objects, parameters, depth);
        String operation = operation(statement);
        // 参数标识只存在于临时 AST；从正常词法态恢复，字面量/注释中的相同文本保持原样。
        for (var parameter : parameters) {
            if (!"?".equals(parameter.getName()) || parameter.getIndex() < 0) {
                throw invalid();
            }
            parameter.setName(":__dbswitch_binding_" + parameter.getIndex() + "__");
        }
        String rendered = SQLUtils.toSQLString(statement, dialect).strip();
        var executable = SqlScriptInput.readStatements(product, new StringReader(rendered), "/").executableText();
        var matcher = MARKER.matcher(rendered);
        var output = new StringBuilder();
        var order = new ArrayList<Integer>();
        int previous = 0;
        while (matcher.find()) {
            if (!rendered.regionMatches(matcher.start(), executable, matcher.start(),
                    matcher.end() - matcher.start())) {
                continue;
            }
            output.append(rendered, previous, matcher.start()).append('?');
            previous = matcher.end();
            order.add(Integer.parseInt(matcher.group(1)));
        }
        output.append(rendered, previous, rendered.length());
        if (order.size() != parameters.size() || !new HashSet<>(order).equals(
                java.util.stream.IntStream.range(0, order.size()).boxed()
                        .collect(java.util.stream.Collectors.toSet()))) {
            throw invalid();
        }
        String routine = statement instanceof SQLCreateProcedureStatement procedure ? physical(product,
                procedure.getName().getSimpleName())
                : statement instanceof SQLCallStatement call ? physical(product,
                call.getProcedureName().getSimpleName()) : null;
        return new Step(output.toString(), operation, objects, order, routine);
    }

    /**
     * 遍历并校验受管模式脚本的语法节点与对象引用，绑定目标模式并拒绝未支持的副作用语法。
     */
    private static void audit(ProductTypeEnum product, String schema, SQLStatement statement, Set<String> objects,
            List<SQLVariantRefExpr> parameters, int depth) {
        boolean procedural = statement instanceof SQLCreateProcedureStatement || statement instanceof SQLBlockStatement;
        java.util.function.Consumer<SQLObject> inspect = node -> {
            if (node instanceof SQLStatement sql) {
                if (!allowed(sql, procedural)) {
                    throw invalid();
                }
                // Managed write steps use JDBC executeUpdate; result-returning writes are outside that contract.
                if (sql instanceof com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement insert
                        && insert.getReturning() != null) {
                    throw invalid();
                }
                if (sql instanceof SQLUpdateStatement update && !update.getReturning().isEmpty()) {
                    throw invalid();
                }
                if (sql instanceof com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGDeleteStatement delete
                        && delete.isReturning()) {
                    throw invalid();
                }
                if (sql instanceof com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleInsertStatement insert
                        && insert.getReturning() != null) {
                    throw invalid();
                }
                if (sql instanceof com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleUpdateStatement update
                        && !update.getReturningInto().isEmpty()) {
                    throw invalid();
                }
                if (sql instanceof com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleDeleteStatement delete
                        && delete.getReturning() != null) {
                    throw invalid();
                }
                if (sql instanceof SQLCreateProcedureStatement procedure) {
                    if (procedure.getDefiner() != null || procedure.getJavaCallSpec() != null
                            || procedure.getWrappedSource() != null || procedure.getBlock() == null) {
                        throw invalid();
                    }
                    if (product != ProductTypeEnum.ORACLE && product != ProductTypeEnum.DM) {
                        throw invalid();
                    }
                    if (procedure.getAuthid() != null && !"CURRENT_USER".equalsIgnoreCase(
                            procedure.getAuthid().toString())) {
                        throw invalid();
                    }
                    procedure.setAuthid(new SQLIdentifierExpr("CURRENT_USER"));
                    procedure.setName(bindName(product, schema, procedure.getName(), objects));
                }
                if (sql instanceof SQLCallStatement call) {
                    if (procedural) {
                        throw invalid(); // 不能借嵌套调用信任未核验的既有过程。
                    }
                    if (call.getOutParameter() != null) {
                        throw invalid();
                    }
                    call.setProcedureName(bindName(product, schema, call.getProcedureName(), objects));
                }
                if (sql instanceof SQLCreateIndexStatement index) {
                    SQLName boundIndex = bindName(product, schema, index.getName(), objects);
                    index.setName(product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM ? boundIndex
                            : new SQLIdentifierExpr(((SQLPropertyExpr) boundIndex).getName()));
                    if (index.getTablespace() != null || index.getIn() != null || index.getStoredAs() != null
                            || !index.getOptions().isEmpty() || !index.getProperties()
                            .isEmpty() || !index.getTableProperties().isEmpty()) {
                        throw invalid();
                    }
                    if (index.getUsing() != null && !"BTREE".equalsIgnoreCase(index.getUsing())) {
                        throw invalid();
                    }
                    if (index.getItems().stream().anyMatch(item -> !(item.getExpr() instanceof SQLIdentifierExpr)
                            || item.getCollate() != null)) {
                        throw invalid();
                    }
                }
                if (sql instanceof SQLCreateTableStatement table && (table.getLocation() != null
                        || table.getStoredAs() != null || table.getStoredBy() != null || table.getTablespace() != null
                        || table.isExternal() || table.getSelect() != null || table.getLike() != null
                        || table.getPartitionOf() != null || table.getPartitioning() != null || table.getInherits() != null
                        || !safeCreateOptions(product, table))) {
                    throw invalid();
                }
                if (sql instanceof SQLSelectStatement select) {
                    if (select.getSelect().getWithSubQuery() != null) {
                        throw invalid();
                    }
                }
                if (sql instanceof SQLDropTableStatement drop && drop.isCascade()) {
                    throw invalid();
                }
                if (sql instanceof SQLTruncateStatement truncate && Boolean.TRUE.equals(truncate.getCascade())) {
                    throw invalid();
                }
                if (sql instanceof SQLAlterTableStatement alter && (!alter.getTableOptions().isEmpty()
                        || !alter.getUnsetTableOptions()
                        .isEmpty() || alter.getPartition() != null || alter.getOn() != null)) {
                    throw invalid();
                }
                if (sql instanceof SQLExecuteImmediateStatement dynamic) {
                    if (!(dynamic.getDynamicSql() instanceof SQLCharExpr literal) || !dynamic.getInto().isEmpty()) {
                        throw invalid();
                    }
                    var nested = plan(product, schema, literal.getText(), depth + 1);
                    if (!"DDL".equals(nested.operation()) || !nested.parameterOrder().isEmpty()) {
                        throw invalid();
                    }
                    dynamic.setDynamicSql(new SQLCharExpr(nested.sql()));
                    objects.addAll(nested.objectNames());
                }
            }
            if (node instanceof SQLExprTableSource table) {
                if (!(table.getExpr() instanceof SQLName name)) {
                    throw invalid();
                }
                if (table.getParent() instanceof SQLCommentStatement comment && comment.getType() != SQLCommentStatement.Type.TABLE) {
                    throw invalid();
                }
                table.setExpr(bindName(product, schema, name, objects));
            }
            if (node instanceof SQLMethodInvokeExpr method && (method.getOwner() != null
                    || !FUNCTIONS.contains(method.getMethodName().toUpperCase(Locale.ROOT)))) {
                throw invalid();
            }
            if (node instanceof SQLMethodInvokeExpr method) {
                if (product == ProductTypeEnum.POSTGRESQL) {
                    method.setOwner(new SQLIdentifierExpr("pg_catalog"));
                } else if (!(method instanceof SQLAggregateExpr)) {
                    throw invalid();
                }
            }
            if (node instanceof SQLForeignKeyConstraint || node instanceof SQLColumnReference) {
                throw invalid();
            }
            if (node instanceof SQLColumnDefinition column) {
                auditColumn(column);
            }
            // Druid stores TEXT/VARCHAR COLLATE on the data type rather than the column.
            if (node instanceof SQLCharacterDataType character && (character.getCollate() != null
                    || character.getCharSetName() != null || character.isHasBinary()
                    || character.getCharType() != null || (character.getHints() != null && !character.getHints()
                    .isEmpty()))) {
                throw invalid();
            }
            if (node instanceof SQLSelectOrderByItem order && order.getCollate() != null) {
                throw invalid();
            }
            if (node instanceof SQLBinaryOpExpr binary && !Set.of("Equality", "NotEqual", "GreaterThan",
                            "GreaterThanOrEqual",
                            "LessThan", "LessThanOrEqual", "LessThanOrGreater", "BooleanAnd", "BooleanOr", "Is", "IsNot",
                            "Like", "NotLike", "Add", "Subtract", "Multiply", "Divide", "Modulus")
                    .contains(binary.getOperator().name())) {
                throw invalid();
            }
            if (node instanceof SQLAlterTableItem item && !Set.of("SQLAlterTableAddColumn",
                            "SQLAlterTableDropColumnItem")
                    .contains(item.getClass().getSimpleName())) {
                throw invalid();
            }
            if (node instanceof SQLAlterTableDropColumnItem drop && drop.isCascade()) {
                throw invalid();
            }
            if (node instanceof SQLSelectQueryBlock block && (block.isForUpdate()
                    || (!procedural && block.getInto() != null))) {
                throw invalid();
            }
            if (node instanceof SQLDataType type && !type.getName().toUpperCase(Locale.ROOT).matches(
                    "INT|INT4|INT8|INTEGER|BIGINT|SMALLINT|TINYINT|NUMBER|NUMERIC|DECIMAL|VARCHAR|VARCHAR2|CHAR|TEXT|DATE|TIME|TIMESTAMP|BOOLEAN|BIT|FLOAT|DOUBLE|REAL|CLOB|BLOB")
                    // PostgreSQL 原生目录使用这些标准别名；仅接纳已支持类型的明确等价名称。
                    && !(product == ProductTypeEnum.POSTGRESQL
                    && Set.of("CHARACTER VARYING", "CHARACTER", "DOUBLE PRECISION")
                    .contains(type.getName().toUpperCase(Locale.ROOT)))
                    && !(product == ProductTypeEnum.MYSQL
                    && Set.of("MEDIUMTEXT", "LONGTEXT", "DATETIME")
                    .contains(type.getName().toUpperCase(Locale.ROOT)))) {
                throw invalid();
            }
            if (node instanceof SQLCommentHint || node instanceof SQLDbLinkExpr) {
                throw invalid();
            }
            if (node instanceof SQLVariantRefExpr parameter) {
                parameters.add(parameter);
            }
        };
        if (product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM) {
            statement.accept(new com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter() {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public void preVisit(SQLObject node) {
                    inspect.accept(node);
                }
            });
        } else if (product == ProductTypeEnum.POSTGRESQL) {
            statement.accept(new com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter() {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public void preVisit(SQLObject node) {
                    inspect.accept(node);
                }
            });
        } else {
            statement.accept(new com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter() {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public void preVisit(SQLObject node) {
                    inspect.accept(node);
                }
            });
        }
    }

    /** 仅接纳 MySQL 方言生成器的固定引擎/字符集组合，不放宽任意表选项或外部存储路径。 */
    private static boolean safeCreateOptions(ProductTypeEnum product, SQLCreateTableStatement table) {
        if (table.getEngine() == null && table.getTableOptions().isEmpty()) {
            return true;
        }
        if (product != ProductTypeEnum.MYSQL || !(table.getEngine() instanceof SQLIdentifierExpr engine)
                || !"InnoDB".equalsIgnoreCase(engine.getName())) {
            return false;
        }
        var options = new HashMap<String, String>();
        for (var option : table.getTableOptions()) {
            if (!(option.getTarget() instanceof SQLIdentifierExpr key)
                    || !(option.getValue() instanceof SQLIdentifierExpr value)
                    || options.putIfAbsent(key.getName().toUpperCase(Locale.ROOT),
                    value.getName().toLowerCase(Locale.ROOT)) != null) {
                return false;
            }
        }
        return options.equals(Map.of("ENGINE", "innodb", "CHARSET", "utf8mb4", "COLLATE", "utf8mb4_bin"));
    }

    /** Only basic columns are verified; several Druid column options are not visited as children. */
    private static void auditColumn(SQLColumnDefinition column) {
        if (column.getCollateExpr() != null || column.getCharsetExpr() != null || column.getOnUpdate() != null
                || column.getFormat() != null || column.getStorage() != null || column.getAsExpr() != null
                || column.isStored() || column.isVirtual() || column.isAutoIncrement() || column.getIdentity() != null
                || column.getGeneratedAlwaysAs() != null || column.isGenerateByDefault() || column.getSequenceType() != null
                || column.getDelimiter() != null || column.getDelimiterTokenizer() != null || column.getNlpTokenizer() != null
                || column.getValueType() != null || column.isDisableIndex() || column.getJsonIndexAttrsExpr() != null
                || column.getAnnIndex() != null || column.getAggType() != null || column.getBitmap() != null
                || column.getIndexComment() != null || column.getBlockSize() != null || column.getUnitCount() != null
                || column.getUnitIndex() != null || column.getStep() != null || column.getEncode() != null
                || column.getCompression() != null || column.isPreSort() || column.isDisableNovalidate()
                || column.getEnable() != null || column.getValidate() != null || column.getRely() != null
                || !column.getMappedBy().isEmpty() || !column.getColProperties().isEmpty()) {
            throw invalid();
        }
        for (var constraint : column.getConstraints()) {
            if (!Set.of("SQLNotNullConstraint", "SQLNullConstraint", "SQLColumnPrimaryKey", "SQLColumnUniqueKey")
                    .contains(constraint.getClass().getSimpleName())) {
                throw invalid();
            }
        }
        // A datatype may itself retain options omitted by a dialect output visitor.
        if (column.getDataType() instanceof SQLCharacterDataType character && (character.getCollate() != null
                || character.getCharSetName() != null || character.isHasBinary()
                || character.getCharType() != null || (character.getHints() != null && !character.getHints()
                .isEmpty()))) {
            throw invalid();
        }
        if (column.getDataType() == null || column.getDataType().getArguments().stream()
                .anyMatch(argument -> !(argument instanceof SQLIntegerExpr))) {
            throw invalid();
        }
    }

    /**
     * 检查原生定义或配置只包含当前允许的属性。
     */
    private static boolean allowed(SQLStatement statement, boolean procedural) {
        if (statement instanceof SQLCreateTableStatement || statement instanceof SQLDropTableStatement
                || statement instanceof SQLCreateIndexStatement || statement instanceof SQLAlterTableStatement
                || statement instanceof SQLTruncateStatement || statement instanceof SQLCommentStatement
                || statement instanceof SQLInsertStatement || statement instanceof SQLUpdateStatement
                || statement instanceof SQLDeleteStatement || statement instanceof SQLSelectStatement
                || statement instanceof SQLCreateProcedureStatement || statement instanceof SQLCallStatement
                || statement instanceof SQLBlockStatement || statement instanceof SQLExecuteImmediateStatement) {
            return true;
        }
        if (procedural && statement instanceof SQLExprStatement expression && expression.getExpr() instanceof SQLNullExpr) {
            return true;
        }
        return procedural && Set.of("SQLExceptionStatement", "SQLNullStatement", "SQLIfStatement", "SQLLoopStatement",
                "SQLWhileStatement", "SQLReturnStatement").contains(statement.getClass().getSimpleName());
    }

    /**
     * 返回当前操作。
     */
    private static String operation(SQLStatement statement) {
        if (statement instanceof SQLCreateProcedureStatement) {
            return "PROCEDURE";
        }
        if (statement instanceof SQLBlockStatement) {
            return "BLOCK";
        }
        if (statement instanceof SQLCallStatement) {
            return "CALL";
        }
        if (statement instanceof SQLSelectStatement) {
            return "QUERY";
        }
        if (statement instanceof SQLInsertStatement || statement instanceof SQLUpdateStatement || statement instanceof SQLDeleteStatement) {
            return "DML";
        }
        return "DDL";
    }

    /** 显式 CAST 到本脚本定义的签名，避免 CALL 绑定选择既有不同类型的 definer 重载。 */
    private static List<SQLDataType> routineTypes(ProductTypeEnum product, String sql) {
        var pg = PG_PROCEDURE.matcher(sql);
        if (product == ProductTypeEnum.POSTGRESQL && pg.matches()) {
            if (pg.group(3).isBlank()) {
                return List.of();
            }
            return Arrays.stream(pg.group(3).split(",")).map(argument -> {
                var words = argument.strip().split("\\s+");
                if (words.length > 2 && !"IN".equalsIgnoreCase(words[0])) {
                    throw invalid();
                }
                return (SQLDataType) new SQLDataTypeImpl(words[words.length - 1]);
            }).toList();
        }
        var parser = SQLParserUtils.createSQLStatementParser(sql, dialect(product));
        var statement = parser.parseStatementList().getFirst();
        if (!(statement instanceof SQLCreateProcedureStatement procedure)) {
            throw invalid();
        }
        return procedure.getParameters().stream().map(parameter -> {
            if (parameter.getParamType() != null && !"IN".equals(parameter.getParamType().name())) {
                throw invalid();
            }
            return parameter.getDataType().clone();
        }).toList();
    }

    /**
     * 绑定名称。
     */
    private static SQLName bindName(ProductTypeEnum product, String schema, SQLName raw, Set<String> objects) {
        String name;
        if (raw instanceof SQLIdentifierExpr identifier) {
            name = physical(product, identifier.getName());
        } else if (raw instanceof SQLPropertyExpr property && property.getOwner() instanceof SQLIdentifierExpr owner) {
            if (!schema.equals(physical(product, owner.getName()))) {
                throw invalid();
            }
            name = physical(product, property.getName());
        } else {
            throw invalid();
        }
        objects.add(name);
        return new SQLPropertyExpr(new SQLIdentifierExpr(quote(product, schema)), quote(product, name));
    }

    /**
     * 返回物理。
     */
    private static String physical(ProductTypeEnum product, String name) {
        char quote = product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.MARIADB ? '`' : '"';
        if (name.length() >= 2 && name.charAt(0) == quote && name.charAt(name.length() - 1) == quote) {
            return name.substring(1, name.length() - 1).replace("" + quote + quote, "" + quote);
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw invalid();
        }
        return product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM ? name.toUpperCase(Locale.ROOT)
                : product == ProductTypeEnum.POSTGRESQL ? name.toLowerCase(Locale.ROOT) : name;
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    private static String quote(ProductTypeEnum product, String name) {
        if (product == ProductTypeEnum.DM && name != null && !name.equals(name.toUpperCase(Locale.ROOT))) {
            throw invalid();
        }
        return RestrictedSelectGuard.quotePhysicalIdentifier(product, name);
    }

    /**
     * 解析当前产品对应的数据库方言。
     */
    private static DbType dialect(ProductTypeEnum product) {
        return product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM ? DbType.oracle
                : product == ProductTypeEnum.POSTGRESQL ? DbType.postgresql : DbType.mysql;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Managed schema script contains unsupported syntax or an object outside the configured schema");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "ManagedSchemaScriptPlan[product=" + product + ", statements=" + steps.size() + "]";
    }
}
