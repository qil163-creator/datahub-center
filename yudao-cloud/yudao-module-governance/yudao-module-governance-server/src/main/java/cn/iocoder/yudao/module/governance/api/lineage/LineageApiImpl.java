package cn.iocoder.yudao.module.governance.api.lineage;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsBatchReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsRespDTO;
import cn.iocoder.yudao.module.governance.framework.security.LineageReportRpcProperties;
import cn.iocoder.yudao.module.governance.service.lineage.LineageReportService;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据血缘 RPC 接口实现：只做协议适配，校验、替换语义与事务都在 {@link LineageReportService}。
 */
@RestController
@Validated
@RequiredArgsConstructor
@EnableConfigurationProperties(LineageReportRpcProperties.class)
public class LineageApiImpl implements LineageApi {

    private final LineageReportService reportService;
    private final MetaTableKeyResolver tables;
    private final LineageReportRpcProperties properties;
    private final HttpServletRequest request;
    private final VerifiedServicePeerResolver peers = new VerifiedServicePeerResolver();

    @Override
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<LineageReportRespDTO> report(LineageReportReqDTO req) {
        requireProducer();
        return success(reportService.report(req));
    }

    @Override
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<List<MetadataTableColumnsRespDTO>> getColumnNamesBatch(MetadataTableColumnsBatchReqDTO req) {
        requireProducer();
        return success(tables.getColumnNamesBatch(req));
    }

    private void requireProducer() {
        if (peers.resolve(request, properties.peers()).filter("dataforge"::equals).isEmpty()) {
            throw exception(UNAUTHORIZED);
        }
    }

}
