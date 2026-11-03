package cn.iocoder.yudao.module.governance.controller.admin.metadata;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectInstancePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectInstanceRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectLogRespVO;
import cn.iocoder.yudao.module.governance.service.metadata.MetaCollectInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 元数据采集实例
 *
 * @author Li
 */
@Tag(name = "管理后台 - 元数据采集实例")
@RestController
@RequestMapping("/governance/meta-collect-instance")
@Validated
public class MetaCollectInstanceController {

    @Resource
    private MetaCollectInstanceService instanceService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.metadata.MetaCollectDisplay display;

    /**
     * 采集实例分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得元数据采集实例分页")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-instance:query')")
    public CommonResult<PageResult<MetaCollectInstanceRespVO>> getInstancePage(
            @Valid MetaCollectInstancePageReqVO pageReqVO) {
        var page = instanceService.getInstancePage(pageReqVO);
        return success(new PageResult<>(display.instances(page.getList()), page.getTotal()));
    }

    /**
     * 获取采集实例
     */
    @GetMapping("/get")
    @Operation(summary = "获得元数据采集实例")
    @Parameter(name = "id", description = "实例编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-instance:query')")
    public CommonResult<MetaCollectInstanceRespVO> getInstance(@RequestParam("id") Long id) {
        return success(display.instances(java.util.List.of(instanceService.getInstance(id))).getFirst());
    }

    /**
     * 获取采集实例日志（按时间升序）
     */
    @GetMapping("/log-list")
    @Operation(summary = "获得元数据采集实例日志（按时间升序）")
    @Parameter(name = "instanceId", description = "实例编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-instance:query')")
    public CommonResult<List<MetaCollectLogRespVO>> getInstanceLogList(
            @RequestParam("instanceId") Long instanceId) {
        return success(BeanUtils.toBean(instanceService.getInstanceLogList(instanceId),
                MetaCollectLogRespVO.class));
    }

    /**
     * 删除已结束的采集实例及日志。
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/delete-list")
    @Operation(summary = "删除已结束的采集实例及日志")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-instance:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam("ids") List<Long> ids) {
        instanceService.deleteInstances(ids);
        return success(true);
    }

    /**
     * 下载采集实例日志，最多 16 MiB。
     */
    @GetMapping("/log-download")
    @Operation(summary = "下载采集实例日志，最多 16 MiB")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-instance:query')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(responseEnable = false)
    public void downloadLog(@RequestParam("instanceId") Long id,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        instanceService.getInstance(id);
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=metadata-instance-" + id + ".log");
        response.setHeader("X-Content-Type-Options", "nosniff");
        instanceService.writeLog(id, response.getOutputStream());
    }

}
