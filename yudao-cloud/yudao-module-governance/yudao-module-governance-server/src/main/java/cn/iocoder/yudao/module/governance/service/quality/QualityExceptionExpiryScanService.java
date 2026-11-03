package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryCursorDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderSubmissionDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryCursorMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderSubmissionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** 质量领域的公平候选预约；持锁短事务只推进扫描位置，不发送通知、不创建复核单。 */
@Service
public class QualityExceptionExpiryScanService {

    @Resource
    private QualityExceptionExpiryCursorMapper cursorMapper;

    @Resource
    private QualityWorkOrderSubmissionMapper submissionMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 在独立短事务中预约到期例外扫描批次，并检查线程与事务上下文清理。
     */
    public List<QualityWorkOrderSubmissionDO> reserve(LocalDateTime now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("例外到期扫描预约要求在事务外执行");
        }

        checkInterrupted();
        boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
        var resources = new HashMap<>(TransactionSynchronizationManager.getResourceMap());
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(10);
        try {
            var candidates = reserveInTransaction(now, transaction);
            checkInterrupted();
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive() != synchronizationActive
                    || !resources.equals(TransactionSynchronizationManager.getResourceMap())) {
                throw new IllegalStateException("例外到期扫描预约上下文未清理");
            }
            return candidates;
        } catch (RuntimeException ex) {
            var completion = ExceptionUtils.getThrowableList(ex).stream()
                    .filter(TransactionSystemException.class::isInstance).map(TransactionSystemException.class::cast)
                    .findFirst().orElse(null);
            Throwable application = completion == null ? null : completion.getApplicationException();
            if (ExceptionUtils.indexOfType(ex, InterruptedException.class) >= 0
                    || (application != null && ExceptionUtils.indexOfType(application,
                    InterruptedException.class) >= 0)) {
                Thread.currentThread().interrupt();
            }
            RuntimeException summary = Thread.currentThread().isInterrupted()
                    || ExceptionUtils.indexOfType(ex, CancellationException.class) >= 0
                    || (application != null && ExceptionUtils.indexOfType(application,
                    CancellationException.class) >= 0)
                    ? new CancellationException("例外到期扫描预约已取消或中断，游标提交结果待核查")
                    : new IllegalStateException("例外到期扫描预约异常，候选不得执行，游标提交结果待核查");
            // 原始驱动错误只保留在受控诊断中，Job 根摘要不得泄露厂商原文。
            summary.addSuppressed(ex);
            if (application != null) {
                summary.addSuppressed(application);
            }
            throw summary;
        }
    }

    /**
     * 锁定扫描游标后查询到期例外并推进游标，扫描结束时回绕。
     */
    private List<QualityWorkOrderSubmissionDO> reserveInTransaction(LocalDateTime now,
            TransactionTemplate transaction) {
        return transaction.execute(tx -> {
            cursorMapper.initializeSingleton();
            var cursor = cursorMapper.selectSingletonForUpdate();
            checkQueryContext();
            if (cursor == null
                    || cursor.getAfterSubmissionId() == null || cursor.getThroughSubmissionId() == null
                    || cursor.getAfterSubmissionId() < 0 || cursor.getThroughSubmissionId() < cursor.getAfterSubmissionId()) {
                throw new IllegalStateException("例外到期扫描游标不完整");
            }
            long after = cursor.getAfterSubmissionId();
            long through = cursor.getThroughSubmissionId();
            if (through == 0 || after == through) {
                after = 0;
                through = submissionMapper.selectScanUpperId();
                checkQueryContext();
            }
            var candidates = submissionMapper.selectExpiredExceptions(now, 20, after, through);
            checkQueryContext();
            if (candidates.isEmpty() && after > 0) {
                // 本轮固定上界以内已扫完；回绕重新纳入失败/崩溃遗漏和新到期项。
                after = 0;
                through = submissionMapper.selectScanUpperId();
                checkQueryContext();
                candidates = submissionMapper.selectExpiredExceptions(now, 20, after, through);
                checkQueryContext();
            }
            // 空轮次也必须结束，否则 (0,旧上界] 会永久冻结并遗漏后续新增到期单。
            long next = candidates.isEmpty() ? through : after;
            if (candidates.size() > 20) {
                throw new IllegalStateException("例外到期扫描候选超限");
            }
            for (var candidate : candidates) {
                if (candidate == null || candidate.getId() == null || candidate.getId() <= next
                        || candidate.getId() > through) {
                    throw new IllegalStateException("例外到期扫描候选顺序异常");
                }
                next = candidate.getId();
            }
            // 先预约不等于先标成功：业务资格不改，只有完整逐单事务的动作标记排除已完成项。
            int updated = cursorMapper.update(null, Wrappers.lambdaUpdate(QualityExceptionExpiryCursorDO.class)
                    .eq(QualityExceptionExpiryCursorDO::getId, cursor.getId())

                    .eq(QualityExceptionExpiryCursorDO::getAfterSubmissionId, cursor.getAfterSubmissionId())
                    .eq(QualityExceptionExpiryCursorDO::getThroughSubmissionId, cursor.getThroughSubmissionId())
                    .setSql("update_time = CURRENT_TIMESTAMP")
                    .set(QualityExceptionExpiryCursorDO::getAfterSubmissionId, next)
                    .set(QualityExceptionExpiryCursorDO::getThroughSubmissionId, through));
            checkQueryContext();
            if (updated != 1) {
                throw new IllegalStateException("例外到期扫描游标推进失败");
            }
            return candidates;
        });
    }

    /**
     * 检查质量例外到期扫描的查询上下文，不满足约束时拒绝当前操作。
     */
    private static void checkQueryContext() {
        checkInterrupted();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("例外到期候选查询上下文异常，停止本批");
        }
    }

    /**
     * 检查质量例外到期扫描的已中断，不满足约束时拒绝当前操作。
     */
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("例外到期扫描已中断");
        }
    }
}
