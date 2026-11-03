package cn.iocoder.yudao.module.dataforge.api.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.*;
import cn.iocoder.yudao.module.dataforge.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = ApiConstants.NAME, contextId = "dataforge-etl-target-batch-v1", configuration = EtlTargetBatchClientConfiguration.class)
@Tag(name = "RPC 服务 - 指定 ETL 目标批次准入")
public interface EtlTargetBatchApi {

    String PREFIX = ApiConstants.PREFIX + "/etl-target-batch/v1";

    /**
     * 按执行身份读取指定批次绑定和准入状态，不解析最新批次。
     *
     * @param request 请求参数
     * @return ETL 目标批次描述响应数据
     */
    @PostMapping(PREFIX + "/describe")
    @Operation(summary = "按执行身份读取指定批次绑定和准入状态，不解析最新批次")
    CommonResult<EtlTargetBatchDescribeRespDTO> describe(@Valid @RequestBody EtlTargetBatchDescribeReqDTO request);

    /**
     * 复核冻结批次绑定并读取已准入、经行权限与脱敏处理的数据。
     *
     * @param request 请求参数
     * @return ETL 目标批次行响应数据
     */
    @PostMapping(PREFIX + "/read")
    @Operation(summary = "复核冻结批次绑定并读取已准入、经行权限与脱敏处理的数据")
    CommonResult<EtlTargetBatchRowsRespDTO> read(@Valid @RequestBody EtlTargetBatchReadReqDTO request);
}
