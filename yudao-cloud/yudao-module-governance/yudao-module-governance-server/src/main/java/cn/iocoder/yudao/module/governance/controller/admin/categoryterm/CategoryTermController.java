package cn.iocoder.yudao.module.governance.controller.admin.categoryterm;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import cn.iocoder.yudao.module.governance.service.categoryterm.CategoryTermService;
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

@Tag(name = "管理后台 - 业务术语类目")
@RestController
@RequestMapping("/governance/category-term")
@Validated
public class CategoryTermController {

    @Resource
    private CategoryTermService categoryTermService;

    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-业务术语类目", bizId = "#result")
    @Operation(summary = "创建业务术语类目")
    @PreAuthorize("@ss.hasPermission('governance:category-term:create')")
    public CommonResult<Long> createCategoryTerm(@Valid @RequestBody CategoryTermSaveReqVO reqVO) {
        return success(categoryTermService.createCategoryTerm(reqVO));
    }

    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-业务术语类目", bizId = "#reqVO.id")
    @Operation(summary = "更新业务术语类目")
    @PreAuthorize("@ss.hasPermission('governance:category-term:update')")
    public CommonResult<Boolean> updateCategoryTerm(@Valid @RequestBody CategoryTermSaveReqVO reqVO) {
        categoryTermService.updateCategoryTerm(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-业务术语类目", bizId = "#id")
    @Operation(summary = "删除业务术语类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-term:delete')")
    public CommonResult<Boolean> deleteCategoryTerm(@RequestParam("id") Long id) {
        categoryTermService.deleteCategoryTerm(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得业务术语类目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:category-term:query')")
    public CommonResult<CategoryTermRespVO> getCategoryTerm(@RequestParam("id") Long id) {
        CategoryTermDO term = categoryTermService.getCategoryTerm(id);
        return success(BeanUtils.toBean(term, CategoryTermRespVO.class));
    }

    @GetMapping("/list")
    @Operation(summary = "获得业务术语类目列表")
    @PreAuthorize("@ss.hasPermission('governance:category-term:query')")
    public CommonResult<List<CategoryTermRespVO>> getCategoryTermList(@Valid CategoryTermListReqVO reqVO) {
        List<CategoryTermDO> list = categoryTermService.getCategoryTermList(reqVO);
        return success(BeanUtils.toBean(list, CategoryTermRespVO.class));
    }

}
