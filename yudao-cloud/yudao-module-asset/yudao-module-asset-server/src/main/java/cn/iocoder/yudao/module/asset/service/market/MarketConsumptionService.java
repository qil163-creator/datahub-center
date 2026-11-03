package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.supply.api.app.SupplyQuotaApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** 读取前预占、授权重验后确认。冻结来源来自本服务数据库，RPC不接受浏览器指定授权范围。 */
@Service
public class MarketConsumptionService {

    @Resource
    private SupplyQuotaApi quotaApi;

    /**
     * 判断市场消费是否包含有限额授权来源。
     */
    public static boolean limited(MarketQuerySnapshot snapshot) {
        return snapshot != null && snapshot.grants().stream()
                .anyMatch(g -> g.quotaSource() != null && g.quotaSource().quota() != null);
    }

    /**
     * 读取市场消费的业务数据。
     */
    public static <T> T read(MarketConsumptionService service, MarketQuerySnapshot snapshot, Object request,
            int limit, IntFunction<T> action, ToIntFunction<T> count) {
        if (!limited(snapshot)) {
            return action.apply(limit);
        }
        return service.readLimited(snapshot, request, limit, action, count);
    }

    /**
     * 读取受限。
     */
    private <T> T readLimited(MarketQuerySnapshot snapshot, Object request, int limit, IntFunction<T> action,
            ToIntFunction<T> count) {
        var http = ServletUtils.getRequest();
        String key = http == null ? null : http.getHeader("X-Consumption-Key");
        String consumer = "PORTAL:" + snapshot.userId() + ":" + snapshot.assetId();
        String hash = DigestUtil.sha256Hex(
                JsonUtils.toJsonString(List.of(snapshot.assetId(), snapshot.grants(), request, limit)));
        var reservation = quotaApi.reserve(new Reserve(consumer, key, snapshot.userId(), hash, limit,
                List.copyOf(snapshot.fieldScope().getColumns()),
                snapshot.grants().stream().map(MarketQuerySnapshot.Grant::quotaSource).toList())).getCheckedData();
        if (reservation == null || !"RESERVED".equals(reservation.state())) {
            throw new IllegalStateException("消费预占未确认");
        }
        try {
            T result = action.apply(reservation.rowLimit()); // action 内含返回前批准范围重验。
            var settlement = new Settle(consumer, key, reservation.token(), count.applyAsInt(result), false);
            Receipt receipt;
            try {
                receipt = quotaApi.settle(settlement).getCheckedData();
            } catch (RuntimeException lostReceipt) {
                // 只查询原回执，不重新读取数据；查询无法确认时仍拒绝返回结果。
                receipt = quotaApi.receipt(consumer, key, snapshot.userId()).getCheckedData();
            }
            if (receipt == null || !"COMMITTED".equals(receipt.state()) || !java.util.Objects.equals(
                    receipt.actualRows(), settlement.actualRows())) {
                throw new IllegalStateException("消费确认失败，请查看消费回执");
            }
            return result;
        } catch (RuntimeException failure) {
            try {
                quotaApi.settle(new Settle(consumer, key, reservation.token(), null, true)).getCheckedData();
            } catch (RuntimeException ignored) { /* 未确认预占由 Supply 租约任务释放，已确认记录不回退。 */ }
            throw failure;
        }
    }
}
