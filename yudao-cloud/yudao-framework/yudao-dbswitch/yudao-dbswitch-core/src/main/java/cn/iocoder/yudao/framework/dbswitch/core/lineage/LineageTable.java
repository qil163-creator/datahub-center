package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import java.util.Locale;
import java.util.Objects;

/**
 * 血缘中的一张物理表。名称已按目标产品的未加引号标识符规则折叠，引号包围的名称保持原样。
 *
 * @param schema 表命名空间（schema 型产品为 schema，catalog 型产品为库名）；未知时为 null
 * @param name   表名
 */
public record LineageTable(String schema, String name) {

    /**
     * 校验表名必填。
     */
    public LineageTable {
        Objects.requireNonNull(name, "name");
        if (schema != null && schema.isBlank()) {
            schema = null;
        }
    }

    /**
     * 返回大小写不敏感的匹配键，仅用于脚本内的临时表、建表结构和自引用判断。
     */
    public String matchKey() {
        return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "\u0000" + name.toLowerCase(Locale.ROOT);
    }

    /**
     * 判断两张表是否指向同一对象（大小写不敏感）。
     */
    public boolean sameAs(LineageTable other) {
        return other != null && matchKey().equals(other.matchKey());
    }

    /**
     * 返回 {@code schema.name} 形式的展示名。
     */
    public String displayName() {
        return schema == null ? name : schema + "." + name;
    }
}
