package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillCreateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillItemRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevBackfillDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevBackfillItemDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevBackfillItemMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevBackfillMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据开发-补数据 Service 实现。
 *
 * <p>推进分三段，避免在批次行锁内调用会抛业务异常的运行登记：
 * ① 锁定批次，按运行实例终态结算已提交日期并选出下一个日期；② 在任务行锁内登记运行（运行键
 * {@code BACKFILL:<批次>:<yyyyMMdd>} 幂等，崩溃后重入得到同一实例）；③ 回填明细的运行实例。
 * 任务存在其他活跃运行（周期触发、手工执行）时只等待，不算失败。</p>
 */
@Service
@Validated
@Slf4j
public class DevBackfillServiceImpl implements DevBackfillService {

    /** 单批次最多日期数 */
    static final int MAX_DAYS = 366;

    /** 单次推进的批次上限 */
    static final int ADVANCE_LIMIT = 50;

    private static final Set<String> QUALITY_ACCEPTED = Set.of("PASSED", "NOT_CONFIGURED");

    private static final Set<String> QUALITY_PENDING = Set.of("PENDING", "UNKNOWN");

    private final DevBackfillMapper backfillMapper;

    private final DevBackfillItemMapper itemMapper;

    private final DevRecordMapper recordMapper;

    private final DevTaskService taskService;

    private final DevDsSubmitter dsSubmitter;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    @Autowired
    public DevBackfillServiceImpl(DevBackfillMapper backfillMapper, DevBackfillItemMapper itemMapper,
            DevRecordMapper recordMapper, DevTaskService taskService, DevDsSubmitter dsSubmitter,
            TransactionTemplate transactionTemplate) {
        this(backfillMapper, itemMapper, recordMapper, taskService, dsSubmitter, transactionTemplate,
                Clock.systemDefaultZone());
    }

    DevBackfillServiceImpl(DevBackfillMapper backfillMapper, DevBackfillItemMapper itemMapper,
            DevRecordMapper recordMapper, DevTaskService taskService, DevDsSubmitter dsSubmitter,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.backfillMapper = backfillMapper;
        this.itemMapper = itemMapper;
        this.recordMapper = recordMapper;
        this.taskService = taskService;
        this.dsSubmitter = dsSubmitter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public Long createBackfill(DevBackfillCreateReqVO req) {
        DevBackfillDO existing = backfillMapper.selectByRequestId(req.getRequestId());
        if (existing != null) {
            return matchingRequest(existing, req);
        }
        List<LocalDate> dates = expandDates(req.getStartDate(), req.getEndDate(),
                StrUtil.blankToDefault(req.getDateOrder(), DevBackfillDO.ORDER_ASC), LocalDate.now(clock));
        // 与“执行一次”相同的护栏：任务已上线、登录人即执行身份、运行权限、原生运行时已冻结。
        DevTaskVersionDO version = taskService.validateBackfill(req.getTaskId());
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(version.getSnapshotJson());
        Long id;
        try {
            id = transactionTemplate.execute(status -> {
                if (backfillMapper.existsRunning(req.getTaskId())) {
                    throw exception(DEV_BACKFILL_ALREADY_RUNNING);
                }
                DevBackfillDO backfill = DevBackfillDO.builder().taskId(req.getTaskId())
                        .taskName(snapshot.getTask().getName()).versionId(version.getId())
                        .executionUserId(snapshot.getTask().getExecutionUserId())
                        .startDate(req.getStartDate()).endDate(req.getEndDate())
                        .dateOrder(StrUtil.blankToDefault(req.getDateOrder(), DevBackfillDO.ORDER_ASC))
                        .failurePolicy(StrUtil.blankToDefault(req.getFailurePolicy(), DevBackfillDO.POLICY_STOP))
                        .status(DevBackfillDO.STATUS_RUNNING).totalCount(dates.size()).successCount(0)
                        .failedCount(0).cancelledCount(0).cancelRequested(false).requestId(req.getRequestId())
                        .remark(req.getRemark()).build();
                backfillMapper.insert(backfill);
                // 至多 366 行，逐行写入并与批次同事务提交。
                for (int i = 0; i < dates.size(); i++) {
                    itemMapper.insert(DevBackfillItemDO.builder().backfillId(backfill.getId()).taskId(req.getTaskId())
                            .seq(i + 1).businessDate(dates.get(i)).status(DevBackfillItemDO.STATUS_PENDING).build());
                }
                return backfill.getId();
            });
        } catch (DuplicateKeyException ex) {
            // 并发重复提交命中请求标识唯一索引，或同任务进行中批次的部分唯一索引。
            DevBackfillDO raced = backfillMapper.selectByRequestId(req.getRequestId());
            if (raced != null) {
                return matchingRequest(raced, req);
            }
            throw exception(DEV_BACKFILL_ALREADY_RUNNING);
        }
        // 立即登记首个日期，只写运行意图，不在请求线程联系调度引擎；失败留给周期推进重试。
        try {
            advance(id);
        } catch (RuntimeException ex) {
            log.warn("[createBackfill][批次({}) 首次推进失败，等待周期推进]", id, ex);
        }
        return id;
    }

    @Override
    public void cancelBackfill(Long id) {
        DevBackfillDO backfill = validateExists(id);
        requireOperator(backfill);
        transactionTemplate.executeWithoutResult(status -> {
            DevBackfillDO locked = backfillMapper.selectForUpdate(id);
            if (locked == null || !DevBackfillDO.STATUS_RUNNING.equals(locked.getStatus())) {
                throw exception(DEV_BACKFILL_NOT_RUNNING);
            }
            backfillMapper.updateById(DevBackfillDO.builder().id(id).cancelRequested(true).build());
            for (DevBackfillItemDO item : itemMapper.selectListByStatus(id, DevBackfillItemDO.STATUS_RUNNING)) {
                if (item.getRecordId() != null) {
                    dsSubmitter.stop(item.getRecordId());
                }
            }
        });
        advance(id);
    }

    @Override
    public DevBackfillRespVO getBackfill(Long id) {
        DevBackfillRespVO resp = BeanUtils.toBean(validateExists(id), DevBackfillRespVO.class);
        resp.setItems(BeanUtils.toBean(itemMapper.selectListByBackfillId(id), DevBackfillItemRespVO.class));
        return resp;
    }

    @Override
    public PageResult<DevBackfillRespVO> getBackfillPage(DevBackfillPageReqVO reqVO) {
        return BeanUtils.toBean(backfillMapper.selectPage(reqVO), DevBackfillRespVO.class);
    }

    @Override
    public void advance() {
        for (Long id : backfillMapper.selectRunningIds(ADVANCE_LIMIT)) {
            try {
                advance(id);
            } catch (RuntimeException ex) {
                log.warn("[advance][补数据批次({}) 推进失败，下次周期重试]", id, ex);
            }
        }
    }

    @Override
    public void advance(Long id) {
        DevBackfillItemDO next = transactionTemplate.execute(status -> settle(id));
        if (next == null) {
            return;
        }
        DevBackfillDO backfill = backfillMapper.selectById(id);
        Long recordId;
        try {
            recordId = taskService.enqueueBackfill(backfill.getTaskId(), backfill.getVersionId(),
                    runKey(id, next.getBusinessDate()), next.getBusinessDate());
        } catch (ServiceException ex) {
            if (Objects.equals(ex.getCode(), DEV_TASK_RUNNING.getCode())) {
                return; // 任务被周期触发或手工执行占用，等待空闲后再登记。
            }
            // 任务下线、版本变更、执行身份失权等是任务级条件，后续日期同样无法执行，整批终止。
            terminate(id, next.getId(), ex.getMessage());
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            boolean bound = itemMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .lambdaUpdate(DevBackfillItemDO.class)
                    .set(DevBackfillItemDO::getStatus, DevBackfillItemDO.STATUS_RUNNING)
                    .set(DevBackfillItemDO::getRecordId, recordId)
                    .set(DevBackfillItemDO::getStartTime, LocalDateTime.now(clock))
                    .eq(DevBackfillItemDO::getId, next.getId())
                    .eq(DevBackfillItemDO::getStatus, DevBackfillItemDO.STATUS_PENDING)) == 1;
            DevBackfillDO current = backfillMapper.selectById(id);
            if (!bound || Boolean.TRUE.equals(current.getCancelRequested())) {
                // 登记期间批次被取消：运行仍处于待投递阶段，持久化停止请求后由提交器直接结束。
                dsSubmitter.stop(recordId);
            }
        });
    }

    /**
     * 在批次行锁内结算已提交日期，按策略取消剩余日期、结束批次，或返回下一个待登记日期。
     */
    private DevBackfillItemDO settle(Long id) {
        DevBackfillDO backfill = backfillMapper.selectForUpdate(id);
        if (backfill == null || !DevBackfillDO.STATUS_RUNNING.equals(backfill.getStatus())) {
            return null;
        }
        boolean cancelRequested = Boolean.TRUE.equals(backfill.getCancelRequested());
        boolean stop = cancelRequested;
        String stopReason = cancelRequested ? "批次已取消" : null;
        int inFlight = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (DevBackfillItemDO item : itemMapper.selectListByStatus(id, DevBackfillItemDO.STATUS_RUNNING)) {
            Outcome outcome = outcome(item.getRecordId() == null ? null : recordMapper.selectById(item.getRecordId()),
                    cancelRequested);
            if (outcome == null) {
                inFlight++;
                continue;
            }
            itemMapper.updateById(DevBackfillItemDO.builder().id(item.getId()).status(outcome.status())
                    .lastError(outcome.error()).endTime(now).build());
            if (DevBackfillItemDO.STATUS_FAILED.equals(outcome.status())) {
                backfill.setLastError(item.getBusinessDate() + "：" + outcome.error());
                if (DevBackfillDO.POLICY_STOP.equals(backfill.getFailurePolicy()) && !stop) {
                    stop = true;
                    stopReason = "业务日期 " + item.getBusinessDate() + " 失败，按失败即停止策略取消";
                }
            }
        }
        if (stop) {
            cancelPending(id, stopReason, now);
        }
        DevBackfillItemDO next = null;
        if (inFlight == 0 && !stop) {
            next = itemMapper.selectNextPending(id);
        }
        String finalStatus = null;
        if (inFlight == 0 && next == null) {
            finalStatus = cancelRequested ? DevBackfillDO.STATUS_CANCELLED : null;
        }
        writeProgress(backfill, finalStatus, inFlight == 0 && next == null, now);
        return next;
    }

    /** 登记被拒绝的日期记为失败，剩余日期取消，批次以失败结束。 */
    private void terminate(Long id, Long itemId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            DevBackfillDO backfill = backfillMapper.selectForUpdate(id);
            if (backfill == null || !DevBackfillDO.STATUS_RUNNING.equals(backfill.getStatus())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            itemMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(DevBackfillItemDO.class)
                    .set(DevBackfillItemDO::getStatus, DevBackfillItemDO.STATUS_FAILED)
                    .set(DevBackfillItemDO::getLastError, truncate(reason))
                    .set(DevBackfillItemDO::getEndTime, now)
                    .eq(DevBackfillItemDO::getId, itemId)
                    .eq(DevBackfillItemDO::getStatus, DevBackfillItemDO.STATUS_PENDING));
            cancelPending(id, "批次终止：" + reason, now);
            backfill.setLastError(reason);
            boolean idle = itemMapper.selectListByStatus(id, DevBackfillItemDO.STATUS_RUNNING).isEmpty();
            writeProgress(backfill, null, idle, now);
        });
    }

    private void cancelPending(Long id, String reason, LocalDateTime now) {
        itemMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(DevBackfillItemDO.class)
                .set(DevBackfillItemDO::getStatus, DevBackfillItemDO.STATUS_CANCELLED)
                .set(DevBackfillItemDO::getLastError, truncate(reason))
                .set(DevBackfillItemDO::getEndTime, now)
                .eq(DevBackfillItemDO::getBackfillId, id)
                .eq(DevBackfillItemDO::getStatus, DevBackfillItemDO.STATUS_PENDING));
    }

    /** 按明细重新统计计数；批次结束时确定终态：取消优先，其次存在失败日期即失败，否则成功。 */
    private void writeProgress(DevBackfillDO backfill, String forcedStatus, boolean finished, LocalDateTime now) {
        int success = 0;
        int failed = 0;
        int cancelled = 0;
        for (DevBackfillItemDO item : itemMapper.selectListByBackfillId(backfill.getId())) {
            switch (item.getStatus()) {
                case DevBackfillItemDO.STATUS_SUCCESS -> success++;
                case DevBackfillItemDO.STATUS_FAILED -> failed++;
                case DevBackfillItemDO.STATUS_CANCELLED -> cancelled++;
                default -> {
                }
            }
        }
        DevBackfillDO update = DevBackfillDO.builder().id(backfill.getId()).successCount(success)
                .failedCount(failed).cancelledCount(cancelled).lastError(truncate(backfill.getLastError())).build();
        if (finished) {
            update.setStatus(forcedStatus != null ? forcedStatus
                    : failed > 0 ? DevBackfillDO.STATUS_FAILED
                    : cancelled > 0 ? DevBackfillDO.STATUS_CANCELLED : DevBackfillDO.STATUS_SUCCESS);
            update.setFinishTime(now);
        }
        backfillMapper.updateById(update);
    }

    /**
     * 运行实例到日期结果的映射；返回 {@code null} 表示仍在执行或等待质量结论。
     */
    static Outcome outcome(DevRecordDO record, boolean cancelRequested) {
        if (record == null) {
            return new Outcome(DevBackfillItemDO.STATUS_FAILED, "运行实例不存在");
        }
        String status = record.getStatus();
        if (DevRecordDO.ACTIVE_STATUSES.contains(status)) {
            return null;
        }
        if (DevRecordDO.STATUS_SUCCESS.equals(status)) {
            if (QUALITY_ACCEPTED.contains(record.getQualityStatus())) {
                return new Outcome(DevBackfillItemDO.STATUS_SUCCESS, null);
            }
            if (QUALITY_PENDING.contains(record.getQualityStatus())) {
                return null;
            }
            return new Outcome(DevBackfillItemDO.STATUS_FAILED, "质量检查未通过（" + record.getQualityStatus() + "）");
        }
        if (DevRecordDO.STATUS_STOPPED.equals(status)) {
            return cancelRequested ? new Outcome(DevBackfillItemDO.STATUS_CANCELLED, "已随批次取消")
                    : new Outcome(DevBackfillItemDO.STATUS_FAILED, "运行已被停止");
        }
        if (DevRecordDO.STATUS_FAILED.equals(status)) {
            return new Outcome(DevBackfillItemDO.STATUS_FAILED,
                    truncate(StrUtil.blankToDefault(record.getLastError(), "运行失败")));
        }
        return new Outcome(DevBackfillItemDO.STATUS_FAILED, "运行实例处于无法识别的状态：" + status);
    }

    /**
     * 校验并展开日期区间：起止均必填、起不晚于止、止不晚于今天、总天数不超过 {@link #MAX_DAYS}。
     */
    static List<LocalDate> expandDates(LocalDate start, LocalDate end, String order, LocalDate today) {
        if (start == null || end == null) {
            throw exception(DEV_BACKFILL_RANGE_INVALID, "起止日期不能为空");
        }
        if (start.isAfter(end)) {
            throw exception(DEV_BACKFILL_RANGE_INVALID, "起始日期不能晚于截止日期");
        }
        if (end.isAfter(today)) {
            throw exception(DEV_BACKFILL_RANGE_INVALID, "截止日期不能晚于今天");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw exception(DEV_BACKFILL_RANGE_INVALID, "单批次最多 " + MAX_DAYS + " 天");
        }
        List<LocalDate> dates = new ArrayList<>((int) days);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            dates.add(date);
        }
        if (DevBackfillDO.ORDER_DESC.equals(order)) {
            java.util.Collections.reverse(dates);
        }
        return dates;
    }

    static String runKey(Long backfillId, LocalDate businessDate) {
        return "BACKFILL:" + backfillId + ":" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private Long matchingRequest(DevBackfillDO existing, DevBackfillCreateReqVO req) {
        if (!Objects.equals(existing.getTaskId(), req.getTaskId())
                || !Objects.equals(existing.getStartDate(), req.getStartDate())
                || !Objects.equals(existing.getEndDate(), req.getEndDate())) {
            throw exception(DEV_RUN_REQUEST_CONFLICT);
        }
        return existing.getId();
    }

    private DevBackfillDO validateExists(Long id) {
        DevBackfillDO backfill = backfillMapper.selectById(id);
        if (backfill == null) {
            throw exception(DEV_BACKFILL_NOT_EXISTS);
        }
        return backfill;
    }

    /** 取消与发起同一身份要求：只有冻结版本的执行身份本人可取消。 */
    private void requireOperator(DevBackfillDO backfill) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || !Objects.equals(actor, backfill.getExecutionUserId())) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    record Outcome(String status, String error) {
    }

}
