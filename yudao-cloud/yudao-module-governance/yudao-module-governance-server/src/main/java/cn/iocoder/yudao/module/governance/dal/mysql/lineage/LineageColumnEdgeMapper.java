package cn.iocoder.yudao.module.governance.dal.mysql.lineage;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageColumnEdgeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageColumnKey;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageEdgeHit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 数据血缘-字段级边 Mapper。边是派生数据，只用显式 {@code @Delete} 物理删除；(节点, 字段) 用行值 IN 批量查询。
 */
@Mapper
public interface LineageColumnEdgeMapper extends BaseMapperX<LineageColumnEdgeDO> {

    /**
     * 物理删除某来源的全部字段级边。
     */
    @Delete("DELETE FROM dh_governance_lineage_column_edge WHERE source_ref_id = #{sourceRefId}")
    int deleteBySourceRefId(@Param("sourceRefId") Long sourceRefId);

    /**
     * 批量插入字段级边（调用方已在报告内去重并分批）。
     */
    @Insert({"<script>",
            "INSERT INTO dh_governance_lineage_column_edge (source_ref_id, upstream_node_id, upstream_column,",
            " downstream_node_id, downstream_column, transform_type, transform_subtype, ambiguous, masking,",
            " statement_index, creator, updater) VALUES",
            "<foreach collection='edges' item='row' separator=','>",
            "(#{row.sourceRefId}, #{row.upstreamNodeId}, #{row.upstreamColumn}, #{row.downstreamNodeId},",
            " #{row.downstreamColumn}, #{row.transformType}, #{row.transformSubtype}, #{row.ambiguous}, #{row.masking},",
            " #{row.statementIndex,jdbcType=INTEGER}, #{actor}, #{actor})",
            "</foreach>",
            "</script>"})
    int insertEdges(@Param("edges") List<LineageColumnEdgeDO> edges, @Param("actor") String actor);

    /**
     * 一步展开：返回 frontier 字段的上游（upstream=true）或下游字段中尚未访问的 (节点, 字段)，最多 limit 个。
     */
    @Select({"<script>",
            "SELECT DISTINCT <choose><when test='upstream'>upstream_node_id AS node_id, upstream_column AS column_name</when>",
            "<otherwise>downstream_node_id AS node_id, downstream_column AS column_name</otherwise></choose>",
            " FROM dh_governance_lineage_column_edge WHERE (",
            "<choose><when test='upstream'>downstream_node_id, downstream_column</when>",
            "<otherwise>upstream_node_id, upstream_column</otherwise></choose>) IN",
            "<foreach collection='frontier' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            " AND (<choose><when test='upstream'>upstream_node_id, upstream_column</when>",
            "<otherwise>downstream_node_id, downstream_column</otherwise></choose>) NOT IN",
            "<foreach collection='visited' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            " ORDER BY node_id, column_name LIMIT #{limit}",
            "</script>"})
    List<LineageColumnKey> selectNeighborKeys(@Param("frontier") Collection<LineageColumnKey> frontier,
            @Param("visited") Collection<LineageColumnKey> visited, @Param("upstream") boolean upstream,
            @Param("limit") int limit);

    /**
     * (节点, 字段) 集合内部的全部字段级边（诱导子图），最多 limit 行。
     */
    @Select({"<script>",
            "SELECT upstream_node_id, upstream_column, downstream_node_id, downstream_column, transform_type,",
            " transform_subtype, ambiguous, masking, source_ref_id FROM dh_governance_lineage_column_edge",
            " WHERE (upstream_node_id, upstream_column) IN",
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            " AND (downstream_node_id, downstream_column) IN",
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            " ORDER BY upstream_node_id, upstream_column, downstream_node_id, downstream_column, transform_type,",
            " transform_subtype, source_ref_id LIMIT #{limit}",
            "</script>"})
    List<LineageColumnEdgeDO> selectInducedEdges(@Param("keys") Collection<LineageColumnKey> keys,
            @Param("limit") int limit);

    /**
     * 返回集合中仍有集合外上游（upstream=true）或下游字段的 (节点, 字段)，用于标记可继续展开。
     */
    @Select({"<script>",
            "SELECT DISTINCT <choose><when test='upstream'>downstream_node_id AS node_id, downstream_column AS column_name</when>",
            "<otherwise>upstream_node_id AS node_id, upstream_column AS column_name</otherwise></choose>",
            " FROM dh_governance_lineage_column_edge WHERE (",
            "<choose><when test='upstream'>downstream_node_id, downstream_column</when>",
            "<otherwise>upstream_node_id, upstream_column</otherwise></choose>) IN",
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            " AND (<choose><when test='upstream'>upstream_node_id, upstream_column</when>",
            "<otherwise>downstream_node_id, downstream_column</otherwise></choose>) NOT IN",
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            "</script>"})
    List<LineageColumnKey> selectKeysWithOutsideNeighbor(@Param("keys") Collection<LineageColumnKey> keys,
            @Param("upstream") boolean upstream);

    /**
     * 影响分析一步展开：frontier 字段的下游中尚未访问的 (节点, 字段) 及触达它的来源，最多 limit 行。
     */
    @Select({"<script>",
            "SELECT DISTINCT downstream_node_id AS node_id, downstream_column AS column_name, source_ref_id",
            " FROM dh_governance_lineage_column_edge WHERE (upstream_node_id, upstream_column) IN",
            "<foreach collection='frontier' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            " AND (downstream_node_id, downstream_column) NOT IN",
            "<foreach collection='visited' item='k' open='(' separator=',' close=')'>(#{k.nodeId}, #{k.columnName})</foreach>",
            " ORDER BY node_id, column_name, source_ref_id LIMIT #{limit}",
            "</script>"})
    List<LineageEdgeHit> selectDownstreamHits(@Param("frontier") Collection<LineageColumnKey> frontier,
            @Param("visited") Collection<LineageColumnKey> visited, @Param("limit") int limit);

    /**
     * 节点在字段级边中出现过的字段名（上下游合并去重，按名称排序），最多 limit 个。
     */
    @Select({"<script>",
            "SELECT column_name FROM (",
            " SELECT upstream_column AS column_name FROM dh_governance_lineage_column_edge WHERE upstream_node_id = #{nodeId}",
            " UNION",
            " SELECT downstream_column FROM dh_governance_lineage_column_edge WHERE downstream_node_id = #{nodeId}",
            ") edge_columns ORDER BY column_name LIMIT #{limit}",
            "</script>"})
    List<String> selectColumnNames(@Param("nodeId") Long nodeId, @Param("limit") int limit);

}
