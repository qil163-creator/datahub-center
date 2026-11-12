package cn.iocoder.yudao.module.governance.service.metadata;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 按 (数据源, schema, 表名) 定位当前有效的元数据表，供血缘关联元数据与 dataforge 展开 {@code SELECT *} 共用。
 *
 * <p>匹配规则：{@code dh_governance_meta_db(datasource_id, schema_name)} → {@code dh_governance_meta_table(db_id, table_name)}。
 * 先做精确匹配，唯一命中即返回、多于一个视为歧义不命中；没有精确命中时再做大小写不敏感匹配，同样只接受唯一结果。
 * schema 为空时在该数据源的全部库下按表名匹配，因此只有表名在数据源内唯一时才命中。已删除（对象状态 3 或逻辑删除）
 * 的库、表、字段不参与匹配。全部查询按数据源和表名批量执行，不逐键访问数据库。</p>
 */
@Component
public class MetaTableKeyResolver {

    private static final int QUERY_BATCH_SIZE = 200;

    @Resource
    private MetaDbMapper dbMapper;
    @Resource
    private MetaTableMapper tableMapper;
    @Resource
    private MetaColumnMapper columnMapper;

    /**
     * 定位键。schemaName 为空串与 null 等价。
     */
    public record TableKey(Long datasourceId, String schemaName, String tableName) {
    }

    /**
     * 命中的元数据表。
     */
    public record TableMatch(Long tableId, Long dbId, Long datasourceId, String schemaName, String dbName,
                             String tableName, String businessName) {
    }

    /**
     * 批量定位；返回值只包含唯一命中的键。
     */
    public Map<TableKey, TableMatch> resolve(Collection<TableKey> keys) {
        List<TableKey> valid = keys.stream()
                .filter(key -> key != null && key.datasourceId() != null && StrUtil.isNotBlank(key.tableName()))
                .distinct().toList();
        if (valid.isEmpty()) {
            return Map.of();
        }
        Map<Long, MetaDbDO> dbs = loadDbs(valid.stream().map(TableKey::datasourceId).collect(Collectors.toSet()));
        if (dbs.isEmpty()) {
            return Map.of();
        }
        Set<String> lowerNames = new LinkedHashSet<>();
        valid.forEach(key -> lowerNames.add(lower(key.tableName())));
        List<MetaTableDO> tables = loadTables(dbs.keySet(), lowerNames);

        Map<TableKey, TableMatch> result = new LinkedHashMap<>();
        for (TableKey key : valid) {
            String schema = StrUtil.nullToEmpty(StrUtil.trim(key.schemaName()));
            String table = StrUtil.trim(key.tableName());
            MetaTableDO hit = unique(tables, dbs, key.datasourceId(),
                    db -> schema.isEmpty() || schema.equals(StrUtil.nullToEmpty(db.getSchemaName())),
                    row -> table.equals(row.getTableName()));
            if (hit == UNMATCHED) {
                hit = unique(tables, dbs, key.datasourceId(),
                        db -> schema.isEmpty() || lower(schema).equals(lower(db.getSchemaName())),
                        row -> lower(table).equals(lower(row.getTableName())));
            }
            if (hit != null && hit != UNMATCHED) {
                MetaDbDO db = dbs.get(hit.getDbId());
                result.put(key, new TableMatch(hit.getId(), hit.getDbId(), db.getDatasourceId(), db.getSchemaName(),
                        db.getDbName(), hit.getTableName(), hit.getBusinessName()));
            }
        }
        return result;
    }

    /**
     * 批量读取表的当前有效字段名（按字段序号、编号排序）。
     */
    public Map<Long, List<String>> loadColumnNames(Collection<Long> tableIds) {
        Map<Long, List<String>> result = new HashMap<>();
        if (tableIds.isEmpty()) {
            return result;
        }
        for (List<Long> batch : CollUtil.split(new ArrayList<>(new LinkedHashSet<>(tableIds)), QUERY_BATCH_SIZE)) {
            List<MetaColumnDO> columns = columnMapper.selectList(new LambdaQueryWrapperX<MetaColumnDO>()
                    .select(MetaColumnDO::getId, MetaColumnDO::getTableId, MetaColumnDO::getColumnName,
                            MetaColumnDO::getOrdinalPosition, MetaColumnDO::getObjectStatus)
                    .in(MetaColumnDO::getTableId, batch)
                    .and(q -> q.isNull(MetaColumnDO::getObjectStatus)
                            .or().ne(MetaColumnDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED))
                    .orderByAsc(MetaColumnDO::getTableId)
                    .orderByAsc(MetaColumnDO::getOrdinalPosition)
                    .orderByAsc(MetaColumnDO::getId));
            for (MetaColumnDO column : columns) {
                result.computeIfAbsent(column.getTableId(), ignored -> new ArrayList<>()).add(column.getColumnName());
            }
        }
        return result;
    }

    /**
     * 读取单个当前有效元数据表及其库；不存在或已删除时返回 null。
     */
    public TableMatch getTable(Long tableId) {
        MetaTableDO table = tableId == null ? null : tableMapper.selectById(tableId);
        if (table == null || Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            return null;
        }
        MetaDbDO db = dbMapper.selectById(table.getDbId());
        if (db == null || Objects.equals(db.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            return null;
        }
        return new TableMatch(table.getId(), db.getId(), db.getDatasourceId(), db.getSchemaName(), db.getDbName(),
                table.getTableName(), table.getBusinessName());
    }

    /**
     * 按业务名称片段（大小写不敏感，通配符按字面匹配）检索当前有效元数据表（最多 limit 个），供血缘节点按中文名检索。
     */
    public List<TableMatch> searchByBusinessName(String keyword, Long datasourceId, int limit) {
        if (StrUtil.isBlank(keyword) || limit <= 0) {
            return List.of();
        }
        List<MetaTableDO> tables = tableMapper.selectList(new LambdaQueryWrapperX<MetaTableDO>()
                .eqIfPresent(MetaTableDO::getDatasourceId, datasourceId)
                .apply("business_name ILIKE {0}", "%" + escapeLike(keyword.strip()) + "%")
                .select(MetaTableDO::getId, MetaTableDO::getDbId, MetaTableDO::getTableName,
                        MetaTableDO::getBusinessName)
                .and(q -> q.isNull(MetaTableDO::getObjectStatus)
                        .or().ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED))
                .orderByAsc(MetaTableDO::getId)
                .last("LIMIT " + limit));
        if (tables.isEmpty()) {
            return List.of();
        }
        Map<Long, MetaDbDO> dbs = new HashMap<>();
        for (MetaDbDO db : dbMapper.selectByIds(tables.stream().map(MetaTableDO::getDbId).distinct().toList())) {
            if (!Objects.equals(db.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
                dbs.put(db.getId(), db);
            }
        }
        List<TableMatch> result = new ArrayList<>();
        for (MetaTableDO table : tables) {
            MetaDbDO db = dbs.get(table.getDbId());
            if (db != null) {
                result.add(new TableMatch(table.getId(), db.getId(), db.getDatasourceId(), db.getSchemaName(),
                        db.getDbName(), table.getTableName(), table.getBusinessName()));
            }
        }
        return result;
    }

    /** 精确阶段没有任何候选的标记，与“有候选但不唯一”（null）区分 */
    private static final MetaTableDO UNMATCHED = new MetaTableDO();

    /**
     * 在数据源内按库条件与表条件筛选：无候选返回 UNMATCHED，唯一返回该表，多于一个返回 null（歧义）。
     */
    private static MetaTableDO unique(List<MetaTableDO> tables, Map<Long, MetaDbDO> dbs, Long datasourceId,
            Predicate<MetaDbDO> dbFilter, Predicate<MetaTableDO> tableFilter) {
        MetaTableDO found = null;
        for (MetaTableDO table : tables) {
            MetaDbDO db = dbs.get(table.getDbId());
            if (db == null || !Objects.equals(db.getDatasourceId(), datasourceId)
                    || !dbFilter.test(db) || !tableFilter.test(table)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = table;
        }
        return found == null ? UNMATCHED : found;
    }

    private Map<Long, MetaDbDO> loadDbs(Set<Long> datasourceIds) {
        Map<Long, MetaDbDO> result = new HashMap<>();
        for (List<Long> batch : CollUtil.split(new ArrayList<>(datasourceIds), QUERY_BATCH_SIZE)) {
            List<MetaDbDO> dbs = dbMapper.selectList(new LambdaQueryWrapperX<MetaDbDO>()
                    .select(MetaDbDO::getId, MetaDbDO::getDatasourceId, MetaDbDO::getDbName, MetaDbDO::getSchemaName,
                            MetaDbDO::getObjectStatus)
                    .in(MetaDbDO::getDatasourceId, batch)
                    .and(q -> q.isNull(MetaDbDO::getObjectStatus)
                            .or().ne(MetaDbDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)));
            dbs.forEach(db -> result.put(db.getId(), db));
        }
        return result;
    }

    private List<MetaTableDO> loadTables(Set<Long> dbIds, Set<String> lowerNames) {
        List<MetaTableDO> result = new ArrayList<>();
        List<Long> dbList = new ArrayList<>(dbIds);
        for (List<String> names : CollUtil.split(new ArrayList<>(lowerNames), QUERY_BATCH_SIZE)) {
            for (List<Long> dbBatch : CollUtil.split(dbList, QUERY_BATCH_SIZE)) {
                result.addAll(tableMapper.selectList(new LambdaQueryWrapperX<MetaTableDO>()
                        .select(MetaTableDO::getId, MetaTableDO::getDbId, MetaTableDO::getTableName,
                                MetaTableDO::getBusinessName, MetaTableDO::getObjectStatus)
                        .in(MetaTableDO::getDbId, dbBatch)
                        .and(q -> q.isNull(MetaTableDO::getObjectStatus)
                                .or().ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED))
                        .and(q -> {
                            for (String name : names) {
                                q.or().apply("lower(table_name) = {0}", name);
                            }
                        })));
            }
        }
        return result;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String lower(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

}
