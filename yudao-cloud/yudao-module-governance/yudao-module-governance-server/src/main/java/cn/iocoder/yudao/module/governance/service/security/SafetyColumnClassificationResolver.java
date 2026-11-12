package cn.iocoder.yudao.module.governance.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 解析物理列的安全分类，供动态脱敏使用。只读，不做授权判断。
 * <p>
 * 来源优先级：
 * <ol>
 *   <li>已确认的敏感字段打标（{@code dh_governance_safety_field_tag}，状态 CONFIRMED 且有分类）；
 *       打标按"数据源 + 表 + 列"记录、不含 schema，因此同名表的打标会合并，只会多脱敏而不会少。</li>
 *   <li>元数据字段的敏感标记（{@code sensitive_flag = 1}）：有标记但没有任何已确认分类的列视为"未分类敏感"，
 *       由调用方失败关闭。</li>
 * </ol>
 * 名称比较大小写不敏感。
 */
@Component
public class SafetyColumnClassificationResolver {

    @Resource
    private SafetyFieldTagMapper fieldTagMapper;
    @Resource
    private MetaTableKeyResolver tableKeyResolver;
    @Resource
    private MetaColumnMapper columnMapper;

    /** 物理列键（名称已转小写）。 */
    public record ColumnKey(Long datasourceId, String schemaName, String tableName, String columnName) {

        /**
         * 规范化构造。
         */
        public static ColumnKey of(Long datasourceId, String schemaName, String tableName, String columnName) {
            return new ColumnKey(datasourceId, lower(schemaName), lower(tableName), lower(columnName));
        }

        /**
         * 所属表键。
         */
        public TableKey table() {
            return new TableKey(datasourceId, schemaName, tableName);
        }
    }

    /** 物理表键（名称已转小写）。 */
    public record TableKey(Long datasourceId, String schemaName, String tableName) {

        /**
         * 规范化构造。
         */
        public static TableKey of(Long datasourceId, String schemaName, String tableName) {
            return new TableKey(datasourceId, lower(schemaName), lower(tableName));
        }
    }

    /**
     * 列分类结果。
     *
     * @param categoryIds 已确认的分类
     * @param sensitive   元数据或打标认为该列敏感
     */
    public record ColumnClass(Set<Long> categoryIds, boolean sensitive) {

        static final ColumnClass NONE = new ColumnClass(Set.of(), false);

        /**
         * 敏感但没有分类：调用方须失败关闭。
         */
        public boolean unclassifiedSensitive() {
            return sensitive && categoryIds.isEmpty();
        }
    }

    /** 解析结果：每列的分类，以及每张表是否含敏感列。 */
    public record Resolution(Map<ColumnKey, ColumnClass> columns, Set<TableKey> sensitiveTables) {

        /**
         * 返回列分类；未知列视为无分类。
         */
        public ColumnClass of(ColumnKey key) {
            return columns.getOrDefault(key, ColumnClass.NONE);
        }

        /**
         * 判断表是否含敏感列。
         */
        public boolean sensitive(TableKey table) {
            return sensitiveTables.contains(table);
        }
    }

    /**
     * 批量解析列分类与表的敏感性。
     *
     * @param columns 需要分类的列
     * @param tables  需要判断是否含敏感列的表（可与列所属表重叠）
     */
    public Resolution resolve(Collection<ColumnKey> columns, Collection<TableKey> tables) {
        Set<TableKey> allTables = new LinkedHashSet<>(tables);
        columns.forEach(column -> allTables.add(column.table()));
        if (allTables.isEmpty()) {
            return new Resolution(Map.of(), Set.of());
        }
        Map<ColumnKey, Set<Long>> categories = new HashMap<>();
        Set<ColumnKey> sensitive = new HashSet<>();
        Set<TableKey> sensitiveTables = new HashSet<>();
        loadFieldTags(allTables, categories, sensitive, sensitiveTables);
        loadMetadataFlags(allTables, sensitive, sensitiveTables);
        Map<ColumnKey, ColumnClass> result = new HashMap<>();
        for (ColumnKey column : columns) {
            // 打标不含 schema：同一数据源同名表同名列的打标也适用。
            ColumnKey schemaLess = new ColumnKey(column.datasourceId(), null, column.tableName(), column.columnName());
            Set<Long> ids = new TreeSet<>(categories.getOrDefault(column, Set.of()));
            ids.addAll(categories.getOrDefault(schemaLess, Set.of()));
            boolean flagged = sensitive.contains(column) || sensitive.contains(schemaLess) || !ids.isEmpty();
            result.put(column, new ColumnClass(Set.copyOf(ids), flagged));
        }
        Set<TableKey> sensitiveResult = new HashSet<>();
        for (TableKey table : allTables) {
            if (sensitiveTables.contains(table) || sensitiveTables.contains(new TableKey(table.datasourceId(), null,
                    table.tableName()))) {
                sensitiveResult.add(table);
            }
        }
        return new Resolution(result, sensitiveResult);
    }

    /**
     * 已确认打标：按数据源与表名批量读取，表名用原样、小写、大写三种写法覆盖常见存储差异。
     */
    private void loadFieldTags(Set<TableKey> tables, Map<ColumnKey, Set<Long>> categories, Set<ColumnKey> sensitive,
                               Set<TableKey> sensitiveTables) {
        Map<Long, Set<String>> namesBySource = new HashMap<>();
        for (TableKey table : tables) {
            Set<String> names = namesBySource.computeIfAbsent(table.datasourceId(), ignored -> new LinkedHashSet<>());
            names.add(table.tableName());
            names.add(table.tableName().toUpperCase(Locale.ROOT));
        }
        for (Map.Entry<Long, Set<String>> entry : namesBySource.entrySet()) {
            List<SafetyFieldTagDO> tags = fieldTagMapper.selectList(new LambdaQueryWrapperX<SafetyFieldTagDO>()
                    .eq(SafetyFieldTagDO::getDatabaseId, entry.getKey())
                    .eq(SafetyFieldTagDO::getTagStatus, SafetyFieldTagDO.TAG_STATUS_CONFIRMED)
                    .in(SafetyFieldTagDO::getTableName, entry.getValue()));
            for (SafetyFieldTagDO tag : tags) {
                if (StrUtil.isBlank(tag.getTableName()) || StrUtil.isBlank(tag.getColumnName())) {
                    continue;
                }
                ColumnKey key = ColumnKey.of(tag.getDatabaseId(), null, tag.getTableName(), tag.getColumnName());
                sensitiveTables.add(new TableKey(tag.getDatabaseId(), null, lower(tag.getTableName())));
                if (tag.getCategoryId() != null) {
                    categories.computeIfAbsent(key, ignored -> new HashSet<>()).add(tag.getCategoryId());
                } else {
                    sensitive.add(key);
                }
            }
        }
    }

    /**
     * 元数据敏感标记：按表定位元数据后读取当前字段。
     */
    private void loadMetadataFlags(Set<TableKey> tables, Set<ColumnKey> sensitive, Set<TableKey> sensitiveTables) {
        List<MetaTableKeyResolver.TableKey> keys = tables.stream()
                .map(table -> new MetaTableKeyResolver.TableKey(table.datasourceId(), table.schemaName(),
                        table.tableName()))
                .toList();
        Map<MetaTableKeyResolver.TableKey, MetaTableKeyResolver.TableMatch> matches = tableKeyResolver.resolve(keys);
        if (matches.isEmpty()) {
            return;
        }
        Map<Long, TableKey> byTableId = new HashMap<>();
        matches.forEach((key, match) -> byTableId.put(match.tableId(),
                TableKey.of(key.datasourceId(), key.schemaName(), key.tableName())));
        List<MetaColumnDO> columns = columnMapper.selectList(new LambdaQueryWrapperX<MetaColumnDO>()
                .in(MetaColumnDO::getTableId, byTableId.keySet())
                .eq(MetaColumnDO::getSensitiveFlag, 1));
        for (MetaColumnDO column : columns) {
            TableKey table = byTableId.get(column.getTableId());
            if (table == null || StrUtil.isBlank(column.getColumnName())) {
                continue;
            }
            sensitive.add(ColumnKey.of(table.datasourceId(), table.schemaName(), table.tableName(),
                    column.getColumnName()));
            sensitiveTables.add(table);
        }
    }

    /**
     * 小写；空白视为 null。
     */
    static String lower(String value) {
        return StrUtil.isBlank(value) ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
