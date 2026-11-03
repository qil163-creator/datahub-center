package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import java.sql.*;
import java.util.*;

/** 可与 Java 8 Worker 共用的受管事务钩子契约；仅接受无函数、无子查询的单表 DML。 */
public final class PostgresUpsertHooks {
    public static final String CONTRACT = "POSTGRESQL_UPSERT_HOOKS_V2";
    public static final String LEDGER = "_datahub_upsert_runs";
    private static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
            "SELECT", "WITH", "MERGE", "CALL", "EXECUTE", "COPY", "TRUNCATE", "CREATE", "ALTER", "DROP",
            "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "BEGIN", "START", "SAVEPOINT", "RELEASE", "PREPARE",
            "VACUUM", "ANALYZE", "EXPLAIN", "RETURNING", "USING", "DEFAULT", "OVERRIDING", "SESSION", "LOCAL", "TABLE"));
    private static final Set<String> LITERAL_PREFIX = new HashSet<>(Arrays.asList(
            "THEN","ELSE","LIKE","ILIKE","TO","ESCAPE","AND","OR","NOT","BETWEEN"));
    private PostgresUpsertHooks() { }

    public static List<String> parse(String script, String schema) {
        PostgresUpsertSql.quote(schema);
        if (script == null || script.trim().isEmpty()) return Collections.emptyList();
        if (script.length() > 16000) throw invalid("单阶段 SQL 最多 16000 字符");
        List<String> result = new ArrayList<>();
        List<Token> tokens = lex(script);
        int start = 0;
        for (Token token : tokens) if (!token.quoted && ";".equals(token.value)) {
            add(result, script.substring(start, token.start), schema); start = token.end;
        }
        add(result, script.substring(start), schema);
        if (result.size() > 32) throw invalid("单阶段 SQL 最多 32 条");
        return Collections.unmodifiableList(result);
    }

    private static void add(List<String> result, String sql, String schema) {
        if (!lex(sql).isEmpty()) { table(sql, schema); result.add(sql.trim()); }
    }

    public static void validate(List<String> statements, String schema) {
        if (statements == null) return;
        if (statements.size() > 32) throw invalid("单阶段 SQL 最多 32 条");
        int length = 0;
        for (String statement : statements) {
            if (statement == null) throw invalid("SQL 不能为空");
            length += statement.length();
            List<String> parsed = parse(statement, schema);
            if (parsed.size() != 1) throw invalid("每个 SQL 数组元素必须恰好包含一条语句");
        }
        if (length > 16000) throw invalid("单阶段 SQL 最多 16000 字符");
    }

    /** 以真实目录核对钩子表，禁止触发器、RLS、默认值及生成列引入事务之外的副作用。 */
    public static void verifyTables(Connection connection, String schema, List<String> statements, int timeout) throws SQLException {
        validate(statements, schema);
        if (statements == null) return;
        Set<String> checked = new HashSet<>();
        for (String sql : statements) {
            String name = table(sql, schema);
            if (!checked.add(name)) continue;
            verifyTable(connection,schema,name,timeout);
        }
    }

    /** 运行时保留表也须核验真实存储对象；本方法不接受用户 SQL，也不决定业务授权。 */
    public static void verifyTable(Connection connection,String schema,String name,int timeout) throws SQLException {
            try (Statement lock = connection.createStatement()) {
                lock.setQueryTimeout(timeout);
                lock.execute("LOCK TABLE " + PostgresUpsertSql.target(schema, name) + " IN ROW EXCLUSIVE MODE");
            }
            String query = "SELECT c.relkind='r' AND c.relpersistence='p' AND c.relam=(SELECT oid FROM pg_catalog.pg_am WHERE amname='heap' AND amtype='t') "
                    + "AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits h WHERE h.inhrelid=c.oid OR h.inhparent=c.oid) "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal AND t.tgenabled<>'D') "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                    + "AND (a.atthasdef OR a.attidentity<>'' OR a.attgenerated<>'')) "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class=c.oid) "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE (k.conrelid=c.oid OR k.confrelid=c.oid) AND k.contype IN ('f','c','x')) "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_index i WHERE i.indrelid=c.oid AND (i.indexprs IS NOT NULL OR i.indpred IS NOT NULL)) "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid "
                    + "JOIN pg_catalog.pg_am am ON am.oid=ix.relam CROSS JOIN LATERAL unnest(i.indclass) cls(opcid) "
                    + "JOIN pg_catalog.pg_opclass op ON op.oid=cls.opcid JOIN pg_catalog.pg_namespace onsp ON onsp.oid=op.opcnamespace "
                    + "WHERE i.indrelid=c.oid AND (am.amname<>'btree' OR NOT op.opcdefault OR onsp.nspname<>'pg_catalog')) "
                    + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid "
                    + "JOIN pg_catalog.pg_namespace tn ON tn.oid=t.typnamespace WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped AND (tn.nspname<>'pg_catalog' OR t.typtype<>'b')) "
                    + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setQueryTimeout(timeout); statement.setString(1, schema); statement.setString(2, name);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new SQLException("钩子表必须是无触发器、规则、RLS、默认值和生成列的普通表");
                }
            }
    }

    private static String table(String sql, String schema) {
        List<Token> tokens = lex(sql);
        if (tokens.size() < 5) throw invalid("仅支持带 schema 的 INSERT VALUES、UPDATE、DELETE");
        String kind = tokens.get(0).keyword();
        int offset;
        if ("INSERT".equals(kind) && "INTO".equals(tokens.get(1).keyword())) offset = 2;
        else if ("DELETE".equals(kind) && "FROM".equals(tokens.get(1).keyword())) offset = 2;
        else if ("UPDATE".equals(kind)) offset = 1;
        else throw invalid("仅支持 INSERT VALUES、UPDATE、DELETE 事务语句");
        if (tokens.size() <= offset + 3 || !tokens.get(offset).identifier || !".".equals(tokens.get(offset + 1).value)
                || !tokens.get(offset + 2).identifier) throw invalid("钩子必须显式限定 schema.table");
        String actualSchema = tokens.get(offset).name();
        String table = tokens.get(offset + 2).name();
        if (!schema.equals(actualSchema) || schema.startsWith("pg_") || "information_schema".equals(schema)
                || table.toLowerCase(Locale.ROOT).startsWith("_datahub_")) throw invalid("钩子仅可操作目标 schema 的业务表");
        PostgresUpsertSql.quote(table);
        int afterTable = offset + 3;
        int columnEnd = -1;
        if ("INSERT".equals(kind)) {
            if (!"(".equals(tokens.get(afterTable).value)) throw invalid("INSERT 必须显式指定所有写入列");
            boolean column = true;
            for (int i = afterTable + 1; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (")".equals(token.value) && !column) { columnEnd = i; break; }
                if (column && token.identifier) column = false;
                else if (!column && ",".equals(token.value)) column = true;
                else throw invalid("INSERT 列清单无效");
            }
            if (columnEnd < 0 || columnEnd + 1 >= tokens.size() || !"VALUES".equals(tokens.get(columnEnd + 1).keyword()))
                throw invalid("INSERT 只支持显式 VALUES，不支持查询或默认值");
        } else if ("UPDATE".equals(kind) && !"SET".equals(tokens.get(afterTable).keyword())) {
            throw invalid("UPDATE 不支持别名或 FROM");
        }
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String word = token.keyword();
            if ((!token.quoted && ";".equals(token.value)) || FORBIDDEN.contains(word)) throw invalid("SQL 含不支持的语句或副作用能力");
            if ("FROM".equals(word) && !("DELETE".equals(kind) && i == 1)) throw invalid("不支持多表或查询 SQL");
            if ("INTO".equals(word) && !("INSERT".equals(kind) && i == 1)) throw invalid("不支持额外写入目标");
            if ("UPDATE".equals(word) && i != 0 && !("INSERT".equals(kind) && i > columnEnd && "DO".equals(tokens.get(i - 1).keyword())))
                throw invalid("不支持嵌套 DML");
            boolean qualified = i > 0 && ".".equals(tokens.get(i - 1).value);
            if (i + 1 < tokens.size() && token.identifier && tokens.get(i + 1).quoted && !tokens.get(i + 1).identifier
                    && (qualified || !LITERAL_PREFIX.contains(word)))
                throw invalid("不支持自定义类型字面量或隐藏的类型转换调用");
            boolean valuesClause = "VALUES".equals(word) && "INSERT".equals(kind) && i == columnEnd + 1;
            boolean conflictClause = "CONFLICT".equals(word) && "INSERT".equals(kind) && i > columnEnd
                    && i > 0 && "ON".equals(tokens.get(i - 1).keyword());
            if (i + 1 < tokens.size() && "(".equals(tokens.get(i + 1).value) && token.identifier
                    && i != offset + 2 && (qualified || (!valuesClause && !"IN".equals(word) && !conflictClause)))
                throw invalid("钩子不支持函数、过程或类型转换调用");
            if (".".equals(token.value) && i != offset + 1) {
                // 限定列只允许当前表或 UPSERT 的 EXCLUDED；不接受函数 schema 或第二个表身份。
                if (i == 0 || !(table.equals(tokens.get(i - 1).name()) || "excluded".equals(tokens.get(i - 1).name())))
                    throw invalid("不支持跨表限定表达式");
            }
        }
        return table;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("前后置 SQL：" + message); }

    private static final class Token {
        final String value; final int start; final int end; final boolean identifier; final boolean quoted;
        Token(String value, int start, int end, boolean identifier, boolean quoted) {
            this.value=value; this.start=start; this.end=end; this.identifier=identifier; this.quoted=quoted;
        }
        String keyword() { return identifier && !quoted ? value.toUpperCase(Locale.ROOT) : ""; }
        String name() { return quoted ? value : value.toLowerCase(Locale.ROOT); }
    }

    private static List<Token> lex(String sql) {
        List<Token> result = new ArrayList<>();
        for (int i=0; i<sql.length();) {
            char c=sql.charAt(i); int start=i;
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c=='-' && i+1<sql.length() && sql.charAt(i+1)=='-') { i+=2; while(i<sql.length() && sql.charAt(i)!='\n' && sql.charAt(i)!='\r') i++; continue; }
            if (c=='/' && i+1<sql.length() && sql.charAt(i+1)=='*') {
                i+=2; int depth=1;
                while(i<sql.length() && depth>0) {
                    if(i+1<sql.length() && sql.charAt(i)=='/' && sql.charAt(i+1)=='*') {depth++; i+=2;}
                    else if(i+1<sql.length() && sql.charAt(i)=='*' && sql.charAt(i+1)=='/') {depth--; i+=2;}
                    else i++;
                }
                if(depth!=0) throw invalid("注释未闭合"); continue;
            }
            if (c=='\'' || c=='"') {
                char quote=c; i++; StringBuilder value=new StringBuilder(); boolean closed=false;
                while(i<sql.length()) {
                    char next=sql.charAt(i++);
                    if(next=='\\' || next=='\0') throw invalid("不支持反斜线转义或 NUL");
                    if(next==quote) {
                        if(i<sql.length() && sql.charAt(i)==quote) { value.append(quote); i++; }
                        else { closed=true; break; }
                    } else value.append(next);
                }
                if(!closed) throw invalid("引号未闭合");
                result.add(new Token(value.toString(),start,i,quote=='"',true)); continue;
            }
            if ((c>='A' && c<='Z') || (c>='a' && c<='z') || c=='_') {
                i++; while(i<sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i)=='_')) i++;
                result.add(new Token(sql.substring(start,i),start,i,true,false)); continue;
            }
            if (c>='0' && c<='9') {
                i++; while(i<sql.length() && (Character.isDigit(sql.charAt(i)) || sql.charAt(i)=='.')) i++;
                result.add(new Token(sql.substring(start,i),start,i,false,false)); continue;
            }
            if ("(),.;=<>!+-*/%".indexOf(c)>=0) {i++; result.add(new Token(String.valueOf(c),start,i,false,false)); continue;}
            throw invalid("含不支持的字符或动态参数");
        }
        return result;
    }
}
