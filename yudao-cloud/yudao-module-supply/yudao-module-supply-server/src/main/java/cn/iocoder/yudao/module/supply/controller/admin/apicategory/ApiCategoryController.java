package cn.iocoder.yudao.module.supply.controller.admin.apicategory;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo.*;
import cn.iocoder.yudao.module.supply.service.apicategory.ApiCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据服务-服务分类")
@RestController
@RequestMapping("/supply/api-category")
@Validated
public class ApiCategoryController {

    @Resource
    private ApiCategoryService apiCategoryService;

    /**
     * 创建服务分类。
     *
     * @param createReqVO 创建参数
     * @return API分类
     */
    @BusinessOperateLog(type = "数据服务-服务分类", bizId = "#result")
    @PostMapping("/create")
    @Operation(summary = "创建服务分类")
    @PreAuthorize("@ss.hasPermission('supply:api-category:create')")
    public CommonResult<Long> createApiCategory(@Valid @RequestBody ApiCategorySaveReqVO createReqVO) {
        return success(apiCategoryService.createApiCategory(createReqVO));
    }

    /**
     * 更新服务分类。
     *
     * @param updateReqVO 更新参数
     * @return API分类
     */
    @BusinessOperateLog(type = "数据服务-服务分类", bizId = "#updateReqVO.id")
    @PutMapping("/update")
    @Operation(summary = "更新服务分类")
    @PreAuthorize("@ss.hasPermission('supply:api-category:update')")
    public CommonResult<Boolean> updateApiCategory(@Valid @RequestBody ApiCategorySaveReqVO updateReqVO) {
        apiCategoryService.updateApiCategory(updateReqVO);
        return success(true);
    }

    /**
     * 更新服务分类状态。
     *
     * @param reqVO 请求参数
     * @return API分类
     */
    @BusinessOperateLog(type = "数据服务-服务分类", bizId = "#reqVO.id")
    @PutMapping("/update-status")
    @Operation(summary = "更新服务分类状态")
    @PreAuthorize("@ss.hasPermission('supply:api-category:update')")
    public CommonResult<Boolean> updateApiCategoryStatus(
            @Valid @RequestBody ApiCategoryUpdateStatusReqVO reqVO) {
        apiCategoryService.updateApiCategoryStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    /**
     * 删除服务分类。
     *
     * @param id 记录编号
     * @return API分类
     */
    @BusinessOperateLog(type = "数据服务-服务分类", bizId = "#id")
    @DeleteMapping("/delete")
    @Operation(summary = "删除服务分类")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:api-category:delete')")
    public CommonResult<Boolean> deleteApiCategory(@RequestParam("id") Long id) {
        apiCategoryService.deleteApiCategory(id);
        return success(true);
    }

    /**
     * 获得服务分类。
     *
     * @param id 记录编号
     * @return API分类响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得服务分类")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('supply:api-category:query')")
    public CommonResult<ApiCategoryRespVO> getApiCategory(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(apiCategoryService.getApiCategory(id), ApiCategoryRespVO.class));
    }

    /**
     * 获得服务分类列表（树形数据平铺返回）。
     *
     * @param listReqVO 列表请求参数
     * @return 服务分类列表（树形数据平铺返回）结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "获得服务分类列表（树形数据平铺返回）")
    @PreAuthorize("@ss.hasPermission('supply:api-category:query')")
    public CommonResult<List<ApiCategoryRespVO>> getApiCategoryList(@Valid ApiCategoryListReqVO listReqVO) {
        return success(BeanUtils.toBean(
                apiCategoryService.getApiCategoryList(listReqVO), ApiCategoryRespVO.class));
    }

}
