package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailurePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailureRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailureRetryReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryFailureDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryFailureMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT;

/**
 * 例外到期处理失败账本的查询与人工授权重试。
 */
@Service
public class QualityExceptionExpiryFailureService {

    private final QualityExceptionExpiryFailureMapper failureMapper;

    private final QualityWorkOrderMapper workOrderMapper;

    private final QualityWorkOrderGuard guard;

    public QualityExceptionExpiryFailureService(QualityExceptionExpiryFailureMapper failureMapper,
            QualityWorkOrderMapper workOrderMapper, QualityWorkOrderGuard guard) {
        this.failureMapper = failureMapper;
        this.workOrderMapper = workOrderMapper;
        this.guard = guard;
    }

    /**
     * 分页查询失败账本，并补充工单名称。
     */
    public PageResult<QualityExpiryFailureRespVO> getPage(QualityExpiryFailurePageReqVO reqVO) {
        PageResult<QualityExceptionExpiryFailureDO> page = failureMapper.selectPage(reqVO);
        Set<Long> orderIds = page.getList().stream().map(QualityExceptionExpiryFailureDO::getWorkOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = orderIds.isEmpty() ? Map.of() : workOrderMapper.selectByIds(orderIds).stream()
                .collect(Collectors.toMap(QualityWorkOrderDO::getId,
                        order -> StrUtil.nullToEmpty(order.getOrderName()), (a, b) -> a));
        return new PageResult<>(page.getList().stream().map(row -> {
            QualityExpiryFailureRespVO vo = BeanUtils.toBean(row, QualityExpiryFailureRespVO.class);
            vo.setMaxAttempts(QualityExceptionExpiryFailureDO.MAX_ATTEMPTS);
            vo.setWorkOrderName(names.get(row.getWorkOrderId()));
            return vo;
        }).toList(), page.getTotal());
    }

    /**
     * 人工排查后授权重试：要求操作者对原工单具备处理权限，并按读取版本 CAS。
     */
    public QualityExpiryFailureRespVO authorizeRetry(QualityExpiryFailureRetryReqVO reqVO) {
        Long actor = guard.requireUserId();
        QualityExceptionExpiryFailureDO failure = failureMapper.selectById(reqVO.getId());
        if (failure == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        QualityWorkOrderDO order = workOrderMapper.selectById(failure.getWorkOrderId());
        if (order == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        guard.assertHandle(order, actor);
        if (failureMapper.authorizeRetry(failure.getId(), reqVO.getExpectedVersion(), reqVO.getReason().trim(),
                actor, LocalDateTime.now()) != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityExpiryFailureRespVO vo = BeanUtils.toBean(failureMapper.selectById(failure.getId()),
                QualityExpiryFailureRespVO.class);
        vo.setMaxAttempts(QualityExceptionExpiryFailureDO.MAX_ATTEMPTS);
        vo.setWorkOrderName(order.getOrderName());
        return vo;
    }

    /**
     * 生成可持久化的安全失败摘要：业务异常保留其提示，其余只记录异常类型，不落驱动或厂商原文。
     */
    static String safeSummary(Throwable failure) {
        ServiceException business = ExceptionUtils.getThrowableList(failure).stream()
                .filter(ServiceException.class::isInstance).map(ServiceException.class::cast)
                .findFirst().orElse(null);
        Throwable root = ExceptionUtils.getRootCause(failure);
        String detail = business != null
                ? "业务校验失败[" + business.getCode() + "]：" + StrUtil.brief(business.getMessage(), 200)
                : "执行异常：" + (root == null ? failure : root).getClass().getSimpleName();
        return StrUtil.brief(detail, 500);
    }
}
