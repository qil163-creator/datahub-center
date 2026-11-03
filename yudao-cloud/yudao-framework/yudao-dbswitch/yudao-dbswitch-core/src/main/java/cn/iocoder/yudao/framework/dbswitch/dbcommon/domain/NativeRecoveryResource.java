package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.Objects;

/** 不可变原生 DDL 对账依据，绝不授予删除或重放资源的权限。
 * TARGET 标识是规划前代际；STAGING 标识仅在原生观测后填充。
 * 标识为 null 表示未确认任何代际，不代表物理资源不存在。 */
public record NativeRecoveryResource(ProductTypeEnum product, String schema, String name, Role role,
                                     String expectedPhysicalIdentity) {

    public enum Role {TARGET, STAGING}

    /**
     * 校验构造参数并初始化 {@code NativeRecoveryResource}。
     */
    public NativeRecoveryResource {
        Objects.requireNonNull(product);
        Objects.requireNonNull(role);
        requireName(schema);
        requireName(name);
        if (expectedPhysicalIdentity != null) {
            requireName(expectedPhysicalIdentity);
        }
    }

    /**
     * 校验并取得名称。
     */
    private static void requireName(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.chars()
                .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Native recovery resource evidence is invalid");
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "NativeRecoveryResource[product=" + product + ",role=" + role + ",identityConfirmed=" + (expectedPhysicalIdentity != null) + "]";
    }
}
