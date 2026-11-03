package cn.iocoder.yudao.module.supply.api.app.dto;

import java.util.List;
import java.util.TreeSet;

/** 仅供已经校验批准证据的服务使用，浏览器不能提交授权来源或主体。 */
public final class SupplyQuotaDTO {

    /**
     * 限制供应额度工具的实例化，统一通过静态能力使用。
     */
    private SupplyQuotaDTO() {
    }

    public record Source(String sourceSystem, String sourceKey, SupplyGrantSourceCommandDTO.Quota quota,
                         List<String> columns) {

        /**
         * 创建来源实例。
         *
         * @param sourceSystem 来源系统
         * @param sourceKey 来源键
         * @param quota 额度
         * @param columns 列集合
         */
        public Source {
            columns = List.copyOf(new TreeSet<>(columns));
        }
    }

    public record Reserve(String consumerKey, String requestKey, long actorId, String requestHash,
                          int rowLimit, List<String> columns, List<Source> sources) {

        /**
         * 创建预留实例。
         *
         * @param consumerKey 消费者标识
         * @param requestKey 请求键
         * @param actorId 操作人编号
         * @param requestHash 请求内容摘要
         * @param rowLimit 行上限
         * @param columns 列集合
         * @param sources 来源
         */
        public Reserve {
            columns = List.copyOf(new TreeSet<>(columns));
            sources = List.copyOf(sources);
        }
    }

    public record Settle(String consumerKey, String requestKey, String token, Integer actualRows, boolean cancel) {

    }

    public record Allocation(String sourceSystem, String sourceKey, String metric, long reserved) {

    }

    public record Balance(String sourceSystem, String sourceKey, String metric, long quotaLimit,
                          long usedUnits, long reservedUnits, long remainingUnits) {

    }

    public record Receipt(String consumerKey, String requestKey, String state, String token,
                          int rowLimit, Integer actualRows, List<Allocation> allocations) {

    }
}
