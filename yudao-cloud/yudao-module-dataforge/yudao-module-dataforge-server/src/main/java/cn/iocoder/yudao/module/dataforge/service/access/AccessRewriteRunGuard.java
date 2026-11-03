package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteMaterial;

import java.nio.charset.StandardCharsets;
import java.sql.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_RUN_REVIEW_REQUIRED;

/** 仅定义共享的 ACCESS 租约策略，复用调用方的短控制事务，不进行目标端 I/O。 */
final class AccessRewriteRunGuard {

    record Owner(long task, long run, String token, boolean recovery, int revision, String frozen, String schema,
                 int timeout) {

    }

    /**
     * 限制访问重写运行门禁工具的实例化，统一通过静态能力使用。
     */
    private AccessRewriteRunGuard() {
    }

    /**
     * 锁定访问重写运行门禁当前记录。
     *
     * @param connection 数据库连接
     * @param owner 所有者
     * @param completion 完成
     */
    static void lock(Connection connection, Owner owner, boolean completion) throws SQLException {
        if (connection.getAutoCommit()) {
            throw unavailable();
        }
        try (var statement = statement(connection, owner,
                "SELECT id FROM " + table(owner, "dh_dataforge_access") + " WHERE id=? AND deleted=0 FOR UPDATE")) {
            statement.setLong(1, owner.task);
            try (var row = statement.executeQuery()) {
                if (!row.next() || row.getLong(1) != owner.task || row.next()) {
                    throw unavailable();
                }
            }
        }
        try (var statement = statement(connection, owner,
                "SELECT data_access_id,execution_token,lease_until>clock_timestamp(),"
                        + "stop_requested,run_status,occupies_slot,protocol_version,execution_stage,config_json::text,release_revision,"
                        + "reconcile_token,reconcile_lease_until>clock_timestamp(),review_required FROM " + table(owner,
                        "dh_dataforge_access_task") + " WHERE id=? AND deleted=0 FOR UPDATE")) {
            statement.setLong(1, owner.run);
            try (var row = statement.executeQuery()) {
                if (!row.next() || row.getLong(1) != owner.task || !row.getBoolean(6)
                        || row.getInt(7) != 1 || !"EXECUTION_STARTED".equals(row.getString(8))
                        || !owner.frozen.equals(hash(row.getString(9))) || row.getInt(10) != owner.revision) {
                    throw unavailable();
                }
                String token = row.getString(owner.recovery ? 11 : 2);
                int status = row.getInt(5);
                if (completion) {
                    // 只有保留的运行时句柄能提供实际退出确认；租约被清除或过期绝不意味着获得新的执行归属权。
                    if (status != 2 && status != 5 || !owner.token.equals(token) && !(status == 5 && token == null)) {
                        throw unavailable();
                    }
                } else if (!owner.token.equals(token) || !row.getBoolean(owner.recovery ? 12 : 3) || row.getBoolean(4)
                        || status != (owner.recovery ? 5 : 2) || owner.recovery && !row.getBoolean(13)) {
                    throw unavailable();
                }
                if (row.next()) {
                    throw unavailable();
                }
            }
        }
    }

    /**
     * 计算输入内容摘要。
     *
     * @param value 待处理的值
     * @return 计算后的摘要或签名字符串
     */
    static String hash(String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw unavailable();
        }
        return GroupRewriteMaterial.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 拼接访问重写运行门禁。
     *
     * @param values 字段值集合
     * @return 转换后的字节序列
     */
    static byte[] join(String... values) {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(bytes)) {
            for (String value : values) {
                if (value == null) {
                    out.writeInt(-1);
                    continue;
                }
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length);
                out.write(encoded);
            }
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * 获取访问重写运行门禁的表。
     *
     * @param owner 所有者
     * @param table 表
     * @return 访问重写运行门禁的表文本
     */
    private static String table(Owner owner, String table) {
        if (owner.schema == null || !owner.schema.matches("[a-z][a-z0-9_-]{0,62}")) {
            throw unavailable();
        }
        return '"' + owner.schema + "\".\"" + table + '"';
    }

    /**
     * 获取访问重写运行门禁的语句。
     *
     * @param connection 数据库连接
     * @param owner 所有者
     * @param sql SQL
     * @return 预编译语句
     */
    private static PreparedStatement statement(Connection connection, Owner owner, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(owner.timeout);
        return statement;
    }

    /**
     * 构造当前依赖或资源不可用时使用的异常。
     *
     * @return 运行时异常
     */
    private static RuntimeException unavailable() {
        return exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
    }
}
