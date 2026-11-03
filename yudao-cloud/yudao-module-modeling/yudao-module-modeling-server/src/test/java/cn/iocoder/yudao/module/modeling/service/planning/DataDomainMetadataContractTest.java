package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.convert.planning.PlanningDomainConvert;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer;
import cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.DataDomainController;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainPageReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataDomainMetadataContractTest {

    @Test
    void createAndUpdateKeepRemainingMetadata() {
        DataDomainMapper domains = mock(DataDomainMapper.class);
        DataDomainServiceImpl service = new DataDomainServiceImpl();
        ReflectionTestUtils.setField(service, "dataDomainMapper", domains);
        var users = mock(AdminUserApi.class);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        when(users.validateUserList(any())).thenReturn(CommonResult.success(true));
        var leader = new AdminUserRespDTO();
        leader.setMobile("00000000000");
        when(users.getUser(77L)).thenReturn(CommonResult.success(leader));
        when(domains.insert(any(DataDomainDO.class))).thenAnswer(call -> {
            call.<DataDomainDO>getArgument(0).setId(10L);
            return 1;
        });

        DataDomainSaveReqVO req = request();
        assertEquals(10L, service.createDataDomain(req));
        ArgumentCaptor<DataDomainDO> inserted = ArgumentCaptor.forClass(DataDomainDO.class);
        verify(domains).insert(inserted.capture());
        assertEquals(1, inserted.getValue().getStatus());
        assertMetadata(inserted.getValue());

        req.setId(10L);
        req.setStatus(1);
        when(domains.selectById(10L)).thenReturn(inserted.getValue());
        when(domains.selectForUpdate(10L)).thenReturn(inserted.getValue());
        service.updateDataDomain(req);
        ArgumentCaptor<DataDomainDO> updated = ArgumentCaptor.forClass(DataDomainDO.class);
        verify(domains).updateById(updated.capture());
        assertEquals(10L, updated.getValue().getId());
        assertEquals(1, updated.getValue().getStatus());
        assertMetadata(updated.getValue());
    }

    @Test
    void detailAndPageExposeRemainingMetadataWithLosslessAuditWire() throws Exception {
        DataDomainService service = mock(DataDomainService.class);
        DataDomainController controller = new DataDomainController();
        ReflectionTestUtils.setField(controller, "dataDomainService", service);
        var presentation = mock(PlanningDomainPresentationService.class);
        ReflectionTestUtils.setField(controller, "presentationService", presentation);
        when(presentation.presentBoards(any())).thenAnswer(call -> call.<List<DataDomainDO>>getArgument(0)
                .stream().map(PlanningDomainConvert::toResponse).toList());
        DataDomainDO row = DataDomainDO.builder().id(9007199254740993L).name("person").code("PERSON")
                .leaderUserId(9007199254740995L).leaderPhone("00000000000")
                .description("description").remark("remark").status(0).build();
        row.setCreator("9007199254740993");
        row.setUpdater("99");
        row.setCreateTime(LocalDateTime.of(2026, 10, 12, 12, 0));
        row.setUpdateTime(LocalDateTime.of(2026, 10, 12, 13, 0));
        when(service.getDataDomain(row.getId())).thenReturn(row);
        DataDomainPageReqVO page = new DataDomainPageReqVO();
        when(service.getDataDomainPage(page)).thenReturn(new PageResult<>(List.of(row), 1L));

        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, NumberSerializer.INSTANCE);
        module.addSerializer(LocalDateTime.class, TimestampLocalDateTimeSerializer.INSTANCE);
        JsonMapper mapper = JsonMapper.builder().addModule(module).build();
        String detail = mapper.writeValueAsString(controller.getDataDomain(row.getId()).getData());
        var wire = mapper.readTree(detail);
        assertEquals("9007199254740993", wire.get("id").asString());
        assertEquals("9007199254740995", wire.get("leaderUserId").asString());
        assertEquals("00000000000", wire.get("leaderPhone").asString());
        assertEquals("description", wire.get("description").asString());
        assertEquals("remark", wire.get("remark").asString());
        assertTrue(wire.get("createTime").isIntegralNumber());
        assertTrue(wire.get("updateTime").isIntegralNumber());
        assertEquals(Set.of("id", "name", "code", "leaderUserId", "leaderPhone", "status", "description", "remark",
                "creator", "createTime", "updater", "updateTime", "icon", "businessOwnerIds", "dataOwnerIds", "namingRules",
                "subjectCount", "businessObjectCount", "businessActivityCount", "leaderName", "creatorName", "creatorPhone"), mapper.readValue(detail, Map.class).keySet());
        assertEquals(detail, mapper.writeValueAsString(controller.getDataDomainPage(page).getData().getList().getFirst()));
    }

    @Test
    void dropdownDoesNotExposeContactsRulesOrStatistics() throws Exception {
        var service = mock(DataDomainService.class);
        var controller = new DataDomainController();
        ReflectionTestUtils.setField(controller, "dataDomainService", service);
        var row = DataDomainDO.builder().id(1L).name("板块").code("BOARD").leaderUserId(2L)
                .leaderPhone("00000000000").businessOwnerIds(List.of(3L)).namingRules("[]").build();
        when(service.getDataDomainSimpleList()).thenReturn(List.of(row));
        var mapper = JsonMapper.builder().build();
        var wire = mapper.readValue(mapper.writeValueAsString(controller.getDataDomainSimpleList().getData().getFirst()), Map.class);
        assertEquals(Set.of("id", "name", "code", "icon", "status"), wire.keySet());
    }

    @Test
    void retainedMetadataValidationStillRejectsMissingLeaderAndOversizedRemark() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            DataDomainSaveReqVO req = request();
            req.setDescription("x".repeat(256));
            req.setRemark("x".repeat(500));
            assertTrue(validator.validate(req).isEmpty());
            req.setRemark("x".repeat(501));
            assertTrue(validator.validate(req).stream().anyMatch(error -> error.getPropertyPath().toString().equals("remark")));
            req.setRemark("remark");
            req.setLeaderUserId(null);
            assertTrue(validator.validate(req).stream().anyMatch(error -> error.getPropertyPath().toString().equals("leaderUserId")));
        }
    }

    private static DataDomainSaveReqVO request() {
        DataDomainSaveReqVO req = new DataDomainSaveReqVO();
        req.setCode("PERSON");
        req.setName("person");
        req.setLeaderUserId(77L);
        req.setLeaderPhone("00000000000");
        req.setDescription("description");
        req.setRemark("remark");
        return req;
    }

    private static void assertMetadata(DataDomainDO row) {
        assertEquals("PERSON", row.getCode());
        assertEquals("person", row.getName());
        assertEquals(77L, row.getLeaderUserId());
        assertEquals("00000000000", row.getLeaderPhone());
        assertEquals("description", row.getDescription());
        assertEquals("remark", row.getRemark());
    }
}
