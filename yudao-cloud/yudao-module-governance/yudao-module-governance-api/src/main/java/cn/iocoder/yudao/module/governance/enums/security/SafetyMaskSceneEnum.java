package cn.iocoder.yudao.module.governance.enums.security;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;

import java.util.Arrays;

/**
 * 动态脱敏场景：数据从平台返回给用户的出口。
 * <p>
 * 每个场景对应一个白名单豁免场景（白名单配置为 ALL 时通配）和一个访问审计类型。
 * 新增场景只能追加，不得改变既有编码含义。
 */
public enum SafetyMaskSceneEnum implements ArrayValuable<String> {

    DATA_QUERY("数据查询", "QUERY", "QUERY"),
    ASSET_PREVIEW("资产预览", "QUERY", "EXPLORE"),
    MARKET_PREVIEW("集市预览", "SHARE", "ACCESS"),
    DATABASE_PREVIEW("数据库预览", "QUERY", "EXPLORE"),
    INDICATOR_QUERY("指标查询", "QUERY", "QUERY");

    public static final String[] ARRAYS = Arrays.stream(values()).map(Enum::name).toArray(String[]::new);

    private final String label;
    private final String whitelistScene;
    private final String accessType;

    /**
     * 初始化场景。
     */
    SafetyMaskSceneEnum(String label, String whitelistScene, String accessType) {
        this.label = label;
        this.whitelistScene = whitelistScene;
        this.accessType = accessType;
    }

    /**
     * 中文名称。
     */
    public String getLabel() {
        return label;
    }

    /**
     * 对应的白名单豁免场景。
     */
    public String getWhitelistScene() {
        return whitelistScene;
    }

    /**
     * 对应的访问审计类型。
     */
    public String getAccessType() {
        return accessType;
    }

    /**
     * 按编码解析；未知编码返回 null。
     */
    public static SafetyMaskSceneEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (SafetyMaskSceneEnum value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 返回全部编码。
     */
    @Override
    public String[] array() {
        return ARRAYS;
    }
}
