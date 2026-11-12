package cn.iocoder.yudao.module.governance.dal.mysql.lineage;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageEdgeHit;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageTableEdgeDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 数据血缘-表级边 Mapper。边是派生数据，只用显式 {@code @Delete} 物理删除；所有图查询都是带上限的 IN 批量查询。
 */
@Mapper
public interface LineageTableEdgeMapper extends BaseMapperX<LineageTableEdgeDO> {

    /**
     * 物理删除某来源的全部表级边。
     */
    @Delete("DELETE FROM dh_governance_lineage_table_edge WHERE source_ref_id = #{sourceRefId}")
    int deleteBySourceRefId(@Param("sourceRefId") Long sourceRefId);

    /**
     * 批量插入表级边（调用方已在报告内去重并分批）。
     */
    @Insert({"<script>",
            "INSERT INTO dh_governance_lineage_table_edge",
            " (source_ref_id, upstream_node_id, downstream_node_id, origin, statement_index, creator, updater) VALUES",
            "<foreach collection='edges' item='row' separator=','>",
            "(#{row.sourceRefId}, #{row.upstreamNodeId}, #{row.downstreamNodeId}, #{row.origin},",
            " #{row.statementIndex,jdbcType=INTEGER}, #{actor}, #{actor})",
            "</foreach>",
            "</script>"})
    int insertEdges(@Param("edges") List<LineageTableEdgeDO> edges, @Param("actor") String actor);

    /**
     * 一步展开：返回 frontier 的上游（upstream=true）或下游相邻节点中尚未访问的节点编号，按编号升序、最多 limit 个。
     */
    @Select({"<script>",
            "SELECT DISTINCT <choose><when test='upstream'>upstream_node_id</when>",
            "<otherwise>downstream_node_id</otherwise></choose> AS neighbor_id",
            " FROM dh_governance_lineage_table_edge WHERE ",
            "<choose><when test='upstream'>downstream_node_id</when><otherwise>upstream_node_id</otherwise></choose> IN",
            "<foreach collection='frontier' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " AND <choose><when test='upstream'>upstream_node_id</when><otherwise>downstream_node_id</otherwise></choose> NOT IN",
            "<foreach collection='visited' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " ORDER BY neighbor_id LIMIT #{limit}",
            "</script>"})
    List<Long> selectNeighborIds(@Param("frontier") Collection<Long> frontier, @Param("visited") Collection<Long> visited,
            @Param("upstream") boolean upstream, @Param("limit") int limit);

    /**
     * 节点集合内部的全部表级边（诱导子图），最多 limit 行。
     */
    @Select({"<script>",
            "SELECT upstream_node_id, downstream_node_id, origin, source_ref_id FROM dh_governance_lineage_table_edge",
            " WHERE upstream_node_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " AND downstream_node_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " ORDER BY upstream_node_id, downstream_node_id, origin, source_ref_id LIMIT #{limit}",
            "</script>"})
    List<LineageTableEdgeDO> selectInducedEdges(@Param("ids") Collection<Long> ids, @Param("limit") int limit);

    /**
     * 返回集合中仍有集合外上游（upstream=true）或下游相邻节点的节点编号，用于标记可继续展开。
     */
    @Select({"<script>",
            "SELECT DISTINCT <choose><when test='upstream'>downstream_node_id</when>",
            "<otherwise>upstream_node_id</otherwise></choose> AS node_id",
            " FROM dh_governance_lineage_table_edge WHERE ",
            "<choose><when test='upstream'>downstream_node_id</when><otherwise>upstream_node_id</otherwise></choose> IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " AND <choose><when test='upstream'>upstream_node_id</when><otherwise>downstream_node_id</otherwise></choose> NOT IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<Long> selectIdsWithOutsideNeighbor(@Param("ids") Collection<Long> ids, @Param("upstream") boolean upstream);

    /**
     * 影响分析一步展开：frontier 的下游中尚未访问的节点及触达它的来源，最多 limit 行。
     */
    @Select({"<script>",
            "SELECT DISTINCT downstream_node_id AS node_id, source_ref_id FROM dh_governance_lineage_table_edge",
            " WHERE upstream_node_id IN",
            "<foreach collection='frontier' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " AND downstream_node_id NOT IN",
            "<foreach collection='visited' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " ORDER BY node_id, source_ref_id LIMIT #{limit}",
            "</script>"})
    List<LineageEdgeHit> selectDownstreamHits(@Param("frontier") Collection<Long> frontier,
            @Param("visited") Collection<Long> visited, @Param("limit") int limit);

}
