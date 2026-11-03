package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

/**
 * 产品观测到的关系名称和原生对象 ID。名称保留目录中的实际拼写。
 * 观测结果不等于授权、持久代际固定信息或具有事务稳定性的租约。
 */
public record CatalogRelationIdentity(ProductTypeEnum product, String schemaName, String tableName, String objectId) {

    /**
     * 校验构造参数并初始化 {@code CatalogRelationIdentity}。
     */
    public CatalogRelationIdentity {
        if (product == null || product == ProductTypeEnum.UNKNOWN
                || invalid(schemaName) || invalid(tableName) || invalid(objectId)) {
            throw new IllegalArgumentException("Complete native catalog identity is required");
        }
    }

    /**
     * 返回当前无效。
     */
    private static boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 2048 || value.indexOf('\0') >= 0;
    }
}
