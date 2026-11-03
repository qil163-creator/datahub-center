package cn.iocoder.yudao.module.asset.service.market;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_QUOTA_UNSUPPORTED;

/** 申请终身累计：0不限、1按成功返回次数、2按实际返回行数。旧有限额记录必须补齐批准证据。 */
final class MarketQuotaPolicy {

    /**
     * 限制市场额度工具类的直接实例化。
     */
    private MarketQuotaPolicy() {
    }

    /**
     * 校验市场额度配置满足当前业务约束。
     */
    static void validate(Integer type, Integer times) {
        quota(type, times);
    }

    /**
     * 校验额度类型与数量，转换为调用次数或返回行数额度。
     */
    static cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Quota quota(Integer type,
            Integer times) {
        if ((type == null || type == 0) && (times == null || times == 0)) {
            return null;
        }
        if (type == null || (type != 1 && type != 2) || times == null || times <= 0) {
            throw exception(MARKET_APPLY_QUOTA_UNSUPPORTED);
        }
        return new cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Quota(
                type == 1 ? "CALLS" : "ROWS", times);
    }

    /**
     * 校验市场额度的冻结，不满足约束时拒绝当前操作。
     */
    static void requireFrozen(cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO apply) {
        var quota = quota(apply.getApplyUseType(), apply.getApplyUseTimes());
        java.util.Map<?, ?> evidence = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                apply.getSnapshot(), java.util.Map.class);
        if (evidence == null || (quota != null || evidence.containsKey("applyUseType"))
                && (!java.util.Objects.equals(evidence.get("applyUseType"),
                String.valueOf(apply.getApplyUseType() == null ? 0 : apply.getApplyUseType()))
                || !java.util.Objects.equals(evidence.get("applyUseTimes"),
                String.valueOf(apply.getApplyUseTimes() == null ? 0 : apply.getApplyUseTimes())))) {
            throw exception(MARKET_APPLY_QUOTA_UNSUPPORTED);
        }
    }
}
