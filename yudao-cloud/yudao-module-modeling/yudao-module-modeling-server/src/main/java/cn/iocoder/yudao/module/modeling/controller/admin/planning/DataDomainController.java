package cn.iocoder.yudao.module.modeling.controller.admin.planning;

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
    @PutMapping("/update")
    @Operation(summary = "更新数据板块")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:update')")
    public CommonResult<Boolean> updateDataDomain(@Valid @RequestBody DataDomainSaveReqVO updateReqVO) {
        dataDomainService.updateDataDomain(updateReqVO);
        return success(true);
    }

    /**
     * 更新数据板块状态。
     *
     * @param reqVO 请求参数
     * @return 数据域
     */
    @PutMapping("/update-status")
    @Operation(summary = "更新数据板块状态")
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:update')")
    public CommonResult<Boolean> updateDataDomainStatus(
            @Valid @RequestBody DataDomainUpdateStatusReqVO reqVO) {
        dataDomainService.updateDataDomainStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    /**
     * 删除数据板块。
     *
     * @param id 记录编号
     * @return 数据域
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除数据板块")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:data-domain:delete')")
    public CommonResult<Boolean> deleteDataDomain(@RequestParam("id") Long id) {
        dataDomainService.deleteDataDomain(id);
        return success(true);
    }

    /**
     * 更新板块六类逻辑表命名规则。
     *
     * @param request 请求参数
     * @return 数据域
     */
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
