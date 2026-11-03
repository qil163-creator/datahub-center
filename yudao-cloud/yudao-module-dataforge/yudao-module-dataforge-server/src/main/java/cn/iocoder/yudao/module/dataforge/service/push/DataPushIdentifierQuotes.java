package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.regex.Pattern;

/**
 * 按源库产品类型给标识符加引号，避免保留字导致 MAX(column) 失败
 *
 * @author Li
 */
public final class DataPushIdentifierQuotes {

    private static final Pattern UNSAFE = Pattern.compile("[;\"'`\\\\.\\s]|--|/\\*");

    /**
     * 限制数据推送标识符引号工具的实例化，统一通过静态能力使用。
     */
    private DataPushIdentifierQuotes() {
    }

    /**
     * 获取数据推送标识符引号的引用列。
     *
     * @param product 产品
     * @param column 列
     * @return 数据推送标识符引号的引用列文本
     */
    public static String quoteColumn(ProductTypeEnum product, String column) {
        return quoteIdentifier(product, column, "增量字段");
    }

    /**
     * 按目标数据库方言引用 schema 标识符。
     *
     * @param product 产品
     * @param schema 结构
     * @return 按目标数据库方言引用 schema 标识符文本
     */
    public static String quoteSchema(ProductTypeEnum product, String schema) {
        return quoteIdentifier(product, schema, "模式名");
    }

    /**
     * 按目标数据库方言引用表标识符。
     *
     * @param product 产品
     * @param table 表
     * @return 按目标数据库方言引用表标识符文本
     */
    public static String quoteTable(ProductTypeEnum product, String table) {
        return quoteIdentifier(product, table, "表名");
    }

    /**
     * 按目标数据库方言引用标识符。
     *
     * @param product 产品
     * @param identifier 标识符
     * @param label 标签
     * @return 按目标数据库方言引用标识符文本
     */
    public static String quoteIdentifier(ProductTypeEnum product, String identifier, String label) {
        if (StrUtil.isBlank(identifier)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String name = identifier.trim();
        if (UNSAFE.matcher(name).find()) {
            throw new IllegalArgumentException(label + "不合法：" + identifier);
        }
        ProductTypeEnum type = product == null ? ProductTypeEnum.POSTGRESQL : product;
        return type.quoteName(name);
    }

}
