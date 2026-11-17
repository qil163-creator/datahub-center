package cn.iocoder.yudao.module.supply.api.dataapi;

import cn.iocoder.yudao.module.supply.service.dataapi.PublishedWriteClaims;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiExecutor;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.framework.security.WriteClaimRpcProperties;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

@RestController
@Validated
@RequiredArgsConstructor
@EnableConfigurationProperties(WriteClaimRpcProperties.class)
public class PublishedWriteClaimApiImpl implements PublishedWriteClaimApi {
    private final PublishedWriteClaims claims;
    private final DataApiMapper apis;
    private final DataApiExecutor executor;
    private final HttpServletRequest request;
    private final WriteClaimRpcProperties properties;
    private final VerifiedServicePeerResolver peers = new VerifiedServicePeerResolver();

    // 开放网关没有租户身份，当前单租户数据源合同不能授权多租户写入。
    @Value("${yudao.tenant.enable:true}")
    private boolean tenantEnabled;

    @Override
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Contract> redeem(Ticket ticket) {
        if (tenantEnabled || peers.resolve(request, properties.peers()).filter("dataforge"::equals).isEmpty()) {
            throw exception(UNAUTHORIZED);
        }
        var contract = claims.redeem(ticket.value());
        var api = apis.selectById(contract.apiId());
        if (api == null || !Integer.valueOf(1).equals(api.getStatus())
                || !Objects.equals(contract.releaseId(), api.getActiveReleaseId())
                || !Objects.equals(contract.databaseId(), api.getDatabaseId())
                || !DataApiDO.CONFIG_TYPE_SQL.equals(api.getConfigType()) || !DataApiDO.AUTH_TYPE_SIGN.equals(api.getAuthType())
                || !Objects.equals(contract.apiType(), api.getApiType())) throw exception(UNAUTHORIZED);
        executor.validateClaimGrant(api, contract.appId());
        return CommonResult.success(contract);
    }
}
