package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketAuditEventDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_OPERATION_NOT_PERSISTED;

/**
 * 普通市场领域审计，与业务写入处于调用方的同一真实事务：恰好写入一行后业务才可提交。
 *
 * <p>PostgreSQL 语句失败会中止整个事务，吞掉审计异常只会让提交静默回滚、业务却返回成功；
 * 因此任何失败都原样向调用方传播并整体回滚，不另开事务、不重试。</p>
 */
@Slf4j
@Component
public class MarketAuditRecorder {

    @Resource
    private MarketAuditEventMapper auditEventMapper;

    /**
     * 保存市场审计操作记录。
     */
    public void record(String eventType, String objectType, Long objectId,
            Integer fromStatus, Integer toStatus, String reason, String result) {
        recordAs(eventType, objectType, objectId, fromStatus, toStatus, reason, result,
                SecurityFrameworkUtils.getLoginUserId(), SecurityFrameworkUtils.getLoginUserDeptId());
    }

    /**
     * 系统作业路径（到期失效等）没有登录上下文，但审计仍需明确操作人；
     * 与业务过程记录一致使用申请人的用户编号作为操作者，部门可空。
     */
    public void recordAs(String eventType, String objectType, Long objectId,
            Integer fromStatus, Integer toStatus, String reason, String result,
            Long actorUserId, Long actorDeptId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("市场领域审计必须与业务写入在同一事务内提交");
        }

        if (actorUserId == null || actorUserId <= 0) {
            throw new IllegalStateException("市场领域审计缺少可信操作人");
        }
        MarketAuditEventDO event = MarketAuditEventDO.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .actorUserId(actorUserId)
                .deptId(actorDeptId)
                .objectType(objectType)
                .objectId(objectId)
                .fromStatus(fromStatus == null ? null : String.valueOf(fromStatus))
                .toStatus(toStatus == null ? null : String.valueOf(toStatus))
                .reason(reason)
                .result(result)
                .build();
        event.setCreator(String.valueOf(actorUserId));
        event.setUpdater(String.valueOf(actorUserId));
        if (auditEventMapper.insertEvent(event) != 1) {
            // 只记录定位信息，不输出原因正文。
            log.error("[record][市场审计未写入 type({}) object({}:{})]", eventType, objectType, objectId);
            throw exception(MARKET_OPERATION_NOT_PERSISTED);
        }
    }
}
