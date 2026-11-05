package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategoryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillPlanCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 采集计划分类")
@RestController
@RequestMapping("/dataforge/fill-plan-category")
@Validated
public class FillPlanCategoryController {

    @Resource
    private FillPlanCategoryService categoryService;

    /**
     * 获得采集计划分类列表，前端构建目录树。
     *
     * @return 分类列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得采集计划分类列表，前端构建目录树")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:query')")
    public CommonResult<List<FillPlanCategoryRespVO>> getCategoryList() {
        return success(categoryService.getCategoryList());
    }

    /**
     * 获得采集计划分类。
     *
     * @param id 记录编号
     * @return 填报计划分类响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得采集计划分类")
    @Parameter(name = "id", description = "分类编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:query')")
    public CommonResult<FillPlanCategoryRespVO> getCategory(@RequestParam("id") @Positive Long id) {
        return success(categoryService.getCategory(id));
    }

    /**
     * 创建采集计划分类。
     *
     * @param reqVO 请求参数
     * @return 填报计划分类
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-采集计划分类", subType = "创建采集计划分类", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了采集计划分类【{{(#reqVO.name) ?: '未设置'}}】，父分类【{{(#reqVO.parentId) ?: '未设置'}}】，排序【{{(#reqVO.sort) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建采集计划分类")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:create')")
    public CommonResult<Long> createCategory(@Valid @RequestBody FillPlanCategorySaveReqVO reqVO) {
        return success(categoryService.createCategory(reqVO));
    }

    /**
     * 更新采集计划分类。
     *
     * @param reqVO 请求参数
     * @return 填报计划分类
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-采集计划分类", subType = "更新采集计划分类", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "修改了采集计划分类【{{(#operateName) ?: '未设置'}}】，名称从【{{(#operateName) ?: '未设置'}}】改为【{{(#reqVO.name) ?: '未设置'}}】，父分类【{{(#reqVO.parentId) ?: '未设置'}}】，排序【{{(#reqVO.sort) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新采集计划分类")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:update')")
    public CommonResult<Boolean> updateCategory(@Valid @RequestBody FillPlanCategorySaveReqVO reqVO) {
        String operateName = "编号" + reqVO.getId();
        try {
            var operateObject = categoryService.getCategory(reqVO.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        categoryService.updateCategory(reqVO);
        return success(true);
    }

    /**
     * 删除采集计划分类，有子分类或计划引用时拒绝。
     *
     * @param id 记录编号
     * @return 填报计划分类
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-采集计划分类", subType = "删除采集计划分类，有子分类或计划引用时拒绝", bizNo = "{{(#id) ?: 0}}",
            success = "删除了采集计划分类【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除采集计划分类，有子分类或计划引用时拒绝")
    @Parameter(name = "id", description = "分类编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-plan:delete')")
    public CommonResult<Boolean> deleteCategory(@RequestParam("id") @Positive Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = categoryService.getCategory(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        categoryService.deleteCategory(id);
        return success(true);
    }

}
