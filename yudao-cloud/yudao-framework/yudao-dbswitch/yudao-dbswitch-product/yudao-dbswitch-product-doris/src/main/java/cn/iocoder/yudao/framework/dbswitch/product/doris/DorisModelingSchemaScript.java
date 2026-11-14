package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

/** 仅接受单一物理表的普通建模 DDL；复用原生词法，不放行会话控制、查询或任意脚本。 */
public final class DorisModelingSchemaScript {

    private DorisModelingSchemaScript() {
    }

    public static SqlScriptPlan plan(String schema, String table, List<String> input) {
        relation(schema, table);
        if (input == null || input.isEmpty() || input.size() > 500) throw invalid();
        List<SqlScriptPlan.Step> steps = new ArrayList<>();
        long size = 0;
        for (String sql : input) {
            if (sql == null || (size += sql.length()) > 1_048_576) throw invalid();
            var parser = new Parser(sql);
            String canonical;
            if (parser.takeWord("CREATE")) {
                parser.word("TABLE");
                target(parser, schema, table);
                // 原生解析器检查其余所有列、模型、分区、分桶与属性，并重新渲染值。
                canonical = parse(sql).ddl(schema, table);
            } else {
                parser.word("ALTER");
                parser.word("TABLE");
                target(parser, schema, table);
                List<String> clauses = new ArrayList<>();
                do {
                    if (clauses.size() >= 2000) throw invalid();
                    clauses.add(clause(parser));
                } while (parser.take(","));
                parser.take(";");
                if (!parser.end()) throw invalid();
                canonical = "ALTER TABLE " + relation(schema, table) + " " + String.join(", ", clauses);
            }
            steps.add(new SqlScriptPlan.Step(steps.size() + 1, canonical, SqlStatementClassifier.Kind.DDL));
        }
        return new SqlScriptPlan(ProductTypeEnum.DORIS,
                SqlScriptPlan.digest(String.join(";\n", steps.stream().map(SqlScriptPlan.Step::sql).toList())), steps);
    }

    private static void target(Parser parser, String schema, String table) {
        if (!Objects.equals(parser.identifier(), schema)) throw invalid();
        parser.require(".");
        if (!Objects.equals(parser.identifier(), table)) throw invalid();
    }

    private static String clause(Parser parser) {
        boolean add = parser.takeWord("ADD");
        boolean drop = !add && parser.takeWord("DROP");
        if (!add && !drop) {
            parser.word("MODIFY");
            if (parser.takeWord("COMMENT")) return "MODIFY COMMENT " + literal(parser.string());
        }
        parser.word("COLUMN");
        String name = parser.identifier();
        if (drop) return "DROP COLUMN " + quote(name);
        if (!add && parser.takeWord("COMMENT")) {
            return "MODIFY COLUMN " + quote(name) + " COMMENT " + columnCommentLiteral(parser.string());
        }
        String type = parser.bare();
        if (parser.take("(")) {
            type += "(" + parser.number();
            if (parser.take(",")) type += "," + parser.number();
            parser.require(")");
            type += ")";
        }
        boolean nullable;
        if (parser.takeWord("NOT")) {
            parser.word("NULL");
            nullable = false;
        } else {
            parser.word("NULL");
            nullable = true;
        }
        String defaultSql = null;
        if (parser.takeWord("DEFAULT")) defaultSql = parser.takeWord("NULL") ? "NULL" : literal(parser.string());
        String comment = "";
        if (parser.takeWord("COMMENT")) comment = parser.string();
        var column = new Column(name, type, nullable, defaultSql, comment);
        return (add ? "ADD" : "MODIFY") + " COLUMN " + quote(column.name()) + " " + column.type()
                + (nullable ? " NULL" : " NOT NULL") + (defaultSql == null ? "" : " DEFAULT " + defaultSql)
                + " COMMENT " + columnCommentLiteral(comment);
    }

    /** 连接读取与结构比较共用原生观察，避免用 MySQL 主键目录代替 Doris 键模型。 */
    public static boolean matchesCreate(Connection connection, String schema, String table, String createSql,
            int timeoutSeconds) throws SQLException {
        return matchesCreate(schema, table, createSql,
                DorisJdbcSupport.inspect(connection, schema, table, timeoutSeconds).table());
    }

    /** 比较用户冻结的语义；允许服务端补充默认属性以及等价的副本数表示。 */
    public static boolean matchesCreate(String schema, String table, String createSql, Table actual) {
        var parser = new Parser(createSql);
        parser.word("CREATE");
        parser.word("TABLE");
        target(parser, schema, table);
        Table expected = parse(createSql);
        if (!expected.model().equals(actual.model()) || !expected.keys().equals(actual.keys())
                || !expected.distribution().equals(actual.distribution())
                || !Objects.equals(expected.partitioning(), actual.partitioning())
                || !expected.indexes().equals(actual.indexes()) || !expected.comment().equals(actual.comment())
                || expected.columns().size() != actual.columns().size()) return false;
        for (int i = 0; i < expected.columns().size(); i++) {
            var desired = expected.columns().get(i);
            var observed = actual.columns().get(i);
            if (!desired.name().equals(observed.name()) || !desired.type().equals(observed.type())
                    || desired.nullable() != observed.nullable() || !desired.comment().equals(observed.comment())
                    || !Objects.equals(desired.aggregation(), observed.aggregation())
                    || !Objects.equals(defaultValue(desired.defaultSql()), defaultValue(observed.defaultSql()))) return false;
        }
        boolean actualAuto = actual.buckets() == 0 || "true".equals(actual.properties().get("auto_bucket"));
        if (expected.buckets() == 0 ? !actualAuto : actualAuto || expected.buckets() != actual.buckets()) return false;
        String replicas = replicaAllocation(expected.properties());
        if (replicas != null && !replicas.equals(replicaAllocation(actual.properties()))) return false;
        for (var property : expected.properties().entrySet()) {
            if (property.getKey().equals("replication_num") || property.getKey().equals("replication_allocation")
                    || property.getKey().equals("auto_bucket")) continue;
            if (!property.getValue().equals(actual.properties().get(property.getKey()))) return false;
        }
        // 序列列改变 Unique 表的更新顺序，不能作为服务端自动补充的普通属性忽略。
        return Objects.equals(expected.properties().get("function_column.sequence_col"),
                actual.properties().get("function_column.sequence_col"))
                && Objects.equals(expected.properties().get("function_column.sequence_type"),
                actual.properties().get("function_column.sequence_type"));
    }

    private static String defaultValue(String value) {
        return value == null || value.equals("NULL") ? null : value;
    }

    private static String replicaAllocation(Map<String, String> properties) {
        String count = properties.get("replication_num"), allocation = properties.get("replication_allocation");
        if (count != null) {
            if (!count.matches("[1-9][0-9]{0,4}")) throw invalid();
            String normalized = "tag.location.default:" + Integer.parseInt(count);
            if (allocation != null && !normalized.equals(allocation.replaceAll("\\s+", ""))) throw invalid();
            return normalized;
        }
        return allocation == null ? null : allocation.replaceAll("\\s+", "");
    }

    /** 当前表没有活动变更才返回；超时和中断保留外部执行未知状态供业务对账。 */
    public static void await(Connection connection, String schema, String table, int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) throw new IllegalArgumentException("Invalid schema timeout");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        String query = "SHOW ALTER TABLE COLUMN FROM " + quote(schema) + " WHERE TableName = " + literal(table);
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new SQLException("Doris schema confirmation interrupted");
            boolean active = false;
            // CreateTime 只有秒级展示精度；按最新一条历史记录不能证明其他任务已结束。
            for (String state : List.of("PENDING", "WAITING_TXN", "RUNNING")) {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(Math.max(1, (int) java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(
                            Math.max(0, deadline - System.nanoTime()))));
                    statement.setMaxRows(1);
                    try (var rows = statement.executeQuery(query + " AND State = " + literal(state) + " LIMIT 1")) {
                        if (rows.next()) {
                            if (!table.equals(rows.getString("TableName")) || !state.equals(rows.getString("State"))) {
                                throw new SQLException("Doris schema job target or state mismatch");
                            }
                            active = true;
                        }
                    }
                }
            }
            if (!active) return;
            if (System.nanoTime() >= deadline) throw new SQLTimeoutException("Doris schema change is not confirmed");
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Doris schema confirmation interrupted");
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Doris modeling DDL requires one bound physical table and supported native syntax");
    }
}
