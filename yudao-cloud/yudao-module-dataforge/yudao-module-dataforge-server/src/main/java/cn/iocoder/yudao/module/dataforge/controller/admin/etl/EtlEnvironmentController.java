package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
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
    @LogRecord(type = "数据开发-ETL 环境连接", subType = "创建或按修订号更新环境", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "{{#req.id == null ? '创建了' : '修改了'}} ETL 环境【{{(#req.name) ?: '未设置'}}】，环境编码【{{(#req.code) ?: '未设置'}}】，启用状态【{{(#req.enabled) ?: '未设置'}}】，提交修订【{{(#req.revision) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据开发-ETL 环境连接", subType = "删除未被引用的环境", bizNo = "{{(#id) ?: 0}}",
            success = "删除了 ETL 环境【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】），要求修订【{{(#revision) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除未被引用的环境")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Boolean> delete(@RequestParam("id") @Positive Long id,
            @RequestParam("revision") @Min(0) Integer revision) {
        String operateName = "编号" + id;
        try {
            var operateObject = service.list().stream().filter(item -> java.util.Objects.equals(item.getId(), id)).findFirst().orElse(null);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

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
    @LogRecord(type = "数据开发-ETL 环境连接", subType = "创建或按修订号更新连接绑定", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "{{#req.id == null ? '创建了' : '修改了'}} ETL 连接绑定【{{(#req.logicalKey) ?: '未设置'}}】，环境编号【{{(#req.environmentId) ?: '未设置'}}】，数据源编号【{{(#req.databaseId) ?: '未设置'}}】，提交修订【{{(#req.revision) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据开发-ETL 环境连接", subType = "按修订号删除连接绑定，引用此修订的运行将被阻止", bizNo = "{{(#id) ?: 0}}",
            success = "删除了 ETL 连接绑定【{{(#id) ?: '未设置'}}】，要求修订【{{(#revision) ?: '未设置'}}】，后续运行不再使用该绑定", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "按修订号删除连接绑定，引用此修订的运行将被阻止")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Boolean> deleteBinding(@RequestParam("id") @Positive Long id,
            @RequestParam("revision") @Min(0) Integer revision) {
        service.deleteBinding(id, revision);
        return success(true);
    }
}
