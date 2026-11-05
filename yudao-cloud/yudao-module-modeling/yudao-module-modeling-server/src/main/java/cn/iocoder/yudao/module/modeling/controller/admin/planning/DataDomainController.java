package cn.iocoder.yudao.module.modeling.controller.admin.planning;

import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainSimpleRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainPageReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainUpdateStatusReqVO;
import cn.iocoder.yudao.module.modeling.service.planning.DataDomainService;
import cn.iocoder.yudao.module.modeling.service.planning.PlanningDomainPresentationService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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

@Tag(name = "管理后台 - 数据板块")
@RestController
@RequestMapping("/modeling/data-domain")
@Validated
public class DataDomainController {

    @Resource
    private DataDomainService dataDomainService;

    @Resource
    private PlanningDomainPresentationService presentationService;

    /**
     * 创建数据板块。
     *
     * @param createReqVO 创建参数
     * @return 数据域
     */
    @LogRecord(type = "数据建模-数据板块", subType = "创建数据板块", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了数据板块【{{#createReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建数据板块")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:create')")
    public CommonResult<Long> createDataDomain(@Valid @RequestBody DataDomainSaveReqVO createReqVO) {
        return success(dataDomainService.createDataDomain(createReqVO));
    }

    /**
     * 更新数据板块。
     *
     * @param updateReqVO 更新参数
     * @return 数据域
     */
    @LogRecord(type = "数据建模-数据板块", subType = "更新数据板块", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了数据板块【{{#auditObjectName}}】，名称由【{{#auditObjectName}}】改为【{{#updateReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新数据板块")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:update')")
    public CommonResult<Boolean> updateDataDomain(@Valid @RequestBody DataDomainSaveReqVO updateReqVO) {
        var auditBefore = dataDomainService.getDataDomain(updateReqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + updateReqVO.getId() : auditBefore.getName());

        dataDomainService.updateDataDomain(updateReqVO);
        return success(true);
    }

    /**
     * 更新数据板块状态。
     *
     * @param reqVO 请求参数
     * @return 数据域
     */
    @LogRecord(type = "数据建模-数据板块", subType = "更新数据板块状态", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "将数据板块【{{#auditObjectName}}】状态由【{{#auditBeforeStatus}}】改为【{{#reqVO.status}}】（0启用、1停用）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-status")
    @Operation(summary = "更新数据板块状态")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:update')")
    public CommonResult<Boolean> updateDataDomainStatus(
            @Valid @RequestBody DataDomainUpdateStatusReqVO reqVO) {
        var auditBefore = dataDomainService.getDataDomain(reqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + reqVO.getId() : auditBefore.getName());
        LogRecordContext.putVariable("auditBeforeStatus", auditBefore == null ? "未知" : String.valueOf(auditBefore.getStatus()));

        dataDomainService.updateDataDomainStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    /**
     * 删除数据板块。
     *
     * @param id 记录编号
     * @return 数据域
     */
    @LogRecord(type = "数据建模-数据板块", subType = "删除数据板块", bizNo = "{{(#id) ?: 0}}",
            success = "删除了数据板块【{{#auditObjectName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除数据板块")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:delete')")
    public CommonResult<Boolean> deleteDataDomain(@RequestParam("id") Long id) {
        var auditBefore = dataDomainService.getDataDomain(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        dataDomainService.deleteDataDomain(id);
        return success(true);
    }

    /**
     * 更新板块六类逻辑表命名规则。
     *
     * @param request 请求参数
     * @return 数据域
     */
    @LogRecord(type = "数据建模-数据板块", subType = "更新板块六类逻辑表命名规则", bizNo = "{{(#request.id) ?: 0}}",
            success = "更新了数据板块【{{#request.id}}】的逻辑表命名规则，共【{{#request.namingRules.size()}}】类规则", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-naming-rules")
    @Operation(summary = "更新板块六类逻辑表命名规则")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:update')")
    public CommonResult<Boolean> updateNamingRules(@Valid @RequestBody
    cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainNamingRulesReqVO request) {
        dataDomainService.updateNamingRules(request);
        return success(true);
    }

    /**
     * 批量删除数据板块。
     *
     * @param ids 记录编号集合
     * @return 数据域
     */
    @LogRecord(type = "数据建模-数据板块", subType = "批量删除数据板块", bizNo = "{{(#ids) ?: 0}}",
            success = "批量删除了数据板块，编号集合【{{#ids}}】，共【{{#ids.size()}}】项", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除数据板块")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:delete')")
    public CommonResult<Boolean> deleteDataDomains(@RequestParam("ids") @NotEmpty @Size(max = 100) List<Long> ids) {
        dataDomainService.deleteDataDomains(ids);
        return success(true);
    }

    /**
     * 获得数据板块。
     *
     * @param id 记录编号
     * @return 数据域响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据板块")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:query')")
    public CommonResult<DataDomainRespVO> getDataDomain(@RequestParam("id") Long id) {
        return success(presentationService.presentBoards(List.of(dataDomainService.getDataDomain(id))).getFirst());
    }

    /**
     * 获得数据板块分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 数据板块分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据板块分页")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:query')")
    public CommonResult<PageResult<DataDomainRespVO>> getDataDomainPage(
            @Valid DataDomainPageReqVO pageReqVO) {
        var page = dataDomainService.getDataDomainPage(pageReqVO);
        return success(new PageResult<>(presentationService.presentBoards(page.getList()), page.getTotal()));
    }

    /**
     * 获得数据板块全量列表（下拉选择用）。
     *
     * @return 数据域精简列表
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得数据板块全量列表（下拉选择用）")
    public CommonResult<List<DataDomainSimpleRespVO>> getDataDomainSimpleList() {
        return success(BeanUtils.toBean(dataDomainService.getDataDomainSimpleList(), DataDomainSimpleRespVO.class));
    }

}
