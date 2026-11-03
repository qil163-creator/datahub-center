package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.common.util.PatterNameUtils;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;

import java.util.Locale;

/** 迁移计划和实际执行共用的目标表命名；不修改输入配置。 */
public final class MigrationTableNames {

    /**
     * 初始化 {@code MigrationTableNames}。
     */
    private MigrationTableNames() {
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    public static String resolve(SourceDataSourceProperties source, TargetDataSourceProperties target, String table) {
        MapperConfig override = source.getConfigMap() == null ? null : source.getConfigMap().get(table);
        if (SourceType.TABLE.getCode().equals(source.getSourceType()) && override != null
                && override.getTargetTableName() != null) {
            String explicit = override.getTargetTableName();
            cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                    .quotePhysicalIdentifier(target.getTargetProductType(), explicit);
            return explicit;
        }
        var mappings = source.getRegexTableMapper();
        if (!MapperType.ALL.name().equals(source.getMapperType())) {
            MapperConfig config = source.getConfigMap() == null ? new MapperConfig()
                    : source.getConfigMap().getOrDefault(table, new MapperConfig());
            mappings = config.getRegexTableMapper();
        }
        String name = SourceType.SQL.getCode().equals(source.getSourceType()) ? target.getTargetTable()
                : PatterNameUtils.getFinalName(table, mappings);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("目标表名不能为空");
        }
        String prefix = target.getTablePrefix();
        if (SourceType.TABLE.getCode().equals(source.getSourceType())
                && prefix != null && !prefix.isBlank() && !name.startsWith(prefix)) {
            name = prefix + name;
        }
        boolean hive = ProductTypeEnum.HIVE.equals(target.getTargetProductType());
        if (hive) {
            name = name.toLowerCase(Locale.ROOT);
        } else if (target.getTableNameCase() != null) {
            name = target.getTableNameCase().convert(name);
        } else {
            if (Boolean.TRUE.equals(target.getLowercase())) {
                name = name.toLowerCase(Locale.ROOT);
            }
            if (Boolean.TRUE.equals(target.getUppercase())) {
                name = name.toUpperCase(Locale.ROOT);
            }
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("目标表名转换后不能为空");
        }
        return name;
    }
}
