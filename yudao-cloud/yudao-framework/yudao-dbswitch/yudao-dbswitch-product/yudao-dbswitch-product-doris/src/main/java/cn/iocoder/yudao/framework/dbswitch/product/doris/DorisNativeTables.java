package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.hutool.json.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;
import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport.*;

/** 原生计划使用结构化定义，并在任何破坏性请求前检查目标代次。 */
public final class DorisNativeTables {

    private static final String FORMAT = "DORIS_40_NATIVE_TABLE_V1";

    /**
     * 初始化 {@code DorisNativeTables}。
     */
    private DorisNativeTables() {
    }

    /** 纯原生传输准入；保留现有分区、索引语法。 */
    public static void preflightStreamLoad(NativeTableDefinition definition, Map<String, String> mapping) {
        if (definition.product() != ProductTypeEnum.DORIS || !FORMAT.equals(definition.format())
                || definition.document().length() > 2_097_152) {
            throw new IllegalArgumentException("Doris native definition is required");
        }
        var json = JSONUtil.parseObj(definition.document());
        if (!json.keySet().equals(Set.of("ddl", "physical"))) {
            throw new IllegalArgumentException("Doris native envelope is invalid");
        }
        Table source = parse(json.getStr("ddl"));
        if (!source.columns().stream().map(Column::name).toList().equals(definition.fields())) {
            throw new IllegalArgumentException("Doris native definition fields disagree");
        }
        Table desired = source.map(mapping);
        if (!Set.of("DUPLICATE", "UNIQUE").contains(desired.model())
                || desired.columns().stream().anyMatch(column -> column.kind().equals("json"))
                || desired.unique() && !"true".equals(desired.properties().get("enable_unique_key_merge_on_write"))
                || desired.properties().containsKey("function_column.sequence_col")) {
            throw new IllegalArgumentException(
                    "Doris Stream Load native type, aggregation or precedence requires a separate plan");
        }
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String name, int timeout) {
        try (var c = source.getConnection()) {
            Evidence first = inspect(c, schema, name, timeout), second = inspect(c, schema, name, timeout);
            if (!first.equals(second)) {
                throw new SQLException("Doris source changed during structure capture");
            }
            String document = new JSONObject().set("ddl", first.table().ddl(schema, name))
                    .set("physical", first.physical()).toString();
            return new NativeTableDefinition(ProductTypeEnum.DORIS, FORMAT, document,
                    first.table().columns().stream().map(Column::name).toList());
        } catch (Exception failure) {
            throw new IllegalStateException("Doris native structure capture failed");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition definition, String schema, String name,
            Map<String, String> mapping, boolean replace, int timeout) {
        return plan(target, definition, schema, name, mapping, replace, timeout, null, null);
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition definition, String schema, String name,
            Map<String, String> mapping, boolean replace, int timeout,
            String comment, cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions options) {
        relation(schema, name);
        if (definition.product() != ProductTypeEnum.DORIS || !definition.format()
                .equals(FORMAT) || definition.document().length() > 2_097_152) {
            throw new IllegalArgumentException("Doris native definition is required");
        }
        var json = JSONUtil.parseObj(definition.document());
        if (!json.keySet().equals(Set.of("ddl", "physical"))) {
            throw new IllegalArgumentException("Doris native envelope is invalid");
        }
        Table source = parse(json.getStr("ddl"));
        String physical = json.getStr("physical");
        if (physical == null || !physical.matches(
                "[1-9][0-9]{0,18}:[1-9][0-9]{0,18}:[1-9][0-9]{0,18}") || !definition.fields()
                .equals(source.columns().stream().map(Column::name).toList())) {
            throw new IllegalArgumentException("Doris native evidence is inconsistent");
        }
        Table desired = DorisTableLayouts.apply(source.map(mapping), comment, options);
        Evidence observed;
        try (var c = target.getConnection()) {
            observed = existing(c, schema, name, timeout);
            if (observed != null && (physical.equals(observed.physical()) || !replace && !desired.equals(
                    observed.table()))) {
                throw new SQLException("Doris target is source or differs from requested shape");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Doris native target preflight failed");
        }
        String sql = desired.ddl(schema, name), digest = cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                definition.document() + "\0" + sql + "\0" + replace);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private volatile String targetIdentity = observed == null ? null : observed.physical();

            /**
             * 返回摘要。
             */
            public String digest() {
                return digest;
            }

            /**
             * 说明当前目标计划是否会替换已有对象。
             */
            public boolean replacesExisting() {
                return replace && observed != null;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            public boolean requiresExclusiveDdlOwnership() {
                return replacesExisting();
            }

            /**
             * 返回当前操作恢复时需要关联的资源信息。
             */
            public List<NativeRecoveryResource> recoveryResources() {
                return List.of(new NativeRecoveryResource(ProductTypeEnum.DORIS, schema, name,
                        NativeRecoveryResource.Role.TARGET, targetIdentity));
            }

            // 与写入器相同的优先级规则，但在任何 DDL 前检查，而非创建目标后。

            /**
             * 在数据搬运前检查源端与目标端的兼容条件。
             */
            @Override
            public void preflightDataTransfer() {
                if (desired.unique() && !"true".equals(
                        desired.properties().get("enable_unique_key_merge_on_write")) || desired.properties()
                        .containsKey("function_column.sequence_col")) {
                    throw new IllegalArgumentException("Doris native write precedence requires a separate plan");
                }
            }

            /**
             * 在当前数据库连接上执行给定 SQL 语句。
             */
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("Doris native plan is single-use");
                }
                NonTransactionalWriteExecution.execute(target, (connection, attempt) -> {
                    if (!connection.getAutoCommit()) {
                        throw new SQLException("Doris native DDL requires idle connection");
                    }
                    Evidence current = existing(connection, schema, name, timeout);
                    if (!Objects.equals(current, observed)) {
                        throw new SQLException("Doris target changed after preflight");
                    }
                    try (var literals = LiteralMode.open(connection, timeout)) {
                        if (current != null && replace) {
                            executeDdl(connection, attempt, "DROP TABLE " + relation(schema, name), timeout);
                        }
                        if (current == null || replace) {
                            executeDdl(connection, attempt, sql, timeout);
                            Evidence created = inspect(connection, schema, name, timeout);
                            targetIdentity = created.physical();
                            if (!desired.equals(created.table())) {
                                throw new SQLException("Doris created structure does not match requested definition");
                            }
                        }
                    }
                    return 0;
                });
            }
        };
    }

    /**
     * 返回当前已有。
     */
    private static Evidence existing(Connection c, String schema, String name, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT TABLE_TYPE,ENGINE FROM internal.information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(2);
            p.setString(1, schema);
            p.setString(2, name);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                if (!"BASE TABLE".equals(r.getString(1)) || !"Doris".equals(r.getString(2)) || r.next()) {
                    throw new SQLException("Doris target namespace is not a native table");
                }
            }
        }
        return inspect(c, schema, name, timeout);
    }

    /**
     * 在当前连接上执行给定的 DDL 语句。
     */
    private static void executeDdl(Connection c, NonTransactionalWriteExecution.Attempt attempt, String sql,
            int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            attempt.sending();
            s.execute(sql);
        }
    }
}
