package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentReplyReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceCommentDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.governance.api.quality.QualityWorkOrderApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderStatusRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Slf4j
@Service
@Validated
public class MarketCommentServiceImpl implements MarketCommentService {

    @Resource
    private MarketResourceCommentMapper commentMapper;

    @Resource
    private MarketDisplayHelper displayHelper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private QualityWorkOrderApi qualityWorkOrderApi;

    @Resource
    private MarketAuditRecorder auditRecorder;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 发表评价。
     */
    @Override
    public Long createComment(MarketCommentSaveReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(MARKET_COMMENT_FORBIDDEN);
        }
        int kind = reqVO.getCommentKind() == null
                ? MarketResourceCommentDO.KIND_NORMAL : reqVO.getCommentKind();
        if (kind == MarketResourceCommentDO.KIND_NORMAL) {
            validateLevel(reqVO.getLevel());
        }
        // 用户隔离的固定长度内部键同时用于评论与治理工单，不把客户端键当作全局身份。
        String requestKey = kind == MarketResourceCommentDO.KIND_CHECK
                && StrUtil.isNotBlank(reqVO.getIdempotentKey())
                ? DigestUtil.sha256Hex("market-check/v1:" + userId + ":" + reqVO.getIdempotentKey()) : null;
        CommentSubmission submitted = new TransactionTemplate(transactionManager).execute(status -> {
            if (requestKey != null) {
                commentMapper.lockCommentRequest(requestKey);
                MarketResourceCommentDO existing = commentMapper.selectByIdempotentKey(requestKey);
                if (existing != null) {
                    assertSameRequest(existing, reqVO, userId);
                    return new CommentSubmission(existing, false);
                }
            }
            MarketResourceCommentDO comment = BeanUtils.toBean(reqVO, MarketResourceCommentDO.class);
            comment.setCreator(String.valueOf(userId));
            comment.setIdempotentKey(requestKey);
            comment.setCommentKind(kind);
            if (comment.getLevel() == null) {
                comment.setLevel(kind == MarketResourceCommentDO.KIND_CHECK ? 3 : 5);
            }
            if (kind == MarketResourceCommentDO.KIND_CHECK && comment.getFoundTime() == null) {
                comment.setFoundTime(LocalDateTime.now());
            }
            if (commentMapper.insert(comment) != 1 || comment.getId() == null || comment.getId() <= 0) {
                throw exception(MARKET_OPERATION_NOT_PERSISTED);
            }
            auditRecorder.record(kind == MarketResourceCommentDO.KIND_CHECK ? "CHECK" : "COMMENT",
                    "COMMENT", comment.getId(), null, kind, reqVO.getCommentStr(), "OK");
            return new CommentSubmission(comment, true);
        });
        if (submitted == null) {
            throw exception(MARKET_OPERATION_NOT_PERSISTED);
        }
        MarketResourceCommentDO comment = submitted.comment();
        // 治理 RPC 只在短事务提交和锁释放之后执行，重试不再次创建评论或工单。
        if (kind == MarketResourceCommentDO.KIND_CHECK && submitted.created()) {
            openWorkOrder(comment, reqVO);
        } else if (kind == MarketResourceCommentDO.KIND_CHECK) {
            refreshWorkOrder(comment);
        }
        return comment.getId();
    }

    private record CommentSubmission(MarketResourceCommentDO comment, boolean created) {
    }

    /** 相同操作编号只能重放同一用户、同一资产与同一业务内容。 */
    private void assertSameRequest(MarketResourceCommentDO existing, MarketCommentSaveReqVO reqVO, Long userId) {
        if (!Objects.equals(existing.getCreator(), String.valueOf(userId))
                || !Objects.equals(existing.getCommentKind(), MarketResourceCommentDO.KIND_CHECK)
                || !Objects.equals(existing.getAssetId(), reqVO.getAssetId())
                || !Objects.equals(existing.getApplyId(), reqVO.getApplyId())
                || !Objects.equals(existing.getCommentStr(), reqVO.getCommentStr())
                || !Objects.equals(existing.getProblemType(), reqVO.getProblemType())
                || !Objects.equals(existing.getImpactScope(), reqVO.getImpactScope())
                || !Objects.equals(existing.getLevel(), reqVO.getLevel() == null ? 3 : reqVO.getLevel())
                || (reqVO.getFoundTime() != null && !Objects.equals(existing.getFoundTime(), reqVO.getFoundTime()))) {
            throw exception(MARKET_COMMENT_REQUEST_CONFLICT);
        }
    }

    /**
     * 修改自己的评价。
     */
    @Override
    public void updateComment(MarketCommentSaveReqVO reqVO) {
        MarketResourceCommentDO comment = validateOwner(reqVO.getId());
        if (!Objects.equals(comment.getCommentKind(), MarketResourceCommentDO.KIND_CHECK)) {
            validateLevel(reqVO.getLevel());
        }
        comment.setCommentStr(reqVO.getCommentStr());
        comment.setLevel(reqVO.getLevel());
        comment.setProblemType(reqVO.getProblemType());
        comment.setImpactScope(reqVO.getImpactScope());
        commentMapper.updateById(comment);
    }

    /**
     * 删除自己的评价。
     */
    @Override
    public void deleteComment(Long id) {
        validateOwner(id);
        commentMapper.deleteById(id);
    }

    /**
     * 资产方回复评价。
     */
    @Override
    public void replyComment(MarketCommentReplyReqVO reqVO) {
        MarketResourceCommentDO comment = commentMapper.selectById(reqVO.getId());
        if (comment == null) {
            throw exception(MARKET_COMMENT_NOT_EXISTS);
        }
        comment.setReplyStr(reqVO.getReplyStr());
        comment.setReplyUserId(SecurityFrameworkUtils.getLoginUserId());
        comment.setReplyTime(LocalDateTime.now());
        commentMapper.updateById(comment);
        refreshWorkOrder(comment);
    }

    /**
     * 评价分页。
     */
    @Override
    public PageResult<MarketCommentRespVO> getCommentPage(MarketCommentPageReqVO reqVO) {
        PageResult<MarketResourceCommentDO> page = commentMapper.selectPage(reqVO);
        page.getList().forEach(this::refreshWorkOrderQuietly);
        Map<String, String> names = displayHelper.userNames(
                page.getList().stream()
                        .flatMap(item -> java.util.stream.Stream.of(
                                parseUserId(item.getCreator()), item.getReplyUserId()))
                        .filter(Objects::nonNull)
                        .toList()
        ).entrySet().stream().collect(java.util.stream.Collectors.toMap(
                e -> String.valueOf(e.getKey()), Map.Entry::getValue, (a, b) -> a));
        return new PageResult<>(page.getList().stream().map(item -> {
            MarketCommentRespVO vo = BeanUtils.toBean(item, MarketCommentRespVO.class);
            vo.setCreatorName(names.get(item.getCreator()));
            vo.setReplyUserName(
                    item.getReplyUserId() == null ? null : names.get(String.valueOf(item.getReplyUserId())));
            return vo;
        }).toList(), page.getTotal());
    }

    /**
     * 为资产评论关联或创建后续处理工单。
     */
    private void openWorkOrder(MarketResourceCommentDO comment, MarketCommentSaveReqVO reqVO) {
        try {
            DataAssetDO asset = comment.getAssetId() == null ? null : assetMapper.selectById(comment.getAssetId());
            QualityWorkOrderCreateReqDTO dto = new QualityWorkOrderCreateReqDTO();
            dto.setOrderName("集市校核：" + (asset == null ? comment.getAssetId() : asset.getAssetName()));
            dto.setDescription(comment.getCommentStr());
            dto.setProblemType(StrUtil.blankToDefault(comment.getProblemType(), "MARKET_CHECK"));
            dto.setSourceType("MARKET_CHECK");
            dto.setTopicRef("asset:" + comment.getAssetId());
            dto.setIdempotentKey(StrUtil.blankToDefault(comment.getIdempotentKey(),
                    "market-comment:" + comment.getId()));
            dto.setRequestDeptId(SecurityFrameworkUtils.getLoginUserDeptId());
            dto.setProviderDeptId(asset == null ? null : asset.getOwnerDeptId());
            QualityWorkOrderStatusRespDTO created = qualityWorkOrderApi.createFromMarket(dto).getCheckedData();
            if (created != null) {
                comment.setWorkOrderId(created.getId());
                comment.setWorkOrderStatus(created.getOrderStatus());
                if (StrUtil.isBlank(comment.getIdempotentKey())) {
                    comment.setIdempotentKey(dto.getIdempotentKey());
                }
                commentMapper.updateById(comment);
            }
        } catch (RuntimeException ex) {
            log.warn("[openWorkOrder] 治理工单创建失败 commentId={} msg={}", comment.getId(), ex.getMessage());
            comment.setWorkOrderStatus("CREATE_FAILED");
            commentMapper.updateById(comment);
        }
    }

    /**
     * 刷新工作工单静默。
     */
    private void refreshWorkOrderQuietly(MarketResourceCommentDO comment) {
        try {
            refreshWorkOrder(comment);
        } catch (RuntimeException ignored) {
            // 列表投影失败不阻断页面
        }
    }

    /**
     * 刷新工作工单。
     */
    private void refreshWorkOrder(MarketResourceCommentDO comment) {
        if (comment == null || comment.getWorkOrderId() == null) {
            return;
        }
        try {
            QualityWorkOrderStatusRespDTO status = qualityWorkOrderApi.getStatus(comment.getWorkOrderId())
                    .getCheckedData();
            if (status != null && !Objects.equals(status.getOrderStatus(), comment.getWorkOrderStatus())) {
                comment.setWorkOrderStatus(status.getOrderStatus());
                commentMapper.updateById(comment);
            }
        } catch (RuntimeException ex) {
            log.warn("[refreshWorkOrder] 工单状态不可用 commentId={} msg={}", comment.getId(), ex.getMessage());
        }
    }

    /**
     * 校验当前用户具有市场注释对象的所属人权限。
     */
    private MarketResourceCommentDO validateOwner(Long id) {
        MarketResourceCommentDO comment = commentMapper.selectById(id);
        if (comment == null) {
            throw exception(MARKET_COMMENT_NOT_EXISTS);
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || !Objects.equals(comment.getCreator(), String.valueOf(userId))) {
            throw exception(MARKET_COMMENT_FORBIDDEN);
        }
        return comment;
    }

    /**
     * 校验市场注释的等级，不满足约束时拒绝当前操作。
     */
    private void validateLevel(Integer level) {
        if (level == null || level < 1 || level > 5) {
            throw exception(MARKET_COMMENT_LEVEL_INVALID);
        }
    }

    /**
     * 解析市场注释的用户编号。
     */
    private Long parseUserId(String text) {
        if (text == null || !text.matches("\\d+")) {
            return null;
        }
        return Long.valueOf(text);
    }

}
