package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlEnvironmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/dataforge/etl/environment")
@Tag(name = "管理后台 - ETL 环境连接")
@Validated
public class EtlEnvironmentController {

    @Resource
    private EtlEnvironmentService service;

    /**
     * 查看当前平台全部环境。
     *
     * @return 查看当前平台全部环境结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "查看当前平台全部环境")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<List<EtlEnvironmentRespVO>> list() {
        return success(service.list());
    }

    /**
     * 创建或按修订号更新环境。
     *
     * @param req 请求参数
     * @return ETL环境
     */
    @PostMapping("/save")
    @Operation(summary = "创建或按修订号更新环境")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Long> save(@Valid @RequestBody EtlEnvironmentSaveReqVO req) {
        return success(service.save(req));
    }

    /**
     * 删除未被引用的环境。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @return ETL环境
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除未被引用的环境")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Boolean> delete(@RequestParam("id") @Positive Long id,
            @RequestParam("revision") @Min(0) Integer revision) {
        service.delete(id, revision);
        return success(true);
    }

    /**
     * 查看环境内全部连接绑定。
     *
     * @param environmentId 环境编号
     * @return 查看环境内全部连接绑定结果集合
     */
    @GetMapping("/binding/list")
    @Operation(summary = "查看环境内全部连接绑定")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<List<EtlEnvironmentBindingRespVO>> bindings(
            @RequestParam("environmentId") @Positive Long environmentId) {
        return success(service.listBindings(environmentId));
    }

    /**
     * 创建或按修订号更新连接绑定。
     *
     * @param req 请求参数
     * @return ETL环境
     */
    @PostMapping("/binding/save")
    @Operation(summary = "创建或按修订号更新连接绑定")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Long> saveBinding(@Valid @RequestBody EtlEnvironmentBindingSaveReqVO req) {
        return success(service.saveBinding(req, SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 按修订号删除连接绑定，引用此修订的运行将被阻止。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @return ETL环境
     */
    @DeleteMapping("/binding/delete")
    @Operation(summary = "按修订号删除连接绑定，引用此修订的运行将被阻止")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Boolean> deleteBinding(@RequestParam("id") @Positive Long id,
            @RequestParam("revision") @Min(0) Integer revision) {
        service.deleteBinding(id, revision);
        return success(true);
    }
}
