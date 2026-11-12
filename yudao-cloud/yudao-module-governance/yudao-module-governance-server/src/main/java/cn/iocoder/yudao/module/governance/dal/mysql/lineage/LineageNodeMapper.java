package cn.iocoder.yudao.module.governance.dal.mysql.lineage;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageNodeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 数据血缘-节点 Mapper。
 */
@Mapper
public interface LineageNodeMapper extends BaseMapperX<LineageNodeDO> {

    /**
     * 批量插入节点，nodeKey 已存在时跳过（不加行锁、不改已有节点）。调用方须按 nodeKey 排序，避免并发首建时互相等待成环。
     */
    @Insert({"<script>",
            "INSERT INTO dh_governance_lineage_node (node_key, node_type, datasource_id, schema_name, table_name,",
            " external_kind, external_key, display_name, creator, updater) VALUES",
            "<foreach collection='nodes' item='row' separator=','>",
            "(#{row.nodeKey}, #{row.nodeType}, #{row.datasourceId,jdbcType=BIGINT}, #{row.schemaName,jdbcType=VARCHAR},",
            " #{row.tableName,jdbcType=VARCHAR}, #{row.externalKind,jdbcType=VARCHAR}, #{row.externalKey,jdbcType=VARCHAR},",
            " #{row.displayName,jdbcType=VARCHAR}, #{actor}, #{actor})",
            "</foreach>",
            " ON CONFLICT (node_key) DO NOTHING",
            "</script>"})
    int insertIgnoreExisting(@Param("nodes") List<LineageNodeDO> nodes, @Param("actor") String actor);

    /**
     * 只为尚无展示名的节点补齐展示名；已有展示名不被后续上报覆盖。
     */
    @Update({"<script>",
            "UPDATE dh_governance_lineage_node node SET display_name = v.display_name, updater = #{actor},",
            " update_time = CURRENT_TIMESTAMP FROM (VALUES",
            "<foreach collection='nodes' item='row' separator=','>",
            "(CAST(#{row.id} AS int8), CAST(#{row.displayName} AS varchar))",
            "</foreach>",
            ") AS v(id, display_name) WHERE node.id = v.id AND node.display_name IS NULL",
            "</script>"})
    int fillMissingDisplayNames(@Param("nodes") List<LineageNodeDO> nodes, @Param("actor") String actor);

    /**
     * 按规范化节点键批量查询。
     */
    default List<LineageNodeDO> selectListByNodeKeys(Collection<String> nodeKeys) {
        return nodeKeys.isEmpty() ? List.of() : selectList(LineageNodeDO::getNodeKey, nodeKeys);
    }

    /**
     * 按名称片段检索至少有一条表级或字段级边的节点；pattern 为已转义的 ILIKE 模式，exact 为小写原词。
     */
    @Select({"<script>",
            "SELECT n.* FROM dh_governance_lineage_node n WHERE n.deleted = 0",
            "<if test='datasourceId != null'> AND n.datasource_id = #{datasourceId}</if>",
            " AND (n.table_name ILIKE #{pattern} OR n.schema_name ILIKE #{pattern}",
            "      OR n.external_key ILIKE #{pattern} OR n.display_name ILIKE #{pattern})",
            " AND (EXISTS (SELECT 1 FROM dh_governance_lineage_table_edge e WHERE e.upstream_node_id = n.id)",
            "   OR EXISTS (SELECT 1 FROM dh_governance_lineage_table_edge e WHERE e.downstream_node_id = n.id)",
            "   OR EXISTS (SELECT 1 FROM dh_governance_lineage_column_edge e WHERE e.upstream_node_id = n.id)",
            "   OR EXISTS (SELECT 1 FROM dh_governance_lineage_column_edge e WHERE e.downstream_node_id = n.id))",
            " ORDER BY CASE WHEN lower(coalesce(n.table_name, n.external_key)) = #{exact} THEN 0 ELSE 1 END,",
            "   length(coalesce(n.table_name, n.external_key)), n.id",
            " LIMIT #{limit}",
            "</script>"})
    List<LineageNodeDO> selectSearchList(@Param("pattern") String pattern, @Param("exact") String exact,
            @Param("datasourceId") Long datasourceId, @Param("limit") int limit);

    /**
     * 返回给定节点中至少有一条表级或字段级边的节点编号。
     */
    @Select({"<script>",
            "SELECT n.id FROM dh_governance_lineage_node n WHERE n.id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " AND (EXISTS (SELECT 1 FROM dh_governance_lineage_table_edge e WHERE e.upstream_node_id = n.id)",
            "   OR EXISTS (SELECT 1 FROM dh_governance_lineage_table_edge e WHERE e.downstream_node_id = n.id)",
            "   OR EXISTS (SELECT 1 FROM dh_governance_lineage_column_edge e WHERE e.upstream_node_id = n.id)",
            "   OR EXISTS (SELECT 1 FROM dh_governance_lineage_column_edge e WHERE e.downstream_node_id = n.id))",
            "</script>"})
    List<Long> selectIdsHavingEdges(@Param("ids") Collection<Long> ids);

}
