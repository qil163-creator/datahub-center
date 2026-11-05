package cn.iocoder.yudao.module.governance.controller.admin.standardterm;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermCategoryRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.service.categoryterm.CategoryTermService;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.service.standardterm.StandardTermService;
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

@Tag(name = "管理后台 - 数据标准--业务术语")
@RestController
@RequestMapping("/governance/standard-term")
@Validated
public class StandardTermController {

    @Resource
    private StandardTermService standardTermService;

    @Resource
    private CategoryTermService categoryTermService;

    @GetMapping("/category-list")
    @Operation(summary = "获得业务术语类目树节点")
    @PreAuthorize("@ss.hasAnyPermissions('governance:standard-term:query', 'governance:standard-term:create', 'governance:standard-term:update')")
    public CommonResult<List<StandardTermCategoryRespVO>> getCategories() {
        return success(BeanUtils.toBean(categoryTermService.getCategoryTermList(new CategoryTermListReqVO()),
                StandardTermCategoryRespVO.class));
    }

    /**
     * 创建业务术语
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-业务术语", bizId = "#result")
    @Operation(summary = "创建业务术语")
    @PreAuthorize("@ss.hasPermission('governance:standard-term:create')")
    public CommonResult<Long> createStandardTerm(@Valid @RequestBody StandardTermSaveReqVO createReqVO) {
        return success(standardTermService.createStandardTerm(createReqVO));
    }

    /**
     * 更新业务术语
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-业务术语", bizId = "#updateReqVO.id")
    @Operation(summary = "更新业务术语")
    @PreAuthorize("@ss.hasPermission('governance:standard-term:update')")
    public CommonResult<Boolean> updateStandardTerm(@Valid @RequestBody StandardTermSaveReqVO updateReqVO) {
        standardTermService.updateStandardTerm(updateReqVO);
        return success(true);
    }

    /**
     * 删除业务术语
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-业务术语", bizId = "#id")
    @Operation(summary = "删除业务术语")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-term:delete')")
    public CommonResult<Boolean> deleteStandardTerm(@RequestParam("id") Long id) {
        standardTermService.deleteStandardTerm(id);
        return success(true);
    }

    /**
     * 批量删除业务术语。
     */
    @DeleteMapping("/delete-list")
    @BusinessOperateLog(type = "数据治理-业务术语", bizId = "#ids")
    @Operation(summary = "批量删除业务术语")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-term:delete')")
    public CommonResult<Boolean> deleteStandardTermList(@RequestParam("ids") List<Long> ids) {
        standardTermService.deleteStandardTermListByIds(ids);
        return success(true);
    }

    /**
     * 获得业务术语
     */
    @GetMapping("/get")
    @Operation(summary = "获得业务术语")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-term:query')")
    public CommonResult<StandardTermRespVO> getStandardTerm(@RequestParam("id") Long id) {
        StandardTermDO term = standardTermService.getStandardTerm(id);
        return success(BeanUtils.toBean(term, StandardTermRespVO.class));
    }

    /**
     * 获得业务术语分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得业务术语分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-term:query')")
    public CommonResult<PageResult<StandardTermRespVO>> getStandardTermPage(
            @Valid StandardTermPageReqVO pageReqVO) {
        PageResult<StandardTermDO> pageResult = standardTermService.getStandardTermPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, StandardTermRespVO.class));
    }

}
