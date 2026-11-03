package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Oracle AI Database 26ai Free Release 23.26.3.0.0 的原生堆表标识。
 * 字典读取使用 {@code SYS.ALL_*} 与 {@code SYS.V_$VERSION}。此构建中 {@code SYS_CONTEXT} 不是
 * 模式对象，{@code SYS.SYS_CONTEXT} 报 ORA-00904；应调用 {@code SYS.STANDARD.SYS_CONTEXT}，
 * 防止当前模式中的同名函数替换它。不调用 {@code DBA_*}，因为
 * 仅有 SELECT 权限的用户会收到 ORA-00942。字典错误不得改写为对象不存在。
 * 此构建中的 {@code SYSTEM} 无法撤销 {@code PUBLIC} 对 {@code SYS.ALL_POLICIES} 的授权，
 * 会报 ORA-01927，授权者为 {@code SYS}。不使用 {@code V$DATABASE} 或 {@code V$INSTANCE}；
 * 在 PDB 内前者仍描述 CDB，后者则对该读取器不可见。
 * <p>
 * 此构建中，对非空表执行 {@code TRUNCATE} 会改变 {@code DATA_OBJECT_ID}，且不复用
 * 旧值。对已空表再次执行 {@code TRUNCATE} 时，
 * {@code DATA_OBJECT_ID} 与 {@code LAST_DDL_TIME} 均不改变，因此时间列不是
 * 代次标识。{@code ALTER TABLE MOVE} 也会改变 {@code DATA_OBJECT_ID}，它属于物理
 * 重组，会使旧指纹失效；{@code INSERT}、{@code UPDATE}、{@code DELETE}、
 * 注释与索引则不会。
 */
public final class OracleRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    static final String BANNER_PREFIX = "Oracle AI Database 26ai Free Release 23.26.3.0.0";

    private static final String VERSION_MARK = "Version 23.26.3.0.0";

    private static final String SQL = """
            SELECT (SELECT CASE WHEN COUNT(DISTINCT banner_full) = 1 THEN MIN(banner_full) END
                    FROM sys.v_$version
                    WHERE banner_full LIKE 'Oracle AI Database 26ai Free Release 23.26.3.0.0%'
                      AND INSTR(banner_full, 'Version 23.26.3.0.0') > 0),
                   SYS.STANDARD.SYS_CONTEXT('USERENV','CON_NAME'), SYS.STANDARD.SYS_CONTEXT('USERENV','CON_ID'),
                   SYS.STANDARD.SYS_CONTEXT('USERENV','CON_DBID'), SYS.STANDARD.SYS_CONTEXT('USERENV','CURRENT_USER'),
                   o.owner, o.object_name,
                   TO_CHAR(o.object_id, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''),
                   TO_CHAR(o.data_object_id, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''),
                   CASE WHEN o.object_type = 'TABLE' AND o.subobject_name IS NULL AND o.generated = 'N'
                         AND o.secondary = 'N' AND o.data_object_id IS NOT NULL AND t.temporary = 'N'
                         AND t.partitioned = 'NO' AND t.iot_type IS NULL AND t.nested = 'NO'
                         AND t.cluster_name IS NULL AND t.duration IS NULL
                         AND NOT EXISTS (SELECT 1 FROM sys.all_external_tables e
                                         WHERE e.owner = o.owner AND e.table_name = o.object_name)
                         AND NOT EXISTS (SELECT 1 FROM sys.all_mviews m
                                         WHERE m.owner = o.owner AND m.mview_name = o.object_name)
                         AND NOT EXISTS (SELECT 1 FROM sys.all_policies p
                                         WHERE p.object_owner = o.owner AND p.object_name = o.object_name)
                        THEN 1 ELSE 0 END
            FROM sys.all_objects o
            JOIN sys.all_tables t ON t.owner = o.owner AND t.table_name = o.object_name
            WHERE o.owner = ? AND o.object_name = ? AND o.object_type = 'TABLE' AND o.subobject_name IS NULL
            """;

    /**
     * 初始化 {@code OracleRelationIdentity}。
     */
    private OracleRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "\\." + IDENTIFIER)) {
            throw unsupported("Oracle quoted, unqualified or complex references are not verified");
        }
        String[] names = reference.toUpperCase(Locale.ROOT).split("\\.", -1);
        List<String> first = identity(connection, names);
        if (!first.equals(identity(connection, names))) {
            throw unsupported("Oracle native identity changed during capture");
        }
        return DigestUtil.sha256Hex(String.join("\0", first));
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String[] names) throws SQLException {
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, names[0]);
            statement.setString(2, names[1]);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("Oracle relation is absent or not a verified heap");
                }
                var fields = new ArrayList<>(List.of("ORACLE_23_26_3_0_0_HEAP_V1"));
                for (int index = 1; index <= 9; index++) {
                    String value = rows.getString(index);
                    int limit = index == 1 ? 1024 : 256;
                    if (value == null || value.isBlank() || value.length() > limit || value.indexOf('\0') >= 0) {
                        throw unsupported("Oracle native identity field is unavailable");
                    }
                    fields.add(value);
                }
                int predicate = rows.getInt(10);
                if (!fields.get(1).startsWith(BANNER_PREFIX) || !fields.get(1).contains(VERSION_MARK)
                        || !fields.get(2).matches("[A-Z][A-Z0-9_$#]{0,127}")
                        || !fields.get(3).matches("[1-9][0-9]{0,8}")
                        || !fields.get(4).matches("[1-9][0-9]{0,19}")
                        || !fields.get(5).matches("[A-Z][A-Z0-9_$#]{0,127}")
                        || !names[0].equals(fields.get(6)) || !names[1].equals(fields.get(7))
                        || !fields.get(8).matches("[1-9][0-9]{0,17}")
                        || !fields.get(9).matches("[1-9][0-9]{0,17}")
                        || rows.wasNull() || predicate != 1 || rows.next()) {
                    throw unsupported("Oracle version, resolved object or storage is not a verified heap");
                }
                return fields;
            }
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
