package cn.iocoder.yudao.module.dataforge.api.migration;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryDTO;
import cn.iocoder.yudao.module.dataforge.framework.security.SyncEvidenceRpcFilter;
import cn.iocoder.yudao.module.dataforge.service.access.SyncBatchObservationService;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class SyncBatchEvidenceApiImpl implements SyncBatchEvidenceApi {

    @Resource
    private HttpServletRequest servletRequest;

    @Resource
    private SyncBatchObservationService service;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.access.SyncBoundaryService boundaryService;

    /**
     * 登记或对账已提交整改的稳定顺序边界；超时只能用同键重试。
     *
     * @param request 请求参数
     * @return 同步边界
     */
    @Override
    @PermitAll
    public CommonResult<SyncBoundaryDTO> ensureBoundary(@Valid SyncBoundaryReqDTO request) {
        SyncEvidenceRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(boundaryService.ensure(request));
    }

    /**
     * 按执行身份观察一个目标表的有界批次事实，不构成到数证明。
     *
     * @param request 请求参数
     * @return 同步批次观测
     */
    @Override
    @PermitAll // 狭窄端点由前置 mTLS 门禁保护；缺失过滤器也必须失败关闭。
    public CommonResult<SyncBatchObservationDTO> observe(@Valid SyncBatchQueryReqDTO request) {
        SyncEvidenceRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(service.observe(request));
    }
}
