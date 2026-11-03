package cn.iocoder.yudao.module.dataforge.api.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.*;
import cn.iocoder.yudao.module.dataforge.framework.security.EtlBatchRpcFilter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlTargetBatchService;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class EtlTargetBatchApiImpl implements EtlTargetBatchApi {

    @Resource
    private HttpServletRequest servletRequest;

    @Resource
    private EtlTargetBatchService service;

    /**
     * 按执行身份读取指定批次绑定和准入状态，不解析最新批次。
     *
     * @param request 请求参数
     * @return ETL 目标批次描述响应数据
     */
    @Override
    @PermitAll
    public CommonResult<EtlTargetBatchDescribeRespDTO> describe(@Valid EtlTargetBatchDescribeReqDTO request) {
        EtlBatchRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(service.describe(request.getBatchId(), request.getExecutionUserId()));
    }

    /**
     * 复核冻结批次绑定并读取已准入、经行权限与脱敏处理的数据。
     *
     * @param request 请求参数
     * @return ETL 目标批次行响应数据
     */
    @Override
    @PermitAll
    public CommonResult<EtlTargetBatchRowsRespDTO> read(@Valid EtlTargetBatchReadReqDTO request) {
        EtlBatchRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(service.readForConsumer(request));
    }
}
