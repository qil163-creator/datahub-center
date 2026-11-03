package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.hutool.core.util.StrUtil;

/**
 * 元数据结构语义版本工具
 *
 * 版本形态 vX.Y.Z，由 diff 引擎按变更性质自动推导：
 * - MAJOR（破坏性）：删除字段、字段类型变更、长度/精度收窄、主键构成变化、表重建
 * - MINOR（兼容新增）：新增字段、索引/分区变化、长度/精度扩大、可空性与约束变化
 * - PATCH（描述类）：源库注释、字段顺序、默认值变更
 *
 * @author Li
 */
public final class MetaVersionUtils {

    /** 变更级别：无变更 */
    public static final int LEVEL_NONE = 0;

    /** 变更级别：描述类 */
    public static final int LEVEL_PATCH = 1;

    /** 变更级别：兼容新增 */
    public static final int LEVEL_MINOR = 2;

    /** 变更级别：破坏性 */
    public static final int LEVEL_MAJOR = 3;

    /** 首个基线版本 */
    public static final String FIRST_VERSION = "v1.0.0";

    /**
     * 限制元数据版本工具类的直接实例化。
     */
    private MetaVersionUtils() {
    }

    /**
     * 按变更级别推进版本号；版本非法时从首个基线重新开始
     */
    public static String bump(String version, int level) {
        int[] parts = parse(version);
        switch (level) {
            case LEVEL_MAJOR:
                return format(parts[0] + 1, 0, 0);
            case LEVEL_MINOR:
                return format(parts[0], parts[1] + 1, 0);
            case LEVEL_PATCH:
                return format(parts[0], parts[1], parts[2] + 1);
            default:
                return format(parts[0], parts[1], parts[2]);
        }
    }

    /**
     * 解析 vX.Y.Z，非法版本返回 {1,0,0}
     */
    public static int[] parse(String version) {
        if (StrUtil.isBlank(version)) {
            return new int[]{1, 0, 0};
        }
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        String[] segments = value.split("\\.");
        if (segments.length != 3) {
            return new int[]{1, 0, 0};
        }
        try {
            return new int[]{Integer.parseInt(segments[0]), Integer.parseInt(segments[1]),
                    Integer.parseInt(segments[2])};
        } catch (NumberFormatException ex) {
            return new int[]{1, 0, 0};
        }
    }

    /**
     * 按版本号约定格式化元数据版本标识。
     */
    public static String format(int major, int minor, int patch) {
        return "v" + major + "." + minor + "." + patch;
    }

    /**
     * 取两个级别中更高的一个
     */
    public static int maxLevel(int a, int b) {
        return Math.max(a, b);
    }

}
