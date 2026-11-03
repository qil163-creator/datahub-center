package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.*;
import cn.iocoder.yudao.module.supply.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 复用来源授权的 mTLS 通道；门户单独申请与网关按同一来源稳定键记账。 */
@FeignClient(name = ApiConstants.NAME, contextId = "supply-consumption-quota-v1",
        configuration = SupplyGrantSourceClientConfiguration.class)
public interface SupplyQuotaApi {

    String PREFIX = SupplyGrantSourceApi.PREFIX + "/quota";

    /**
     * 预留当前操作所需的资源或领域占用，并返回对应凭据。
     *
     * @param request 请求参数
     * @return 回执
     */
    @PostMapping(PREFIX + "/reserve")
    CommonResult<Receipt> reserve(@RequestBody Reserve request);

    /**
     * 获取供应额度的结算。
     *
     * @param request 请求参数
     * @return 回执
     */
    @PostMapping(PREFIX + "/settle")
    CommonResult<Receipt> settle(@RequestBody Settle request);

    /**
     * 读取供应额度处理回执。
     *
     * @param consumerKey 消费者标识
     * @param requestKey 请求键
     * @param actorId 操作人编号
     * @return 回执
     */
    @GetMapping(PREFIX + "/receipt")
    CommonResult<Receipt> receipt(@RequestParam("consumerKey") String consumerKey,
            @RequestParam("requestKey") String requestKey, @RequestParam("actorId") long actorId);

    /**
     * 读取供应额度历史记录。
     *
     * @param actorId 操作人编号
     * @return 供应额度历史记录结果集合
     */
    @GetMapping(PREFIX + "/history")
    CommonResult<List<Receipt>> history(@RequestParam("actorId") long actorId);

    /**
     * 获取供应额度的余额。
     *
     * @param actorId 操作人编号
     * @return 供应额度的余额结果集合
     */
    @GetMapping(PREFIX + "/balances")
    CommonResult<List<Balance>> balances(@RequestParam("actorId") long actorId);
}
