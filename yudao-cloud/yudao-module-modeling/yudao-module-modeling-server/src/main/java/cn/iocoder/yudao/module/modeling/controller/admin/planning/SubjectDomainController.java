package cn.iocoder.yudao.module.modeling.controller.admin.planning;

import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.*;
import cn.iocoder.yudao.module.modeling.service.planning.SubjectDomainService;
import cn.iocoder.yudao.module.modeling.service.planning.PlanningDomainPresentationService;
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

@Tag(name = "管理后台 - 主题域")
@RestController
@RequestMapping("/modeling/subject-domain")
@Validated
public class SubjectDomainController {

    @Resource
    private SubjectDomainService subjectDomainService;

    @Resource
    private PlanningDomainPresentationService presentationService;

    /**
     * 创建主题域。
     *
     * @param createReqVO 创建参数
     * @return 主题域
     */
    @LogRecord(type = "数据建模-主题域", subType = "创建主题域", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了主题域【{{#createReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建主题域")
    @PreAuthorize("@ss.hasPermission('modeling:subject-domain:create')")
    public CommonResult<Long> createSubjectDomain(@Valid @RequestBody SubjectDomainSaveReqVO createReqVO) {
        return success(subjectDomainService.createSubjectDomain(createReqVO));
    }

    /**
     * 更新主题域。
     *
     * @param updateReqVO 更新参数
     * @return 主题域
     */
    @LogRecord(type = "数据建模-主题域", subType = "更新主题域", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了主题域【{{#auditObjectName}}】，名称由【{{#auditObjectName}}】改为【{{#updateReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新主题域")
    @PreAuthorize("@ss.hasPermission('modeling:subject-domain:update')")
    public CommonResult<Boolean> updateSubjectDomain(@Valid @RequestBody SubjectDomainSaveReqVO updateReqVO) {
        var auditBefore = subjectDomainService.getSubjectDomain(updateReqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + updateReqVO.getId() : auditBefore.getName());

        subjectDomainService.updateSubjectDomain(updateReqVO);
        return success(true);
    }

    /**
     * 更新主题域状态。
     *
     * @param reqVO 请求参数
     * @return 主题域
     */
    @LogRecord(type = "数据建模-主题域", subType = "更新主题域状态", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "将主题域【{{#auditObjectName}}】状态由【{{#auditBeforeStatus}}】改为【{{#reqVO.status}}】（0启用、1停用）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-status")
    @Operation(summary = "更新主题域状态")
    @PreAuthorize("@ss.hasPermission('modeling:subject-domain:update')")
    public CommonResult<Boolean> updateSubjectDomainStatus(
            @Valid @RequestBody SubjectDomainUpdateStatusReqVO reqVO) {
        var auditBefore = subjectDomainService.getSubjectDomain(reqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + reqVO.getId() : auditBefore.getName());
        LogRecordContext.putVariable("auditBeforeStatus", auditBefore == null ? "未知" : String.valueOf(auditBefore.getStatus()));

        subjectDomainService.updateSubjectDomainStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    /**
     * 删除主题域。
     *
     * @param id 记录编号
     * @return 主题域
     */
    @LogRecord(type = "数据建模-主题域", subType = "删除主题域", bizNo = "{{(#id) ?: 0}}",
            success = "删除了主题域【{{#auditObjectName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除主题域")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:subject-domain:delete')")
    public CommonResult<Boolean> deleteSubjectDomain(@RequestParam("id") Long id) {
        var auditBefore = subjectDomainService.getSubjectDomain(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        subjectDomainService.deleteSubjectDomain(id);
        return success(true);
    }

    /**
     * 获得主题域。
     *
     * @param id 记录编号
     * @return 主题域响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得主题域")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('modeling:subject-domain:query')")
    public CommonResult<SubjectDomainRespVO> getSubjectDomain(@RequestParam("id") Long id) {
        return success(
                presentationService.presentSubjects(List.of(subjectDomainService.getSubjectDomain(id))).getFirst());
    }

    /**
     * 获得主题域列表（树形数据平铺返回）。
     *
     * @param listReqVO 列表请求参数
     * @return 主题域列表（树形数据平铺返回）结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "获得主题域列表（树形数据平铺返回）")
    @PreAuthorize("@ss.hasPermission('modeling:subject-domain:query')")
    public CommonResult<List<SubjectDomainRespVO>> getSubjectDomainList(
            @Valid SubjectDomainListReqVO listReqVO) {
        return success(presentationService.presentSubjects(subjectDomainService.getSubjectDomainList(listReqVO)));
    }

}
