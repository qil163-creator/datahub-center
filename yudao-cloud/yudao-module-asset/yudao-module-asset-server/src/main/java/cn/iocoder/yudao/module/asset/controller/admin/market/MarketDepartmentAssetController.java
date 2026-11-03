package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.service.market.MarketDepartmentAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 同部门申请资产")
@RestController
@RequestMapping("/asset/market/department-assets")
@Validated
public class MarketDepartmentAssetController {

    @Resource
    private MarketDepartmentAssetService service;

    /**
     * 查看当前用户相同部门成员的全部申请资产，不自动包含子部门。
     */
    @GetMapping("/page")
    @Operation(summary = "查看当前用户相同部门成员的全部申请资产，不自动包含子部门")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<PageResult<MarketDepartmentAssetRespVO>> page(@Valid MarketDepartmentAssetPageReqVO req) {
        return success(service.getPage(req));
    }

    /**
     * 按指定申请编号读取同部门资产详情，查看不产生消费授权。
     */
    @GetMapping("/get")
    @Operation(summary = "按指定申请编号读取同部门资产详情，查看不产生消费授权")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<MarketDepartmentAssetDetailRespVO> get(@RequestParam("id") @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 读取指定同部门申请的复制数据；原生实时预览仍校验本人真实批准。
     */
    @GetMapping("/preview")
    @Operation(summary = "读取指定同部门申请的复制数据；原生实时预览仍校验本人真实批准")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<MarketCheckDetailRespVO.Preview> preview(@RequestParam("id") @Positive Long id,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(100) Integer limit) {
        return success(service.preview(id, limit));
    }

    /**
     * 读取指定申请的 API 文档；不执行来源 API，不转授同部门其他人的合同。
     */
    @GetMapping("/api-document")
    @Operation(summary = "读取指定申请的 API 文档；不执行来源 API，不转授同部门其他人的合同")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<MarketCheckDetailRespVO.ApiDocument> apiDocument(@RequestParam("id") @Positive Long id) {
        return success(service.apiDocument(id));
    }

    /**
     * 获取当前同部门申请资产实际关联的集市类目。
     */
    @GetMapping("/categories")
    @Operation(summary = "获取当前同部门申请资产实际关联的集市类目")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<List<MarketDepartmentAssetCategoryRespVO>> categories() {
        return success(service.categories());
    }
}
