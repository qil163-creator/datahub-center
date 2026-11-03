package cn.iocoder.yudao.module.dataforge.controller.admin.operations;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.ExecutionTopologyRespVO;
import cn.iocoder.yudao.module.dataforge.service.operations.ExecutionTopologyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 管理后台 - 数据任务执行拓扑。 */
@Tag(name = "管理后台 - 数据任务执行拓扑")
@RestController
@RequestMapping("/dataforge/execution-topology")
@Validated
public class ExecutionTopologyController {

    @Resource
    private ExecutionTopologyService service;

    /** 返回 qData 页面任务类型与真实执行器的映射目录。 */
    @GetMapping("/catalog")
    @Operation(summary = "获得数据任务执行拓扑目录")
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:query')")
    public CommonResult<List<ExecutionTopologyRespVO>> catalog() {
        return success(service.catalog());
    }

    /** 解析单个任务类型，供详情和运行控制弹窗显示执行边界。 */
    @GetMapping("/resolve")
    @Operation(summary = "解析数据任务执行拓扑")
    @Parameter(name = "taskKind", description = "任务类型编码", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:ops-instance:query')")
    public CommonResult<ExecutionTopologyRespVO> resolve(@RequestParam("taskKind") String taskKind) {
        ExecutionTopologyRespVO topology = service.resolve(taskKind);
        if (topology == null) {
            throw exception(BAD_REQUEST);
        }
        return success(topology);
    }
}
