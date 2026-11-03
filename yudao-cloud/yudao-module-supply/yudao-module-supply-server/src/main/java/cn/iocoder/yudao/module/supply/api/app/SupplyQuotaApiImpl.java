package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.*;
import cn.iocoder.yudao.module.supply.framework.security.SourceGrantRpcFilter;
import cn.iocoder.yudao.module.supply.service.app.SupplyQuotaService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.*;

@RestController
public class SupplyQuotaApiImpl implements SupplyQuotaApi {

    @Resource
    private SupplyQuotaService service;

    /**
     * 确认当前 RPC 的已验证服务身份属于资源门户服务。
     */
    private void peer() {
        if (!"asset".equals(SourceGrantRpcFilter.requireVerifiedPeer(ServletUtils.getRequest()))) {
            throw exception(GRANT_SOURCE_SYSTEM_MISMATCH);
        }
    }

    /**
     * 校验 RPC 服务身份及门户消费方标识格式。
     *
     * @param key 标识键
     */
    private void consumer(String key) {
        peer();
        if (key == null || !key.matches("PORTAL:[0-9]+:[0-9]+")) {
            throw exception(QUOTA_REQUEST_INVALID);
        }
    }

    /**
     * 预留当前操作所需的资源或领域占用，并返回对应凭据。
     *
     * @param r 当前记录
     * @return 回执
     */
    public CommonResult<Receipt> reserve(Reserve r) {
        consumer(r.consumerKey());
        if (!r.consumerKey().startsWith("PORTAL:" + r.actorId() + ":")
                || r.sources().stream()
                .anyMatch(s -> !"asset".equals(s.sourceSystem()) || !s.sourceKey().matches("asset:apply:[0-9]+"))) {
            throw exception(QUOTA_REQUEST_INVALID);
        }
        return success(service.reserve(r));
    }

    /**
     * 获取供应额度的结算。
     *
     * @param r 当前记录
     * @return 回执
     */
    public CommonResult<Receipt> settle(Settle r) {
        consumer(r.consumerKey());
        return success(service.settle(r));
    }

    /**
     * 读取供应额度处理回执。
     *
     * @param consumerKey 消费者标识
     * @param requestKey 请求键
     * @param actorId 操作人编号
     * @return 回执
     */
    public CommonResult<Receipt> receipt(String consumerKey, String requestKey, long actorId) {
        consumer(consumerKey);
        return success(service.receipt(consumerKey, requestKey, actorId));
    }

    /**
     * 读取供应额度历史记录。
     *
     * @param actorId 操作人编号
     * @return 供应额度历史记录结果集合
     */
    public CommonResult<List<Receipt>> history(long actorId) {
        peer();
        return success(service.history(actorId));
    }

    /**
     * 获取供应额度的余额。
     *
     * @param actorId 操作人编号
     * @return 供应额度的余额结果集合
     */
    public CommonResult<List<Balance>> balances(long actorId) {
        peer();
        return success(service.balances(actorId));
    }
}
