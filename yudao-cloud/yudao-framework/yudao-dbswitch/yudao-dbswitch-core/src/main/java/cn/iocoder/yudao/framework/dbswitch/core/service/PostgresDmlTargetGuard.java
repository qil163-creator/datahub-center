package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.sql.*;
import java.util.*;

/**
 * 在调用方持有的执行事务上，对精确 DML 关系加锁并检查。
 * 此处不获取连接、不提交、不回滚、不查找角色，也不处理租户。
 */
public final class PostgresDmlTargetGuard {

    /**
     * 初始化 {@code PostgresDmlTargetGuard}。
     */
    private PostgresDmlTargetGuard() {
    }

    /**
     * 锁定真实普通读取源，防止视图、分区或继承把另一个名称映射到写入目标。
     * 只需 SELECT 权限；保留调用方连接及 READ_COMMITTED 事务，不提交、回滚或关闭。
     * 写入触发器及非 SELECT 规则不会在读取时执行，因此不作为读取源的拒绝条件。
     */
    public static void lockAndValidateReadSource(Connection connection, String schema, String table) {
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, table);
        try {
            var metadata = connection.getMetaData();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() < 12) {
                throw new IllegalArgumentException("Native read-source checks require PostgreSQL 12 or newer");
            }
            if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalArgumentException(
                        "Exact read-source checks require the caller's READ COMMITTED transaction");
            }
            try (Statement settings = connection.createStatement()) {
                settings.setQueryTimeout(15);
                settings.execute("SET LOCAL lock_timeout = '5s'");
            }
            String initialOid = inspect(connection, schema, table, true);
            try (Statement lock = connection.createStatement()) {
                lock.setQueryTimeout(15);
                lock.execute("LOCK TABLE ONLY " + relation + " IN ACCESS SHARE MODE");
            }
            if (!initialOid.equals(inspect(connection, schema, table, true))) {
                throw new IllegalArgumentException("Read-source identity changed while acquiring its lock");
            }
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Unable to lock and verify the ordinary PostgreSQL read source");
        }
    }

    /**
     * 校验计划。
     */
    public static void validatePlan(SqlScriptPlan plan, List<RestrictedDmlTarget> targets) {
        if (plan == null || plan.product() != ProductTypeEnum.POSTGRESQL || targets == null
                || plan.steps().isEmpty() || plan.steps().size() > 500 || targets.size() != plan.steps().size()) {
            throw new IllegalArgumentException("Only bounded PostgreSQL DML plans with exact targets are supported");
        }
        for (int index = 0; index < plan.steps().size(); index++) {
            var step = plan.steps().get(index);
            var target = targets.get(index);
            if (target == null || step.ordinal() != index + 1 || target.ordinal() != step.ordinal()
                    || step.kind() != SqlStatementClassifier.Kind.DML) {
                throw new IllegalArgumentException("Authorized DML targets must match every statement ordinal");
            }
            var shape = RestrictedDmlAuthorization.inspect(plan.product(), step.sql(), null);
            if (!target.schemaName().equals(shape.schemaName()) || !target.tableName().equals(shape.tableName())
                    || shape.parameterCount() != step.parameterCount()) {
                throw new IllegalArgumentException("SQL statement differs from its authorized physical target");
            }
        }
    }

    /**
     * 在锁定目标对象后校验当前定义与预期一致。
     */
    public static void lockAndValidate(Connection connection, SqlScriptPlan plan, List<RestrictedDmlTarget> targets) {
        validatePlan(plan, targets);
        try {
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalArgumentException(
                        "Exact DML target checks require a writable READ COMMITTED transaction");
            }
            // 按序获取锁以限制死锁风险；本地超时不在提交或回滚后泄漏。
            try (Statement settings = connection.createStatement()) {
                settings.setQueryTimeout(15);
                settings.execute("SET LOCAL lock_timeout = '5s'");
            }
            Map<String, RestrictedDmlTarget> sorted = new TreeMap<>();
            for (var target : targets) {
                sorted.put(qualified(target), target);
            }
            for (var entry : sorted.entrySet()) {
                String initialOid = inspect(connection, entry.getValue());
                try (Statement lock = connection.createStatement()) {
                    lock.setQueryTimeout(15);
                    lock.execute("LOCK TABLE ONLY " + entry.getKey() + " IN SHARE ROW EXCLUSIVE MODE");
                }
                if (!initialOid.equals(inspect(connection, entry.getValue()))) {
                    throw new IllegalArgumentException("DML target identity changed while acquiring its lock");
                }
            }
        } catch (SQLException failure) {
            // 驱动消息可能包含连接详情和调用方编写的 SQL。
            throw new IllegalArgumentException("Unable to lock and verify the authorized DML targets");
        }
    }

    /**
     * 读取 PostgreSQL 目标表的原生结构事实，并核对受限 DML 所需条件。
     */
    private static String inspect(Connection connection, RestrictedDmlTarget target) throws SQLException {
        return inspect(connection, target.schemaName(), target.tableName(), false);
    }

    /** 读写边界复用同一份关系身份查询，读取只限制会扩展或重写 SELECT 的结构。 */
    private static String inspect(Connection connection, String schema, String table,
            boolean readSource) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.oid::text,c.relkind,c.relispartition,
                  EXISTS(SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid=c.oid) AS has_trigger,
                  EXISTS(SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class=c.oid) AS has_rule,
                  EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) AS inherited,
                  EXISTS(SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class=c.oid AND r.ev_type='1') AS has_select_rule
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relname=?
                """)) {
            statement.setQueryTimeout(15);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !"r".equals(row.getString(2)) || row.getBoolean(3)
                        || row.getBoolean(6) || (readSource ? row.getBoolean(7) : row.getBoolean(4) || row.getBoolean(
                        5))) {
                    throw new IllegalArgumentException(readSource
                            ? "Read source must be an ordinary table without partitions, inheritance or SELECT rewrite rules"
                            : "DML target must be an ordinary table without partitions, inheritance, triggers or rewrite rules");
                }
                String oid = row.getString(1);
                if (row.next()) {
                    throw new IllegalArgumentException("DML target identity is ambiguous");
                }
                return oid;
            }
        }
    }

    /**
     * 返回当前数据库对象经过引用处理的限定名称。
     */
    private static String qualified(RestrictedDmlTarget target) {
        return RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, target.schemaName(),
                target.tableName());
    }
}
