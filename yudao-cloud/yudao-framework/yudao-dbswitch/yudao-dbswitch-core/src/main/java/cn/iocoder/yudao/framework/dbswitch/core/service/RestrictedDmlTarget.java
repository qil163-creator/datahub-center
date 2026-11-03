package cn.iocoder.yudao.framework.dbswitch.core.service;

/** 与单个已授权语句关联的原始物理名称，不是 SQL 片段或角色授权。 */
public record RestrictedDmlTarget(int ordinal, String schemaName, String tableName) {

    /**
     * 校验构造参数并初始化 {@code RestrictedDmlTarget}。
     */
    public RestrictedDmlTarget {
        if (ordinal < 1 || schemaName == null || schemaName.isBlank() || tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("A DML target requires statement ordinal and exact physical names");
        }
    }
}
