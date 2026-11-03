package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;

/** 两个已显式接入产品的 pg_catalog 字节投影机制；不因协议或继承自动开放其他产品。 */
final class PgCatalogKeyProjections {

    /**
     * 初始化 {@code PgCatalogKeyProjections}。
     */
    private PgCatalogKeyProjections() {
    }

    /**
     * 拒绝当前实现未支持的 {@code text} 操作。
     */
    static BoundedTextKeyProjection text(ProductTypeEnum product, String alias, String column, int maxBytes) {
        if (product != ProductTypeEnum.OPENGAUSS && product != ProductTypeEnum.GREENPLUM) {
            throw new UnsupportedOperationException("Unverified native text projection product");
        }
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid text key byte limit");
        }
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(product, alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(product, column);
        String bytes = "pg_catalog.convert_to(CAST(" + field + " AS pg_catalog.text), 'UTF8')";
        String value = "CASE WHEN pg_catalog.octet_length(" + bytes + ") <= " + maxBytes + " THEN " + bytes + " ELSE NULL END";
        // 空标量子查询保留未转换源列的原生类型；NULLIF 的等号可能把 varchar 推导成 text。
        return new TextProjection(bytes, value, value, "(SELECT " + field + " WHERE FALSE)", maxBytes);
    }

    /**
     * 返回当前混合。
     */
    static BoundedKeyComponentProjection mixed(ProductTypeEnum product, String alias, String column, int maxBytes) {
        return new MixedProjection(text(product, alias, column, maxBytes));
    }

    /**
     * 根据位宽构建有符号整数类型定义。
     */
    static int signedIntegerType(ResultSetMetaData metadata, int column) throws SQLException {
        if (!metadata.isSigned(column)) {
            return 0;
        }
        int jdbc = metadata.getColumnType(column);
        String nativeType = metadata.getColumnTypeName(column);
        if (nativeType == null) {
            return 0;
        }
        return switch (nativeType) {
            case "int2" -> jdbc == Types.SMALLINT ? jdbc : 0;
            case "int4" -> jdbc == Types.INTEGER ? jdbc : 0;
            case "int8" -> jdbc == Types.BIGINT ? jdbc : 0;
            default -> 0;
        };
    }

    private record TextProjection(String identitySql, String groupSql, String valueSql, String typeSql, int maxBytes)
            implements BoundedTextKeyProjection {

        /**
         * 0 表示类型未经验证；不可由转成文本的结果反推源字段类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            if (metadata.getColumnType(column) != Types.VARCHAR) {
                return 0;
            }
            String nativeType = metadata.getColumnTypeName(column);
            return "varchar".equals(nativeType) ? Types.VARCHAR : "text".equals(nativeType) ? Types.LONGVARCHAR : 0;
        }

        /**
         * 有界的精确值过滤表达式，返回方言安全常量，不依赖会话反斜杠转义或 collation。
         */
        @Override
        public String equalitySql(String value) {
            if (value == null) {
                throw new IllegalArgumentException("A non-null text key is required");
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes || !value.equals(new String(bytes, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Invalid or oversized UTF-8 text key");
            }
            return identitySql + " = pg_catalog.decode('" + HexFormat.of().formatHex(bytes) + "', 'hex')";
        }
    }

    private record MixedProjection(BoundedTextKeyProjection text) implements BoundedKeyComponentProjection {

        /**
         * 按目标类型和数据库方言生成值表达式。
         */
        @Override
        public String valueSql() {
            return text.valueSql();
        }

        /**
         * 按当前列属性生成数据库类型 SQL。
         */
        @Override
        public String typeSql() {
            return text.typeSql();
        }

        /**
         * 生成按当前键列分组的 SQL 片段。
         */
        @Override
        public String groupSql() {
            return text.groupSql();
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            int integer = signedIntegerType(metadata, column);
            return integer != 0 ? integer : text.jdbcType(metadata, column);
        }
    }
}
