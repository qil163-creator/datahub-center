package cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryKindReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryStatusReqVO;
import cn.iocoder.yudao.module.dataforge.service.projectcategory.ProjectCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
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

@Tag(name = "管理后台 - 项目类目管理")
@RestController
@RequestMapping("/dataforge/project-category")
@Validated
@RequiredArgsConstructor
public class ProjectCategoryController {

    private final ProjectCategoryService service;

    /**
     * 查询平台共享类目列表。
     *
     * @param request 请求参数
     * @return 平台共享类目列表结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "查询平台共享类目列表")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:query' : 'dataforge:dev-category:query')")
    public CommonResult<List<ProjectCategoryRespVO>> list(@Valid ProjectCategoryKindReqVO request) {
        return success(service.list(request.getKind()));
    }

    /**
     * 读取项目类目当前详情。
     *
     * @param request 请求参数
     * @param id 记录编号
     * @return 项目分类响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "读取项目类目当前详情")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:query' : 'dataforge:dev-category:query')")
    public CommonResult<ProjectCategoryRespVO> get(@Valid ProjectCategoryKindReqVO request,
            @RequestParam @Positive Long id) {
        return success(service.get(request.getKind(), id));
    }

    /**
     * 创建项目类目。
     *
     * @param request 请求参数
     * @return 项目分类
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据开发-项目类目管理", bizId = "#result")
    @Operation(summary = "创建项目类目")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:create' : 'dataforge:dev-category:create')")
    public CommonResult<Long> create(@Valid @RequestBody ProjectCategorySaveReqVO request) {
        return success(service.create(request));
    }

    /**
     * 修改项目类目。
     *
     * @param request 请求参数
     * @return 项目分类
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据开发-项目类目管理", bizId = "#request.id")
    @Operation(summary = "修改项目类目")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:update' : 'dataforge:dev-category:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody ProjectCategorySaveReqVO request) {
        service.update(request);
        return success(true);
    }

    /**
     * 删除无子级和任务引用的项目类目。
     *
     * @param request 请求参数
     * @param id 记录编号
     * @return 项目分类
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据开发-项目类目管理", bizId = "#id")
    @Operation(summary = "删除无子级和任务引用的项目类目")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:delete' : 'dataforge:dev-category:delete')")
    public CommonResult<Boolean> delete(@Valid ProjectCategoryKindReqVO request, @RequestParam @Positive Long id) {
        service.delete(request.getKind(), id);
        return success(true);
    }

    /**
     * 按叶子到父级的顺序整批删除项目类目。
     *
     * @param request 请求参数
     * @param ids 记录编号集合
     * @return 项目分类
     */
    @DeleteMapping("/delete-list")
    @BusinessOperateLog(type = "数据开发-项目类目管理", bizId = "#ids")
    @Operation(summary = "按叶子到父级的顺序整批删除项目类目")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:delete' : 'dataforge:dev-category:delete')")
    public CommonResult<Boolean> deleteList(@Valid ProjectCategoryKindReqVO request,
            @RequestParam @NotEmpty @Size(max = 200) List<@Positive Long> ids) {
        service.deleteList(request.getKind(), ids);
        return success(true);
    }

    /**
     * 仅修改项目类目启停状态。
     *
     * @param request 请求参数
     * @return 项目分类
     */
    @PutMapping("/update-status")
    @BusinessOperateLog(type = "数据开发-项目类目管理", bizId = "#request.id")
    @Operation(summary = "仅修改项目类目启停状态")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:update' : 'dataforge:dev-category:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody ProjectCategoryStatusReqVO request) {
        service.updateStatus(request);
        return success(true);
    }
}
