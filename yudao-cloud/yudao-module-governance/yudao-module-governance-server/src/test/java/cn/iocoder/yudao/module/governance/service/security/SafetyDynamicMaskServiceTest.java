package cn.iocoder.yudao.module.governance.service.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyMaskExemptRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyMaskSceneDO;
import cn.iocoder.yudao.module.governance.enums.security.SafetyMaskSceneEnum;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyMaskSceneMapper;
import cn.iocoder.yudao.module.governance.service.accesslog.SafetyAccessLogService;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskEngine;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskingSpec;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import cn.iocoder.yudao.module.governance.service.security.SafetyColumnClassificationResolver.ColumnClass;
import cn.iocoder.yudao.module.governance.service.security.SafetyColumnClassificationResolver.ColumnKey;
import cn.iocoder.yudao.module.governance.service.security.SafetyColumnClassificationResolver.Resolution;
import cn.iocoder.yudao.module.governance.service.security.SafetyColumnClassificationResolver.TableKey;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyFieldMaskService;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyWhitelistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafetyDynamicMaskServiceTest extends BaseMockitoUnitTest {

    private static final long DS = 7L;
    private static final long PHONE_CATEGORY = 11L;
    private static final long PHONE_RULE = 21L;
    private static final ColumnKey PHONE = ColumnKey.of(DS, "ods", "users", "phone");
    private static final ColumnKey NAME = ColumnKey.of(DS, "ods", "users", "name");
    private static final ColumnKey ID_CARD = ColumnKey.of(DS, "ods", "users", "id_card");
    private static final TableKey USERS = TableKey.of(DS, "ods", "users");

    @InjectMocks
    private SafetyDynamicMaskService service;
    @Mock
    private SafetyMaskSceneMapper sceneMapper;
    @Mock
    private SafetyColumnClassificationResolver resolver;
    @Mock
    private SafetyWhitelistService whitelistService;
    @Mock
    private SafetyCategoryMapper categoryMapper;
    @Mock
    private SafetyAccessLogService accessLogService;
    @Mock
    private SafetyRuleService ruleService;

    @BeforeEach
    void setUp() {
        // 真实策略服务与脱敏引擎；只 mock 它读取的白名单、分类与规则事实。
        SafetyFieldMaskService fieldMaskService = new SafetyFieldMaskService();
        ReflectionTestUtils.setField(fieldMaskService, "whitelists", whitelistService);
        ReflectionTestUtils.setField(fieldMaskService, "categories", categoryMapper);
        ReflectionTestUtils.setField(fieldMaskService, "rules", ruleService);
        ReflectionTestUtils.setField(service, "fieldMaskService", fieldMaskService);

        SafetyCategoryDO phone = new SafetyCategoryDO();
        phone.setId(PHONE_CATEGORY);
        phone.setName("手机号");
        phone.setRuleId(PHONE_RULE);
        lenient().when(categoryMapper.selectByIds(anyCollection())).thenReturn(List.of(phone));
        lenient().when(ruleService.freezeUsableMaskingSpecs(anyCollection())).thenReturn(Map.of(PHONE_RULE,
                SafetyMaskingSpec.builder().ruleId(PHONE_RULE).methodCode("NULLIFY")
                        .config(Map.of("applicationScene", List.of("1"))).replaceItems(List.of()).build()));
        lenient().when(whitelistService.getMaskExemptStrict(eq(1L), anyString())).thenReturn(exempt(false));
        lenient().when(whitelistService.getCategoryExemptionsStrict(eq(1L), anyString(), anySet())).thenReturn(Set.of());
        lenient().when(resolver.resolve(anyCollection(), anyCollection())).thenReturn(new Resolution(Map.of(
                PHONE, new ColumnClass(Set.of(PHONE_CATEGORY), true),
                NAME, new ColumnClass(Set.of(), false),
                ID_CARD, new ColumnClass(Set.of(), true)), Set.of(USERS)));
    }

    @Test
    void classifiedColumnIsMaskedByItsRuleAndOthersAreUntouched() {
        var resp = service.maskRows(request(1L, column("phone", PHONE), column("name", NAME)));
        assertTrue(resp.getEnabled());
        assertNull(resp.getRows().getFirst().get("phone"));
        assertEquals("张三", resp.getRows().getFirst().get("name"));
        assertEquals(1, resp.getMaskedColumns().size());
        assertEquals(SafetyDynamicMaskRespDTO.REASON_RULE, resp.getMaskedColumns().getFirst().getReason());
        assertEquals(List.of("手机号"), resp.getMaskedColumns().getFirst().getCategoryNames());
        assertNotNull(resp.getPolicyFingerprint());
        ArgumentCaptor<SafetyAccessLogCreateReqDTO> audit = ArgumentCaptor.forClass(SafetyAccessLogCreateReqDTO.class);
        verify(accessLogService).recordAccessLog(audit.capture());
        assertEquals("phone", audit.getValue().getSensitiveColumns());
        assertFalse(audit.getValue().toString().contains("13800000000"), "审计不得包含原值");
    }

    @Test
    void categoryExemptionRevealsTheValue() {
        when(whitelistService.getCategoryExemptionsStrict(eq(1L), anyString(), anySet())).thenReturn(Set.of(PHONE_CATEGORY));
        var resp = service.maskRows(request(1L, column("phone", PHONE)));
        assertEquals("13800000000", resp.getRows().getFirst().get("phone"));
        assertTrue(resp.getMaskedColumns().isEmpty());
        // 豁免看到原值也必须留痕：明文访问审计只记列名。
        ArgumentCaptor<SafetyAccessLogCreateReqDTO> audit = ArgumentCaptor.forClass(SafetyAccessLogCreateReqDTO.class);
        verify(accessLogService).recordAccessLog(audit.capture());
        assertEquals(0, audit.getValue().getMaskedFlag());
        assertEquals("phone", audit.getValue().getSensitiveColumns());
        assertFalse(audit.getValue().toString().contains("13800000000"), "审计不得包含原值");
    }

    @Test
    void nonSensitiveResultIsNotAudited() {
        service.maskRows(request(1L, column("name", NAME)));
        verify(accessLogService, never()).recordAccessLog(any());
    }

    @Test
    void configuredWhitelistSceneDrivesTheExemptionLookup() {
        SafetyMaskSceneDO configured = new SafetyMaskSceneDO();
        configured.setSceneCode("DATA_QUERY");
        configured.setEnabled(true);
        configured.setWhitelistScene("SHARE");
        when(sceneMapper.selectByScene("DATA_QUERY")).thenReturn(configured);
        when(whitelistService.getMaskExemptStrict(eq(1L), eq("SHARE"))).thenReturn(exempt(false));
        when(whitelistService.getCategoryExemptionsStrict(eq(1L), eq("SHARE"), anySet())).thenReturn(Set.of());

        service.maskRows(request(1L, column("phone", PHONE)));

        verify(whitelistService, never()).getMaskExemptStrict(eq(1L), eq("QUERY"));
        verify(whitelistService, atLeastOnce()).getMaskExemptStrict(eq(1L), eq("SHARE"));
    }

    @Test
    void invalidConfiguredWhitelistSceneFallsBackToTheDefault() {
        SafetyMaskSceneDO configured = new SafetyMaskSceneDO();
        configured.setWhitelistScene("ALL");
        when(sceneMapper.selectByScene("DATA_QUERY")).thenReturn(configured);
        assertEquals("QUERY", service.whitelistScene(SafetyMaskSceneEnum.DATA_QUERY));
    }

    @Test
    void unclassifiedSensitiveColumnFailsClosed() {
        var resp = service.maskRows(request(1L, column("id_card", ID_CARD)));
        assertEquals(SafetyMaskEngine.unavailableMask("110101"), resp.getRows().getFirst().get("id_card"));
        assertEquals(SafetyDynamicMaskRespDTO.REASON_UNCLASSIFIED, resp.getMaskedColumns().getFirst().getReason());
    }

    @Test
    void untraceableColumnFailsClosedOnlyWhenItsTableIsSensitive() {
        var risky = untraceable("expr", TableKey.of(DS, "ods", "users"));
        var resp = service.maskRows(request(1L, risky));
        assertEquals(SafetyMaskEngine.unavailableMask("x"), resp.getRows().getFirst().get("expr"));
        assertEquals(SafetyDynamicMaskRespDTO.REASON_UNTRACEABLE, resp.getMaskedColumns().getFirst().getReason());

        var safe = untraceable("expr", TableKey.of(DS, "ods", "public_dict"));
        var safeResp = service.maskRows(request(1L, safe));
        assertEquals("x", safeResp.getRows().getFirst().get("expr"));
    }

    @Test
    void untraceableColumnWithoutAnyCandidateTableFailsClosed() {
        SafetyDynamicMaskReqDTO.Column column = new SafetyDynamicMaskReqDTO.Column();
        column.setOutputName("expr");
        column.setTraceable(false);
        var resp = service.maskRows(request(1L, column));
        assertEquals(SafetyMaskEngine.unavailableMask("x"), resp.getRows().getFirst().get("expr"));
    }

    @Test
    void unverifiableExemptionFailsClosedInsteadOfLeaking() {
        when(whitelistService.getMaskExemptStrict(eq(2L), anyString())).thenThrow(new ServiceException(1, "无法核验"));
        var resp = service.maskRows(request(2L, column("phone", PHONE)));
        assertEquals(SafetyMaskEngine.unavailableMask("13800000000"), resp.getRows().getFirst().get("phone"));
        assertEquals(SafetyDynamicMaskRespDTO.REASON_UNVERIFIED, resp.getMaskedColumns().getFirst().getReason());
        assertNull(resp.getPolicyFingerprint());
    }

    @Test
    void systemCallsWithoutUserNeverGetExemptions() {
        var resp = service.maskRows(request(null, column("phone", PHONE), column("id_card", ID_CARD)));
        assertFalse(resp.getRows().getFirst().containsValue("13800000000"));
        assertFalse(resp.getRows().getFirst().containsValue("110101"));
    }

    @Test
    void globalExemptionSkipsFailClosedMasking() {
        when(whitelistService.getMaskExemptStrict(eq(1L), anyString())).thenReturn(exempt(true));
        var resp = service.maskRows(request(1L, column("id_card", ID_CARD)));
        assertEquals("110101", resp.getRows().getFirst().get("id_card"));
        assertTrue(resp.getMaskedColumns().isEmpty());
        ArgumentCaptor<SafetyAccessLogCreateReqDTO> audit = ArgumentCaptor.forClass(SafetyAccessLogCreateReqDTO.class);
        verify(accessLogService).recordAccessLog(audit.capture());
        assertEquals(0, audit.getValue().getMaskedFlag());
        assertEquals("id_card", audit.getValue().getSensitiveColumns());
    }

    @Test
    void disabledSceneReturnsRowsUnchanged() {
        SafetyMaskSceneDO off = new SafetyMaskSceneDO();
        off.setSceneCode("DATA_QUERY");
        off.setEnabled(false);
        when(sceneMapper.selectByScene("DATA_QUERY")).thenReturn(off);
        var resp = service.maskRows(request(1L, column("phone", PHONE)));
        assertFalse(resp.getEnabled());
        assertEquals("13800000000", resp.getRows().getFirst().get("phone"));
        verify(resolver, never()).resolve(anyCollection(), anyCollection());
    }

    @Test
    void duplicateOutputColumnsAreRejected() {
        assertThrows(ServiceException.class, () -> service.maskRows(request(1L, column("phone", PHONE),
                column("phone", PHONE))));
    }

    @Test
    void callerRowsAreNotMutated() {
        SafetyDynamicMaskReqDTO req = request(1L, column("phone", PHONE));
        service.maskRows(req);
        assertEquals("13800000000", req.getRows().getFirst().get("phone"));
    }

    @Test
    void storageMaskingIgnoresSwitchesAndExemptionsAndMasksEverySensitiveColumn() {
        SafetyDynamicMaskSceneOff.disableAll(sceneMapper);
        lenient().when(whitelistService.getMaskExemptStrict(eq(1L), anyString())).thenReturn(exempt(true));
        SafetyDynamicMaskReqDTO req = request(1L, column("phone", PHONE), column("name", NAME),
                column("id_card", ID_CARD), untraceable("expr", USERS));

        var resp = service.maskForStorage(req.getColumns(), req.getRows());

        var row = resp.getRows().getFirst();
        assertEquals(SafetyMaskEngine.unavailableMask("13800000000"), row.get("phone"));
        assertEquals(SafetyMaskEngine.unavailableMask("110101"), row.get("id_card"));
        assertEquals(SafetyMaskEngine.unavailableMask("x"), row.get("expr"));
        assertEquals("张三", row.get("name"));
        assertEquals(List.of("phone", "id_card", "expr"), resp.getMaskedColumns().stream()
                .map(SafetyDynamicMaskRespDTO.MaskedColumn::getOutputName).toList());
        assertEquals(List.of("手机号"), resp.getMaskedColumns().getFirst().getCategoryNames());
        assertEquals("13800000000", req.getRows().getFirst().get("phone"), "不得修改调用方的行");
        verify(whitelistService, never()).getMaskExemptStrict(any(), anyString());
        verify(whitelistService, never()).getCategoryExemptionsStrict(any(), anyString(), anySet());
        verify(accessLogService, never()).recordAccessLog(any());
    }

    /** 把所有场景置为关闭，验证持久化脱敏不看开关。 */
    private static final class SafetyDynamicMaskSceneOff {
        static void disableAll(SafetyMaskSceneMapper mapper) {
            SafetyMaskSceneDO off = new SafetyMaskSceneDO();
            off.setEnabled(false);
            lenient().when(mapper.selectByScene(anyString())).thenReturn(off);
        }
    }

    // ==================== 工具 ====================

    private static SafetyDynamicMaskReqDTO request(Long userId, SafetyDynamicMaskReqDTO.Column... columns) {
        SafetyDynamicMaskReqDTO req = new SafetyDynamicMaskReqDTO();
        req.setUserId(userId);
        req.setScene("DATA_QUERY");
        req.setResourceName("ods.users");
        req.setColumns(new ArrayList<>(List.of(columns)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phone", "13800000000");
        row.put("name", "张三");
        row.put("id_card", "110101");
        row.put("expr", "x");
        req.setRows(new ArrayList<>(List.of(row)));
        return req;
    }

    private static SafetyDynamicMaskReqDTO.Column column(String name, ColumnKey source) {
        SafetyDynamicMaskReqDTO.Column column = new SafetyDynamicMaskReqDTO.Column();
        column.setOutputName(name);
        column.setSources(List.of(SafetyDynamicMaskReqDTO.Source.of(source.datasourceId(), source.schemaName(),
                source.tableName(), source.columnName())));
        return column;
    }

    private static SafetyDynamicMaskReqDTO.Column untraceable(String name, TableKey table) {
        SafetyDynamicMaskReqDTO.Column column = new SafetyDynamicMaskReqDTO.Column();
        column.setOutputName(name);
        column.setTraceable(false);
        column.setCandidateTables(List.of(SafetyDynamicMaskReqDTO.Source.of(table.datasourceId(), table.schemaName(),
                table.tableName(), null)));
        return column;
    }

    private static SafetyMaskExemptRespDTO exempt(boolean value) {
        SafetyMaskExemptRespDTO exempt = new SafetyMaskExemptRespDTO();
        exempt.setExempt(value);
        return exempt;
    }
}
