package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageColumnGraphReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageColumnGraphRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageGraphReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageGraphRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageImpactReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageImpactRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageNodeRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageNodeSearchReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageSourcePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageSourceRespVO;

import java.util.List;

/**
 * 血缘查询。数据范围：平台内全可见（与元数据表浏览一致），只要求 governance:lineage:query 接口权限。
 */
public interface LineageQueryService {

    /** 表级血缘图节点上限 */
    int MAX_GRAPH_NODES = 300;
    /** 字段级血缘图字段节点上限 */
    int MAX_COLUMN_NODES = 500;
    /** 影响分析条目上限 */
    int MAX_IMPACT_ITEMS = 500;

    /**
     * 按名称检索有边的节点，并匹配元数据业务名称。
     */
    List<LineageNodeRespVO> searchNodes(LineageNodeSearchReqVO reqVO);

    /**
     * 由元数据表定位血缘节点；没有对应节点时返回 null。
     */
    LineageNodeRespVO resolveByMetaTable(Long metaTableId);

    /**
     * 表级血缘图：分批 BFS，最多 {@value #MAX_GRAPH_NODES} 个节点。
     */
    LineageGraphRespVO getGraph(LineageGraphReqVO reqVO);

    /**
     * 字段级血缘图：分批 BFS，最多 {@value #MAX_COLUMN_NODES} 个字段节点。
     */
    LineageColumnGraphRespVO getColumnGraph(LineageColumnGraphReqVO reqVO);

    /**
     * 节点字段：元数据当前字段（按序号）在前，仅出现在字段级边中的字段（小写）在后，大小写不敏感去重。
     */
    List<String> getNodeColumns(Long nodeId);

    /**
     * 下游影响分析（平铺），最多 {@value #MAX_IMPACT_ITEMS} 条。
     */
    LineageImpactRespVO getImpact(LineageImpactReqVO reqVO);

    /**
     * 上报来源分页。
     */
    PageResult<LineageSourceRespVO> getSourcePage(LineageSourcePageReqVO reqVO);

}
