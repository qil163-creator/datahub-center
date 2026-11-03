package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceCommentDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.governance.api.quality.QualityWorkOrderApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderStatusRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketCommentServiceImplTest {

    private MockedStatic<SecurityFrameworkUtils> security;
    private Long userId = 7L;

    @BeforeEach
    void login() {
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenAnswer(call -> userId);
    }

    @AfterEach
    void clearLogin() { security.close(); }

    @Test
    void structuredCheckOpensGovernanceWorkOrder() {
        MarketCommentServiceImpl service = new MarketCommentServiceImpl();
        MarketResourceCommentMapper commentMapper = mock(MarketResourceCommentMapper.class);
        QualityWorkOrderApi workOrderApi = mock(QualityWorkOrderApi.class);
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "displayHelper", new MarketDisplayHelper());
        ReflectionTestUtils.setField(service, "assetMapper", mock(DataAssetMapper.class));
        ReflectionTestUtils.setField(service, "qualityWorkOrderApi", workOrderApi);
        MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
        ReflectionTestUtils.setField(service, "auditRecorder", audit);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        when(commentMapper.insert(any(MarketResourceCommentDO.class))).thenAnswer(call -> {
            ((MarketResourceCommentDO) call.getArgument(0)).setId(31L);
            return 1;
        });
        QualityWorkOrderStatusRespDTO status = new QualityWorkOrderStatusRespDTO();
        status.setId(77L);
        status.setOrderStatus("OPEN");
        when(workOrderApi.createFromMarket(any())).thenReturn(CommonResult.success(status));
        MarketCommentSaveReqVO req = new MarketCommentSaveReqVO();
        req.setAssetId(8L);
        req.setCommentStr("区县办件数口径不符");
        req.setCommentKind(MarketResourceCommentDO.KIND_CHECK);
        req.setProblemType("口径");
        assertEquals(31L, service.createComment(req));
        // 评价与审计先随短事务提交，跨服务工单只在提交之后发起。
        InOrder order = inOrder(commentMapper, audit, transactions, workOrderApi);
        order.verify(commentMapper).insert(any(MarketResourceCommentDO.class));
        order.verify(audit).record("CHECK", "COMMENT", 31L, null, MarketResourceCommentDO.KIND_CHECK,
                "区县办件数口径不符", "OK");
        order.verify(transactions).commit(any());
        order.verify(workOrderApi).createFromMarket(any());
    }

    @Test
    void idempotentCheckReusesExistingComment() {
        MarketCommentServiceImpl service = new MarketCommentServiceImpl();
        MarketResourceCommentMapper commentMapper = mock(MarketResourceCommentMapper.class);
        QualityWorkOrderApi workOrderApi = mock(QualityWorkOrderApi.class);
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "displayHelper", new MarketDisplayHelper());
        ReflectionTestUtils.setField(service, "assetMapper", mock(DataAssetMapper.class));
        ReflectionTestUtils.setField(service, "qualityWorkOrderApi", workOrderApi);
        ReflectionTestUtils.setField(service, "auditRecorder", mock(MarketAuditRecorder.class));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        MarketResourceCommentDO existing = new MarketResourceCommentDO();
        existing.setId(5L);
        existing.setCreator("7");
        existing.setAssetId(8L);
        existing.setCommentStr("重复");
        existing.setCommentKind(MarketResourceCommentDO.KIND_CHECK);
        existing.setLevel(3);
        String requestKey = DigestUtil.sha256Hex("market-check/v1:7:k1");
        when(commentMapper.selectByIdempotentKey(requestKey)).thenReturn(existing);
        MarketCommentSaveReqVO req = new MarketCommentSaveReqVO();
        req.setAssetId(8L);
        req.setCommentStr("重复");
        req.setCommentKind(MarketResourceCommentDO.KIND_CHECK);
        req.setIdempotentKey("k1");
        assertEquals(5L, service.createComment(req));
        InOrder order = inOrder(commentMapper, transactions);
        order.verify(commentMapper).lockCommentRequest(requestKey);
        order.verify(commentMapper).selectByIdempotentKey(requestKey);
        order.verify(transactions).commit(any());
        verify(commentMapper, never()).insert(any(MarketResourceCommentDO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"creator", "kind", "asset", "apply", "content", "problem", "impact", "level", "found"})
    void aReusedOperationCannotReturnADifferentRequest(String changed) {
        MarketCommentServiceImpl service = new MarketCommentServiceImpl();
        MarketResourceCommentMapper mapper = mock(MarketResourceCommentMapper.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "commentMapper", mapper);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        MarketResourceCommentDO existing = MarketResourceCommentDO.builder().id(5L).assetId(8L)
                .commentStr("重复").commentKind(MarketResourceCommentDO.KIND_CHECK).level(3).build();
        existing.setCreator("7");
        MarketCommentSaveReqVO req = new MarketCommentSaveReqVO();
        req.setAssetId(8L);
        req.setCommentStr("重复");
        req.setCommentKind(MarketResourceCommentDO.KIND_CHECK);
        req.setIdempotentKey("k1");
        switch (changed) {
            case "creator" -> existing.setCreator("9");
            case "kind" -> existing.setCommentKind(MarketResourceCommentDO.KIND_NORMAL);
            case "asset" -> req.setAssetId(9L);
            case "apply" -> req.setApplyId(2L);
            case "content" -> req.setCommentStr("其他问题");
            case "problem" -> req.setProblemType("口径");
            case "impact" -> req.setImpactScope("全省");
            case "level" -> req.setLevel(4);
            case "found" -> req.setFoundTime(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
            default -> throw new AssertionError(changed);
        }
        when(mapper.selectByIdempotentKey(any())).thenReturn(existing);
        ServiceException error = assertThrows(ServiceException.class, () -> service.createComment(req));
        assertEquals(cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_COMMENT_REQUEST_CONFLICT.getCode(), error.getCode());
        verify(mapper, never()).insert(any(MarketResourceCommentDO.class));
        verify(transactions).rollback(any());
    }

    @Test
    void unauthenticatedSubmissionCannotReachPersistence() {
        userId = null;
        MarketCommentServiceImpl service = new MarketCommentServiceImpl();
        assertThrows(ServiceException.class, () -> service.createComment(new MarketCommentSaveReqVO()));
    }

}
