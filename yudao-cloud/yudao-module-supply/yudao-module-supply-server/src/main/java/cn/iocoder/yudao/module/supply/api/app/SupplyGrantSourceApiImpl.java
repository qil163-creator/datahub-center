package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import cn.iocoder.yudao.module.supply.framework.security.SourceGrantRpcFilter;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantCoordinator;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.GRANT_SOURCE_SYSTEM_MISMATCH;

/**
 * 来源级授权 v2 RPC 适配：把已认证的服务身份映射为唯一允许的 sourceSystem，服务身份取自受信通道。
 *
 * 身份来自 {@link SourceGrantRpcFilter} 写入的请求属性；没有该属性（未经过门禁）即拒绝，
 * 请求体或参数不能自由声明 sourceSystem。事务与回执由 {@link SupplyGrantCoordinator} 负责，本类不自调用事务方法。
 */
@RestController
@Validated
public class SupplyGrantSourceApiImpl implements SupplyGrantSourceApi {

    @Resource
    private SupplyGrantCoordinator coordinator;

    /**
     * 应用来源授权命令。
     *
     * @param command 待处理命令
     * @return 供应授权来源结果
     */
    @Override
    public CommonResult<SupplyGrantSourceResultDTO> applySource(SupplyGrantSourceCommandDTO command) {
        String peer = SourceGrantRpcFilter.requireVerifiedPeer(ServletUtils.getRequest());
        if (!peer.equals(command.sourceSystem())) {
            throw exception(GRANT_SOURCE_SYSTEM_MISMATCH);
        }
        return success(coordinator.apply(command));
    }

    /**
     * 查询当前平台的来源操作回执。
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权来源结果
     */
    @Override
    public CommonResult<SupplyGrantSourceResultDTO> querySourceOperation(String sourceSystem, String operationId) {
        String peer = SourceGrantRpcFilter.requireVerifiedPeer(ServletUtils.getRequest());
        if (!peer.equals(sourceSystem)) {
            throw exception(GRANT_SOURCE_SYSTEM_MISMATCH);
        }
        return success(coordinator.query(sourceSystem, operationId));
    }

}
