package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import java.time.LocalDateTime;
import java.util.List;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MasterModelEvolutionServiceTest extends BaseMockitoUnitTest {
    @Mock private MasterModelMapper models;
    @Mock private MasterColumnMapper columns;
    @Mock private MasterDomainMapper domains;
    @Mock private MasterCodeRuleMapper rules;
    @Mock private MasterDataChangeMapper changes;
    @Mock private MasterDistributeMapper distributions;
    @Mock private MasterDatabaseResolver resolver;
    @Mock private DbSwitchRuntime runtime;
    @Mock private MasterModelSchemaPublisher publisher;
    @Mock private com.mzt.logapi.service.ILogRecordService audit;
    @InjectMocks private MasterModelServiceImpl service;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void structuralEditBecomesVersionedDraftWithoutTouchingPhysicalTable(int status) {
        var old = prepareStructureEdit(status);
        service.updateModel(request());
        var saved = ArgumentCaptor.forClass(MasterModelDO.class);
        verify(models).updateById(saved.capture());
        assertEquals(0, saved.getValue().getStatus());
        assertEquals(4, saved.getValue().getVersion());
        assertEquals(old.getReleaseTime(), saved.getValue().getReleaseTime());
        assertEquals(old.getReleaseUserId(), saved.getValue().getReleaseUserId());
        verify(columns).deleteByModelId(100L);
        verify(columns, times(2)).insert(any(MasterColumnDO.class));
        verifyNoInteractions(resolver, runtime, publisher);
    }

    @Test
    void staleOrMissingVersionCannotOverwritePublishedDefinition() {
        prepareEdit(1);
        for (Integer version : new Integer[]{null, 2}) {
            var req = request(); req.setExpectedVersion(version);
            assertCode(MASTER_MODEL_PUBLISH_CONFLICT.getCode(), () -> service.updateModel(req));
        }
        verify(models, never()).updateById(any(MasterModelDO.class));
        verify(columns, never()).deleteByModelId(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void pendingApprovalBlocksStructureDraftAndKeepsCurrentDefinition() {
        prepareStructureEdit(1);
        when(changes.selectActive(100L)).thenReturn(List.of(new MasterDataChangeDO()));
        assertCode(MASTER_DATA_ROW_PENDING.getCode(), () -> service.updateModel(request()));
        verify(models, never()).updateById(any(MasterModelDO.class));
        verify(columns, never()).deleteByModelId(any());
    }

    @Test
    void codeRuleAndDistributionReferencesBlockStructureChanges() {
        prepareStructureEdit(1);
        when(distributions.selectCountByModelId(100L)).thenReturn(1L);
        assertCode(MASTER_MODEL_HAS_DISTRIBUTE.getCode(), () -> service.updateModel(request()));
        when(distributions.selectCountByModelId(100L)).thenReturn(0L);
        when(rules.selectCount(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class), eq(100L))).thenReturn(1L);
        assertCode(MASTER_MODEL_HAS_CODE_RULE.getCode(), () -> service.updateModel(request()));
        verify(models, never()).updateById(any(MasterModelDO.class));
    }

    @Test
    void publicationClaimPrecedesExternalSynchronizationAndCompletion() throws Exception {
        var old = prepareRelease(MasterModelSchemaPublisher.Status.APPLIED);
        when(models.completePublish(100L, false)).thenReturn(1);
        service.releaseModel(100L);
        var ordered = inOrder(models, publisher);
        ordered.verify(models).claimPublish(old);
        ordered.verify(publisher).synchronize(any(), eq("md_person"), eq("人员"), anyList());
        ordered.verify(models).completePublish(100L, false);
        verify(models, never()).resetPublishAfterRollback(anyLong());
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
    }

    @Test
    void confirmedRollbackKeepsDraftAndPermitsExplicitRetry() {
        prepareRelease(MasterModelSchemaPublisher.Status.NOT_APPLIED);
        when(models.resetPublishAfterRollback(100L)).thenReturn(1);
        assertCode(MASTER_MODEL_RELEASE_FAILED.getCode(), () -> service.releaseModel(100L));
        verify(models).resetPublishAfterRollback(100L);
        verify(models, never()).markPublishUnknown(anyLong());
        verify(models, never()).completePublish(anyLong(), anyBoolean());
    }

    @Test
    void uncertainCommitBlocksRetriesAndDoesNotAnnouncePublished() {
        prepareRelease(MasterModelSchemaPublisher.Status.UNKNOWN);
        assertCode(MASTER_MODEL_PUBLISH_UNCERTAIN.getCode(), () -> service.releaseModel(100L));
        verify(models).markPublishUnknown(100L);
        verify(models, never()).resetPublishAfterRollback(anyLong());
        verify(models, never()).completePublish(anyLong(), anyBoolean());
    }

    @Test
    void staleClaimNeverTouchesTarget() {
        var old = prepareRelease(null);
        when(models.claimPublish(old)).thenReturn(0);
        assertCode(MASTER_MODEL_PUBLISH_CONFLICT.getCode(), () -> service.releaseModel(100L));
        verifyNoInteractions(publisher);
    }

    @Test
    void verifyingUncertainEvolutionNeverContinuesTheDdl() {
        var old = prepareObservation();
        old.setPublishState(MasterModelDO.PUBLISH_UNKNOWN);
        when(publisher.verify(any(), anyString(), anyString(), anyList()))
                .thenReturn(new MasterModelSchemaPublisher.Result(MasterModelSchemaPublisher.Status.NOT_APPLIED, List.of()));
        assertCode(MASTER_MODEL_PUBLISH_VERIFY_FAILED.getCode(), () -> service.verifyRelease(100L));
        verify(publisher, never()).synchronize(any(), any(), any(), any());
        verify(models, never()).completePublish(anyLong(), anyBoolean());
        when(publisher.verify(any(), anyString(), anyString(), anyList()))
                .thenReturn(new MasterModelSchemaPublisher.Result(MasterModelSchemaPublisher.Status.APPLIED, List.of()));
        when(models.completePublish(100L, true)).thenReturn(1);
        service.verifyRelease(100L);
        verify(models).completePublish(100L, true);
    }

    @Test
    void equivalentEmptyCommentAndDefaultFlagsDoNotCreateAnotherStructureVersion() {
        var stored = MasterColumnDO.builder().name("name").fieldType(2).fieldLength(64).commentStr(null).pk(null).nullable(null).build();
        var req = field("name", 0); req.setCommentStr("");
        assertTrue(MasterModelServiceImpl.sameColumns(List.of(stored), List.of(req)));
    }

    private MasterModelDO prepareEdit(int status) {
        var model = model(status);
        when(domains.selectForUpdate(10L)).thenReturn(MasterDomainDO.builder().id(10L).status(0).build());
        when(models.selectForUpdate(100L)).thenReturn(model);
        return model;
    }

    private MasterModelDO prepareStructureEdit(int status) {
        var model = prepareEdit(status);
        when(columns.selectListByModelId(100L)).thenReturn(List.of(storedField()));
        return model;
    }

    private MasterModelDO prepareRelease(MasterModelSchemaPublisher.Status result) {
        var model = prepareObservation();
        when(domains.selectById(10L)).thenReturn(MasterDomainDO.builder().id(10L).status(0).build());
        when(models.claimPublish(model)).thenReturn(1);
        if (result != null) when(publisher.synchronize(any(), anyString(), anyString(), anyList()))
                .thenReturn(new MasterModelSchemaPublisher.Result(result, List.of()));
        return model;
    }

    private MasterModelDO prepareObservation() {
        var model = model(0);
        when(models.selectById(100L)).thenReturn(model);
        when(columns.selectListByModelId(100L)).thenReturn(List.of(storedField()));
        when(resolver.resolve(eq(20L), any())).thenReturn(new MasterDatabaseResolver.ConnectionInfo());
        return model;
    }

    private static MasterModelDO model(int status) {
        return MasterModelDO.builder().id(100L).domainId(10L).databaseId(20L).tableName("md_person")
                .tableCn("人员").status(status).version(3).publishState(MasterModelDO.PUBLISH_READY)
                .releaseTime(LocalDateTime.of(2026, 10, 12, 12, 0)).releaseUserId(99L).build();
    }
    private static MasterColumnDO storedField() {
        return MasterColumnDO.builder().name("code").fieldType(2).fieldLength(64).pk(1).nullable(0).build();
    }
    private static MasterColumnVO field(String name, int pk) {
        var field = new MasterColumnVO(); field.setName(name); field.setFieldType(2); field.setFieldLength(64); field.setPk(pk);
        return field;
    }
    private static MasterModelSaveReqVO request() {
        var req = new MasterModelSaveReqVO(); req.setId(100L); req.setExpectedVersion(3); req.setDomainId(10L); req.setDatabaseId(20L);
        req.setTableName("md_person"); req.setTableCn("人员"); req.setColumns(List.of(field("code", 1), field("phone", 0)));
        return req;
    }
    private static void assertCode(Integer code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ServiceException.class, operation).getCode());
    }
}
