package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据集市站内信。事务成功提交并释放行锁后尽力通知；回滚不通知，失败只打日志。
 */
@Slf4j
@Component
public class MarketNotifyHelper {

    public static final String APPLY_SUBMIT = "market_apply_submit";

    public static final String APPLY_CHECKED = "market_apply_checked";

    public static final String APPLY_REVOKED = "market_apply_revoked";

    public static final String APPLY_EXPIRED = "market_apply_expired";

    public static final String DEMAND_REPLIED = "market_demand_replied";

    public static final String DEMAND_MATCHED = "market_demand_matched";

    public static final String DEMAND_CONFIRMED = "market_demand_confirmed";

    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    /**
     * 通知申请提交。
     */
    public void notifyApplySubmit(MarketResourceApplyDO apply, DataAssetDO asset) {
        if (apply == null || asset == null) {
            return;
        }
        Map<String, Object> params = applyParams(apply, asset, null);
        for (Long userId : defaultReviewers(asset)) {
            if (!Objects.equals(userId, apply.getApplyUserId())) {
                send(userId, APPLY_SUBMIT, params);
            }
        }
    }

    /**
     * 通知申请已核验。
     */
    public void notifyApplyChecked(MarketResourceApplyDO apply, DataAssetDO asset, String result) {
        if (apply == null || apply.getApplyUserId() == null) {
            return;
        }
        send(apply.getApplyUserId(), APPLY_CHECKED, applyParams(apply, asset, result));
    }

    /**
     * 通知申请已撤回。
     */
    public void notifyApplyRevoked(MarketResourceApplyDO apply, DataAssetDO asset) {
        if (apply == null || apply.getApplyUserId() == null) {
            return;
        }
        send(apply.getApplyUserId(), APPLY_REVOKED, applyParams(apply, asset, "收回"));
    }

    /**
     * 通知申请已过期。
     */
    public void notifyApplyExpired(MarketResourceApplyDO apply, DataAssetDO asset) {
        if (apply == null || apply.getApplyUserId() == null) {
            return;
        }
        send(apply.getApplyUserId(), APPLY_EXPIRED, applyParams(apply, asset, "过期"));
    }

    /**
     * 通知需求已回复。
     */
    public void notifyDemandReplied(MarketDemandDO demand, Long replyUserId) {
        if (demand == null || demand.getApplyUserId() == null
                || Objects.equals(demand.getApplyUserId(), replyUserId)) {
            return;
        }
        send(demand.getApplyUserId(), DEMAND_REPLIED, demandParams(demand, null, null));
    }

    /**
     * 通知需求已匹配。
     */
    public void notifyDemandMatched(MarketDemandDO demand, DataAssetDO asset) {
        if (demand == null || demand.getApplyUserId() == null) {
            return;
        }
        send(demand.getApplyUserId(), DEMAND_MATCHED, demandParams(demand, asset, null));
    }

    /**
     * 通知需求已确认。
     */
    public void notifyDemandConfirmed(MarketDemandDO demand, MarketDemandMatchDO match,
            DataAssetDO asset, String result) {
        if (match == null || match.getMatcherUserId() == null
                || Objects.equals(match.getMatcherUserId(), demand == null ? null : demand.getApplyUserId())) {
            return;
        }
        send(match.getMatcherUserId(), DEMAND_CONFIRMED, demandParams(demand, asset, result));
    }

    /**
     * 收集资产技术负责人和业务负责人作为默认审核人。
     */
    public Set<Long> defaultReviewers(DataAssetDO asset) {
        Set<Long> userIds = new LinkedHashSet<>();
        if (asset == null) {
            return userIds;
        }
        if (asset.getTechLeaderUserId() != null) {
            userIds.add(asset.getTechLeaderUserId());
        }
        if (asset.getBusinessLeaderUserId() != null) {
            userIds.add(asset.getBusinessLeaderUserId());
        }
        return userIds;
    }

    /**
     * 优先读取资产技术负责人，缺失时使用业务负责人。
     */
    public Long primaryReviewer(DataAssetDO asset) {
        if (asset == null) {
            return null;
        }
        return asset.getTechLeaderUserId() != null
                ? asset.getTechLeaderUserId() : asset.getBusinessLeaderUserId();
    }

    /**
     * 应用参数。
     */
    private Map<String, Object> applyParams(MarketResourceApplyDO apply, DataAssetDO asset, String result) {
        return Map.of(
                "applyNo", StrUtil.blankToDefault(apply.getApplyNo(), ""),
                "title", StrUtil.blankToDefault(apply.getTitle(), ""),
                "assetName", asset == null ? "" : StrUtil.blankToDefault(asset.getAssetName(), ""),
                "checkNote", StrUtil.blankToDefault(apply.getCheckNote(), "-"),
                "result", StrUtil.blankToDefault(result, ""));
    }

    /**
     * 组装需求通知模板中的需求、资产和处理结果参数。
     */
    private Map<String, Object> demandParams(MarketDemandDO demand, DataAssetDO asset, String result) {
        return Map.of(
                "demandNo", demand == null ? "" : StrUtil.blankToDefault(demand.getDemandNo(), ""),
                "title", demand == null ? "" : StrUtil.blankToDefault(demand.getTitle(), ""),
                "assetName", asset == null ? "" : StrUtil.blankToDefault(asset.getAssetName(), ""),
                "result", StrUtil.blankToDefault(result, ""));
    }

    /**
     * 事务提交后派发站内信，无事务时直接派发。
     */
    private void send(Long userId, String templateCode, Map<String, Object> params) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 在当前事务成功提交后触发市场通知后续处理。
                 */
                @Override
                public void afterCommit() {
                    dispatch(userId, templateCode, params);
                }
            });
            return;
        }
        dispatch(userId, templateCode, params);
    }

    /**
     * 调用通知 API 发送站内信，发送失败时记录告警。
     */
    private void dispatch(Long userId, String templateCode, Map<String, Object> params) {
        try {
            NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
            reqDTO.setUserId(userId);
            reqDTO.setTemplateCode(templateCode);
            reqDTO.setTemplateParams(params);
            notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
        } catch (Exception ex) {
            log.warn("[send][集市站内信失败 template={} userId={}：{}]",
                    templateCode, userId, ex.getMessage());
        }
    }

}
