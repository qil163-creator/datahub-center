package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDomainDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCodeRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDataChangeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MasterModelDraftServiceTest extends BaseMockitoUnitTest {

    @Mock private MasterModelMapper models;
    @Mock private MasterColumnMapper columns;
    @Mock private MasterDomainMapper domains;
    @Mock private MasterDatabaseResolver databaseResolver;
    @Mock private DbSwitchRuntime runtime;
    @Mock private MasterCodeRuleMapper rules;
    @Mock private MasterDataChangeMapper changes;
    @Mock private MasterDistributeMapper distributions;
    @Mock private com.mzt.logapi.service.ILogRecordService audit;
    @InjectMocks private MasterModelServiceImpl service;

    @Test
    void emptyDraftOnlyWritesPlatformDefinition() {
        enabledDomain();
        when(models.insert(any(MasterModelDO.class))).thenAnswer(invocation -> {
            invocation.<MasterModelDO>getArgument(0).setId(9007199254740993L);
            return 1;
        });
        assertEquals(9007199254740993L, service.createModel(request(List.of())));
        ArgumentCaptor<MasterModelDO> captured = ArgumentCaptor.forClass(MasterModelDO.class);
        verify(models).insert(captured.capture());
        MasterModelDO saved = captured.getValue();
        assertEquals(MasterModelDO.STATUS_UNRELEASED, saved.getStatus());
        assertEquals(MasterModelDO.PUBLISH_READY, saved.getPublishState());
        assertEquals(1, saved.getVersion());
        assertNull(saved.getReleaseTime());
        assertNull(saved.getReleaseUserId());
        verify(columns).deleteByModelId(9007199254740993L);
        verify(columns, never()).insert(any(MasterColumnDO.class));
        noMaterialization();
    }

    @Test
    void draftCanSaveCompleteFieldsBeforeChoosingPrimaryKey() {
        enabledDomain();
        when(models.insert(any(MasterModelDO.class))).thenAnswer(invocation -> {
            invocation.<MasterModelDO>getArgument(0).setId(100L);
            return 1;
        });
        service.createModel(request(List.of(column("name", 0))));
        ArgumentCaptor<MasterColumnDO> captured = ArgumentCaptor.forClass(MasterColumnDO.class);
        verify(columns).insert(captured.capture());
        assertEquals("name", captured.getValue().getName());
        assertEquals(0, captured.getValue().getPk());
        noMaterialization();
    }

    @Test
    void editingDraftRetainsDraftStateAndVersion() {
        enabledDomain();
        MasterModelDO old = storedModel(0);
        old.setReleaseTime(null);
        old.setReleaseUserId(null);
        when(models.selectForUpdate(100L)).thenReturn(old);
        MasterModelSaveReqVO req = request(List.of());
        req.setId(100L);
        service.updateModel(req);
        ArgumentCaptor<MasterModelDO> captured = ArgumentCaptor.forClass(MasterModelDO.class);
        verify(models).updateById(captured.capture());
        assertEquals(0, captured.getValue().getStatus());
        assertEquals(7, captured.getValue().getVersion());
        assertNull(captured.getValue().getReleaseTime());
        verify(columns).deleteByModelId(100L);
        noMaterialization();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void savingPublishedHistoryKeepsStatusVersionAndReleaseRecord(int status) {
        enabledDomain();
        MasterModelDO old = storedModel(status);
        when(models.selectForUpdate(100L)).thenReturn(old);
        when(columns.selectListByModelId(100L)).thenReturn(List.of(storedColumn()));
        MasterModelSaveReqVO req = request(List.of(column("code", 1)));
        req.setId(100L);
        req.setTableCn("更新业务名称");
        req.setExpectedVersion(old.getVersion());
        service.updateModel(req);
        ArgumentCaptor<MasterModelDO> captured = ArgumentCaptor.forClass(MasterModelDO.class);
        verify(models).updateById(captured.capture());
        assertEquals(status, captured.getValue().getStatus());
        assertEquals(old.getVersion(), captured.getValue().getVersion());
        assertEquals(old.getReleaseTime(), captured.getValue().getReleaseTime());
        assertEquals(old.getReleaseUserId(), captured.getValue().getReleaseUserId());
        verify(columns, never()).deleteByModelId(any());
        verify(columns, never()).insert(any(MasterColumnDO.class));
        noMaterialization();
    }

    @Test
    void publishedStructureCanBeClearedAsDraftButCannotBePublishedEmpty() {
        enabledDomain();
        when(models.selectForUpdate(100L)).thenReturn(storedModel(1));
        when(columns.selectListByModelId(100L)).thenReturn(List.of(storedColumn()));
        MasterModelSaveReqVO req = request(List.of());
        req.setId(100L);
        req.setExpectedVersion(7);
        service.updateModel(req);
        ArgumentCaptor<MasterModelDO> captured = ArgumentCaptor.forClass(MasterModelDO.class);
        verify(models).updateById(captured.capture());
        assertEquals(MasterModelDO.STATUS_UNRELEASED, captured.getValue().getStatus());
        assertEquals(8, captured.getValue().getVersion());
        verify(columns).deleteByModelId(100L);
        noMaterialization();
    }

    @Test
    void publishingEmptyDraftFailsBeforeResolvingExternalSource() {
        MasterModelDO old = storedModel(0);
        old.setReleaseTime(null);
        when(models.selectById(100L)).thenReturn(old);
        when(columns.selectListByModelId(100L)).thenReturn(List.of());
        ServiceException error = assertThrows(ServiceException.class, () -> service.releaseModel(100L));
        assertEquals(MASTER_MODEL_NO_COLUMN.getCode(), error.getCode());
        noMaterialization();
    }

    @Test
    void publishingDraftWithoutPrimaryKeyFailsBeforeResolvingExternalSource() {
        MasterModelDO old = storedModel(0);
        old.setReleaseTime(null);
        when(models.selectById(100L)).thenReturn(old);
        MasterColumnDO noPk = storedColumn();
        noPk.setPk(0);
        when(columns.selectListByModelId(100L)).thenReturn(List.of(noPk));
        ServiceException error = assertThrows(ServiceException.class, () -> service.releaseModel(100L));
        assertEquals(MASTER_MODEL_NO_PK.getCode(), error.getCode());
        noMaterialization();
    }

    @Test
    void providedInvalidFieldCannotBeSilentlyDiscarded() {
        enabledDomain();
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createModel(request(List.of(column("", 0)))));
        assertEquals(MASTER_MODEL_COLUMN_NAME_INVALID.getCode(), error.getCode());
        verify(models, never()).insert(any(MasterModelDO.class));
        noMaterialization();
    }

    @Test
    void saveContractAllowsEmptyColumnsButStillRequiresBasicsAndValidProvidedFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            MasterModelSaveReqVO req = request(List.of());
            assertTrue(validator.validate(req).isEmpty());
            req.setColumns(null);
            assertTrue(validator.validate(req).stream().anyMatch(v -> v.getPropertyPath().toString().equals("columns")));
            req.setColumns(List.of(column("", 0)));
            assertTrue(validator.validate(req).stream().anyMatch(v -> v.getPropertyPath().toString().equals("columns[0].name")));
            req.setColumns(List.of());
            req.setDomainId(null);
            req.setDatabaseId(null);
            req.setTableCn("");
            req.setTableName("");
            Set<String> invalid = validator.validate(req).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
            assertTrue(invalid.containsAll(Set.of("domainId", "databaseId", "tableCn", "tableName")));
        }
        Set<String> fields = Arrays.stream(MasterModelSaveReqVO.class.getDeclaredFields()).map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        assertFalse(fields.contains("status"));
        assertFalse(fields.contains("version"));
        assertFalse(fields.contains("releaseTime"));
    }

    @Test
    void statisticsCountsDraftStateIndependentlyOfPublishedState() {
        when(models.selectStatisticsCount(10L, null)).thenReturn(6L);
        when(models.selectStatisticsCount(10L, 0)).thenReturn(2L);
        when(models.selectStatisticsCount(10L, 1)).thenReturn(3L);
        when(models.selectStatisticsCount(10L, 2)).thenReturn(1L);
        var stats = service.getStatistics(10L);
        assertEquals(6L, stats.getTotal());
        assertEquals(2L, stats.getDraft());
        assertEquals(3L, stats.getReleased());
        assertEquals(1L, stats.getOffline());
        noMaterialization();
    }

    private void enabledDomain() {
        when(domains.selectForUpdate(10L)).thenReturn(MasterDomainDO.builder().id(10L).status(0).build());
    }

    private void noMaterialization() {
        verifyNoInteractions(databaseResolver, runtime);
        verify(models, never()).claimPublish(any());
        verify(models, never()).completePublish(anyLong(), anyBoolean());
    }

    private static MasterModelSaveReqVO request(List<MasterColumnVO> fields) {
        MasterModelSaveReqVO req = new MasterModelSaveReqVO();
        req.setDomainId(10L);
        req.setDatabaseId(20L);
        req.setTableName("master_person");
        req.setTableCn("人员");
        req.setColumns(fields);
        return req;
    }

    private static MasterColumnVO column(String name, int pk) {
        MasterColumnVO column = new MasterColumnVO();
        column.setName(name);
        column.setFieldType(2);
        column.setPk(pk);
        return column;
    }

    private static MasterColumnDO storedColumn() {
        return MasterColumnDO.builder().name("code").fieldType(2).pk(1).nullable(0).build();
    }

    private static MasterModelDO storedModel(int status) {
        return MasterModelDO.builder().id(100L).domainId(10L).databaseId(20L).tableName("master_person")
                .tableCn("人员").status(status).version(7).releaseUserId(99L)
                .releaseTime(LocalDateTime.of(2026, 10, 12, 12, 0)).publishState(MasterModelDO.PUBLISH_READY).build();
    }
}
