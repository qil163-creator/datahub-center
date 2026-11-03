package cn.iocoder.yudao.module.dataforge.controller.admin.operations;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.*;
import cn.iocoder.yudao.module.dataforge.service.operations.OpsInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 运维管理实例")
@RestController
@RequestMapping("/dataforge/ops-instance")
@Validated
public class OpsInstanceController {

    @Resource
    private OpsInstanceService service;

    /**
     * 分页读取平台共享实例及源系统迁入快照。
     *
     * @param req 请求参数
     * @return 分页读取平台共享实例及源系统迁入快照结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页读取平台共享实例及源系统迁入快照")
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:query')")
    public CommonResult<PageResult<InstanceResp>> page(@Valid PageReq req) {
        return success(service.page(req));
    }

    /**
     * 读取运维实例类目树。
     *
     * @param req 请求参数
     * @return 运维实例类目树结果集合
     */
    @GetMapping("/categories")
    @Operation(summary = "读取运维实例类目树")
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:query')")
    public CommonResult<List<CategoryResp>> categories(@Valid KindReq req) {
        return success(service.categories(req.getKind()));
    }

    /**
     * 读取实例统计，迁入状态不表示本平台存在运行进程。
     *
     * @param req 请求参数
     * @return 统计
     */
    @GetMapping("/statistics")
    @Operation(summary = "读取实例统计，迁入状态不表示本平台存在运行进程")
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:query')")
    public CommonResult<StatisticsResp> statistics(@Valid StatisticsReq req) {
        return success(service.statistics(req));
    }

    /**
     * 读取实例详情和不含执行参数的只读流程图。
     *
     * @param req 请求参数
     * @return 实例
     */
    @GetMapping("/get")
    @Operation(summary = "读取实例详情和不含执行参数的只读流程图")
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:query')")
    public CommonResult<InstanceResp> get(@Valid IdentityReq req) {
        return success(service.get(req));
    }

    /**
     * 读取实例的有界脱敏日志。
     *
     * @param req 请求参数
     * @return 日志
     */
    @GetMapping("/log")
    @Operation(summary = "读取实例的有界脱敏日志", description = "安全元数据按平台共享；本平台原生执行日志额外沿用既有原生权限和冻结项目校验，拒绝时不回退源快照。开发日志最多聚合二十页和一兆字节，读取受限原因与截断提示保留在日志中。")
    @ApiAccessLog(responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:query')")
    public CommonResult<LogResp> log(@Valid IdentityReq req) {
        return success(service.log(req));
    }

    /**
     * 下载相同平台范围内的脱敏实例日志。
     *
     * @param req 请求参数
     * @return 响应实体字节
     */
    @GetMapping("/log-download")
    @Operation(summary = "下载相同平台范围内的脱敏实例日志", description = "本平台原生执行日志额外沿用既有原生权限和冻结项目校验，拒绝时不回退源快照；仅下载有界且脱敏的日志。")
    @ApiAccessLog(operateType = OperateTypeEnum.EXPORT, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:export')")
    public ResponseEntity<byte[]> download(@Valid IdentityReq req) {
        byte[] body = service.download(req).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=ops-" + req.getKind()
                                .toLowerCase(java.util.Locale.ROOT) + "-" + req.getId() + ".log")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(body);
    }
}
