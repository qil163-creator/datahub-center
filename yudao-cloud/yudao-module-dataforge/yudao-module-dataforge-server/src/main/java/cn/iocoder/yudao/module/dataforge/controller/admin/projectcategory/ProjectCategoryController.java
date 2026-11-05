package cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
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
    @LogRecord(type = "数据开发-项目类目管理", subType = "创建项目类目", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了项目类目【{{(#request.name) ?: '未设置'}}】，类目种类【{{(#request.kind) ?: '未设置'}}】，父类目【{{(#request.parentId) ?: '未设置'}}】，状态【{{(#request.status) ?: '未设置'}}】，排序【{{(#request.sort) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据开发-项目类目管理", subType = "修改项目类目", bizNo = "{{(#request.id) ?: 0}}",
            success = "修改了项目类目【{{(#operateName) ?: '未设置'}}】，名称从【{{(#operateName) ?: '未设置'}}】改为【{{(#request.name) ?: '未设置'}}】，类目种类【{{(#request.kind) ?: '未设置'}}】，父类目【{{(#request.parentId) ?: '未设置'}}】，状态【{{(#request.status) ?: '未设置'}}】，排序【{{(#request.sort) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改项目类目")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:update' : 'dataforge:dev-category:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody ProjectCategorySaveReqVO request) {
        String operateName = "编号" + request.getId();
        try {
            var operateObject = service.get(request.getKind(), request.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

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
    @LogRecord(type = "数据开发-项目类目管理", subType = "删除无子级和任务引用的项目类目", bizNo = "{{(#id) ?: 0}}",
            success = "删除了项目类目【{{(#operateName) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除无子级和任务引用的项目类目")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:delete' : 'dataforge:dev-category:delete')")
    public CommonResult<Boolean> delete(@Valid ProjectCategoryKindReqVO request, @RequestParam @Positive Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = service.get(request.getKind(), id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

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
    @LogRecord(type = "数据开发-项目类目管理", subType = "按叶子到父级的顺序整批删除项目类目", bizNo = "{{(#ids) ?: 0}}",
            success = "批量删除了项目类目【{{(#operateNames) ?: '未设置'}}】，类目种类【{{(#request.kind) ?: '未设置'}}】，请求编号【{{(#ids) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "按叶子到父级的顺序整批删除项目类目")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:delete' : 'dataforge:dev-category:delete')")
    public CommonResult<Boolean> deleteList(@Valid ProjectCategoryKindReqVO request,
            @RequestParam @NotEmpty @Size(max = 200) List<@Positive Long> ids) {
        LogRecordContext.putVariable("operateNames", ids.toString());
        try {
            var operateNames = service.list(request.getKind()).stream()
                    .filter(item -> ids.contains(item.getId()))
                    .map(item -> java.util.Objects.toString(item.getName(), "编号" + item.getId()) + "（编号" + item.getId() + "）")
                    .toList();
            LogRecordContext.putVariable("operateNames", operateNames);
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留请求编号，不改变批量操作原有的缺失处理和原子性。
        }

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
    @LogRecord(type = "数据开发-项目类目管理", subType = "仅修改项目类目启停状态", bizNo = "{{(#request.id) ?: 0}}",
            success = "将项目类目【{{(#operateName) ?: '未设置'}}】的状态从【{{(#operateStatus) ?: '未设置'}}】改为【{{(#request.status) ?: '未设置'}}】（0启用，1停用）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "仅修改项目类目启停状态")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'integration' ? 'dataforge:integration-category:update' : 'dataforge:dev-category:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody ProjectCategoryStatusReqVO request) {
        String operateName = "编号" + request.getId();
        LogRecordContext.putVariable("operateStatus", "未读取");
        try {
            var operateObject = service.get(request.getKind(), request.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
                LogRecordContext.putVariable("operateStatus", operateObject.getStatus());
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        service.updateStatus(request);
        return success(true);
    }
}
