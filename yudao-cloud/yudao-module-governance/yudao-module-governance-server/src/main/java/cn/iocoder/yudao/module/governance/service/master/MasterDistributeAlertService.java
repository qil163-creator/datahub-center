package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeAlertMapper;
import cn.iocoder.yudao.module.governance.framework.rpc.MasterDistributeAlertNotifyClient;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;

/** 主数据派发领域告警：持久意图同事务，RPC 在事务外。仅通知采用有界至少一次投递。 */
@Service
public class MasterDistributeAlertService {

    public static final String TEMPLATE = "governance_master_distribute_outcome";

    @Resource
    private MasterDistributeAlertMapper alerts;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private MasterDistributeAlertNotifyClient notifyClient;

    /**
     * 在平台事务边界内完成主数据分发告警的本地读写。
     */
    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return tx;
    }

    /** 必须随领域终态提交；缺少接收者也保存为死信，不能使关键告警静默消失。 */
    void enqueue(MasterDistributeDO task, Long runId, String kind) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("告警必须与业务状态同事务");
        }
        if (!"UNKNOWN".equals(kind) && !"DISPATCH_EXHAUSTED".equals(kind)) {
            throw new IllegalArgumentException("告警类型无效");
        }
        Long recipient = task.getReleaseUserId();
        if (recipient == null || recipient <= 0) {
            try {
                recipient = Long.valueOf(task.getCreator());
            } catch (RuntimeException ignored) {
                recipient = null;
            }
        }
        if (recipient != null && recipient <= 0) {
            recipient = null;
        }
        String name = task.getName() == null ? String.valueOf(task.getId()) : task.getName();
        if (name.length() > 128) {
            name = name.substring(0, 128);
        }
        alerts.enqueue(task.getId(), runId, kind, recipient, name);
    }

    /** true 表示该条已确认投递或被其他节点持有，false 表示需运维关注。 */
    public boolean deliver(Long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("通知RPC不能处于业务事务中");
        }
        MasterDistributeAlertDO row = transaction().execute(status -> {
            if (alerts.exhaust(id) == 1) {
                return null;
            }
            if (alerts.claim(id, IdUtil.fastSimpleUUID()) != 1) {
                return null;
            }
            return alerts.selectById(id);
        });
        if (row == null) {
            return true;
        }
        String error;
        try {
            var request = new NotifySendSingleToUserReqDTO();
            request.setUserId(row.getRecipientId());
            request.setTemplateCode(TEMPLATE);
            request.setTemplateParams(Map.of("taskName", row.getTaskName(), "runId", String.valueOf(row.getRunId()),
                    "outcome", "UNKNOWN".equals(
                            row.getEventType()) ? "结果未知，请核查，禁止直接重跑" : "发送前领取次数耗尽，未执行发送",
                    "alertId", String.valueOf(row.getId())));
            var response = notifyClient.sendSingleMessageToAdmin(request);
            if (response != null && response.isSuccess() && response.getData() != null && response.getData() > 0) {
                return Boolean.TRUE.equals(transaction().execute(status ->
                        alerts.delivered(id, row.getLeaseToken(), response.getData()) == 1));
            }
            // 包括HTTP成功但业务错误，以及模板禁用返回空编号，不得误标为已投递。
            error = response == null || response.isSuccess() ? "MISSING_RECEIPT" : "REMOTE_REJECTED";
        } catch (Exception ex) {
            // 站内信接口无去重/查询契约，丢回执后可能重复通知；编号稳定，绝不触发业务重跑。
            error = "DELIVERY_UNCONFIRMED";
        }
        String failure = error;
        transaction().executeWithoutResult(status -> alerts.failed(id, row.getLeaseToken(), failure));
        return false;
    }

    /** 仅供受控XXL-Job人工任务；job/log编号关联调度中心审计，不接受浏览器自报身份。 */
    public void dispose(Long id, String action, String reason, String command) {
        if (id == null || id <= 0 || !("RETRY".equals(action) || "ACKNOWLEDGE".equals(action))
                || reason == null || reason.isBlank() || reason.length() > 500
                || command == null || !command.matches("xxl-job:[0-9]+:[0-9]+")) {
            throw new IllegalArgumentException("需要有效告警、操作、受控原因和调度执行编号");
        }
        transaction().executeWithoutResult(status -> {
            var row = alerts.lockOwned(id);
            if (row == null) {
                throw new IllegalArgumentException("告警不存在");
            }
            if (alerts.commandExists(command) > 0) {
                if (alerts.matchesCommand(command, id, action, reason) != 1) {
                    throw new IllegalStateException("人工处置请求冲突");
                }
                return;
            }
            if (row.getStatus() != 3 || ("RETRY".equals(action) && row.getRecipientId() == null)) {
                throw new IllegalStateException("仅死信允许处置；无接收者只能人工接管");
            }
            if (alerts.dispose(id, action) != 1) {
                throw new IllegalStateException("告警处置竞争");
            }
            alerts.audit(command, id, action, reason);
        });
    }
}
