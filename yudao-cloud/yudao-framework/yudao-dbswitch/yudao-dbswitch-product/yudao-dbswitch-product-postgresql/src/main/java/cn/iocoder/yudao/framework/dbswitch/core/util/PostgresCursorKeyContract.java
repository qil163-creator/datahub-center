package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** PG16 键比较契约不包含列名，从而保证显式列重命名仍安全。 */
public final class PostgresCursorKeyContract {

    /**
     * 初始化 {@code PostgresCursorKeyContract}。
     */
    private PostgresCursorKeyContract() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference, List<String> keys) throws SQLException {
        return describe(connection, reference, keys).nativeFingerprint();
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract describe(
            Connection connection, String reference, List<String> keys) throws SQLException {
        if (connection.getMetaData().getDatabaseMajorVersion() != 16) {
            throw new SQLException("Cursor key contract requires PostgreSQL 16");
        }
        String sql = "SELECT pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attcollation<>0,"
                + "c.collisdeterministic,n.nspname,c.collname,c.collprovider,c.collcollate,c.collctype,c.colliculocale,"
                + "c.collversion,pg_catalog.pg_collation_actual_version(c.oid),"
                + "d.datcollate,d.datctype,d.datlocprovider,d.daticulocale,d.datcollversion,pg_catalog.pg_database_collation_actual_version(d.oid) "
                + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_database d ON d.datname=current_database() "
                + "LEFT JOIN pg_catalog.pg_collation c ON c.oid=a.attcollation LEFT JOIN pg_catalog.pg_namespace n ON n.oid=c.collnamespace "
                + "WHERE a.attrelid=pg_catalog.to_regclass(?) AND a.attname=? AND a.attnum>0 AND NOT a.attisdropped";
        StringBuilder material = new StringBuilder("PG_CURSOR_KEY_V1");
        var domains = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract.Domain>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (String key : keys) {
                statement.setString(1, reference);
                statement.setString(2, key);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(2)) {
                        throw new SQLException("Cursor keys must be non-null");
                    }
                    material.append('\0').append(rows.getString(1));
                    domains.add(numericDomain(rows.getString(1)));
                    if (rows.getBoolean(3)) {
                        if (!rows.getBoolean(4)) {
                            throw new SQLException("Nondeterministic cursor key collations are unsupported");
                        }
                        for (int i = 5; i <= 18; i++) {
                            material.append('\0').append(rows.getString(i));
                        }
                    }
                    if (rows.next()) {
                        throw new SQLException("Cursor key metadata is ambiguous");
                    }
                }
            }
        }
        return new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL,
                DigestUtil.sha256Hex(material.toString()), domains);
    }

    /**
     * 根据数值原生类型确定游标键允许的精确值域。
     */
    private static cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract.Domain numericDomain(
            String type) {
        var integerBits = switch (type) {
            case "smallint" -> 16;
            case "integer" -> 32;
            case "bigint" -> 64;
            default -> 0;
        };
        if (integerBits != 0) {
            return cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract.Domain.integer(integerBits,
                    false);
        }
        var decimal = java.util.regex.Pattern.compile("numeric\\(([1-9][0-9]*),([0-9]+)\\)").matcher(type);
        return decimal.matches() ? cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract.Domain.decimal(
                Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)), false)
                : cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract.Domain.nativeOnly();
    }
}
