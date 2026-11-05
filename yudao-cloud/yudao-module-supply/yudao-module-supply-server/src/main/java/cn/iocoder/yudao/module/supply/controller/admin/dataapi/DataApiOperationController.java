package cn.iocoder.yudao.module.supply.controller.admin.dataapi;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.*;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据服务写操作台账")
@RestController
@RequestMapping("/supply/data-api/operation")
@Validated
public class DataApiOperationController {

    @Resource
    private DataApiOperationService operationService;

    /**
     * 游标查询当前平台写操作（至多51条，第51条表示仍有下一页）。
     *
     * @param apiId API 编号
     * @param state 状态
     * @param afterId 变更后编号
     * @param operationKey 操作键
     * @return 游标查询当前平台写操作（至多51条，第51条表示仍有下一页）结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "游标查询当前平台写操作（至多51条，第51条表示仍有下一页）")
    @PreAuthorize("@ss.hasPermission('supply:data-api:operation-query')")
    public CommonResult<List<DataApiOperationRespVO>> list(@RequestParam(required = false) @Positive Long apiId,
            @RequestParam(required = false) @Pattern(regexp = "RUNNING|SUCCEEDED|UNKNOWN|CONFIRMED_SUCCEEDED|CONFIRMED_NOT_APPLIED") String state,
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}") String operationKey) {
        return success(operationService.history(apiId, state, afterId, operationKey));
    }

    /**
     * 记录未知写操作核查证据；不会重试或释放业务键。
     *
     * @param request 请求参数
     * @return 数据 API操作响应数据
     */
    @BusinessOperateLog(type = "数据服务-数据服务写操作台账", bizId = "#request.id")
    @PostMapping("/resolve")
    @Operation(summary = "记录未知写操作核查证据；不会重试或释放业务键")
    @PreAuthorize("@ss.hasPermission('supply:data-api:operation-resolve')")
    public CommonResult<DataApiOperationRespVO> resolve(@Valid @RequestBody DataApiOperationResolveReqVO request) {
        return success(operationService.resolve(request.getId(), request.getDecision(), request.getReason(),
                request.getEvidence()));
    }
}
