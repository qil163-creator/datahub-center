package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 汇总探查字段的敏感依据：字段 sensitiveFlag / safetyLevelId、表 sensitiveFlag，以及该数据源下
 * 已确认（CONFIRMED）的敏感字段打标。打标读取失败时全部字段按敏感处理（失败关闭）。
 *
 * @author Li
 */
@Slf4j
@Component
public class ProfileSensitivityResolver {

    @Resource
    private SafetyFieldTagMapper fieldTagMapper;

    /**
     * 按字段名（小写）返回敏感依据。
     */
    public Map<String, ProfileColumnSensitivity> resolve(MetaTableDO table, String schemaName,
            List<MetaColumnDO> columns) {
        Map<String, SafetyFieldTagDO> tags = new HashMap<>();
        boolean unresolved = false;
        try {
            tags = confirmedTags(table, schemaName);
        } catch (RuntimeException ex) {
            log.warn("[resolve][元数据表({}) 敏感打标读取失败，按敏感处理] {}", table.getId(), ex.getClass().getSimpleName());
            unresolved = true;
        }
        boolean tableSensitive = Objects.equals(table.getSensitiveFlag(), 1);
        Map<String, ProfileColumnSensitivity> result = new HashMap<>();
        for (MetaColumnDO column : columns) {
            String key = lower(column.getColumnName());
            SafetyFieldTagDO tag = tags.get(key);
            result.put(key, new ProfileColumnSensitivity(table.getId(), column.getId(), table.getDatasourceId(),
                    schemaName, table.getTableName(), column.getColumnName(),
                    Objects.equals(column.getSensitiveFlag(), 1), column.getSafetyLevelId(), tableSensitive,
                    tag != null, tag == null ? null : tag.getLevelId(), unresolved));
        }
        return result;
    }

    /**
     * 读取该表已确认的敏感打标；打标表名可能是裸表名或 schema.表名，按不区分大小写匹配。
     */
    private Map<String, SafetyFieldTagDO> confirmedTags(MetaTableDO table, String schemaName) {
        Set<String> names = new LinkedHashSet<>();
        names.add(table.getTableName());
        names.add(lower(table.getTableName()));
        if (schemaName != null) {
            names.add(schemaName + "." + table.getTableName());
            names.add(lower(schemaName + "." + table.getTableName()));
        }
        List<SafetyFieldTagDO> tags = fieldTagMapper.selectList(new LambdaQueryWrapperX<SafetyFieldTagDO>()
                .eq(SafetyFieldTagDO::getDatabaseId, table.getDatasourceId())
                .eq(SafetyFieldTagDO::getTagStatus, SafetyFieldTagDO.TAG_STATUS_CONFIRMED)
                .in(SafetyFieldTagDO::getTableName, names));
        Map<String, SafetyFieldTagDO> result = new HashMap<>();
        for (SafetyFieldTagDO tag : tags) {
            if (tag.getColumnName() != null) {
                result.putIfAbsent(lower(tag.getColumnName()), tag);
            }
        }
        return result;
    }

    /**
     * 统一小写比较键。
     */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

}
