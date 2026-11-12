package cn.iocoder.yudao.module.modeling.controller.admin.indicator;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorBoardRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorBoardSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorImpactRespVO;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorBoardService;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorImpactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 指标看板与口径变更影响分析。
 * <p>
 * 数据范围：看板只含查询定义，平台内可见、创建人可改；看板展示调用指标查询接口，结果按查看者身份脱敏。
 * 影响分析只读取指标、看板定义与数据服务接口摘要，不读取业务数据。
 */
@Tag(name = "管理后台 - 指标看板")
@RestController
@RequestMapping("/modeling/indicator-board")
@Validated
public class IndicatorBoardController {

    @Resource
    private IndicatorBoardService boardService;
    @Resource
    private IndicatorImpactService impactService;

    /**
     * 看板列表。
     */
    @GetMapping("/list")
    @Operation(summary = "获得指标看板列表")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-board:query')")
    public CommonResult<List<IndicatorBoardRespVO>> list(
            @RequestParam(value = "name", required = false) @Size(max = 100) String name) {
        return success(boardService.list(name));
    }

    /**
     * 看板详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得指标看板")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-board:query')")
    public CommonResult<IndicatorBoardRespVO> get(@RequestParam("id") @Positive Long id) {
        return success(boardService.get(id));
    }

    /**
     * 新建看板。
     */
    @PostMapping("/create")
    @Operation(summary = "新建指标看板")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-board:create')")
    public CommonResult<Long> create(@Valid @RequestBody IndicatorBoardSaveReqVO reqVO) {
        return success(boardService.create(reqVO));
    }

    /**
     * 修改本人看板。
     */
    @PutMapping("/update")
    @Operation(summary = "修改指标看板")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-board:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody IndicatorBoardSaveReqVO reqVO) {
        boardService.update(reqVO);
        return success(true);
    }

    /**
     * 删除本人看板。
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除指标看板")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-board:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") @Positive Long id) {
        boardService.delete(id);
        return success(true);
    }

    /**
     * 指标口径变更影响分析。
     */
    @GetMapping("/impact")
    @Operation(summary = "指标口径变更影响分析")
    @PreAuthorize("@ss.hasPermission('modeling:indicator-model:query')")
    public CommonResult<IndicatorImpactRespVO> impact(@RequestParam("indicatorId") @Positive Long indicatorId) {
        return success(impactService.analyze(indicatorId));
    }
}
