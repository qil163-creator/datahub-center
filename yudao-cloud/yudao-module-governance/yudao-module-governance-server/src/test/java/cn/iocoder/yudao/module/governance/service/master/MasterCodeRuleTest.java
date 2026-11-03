package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MasterCodeRuleTest {
    private MasterCodeSegmentVO segment(String type, String value, String separator) {
        var segment = new MasterCodeSegmentVO(); segment.setSegmentType(type);
        segment.setSegmentValue(value); segment.setSepChar(separator); return segment;
    }

    @Test void segmentsRenderInConfiguredOrderWithoutAllocatingOrRounding() {
        var segments = List.of(segment("CONSTANT", "STU", "-"), segment("DATE", "yyyyMMdd", "-"),
                segment("ATTRIBUTE", "region", "-"), segment("SEQUENCE", "4", null));
        assertEquals("STU-20261012-CN-0012", MasterCodeRuleService.render(segments, 12,
                Map.of("region", "CN"), LocalDate.of(2026, 10, 12), false));
        assertEquals("STU-20261012-{region}-0012", MasterCodeRuleService.render(segments, 12,
                Map.of(), LocalDate.of(2026, 10, 12), true));
        assertThrows(ServiceException.class, () -> MasterCodeRuleService.render(segments, 12,
                Map.of(), LocalDate.of(2026, 10, 12), false));
        assertThrows(ServiceException.class, () -> MasterCodeRuleService.render(List.of(segment("SEQUENCE", "2", null)),
                100, Map.of(), LocalDate.now(), false));
    }

    @Test void generatedBatchPreservesCallerInputAndAdvancesDurableCounter() {
        var service = new MasterCodeRuleService(); var models = mock(MasterModelMapper.class);
        var rules = mock(MasterCodeRuleMapper.class); var counters = mock(MasterCodeCounterMapper.class);
        var model = MasterModelDO.builder().id(1L).build();
        when(models.selectOne(any(Wrapper.class))).thenReturn(model);
        var rule = new MasterCodeRuleDO(); rule.setFieldName("code"); rule.setModelId(1L);
        rule.setSegmentsJson(JsonUtils.toJsonString(List.of(segment("CONSTANT", "MD", "-"), segment("SEQUENCE", "4", null))));
        when(rules.selectEnabled(1L)).thenReturn(rule);
        var counter = new MasterCodeCounterDO(); counter.setId(10L); counter.setModelId(1L); counter.setNextValue(8L);
        when(counters.selectForUpdate(1L)).thenReturn(counter);
        ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "ruleMapper", rules);
        ReflectionTestUtils.setField(service, "counterMapper", counters);
        List<Map<String, Object>> input = List.of(Map.of("name", "甲"), Map.of("name", "乙"));
        var output = service.generateForCreate(1L, input);
        assertEquals(List.of("MD-0008", "MD-0009"), output.stream().map(row -> row.get("code")).toList());
        assertFalse(input.getFirst().containsKey("code"));
        assertEquals(10L, counter.getNextValue()); verify(counters).updateById(counter);
        assertThrows(ServiceException.class, () -> service.generateForCreate(1L, List.of(Map.of("code", "manual"))));
    }

    @Test void enablingRuleRechecksCurrentModelFields() {
        var service = new MasterCodeRuleService(); var models = mock(MasterModelMapper.class);
        var rules = mock(MasterCodeRuleMapper.class); var columns = mock(MasterColumnMapper.class);
        when(models.selectOne(any(Wrapper.class))).thenReturn(MasterModelDO.builder().id(1L).build());
        when(models.selectById(1L)).thenReturn(MasterModelDO.builder().id(1L).build());
        var rule = new MasterCodeRuleDO(); rule.setId(1L); rule.setModelId(1L); rule.setFieldName("removed");
        rule.setSegmentsJson(JsonUtils.toJsonString(List.of(segment("SEQUENCE", "4", null))));
        when(rules.selectById(1L)).thenReturn(rule); when(columns.selectListByModelId(1L)).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "modelMapper", models); ReflectionTestUtils.setField(service, "ruleMapper", rules);
        ReflectionTestUtils.setField(service, "columnMapper", columns);
        var req = new MasterCodeRuleStatusReqVO(); req.setId(1L); req.setStatus(0);
        assertThrows(ServiceException.class, () -> service.updateStatus(req));
        verify(rules, never()).update(isNull(), any(Wrapper.class));
    }

    @Test void integralAndExactDecimalNormalizationIsLosslessAndStable() {
        var integer = MasterColumnDO.builder().name("id").fieldType(ColumnMetaData.TYPE_INTEGER).build();
        assertEquals(9007199254740993L, MasterDataServiceImpl.convertCell(integer, "9007199254740993"));
        assertEquals(Long.MAX_VALUE, MasterDataServiceImpl.convertCell(integer, Long.toString(Long.MAX_VALUE)));
        assertThrows(ServiceException.class, () -> MasterDataServiceImpl.convertCell(integer, 9007199254740993d));
        var decimal = MasterColumnDO.builder().name("amount").fieldType(ColumnMetaData.TYPE_BIGNUMBER)
                .fieldLength(20).fieldScale(2).build();
        assertEquals(new BigDecimal("1.00"), MasterDataServiceImpl.convertCell(decimal, "1.0"));
        assertThrows(ServiceException.class, () -> MasterDataServiceImpl.convertCell(decimal, "1.001"));
        String raw = JsonUtils.toJsonString(MasterDataServiceImpl.toWireRows(List.of(Map.of(
                "id", 9007199254740993L, "amount", MasterDataServiceImpl.convertCell(decimal, "1.0")))));
        assertTrue(raw.contains("\"id\":\"9007199254740993\""));
        assertTrue(raw.contains("\"amount\":\"1.00\""));
        assertEquals("1", MasterDataServiceImpl.toWireRows(List.of(Map.of("id", 1))).getFirst().get("id"));
        var timestamp = MasterColumnDO.builder().name("time").fieldType(ColumnMetaData.TYPE_TIMESTAMP).build();
        String nanos = "2026-10-12 11:22:33.123456";
        var jdbcTime = (java.sql.Timestamp) MasterDataServiceImpl.convertCell(timestamp, nanos);
        assertEquals(123456000, jdbcTime.getNanos());
        assertEquals(nanos, MasterDataServiceImpl.toWireRows(List.of(Map.of("time", jdbcTime))).getFirst().get("time"));
        var bool = MasterColumnDO.builder().name("flag").fieldType(ColumnMetaData.TYPE_BOOLEAN).build();
        assertThrows(ServiceException.class, () -> MasterDataServiceImpl.convertCell(bool, "wrong"));
    }

    @Test void mutationBoundaryRejectsMissingKeysAndUnboundedBatches() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var req = new MasterDataChangeSubmitReqVO(); req.setModelId(1L); req.setOperation("CREATE");
            req.setRequestId(UUID.randomUUID().toString()); req.setRows(List.of(Map.of("id", "1")));
            assertTrue(factory.getValidator().validate(req).isEmpty());
            req.setRequestId("invalid"); assertFalse(factory.getValidator().validate(req).isEmpty());
            req.setRequestId(UUID.randomUUID().toString()); req.setRows(Collections.nCopies(201, Map.of("id", "1")));
            assertFalse(factory.getValidator().validate(req).isEmpty());
        }
    }
}
