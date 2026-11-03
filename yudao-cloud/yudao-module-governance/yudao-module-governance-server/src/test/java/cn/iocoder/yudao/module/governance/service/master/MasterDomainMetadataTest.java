package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDomainDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.time.LocalDateTime;
import java.util.*;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDomainMetadataTest {
    private MasterDomainMapper domains;
    private MasterModelMapper models;
    private AdminUserApi users;
    private MasterDomainServiceImpl service;

    static class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "master-domain-metadata"), MasterDomainDO.class);
        domains = mock(MasterDomainMapper.class); models = mock(MasterModelMapper.class); users = mock(AdminUserApi.class);
        service = new MasterDomainServiceImpl();
        ReflectionTestUtils.setField(service, "domainMapper", domains); ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "adminUserApi", users); ReflectionTestUtils.setField(service, "transactionManager", new Transactions());
        when(users.validateUser(77L)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return user(77L, "leader", "00000000000");
        });
    }

    @Test void createCopiesDomainMetadataAndUsesProviderPhone() {
        MasterDomainSaveReqVO req = request();
        req.setCode("001-D"); req.setLeaderPhone("client-phone"); req.setDescription("description"); req.setRemark("remark");
        when(domains.insert(any(MasterDomainDO.class))).thenAnswer(call -> { call.<MasterDomainDO>getArgument(0).setId(10L); return 1; });
        assertEquals(10L, service.createDomain(req));
        ArgumentCaptor<MasterDomainDO> inserted = ArgumentCaptor.forClass(MasterDomainDO.class); verify(domains).insert(inserted.capture());
        MasterDomainDO actual = inserted.getValue();
        assertEquals("001-D", actual.getCode()); assertEquals(77L, actual.getLeaderUserId()); assertEquals("00000000000", actual.getLeaderPhone());
        assertEquals("description", actual.getDescription()); assertEquals("remark", actual.getRemark());
        assertEquals(99, actual.getSort()); assertEquals(0, actual.getStatus());
    }

    @Test void updateValidatesUserBeforeTransactionAndPreservesHiddenLegacyFields() {
        MasterDomainDO old = MasterDomainDO.builder().id(10L).code("PERSON").name("person").sort(6).icon("legacy-icon").status(1).build();
        when(domains.selectForUpdate(10L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return old;
        });
        MasterDomainSaveReqVO req = request(); req.setId(10L);
        service.updateDomain(req);
        var order = inOrder(users, domains); order.verify(users).validateUser(77L); order.verify(domains).selectForUpdate(10L);
        ArgumentCaptor<MasterDomainDO> updated = ArgumentCaptor.forClass(MasterDomainDO.class); verify(domains).updateById(updated.capture());
        assertEquals(6, updated.getValue().getSort()); assertEquals("legacy-icon", updated.getValue().getIcon()); assertEquals(1, updated.getValue().getStatus());
        assertEquals("00000000000", updated.getValue().getLeaderPhone());
        verify(domains).update(isNull(), any(Wrapper.class));
    }

    @Test void mandatoryAndInvalidLeaderPreventWrites() {
        MasterDomainSaveReqVO req = request(); req.setLeaderUserId(null);
        assertEquals(MASTER_DOMAIN_LEADER_REQUIRED.getCode(), assertThrows(ServiceException.class, () -> service.createDomain(req)).getCode());
        req.setLeaderUserId(88L); when(users.validateUser(88L)).thenReturn(null);
        assertEquals(MASTER_DOMAIN_LEADER_INVALID.getCode(), assertThrows(ServiceException.class, () -> service.createDomain(req)).getCode());
        verify(domains, never()).insert(any(MasterDomainDO.class));
    }

    @Test void pageBatchesUserNamesAndModelCountsWhileKeepingLegacyAuditIds() {
        MasterDomainDO first = MasterDomainDO.builder().id(10L).leaderUserId(77L).leaderPhone("old-phone")
                .description("description").remark("remark").build();
        first.setCreator("9007199254740993"); first.setUpdater("migration:0150");
        MasterDomainDO second = MasterDomainDO.builder().id(20L).build(); second.setCreator("99");
        MasterDomainPageReqVO req = new MasterDomainPageReqVO();
        when(domains.selectPage(req)).thenReturn(new PageResult<>(List.of(first, second), 2L));
        when(models.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of("domain_id", 10L, "model_count", 3L)));
        when(users.getUserMap(anyCollection())).thenReturn(Map.of(77L, user(77L, "leader", "00000000000"),
                9007199254740993L, user(9007199254740993L, "creator", null), 99L, user(99L, "other", null)));
        var result = service.getDomainPage(req).getList();
        assertEquals("leader", result.getFirst().getLeaderUserName()); assertEquals("00000000000", result.getFirst().getLeaderPhone());
        assertEquals("description", result.getFirst().getDescription()); assertEquals("remark", result.getFirst().getRemark());
        assertEquals("creator", result.getFirst().getCreatorName()); assertEquals("migration:0150", result.getFirst().getUpdater());
        assertEquals("other", result.get(1).getCreatorName()); assertEquals(3L, result.getFirst().getModelCount()); assertEquals(0L, result.get(1).getModelCount());
        verify(users, times(1)).getUserMap(anyCollection()); verify(models, never()).selectCountByDomainId(anyLong());
    }

    @Test void unavailableUserEnrichmentLeavesListAndIdsReadable() {
        MasterDomainDO row = MasterDomainDO.builder().id(10L).leaderUserId(77L).leaderPhone("stored-phone").build(); row.setCreator("99");
        when(domains.selectSimpleList()).thenReturn(List.of(row)); when(users.getUserMap(anyCollection())).thenThrow(new IllegalStateException("fixture unavailable"));
        MasterDomainRespVO actual = service.getSimpleList().getFirst();
        assertEquals(77L, actual.getLeaderUserId()); assertEquals("99", actual.getCreator()); assertEquals("stored-phone", actual.getLeaderPhone());
        assertNull(actual.getLeaderUserName());
    }

    @Test void validationMatchesDataDomainLengthsAndAuditWireKeepsLargeIdsAndMillis() throws Exception {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator(); MasterDomainSaveReqVO req = request(); req.setCode("001-D");
            req.setDescription("x".repeat(500)); req.setRemark("x".repeat(500));
            assertTrue(validator.validate(req).isEmpty()); req.setRemark("x".repeat(501));
            assertTrue(validator.validate(req).stream().anyMatch(error -> error.getPropertyPath().toString().equals("remark")));
        }
        var module = new tools.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class, cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        module.addSerializer(LocalDateTime.class, cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer.INSTANCE);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().addModule(module).build();
        MasterDomainRespVO resp = new MasterDomainRespVO(); resp.setLeaderUserId(9007199254740993L); resp.setCreator("9007199254740993");
        resp.setCreateTime(LocalDateTime.of(2026, 10, 12, 12, 0)); resp.setUpdateTime(LocalDateTime.of(2026, 10, 12, 13, 0));
        resp.setDescription("description"); resp.setRemark("remark");
        var wire = mapper.readTree(mapper.writeValueAsString(resp));
        assertEquals("9007199254740993", wire.get("leaderUserId").asString());
        assertEquals("description", wire.get("description").asString()); assertEquals("remark", wire.get("remark").asString());
        assertTrue(wire.get("createTime").isIntegralNumber()); assertTrue(wire.get("updateTime").isIntegralNumber());
        assertEquals(Set.of("id", "code", "name", "description", "remark", "leaderUserId", "leaderUserName", "leaderPhone",
                "icon", "sort", "status", "modelCount", "creator", "creatorName", "createTime", "updater", "updaterName", "updateTime"),
                mapper.readValue(mapper.writeValueAsString(resp), Map.class).keySet());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class TransactionConfig { }

    @Test void minimalContextWiresUserApiAndTransactionManagerAndProxiesReferenceLock() {
        when(domains.selectForUpdate(10L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return MasterDomainDO.builder().id(10L).build();
        });
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("domainMapper", domains);
            context.getBeanFactory().registerSingleton("modelMapper", models);
            context.getBeanFactory().registerSingleton("adminUserApi", users);
            context.getBeanFactory().registerSingleton("transactionManager", new Transactions());
            context.register(TransactionConfig.class, MasterDomainServiceImpl.class); context.refresh();
            var bean = context.getBean(MasterDomainService.class);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(bean));
            bean.deleteDomain(10L);
            verify(domains).deleteById(10L);
        }
    }

    private static MasterDomainSaveReqVO request() { var req = new MasterDomainSaveReqVO(); req.setName("person"); req.setCode("PERSON"); req.setLeaderUserId(77L); return req; }
    private static AdminUserRespDTO user(Long id, String name, String phone) { var user = new AdminUserRespDTO(); user.setId(id); user.setNickname(name); user.setMobile(phone); return user; }
}
