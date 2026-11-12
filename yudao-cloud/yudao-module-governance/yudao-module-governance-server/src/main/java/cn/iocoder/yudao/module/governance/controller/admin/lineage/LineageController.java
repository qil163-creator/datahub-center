package cn.iocoder.yudao.module.governance.controller.admin.lineage;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
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
import cn.iocoder.yudao.module.governance.service.lineage.LineageQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 数据血缘（只读）。
 *
 * <p>数据范围：平台内全可见，与 {@code MetaTableController} 的元数据表浏览一致——血缘节点是平台元数据的派生视图，
 * 不按部门或对象再做过滤；接口统一要求 {@code governance:lineage:query}。血缘只能由 dataforge 经 RPC 上报写入，
 * 管理端不提供修改接口。</p>
 */
@Tag(name = "管理后台 - 数据血缘")
@RestController
@RequestMapping("/governance/lineage")
@Validated
public class LineageController {

    @Resource
    private LineageQueryService queryService;

    @GetMapping("/node/search")
    @Operation(summary = "按名称检索血缘节点")
    @PreAuthorize("@ss.hasPermission('governance:lineage:query')")
    public CommonResult<List<LineageNodeRespVO>> searchNodes(@Valid LineageNodeSearchReqVO reqVO) {
        return success(queryService.searchNodes(reqVO));
    }

    @GetMapping("/node/resolve")
    @Operation(summary = "由元数据表定位血缘节点，无对应节点时返回空")
    @Parameter(name = "metaTableId", description = "元数据表编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:lineage:query')")
    public CommonResult<LineageNodeRespVO> resolveNode(@RequestParam("metaTableId") Long metaTableId) {
        return success(queryService.resolveByMetaTable(metaTableId));
    }

    @GetMapping("/node/columns")
    @Operation(summary = "获得节点字段（元数据字段与字段级血缘中出现的字段合并去重）")
    @Parameter(name = "nodeId", description = "血缘节点编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:lineage:query')")
    public CommonResult<List<String>> getNodeColumns(@RequestParam("nodeId") Long nodeId) {
        return success(queryService.getNodeColumns(nodeId));
    }

    @GetMapping("/graph")
    @Operation(summary = "获得表级血缘图")
    @PreAuthorize("@ss.hasPermission('governance:lineage:query')")
    public CommonResult<LineageGraphRespVO> getGraph(@Valid LineageGraphReqVO reqVO) {
        return success(queryService.getGraph(reqVO));
    }

    @GetMapping("/column-graph")
    @Operation(summary = "获得字段级血缘图")
    @PreAuthorize("@ss.hasPermission('governance:lineage:query')")
    public CommonResult<LineageColumnGraphRespVO> getColumnGraph(@Valid LineageColumnGraphReqVO reqVO) {
        return success(queryService.getColumnGraph(reqVO));
    }

    @GetMapping("/impact")
    @Operation(summary = "下游影响分析")
    @PreAuthorize("@ss.hasPermission('governance:lineage:query')")
    public CommonResult<LineageImpactRespVO> getImpact(@Valid LineageImpactReqVO reqVO) {
        return success(queryService.getImpact(reqVO));
    }

    @GetMapping("/source/page")
    @Operation(summary = "获得血缘上报来源分页")
    @PreAuthorize("@ss.hasPermission('governance:lineage:query')")
    public CommonResult<PageResult<LineageSourceRespVO>> getSourcePage(@Valid LineageSourcePageReqVO reqVO) {
        return success(queryService.getSourcePage(reqVO));
    }

}
