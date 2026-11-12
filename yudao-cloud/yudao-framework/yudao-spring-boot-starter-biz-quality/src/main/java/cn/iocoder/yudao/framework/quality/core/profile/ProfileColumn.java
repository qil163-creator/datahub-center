package cn.iocoder.yudao.framework.quality.core.profile;

import java.util.Objects;

/**
 * 待探查的物理列：名称按物理标识符原样传入，由生成器经 DB Switch 方言引用，不接受 SQL 表达式。
 *
 * @param name 物理列名
 * @param kind 字段类别
 * @author Li
 */
public record ProfileColumn(String name, ProfileColumnKind kind) {

    /**
     * 校验列名与类别均已提供。
     */
    public ProfileColumn {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Profile column name is required");
        }
        Objects.requireNonNull(kind, "kind");
    }

}
