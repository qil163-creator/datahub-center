package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.service.market.MarketCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 集市类目")
@RestController
@RequestMapping("/asset/market-category")
@Validated
public class MarketCategoryController {

    @Resource
    private MarketCategoryService service;

    /**
     * 市场导航和发布表单使用的启用类目。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "市场导航和发布表单使用的启用类目")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<List<MarketCategoryRespVO>> simpleList() {
        return success(service.getEnabledList());
    }

    /**
     * 查询平台集市类目树节点。
     */
    @GetMapping("/list")
    @Operation(summary = "查询平台集市类目树节点")
    @PreAuthorize("@ss.hasPermission('asset:market-category:query')")
    public CommonResult<List<MarketCategoryRespVO>> list(@Valid MarketCategoryListReqVO req) {
        return success(service.list(req));
    }

    /**
     * 读取集市类目。
     */
    @GetMapping("/get")
    @Operation(summary = "读取集市类目")
    @PreAuthorize("@ss.hasPermission('asset:market-category:query')")
    public CommonResult<MarketCategoryRespVO> get(@RequestParam("id") @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 新增集市类目。
     */
    @BusinessOperateLog(type = "数据资产-集市类目", bizId = "#result")
    @PostMapping("/create")
    @Operation(summary = "新增集市类目")
    @PreAuthorize("@ss.hasPermission('asset:market-category:create')")
    public CommonResult<Long> create(@Valid @RequestBody MarketCategorySaveReqVO req) {
        return success(service.create(req));
    }

    /**
     * 修改集市类目。
     */
    @BusinessOperateLog(type = "数据资产-集市类目", bizId = "#req.id")
    @PutMapping("/update")
    @Operation(summary = "修改集市类目")
    @PreAuthorize("@ss.hasPermission('asset:market-category:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MarketCategorySaveReqVO req) {
        service.update(req);
        return success(true);
    }

    /**
     * 启用或停用集市类目。
     */
    @BusinessOperateLog(type = "数据资产-集市类目", bizId = "#req.id")
    @PutMapping("/update-status")
    @Operation(summary = "启用或停用集市类目")
    @PreAuthorize("@ss.hasPermission('asset:market-category:update')")
    public CommonResult<Boolean> status(@Valid @RequestBody MarketCategoryStatusReqVO req) {
        service.updateStatus(req.getId(), req.getStatus());
        return success(true);
    }

    /**
     * 删除无引用的集市类目。
     */
    @BusinessOperateLog(type = "数据资产-集市类目", bizId = "#id")
    @DeleteMapping("/delete")
    @Operation(summary = "删除无引用的集市类目")
    @PreAuthorize("@ss.hasPermission('asset:market-category:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") @Positive Long id) {
        service.delete(List.of(id));
        return success(true);
    }

    /**
     * 原子删除无引用的集市类目集合。
     */
    @BusinessOperateLog(type = "数据资产-集市类目", bizId = "#ids")
    @DeleteMapping("/delete-list")
    @Operation(summary = "原子删除无引用的集市类目集合")
    @PreAuthorize("@ss.hasPermission('asset:market-category:delete')")
    public CommonResult<Boolean> deleteList(
            @RequestParam("ids") @NotEmpty @Size(max = 200) List<@NotNull @Positive Long> ids) {
        service.delete(ids);
        return success(true);
    }

}
