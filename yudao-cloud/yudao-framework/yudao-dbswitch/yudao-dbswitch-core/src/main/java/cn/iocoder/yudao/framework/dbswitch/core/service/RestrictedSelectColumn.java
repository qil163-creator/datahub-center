package cn.iocoder.yudao.framework.dbswitch.core.service;

/**
 * 受限 SELECT 中出现的一个列引用。
 *
 * @param qualifier 限定符（表名或表别名），未写限定符时为 null
 * @param name      标识符原文（已去掉引号，保留大小写与空格）
 * @param quoted    原文是否使用了引号
 */
public record RestrictedSelectColumn(String qualifier, String name, boolean quoted) {

}
