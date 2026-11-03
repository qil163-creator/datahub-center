package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaPrecondition;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import java.util.function.*;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 部署回归覆盖版本资格、整批重建、外部未知结果和只读恢复，不把当前草稿视为发布快照。 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ModelMaterializedServiceImplTest {
    @Mock ModelMaterializedMapper materializedMapper;
    @Mock ModelMaterializedLogMapper logMapper;
    @Mock LogicModelMapper logicModelMapper;
    @Mock LogicModelColumnService columnService;
    @Mock ModelDeploymentAttemptStore attemptStore;
    @Mock ModelTypeConfigValidator typeConfigValidator;
    @Mock ModelHierarchyResolver hierarchyResolver;
    @Mock ModelTableNameResolver tableNameResolver;
    @Mock ModelDdlExecutor ddlExecutor;
    @Mock ModelingDatabaseResolver databaseResolver;
    @InjectMocks ModelMaterializedServiceImpl service;
    ModelingDatabaseResolver.ConnectionInfo connection;
    LogicModelDO current;
    ModelDdlExecutor.TableObservation before;
    ModelDdlExecutor.TableObservation after;
    ModelDeploymentAttemptDO attempt;
    List<ModelFieldItem> fields;

    @BeforeEach void objects() {
        connection = new ModelingDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(7L); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema("public"); connection.setConfigurationRevision("r1");
        ModelFieldItem id = new ModelFieldItem(); id.setFieldUid("uid-id"); id.setFieldName("id"); id.setDataType("bigint");
        fields = List.of(id);
        current = LogicModelDO.builder().id(2L).name("客户模型").code("customer").layerId(3L)
                .revision(4L).enabled(1).status("PUBLISHED").modelStyle("RELATIONAL").tableType("DETAIL")
                .dataDomainId(4L).bizCategoryId(5L).build();
        before = observation("original", "before"); after = observation("recreated", "after");
        attempt = new ModelDeploymentAttemptDO(); attempt.setId(10L); attempt.setMaterializedId(100L);
        attempt.setModelId(2L); attempt.setDesignRevision(4L); attempt.setDatasourceId(7L); attempt.setSchemaName("public"); attempt.setTableName("dwd_customer");
        attempt.setTargetKey("target"); attempt.setConfigurationRevision("r1"); attempt.setLeaseOwner("owner");
        attempt.setPhase("EXECUTING"); attempt.setPublishMode("REBUILD");
        attempt.setBeforeObservationJson(JsonUtils.toJsonString(before)); attempt.setFieldsJson(JsonUtils.toJsonString(fields));
        attempt.setModelSnapshotJson(ModelDeploymentAttemptStore.taskModelInput(current));
    }

    void prepared() {
        when(logicModelMapper.selectById(2L)).thenReturn(current);
        when(columnService.listByModelId(2L)).thenReturn(fields);
        when(databaseResolver.resolve(7L)).thenReturn(connection);
        when(tableNameResolver.buildFullTableName(any(), eq("customer"))).thenReturn("dwd_customer");
        when(ddlExecutor.observeTable(connection, 7L, "dwd_customer")).thenReturn(before, after);
        when(ddlExecutor.physicalTargetKey(connection, "dwd_customer")).thenReturn("target");
        when(ddlExecutor.buildCreateTableDdl(eq("dwd_customer"), eq("客户模型"), anyList(), same(connection), isNull()))
                .thenReturn(List.of("CREATE TABLE public.dwd_customer (id bigint)"));
        when(attemptStore.reserve(any(), any())).thenAnswer(call -> {
            ModelMaterializedDO desired = call.getArgument(0); desired.setId(100L);
            return attempt;
        });
        locked(after, true);
    }

    @SuppressWarnings("unchecked") void locked(ModelDdlExecutor.TableObservation observation, boolean matches) {
        doAnswer(call -> {
            ((BiConsumer<ModelDdlExecutor.TableObservation, Boolean>)call.getArgument(4)).accept(observation, matches);
            return null;
        }).when(ddlExecutor).withLockedExpectedObservation(same(connection), eq("dwd_customer"), anyString(), anyList(), any());
    }

    @Test void draftWithoutPublishedVersionCannotReachTheTarget() {
        when(logicModelMapper.selectById(2L)).thenReturn(LogicModelDO.builder().id(2L).enabled(1).status("DRAFT").build());
        var error = assertThrows(ServiceException.class, () -> service.publish(request("INCREMENT")));
        assertEquals(MODEL_MATERIALIZED_MODEL_UNPUBLISHED.getCode(), error.getCode());
        verifyNoInteractions(databaseResolver, attemptStore, ddlExecutor);
    }

    @Test void missingFrozenModelSnapshotIsRejectedBeforeOpeningTarget() {
        when(logicModelMapper.selectById(2L)).thenReturn(LogicModelDO.builder().id(2L).enabled(1).build());
        assertThrows(ServiceException.class, () -> service.publish(request("REBUILD")));
        verifyNoInteractions(databaseResolver, ddlExecutor, attemptStore);
    }

    @Test void newerDraftDoesNotReplacePublishedFieldsOrNameAndRebuildRunsInOneFrozenTransaction() {
        prepared();
        assertEquals(100L, service.publish(request("REBUILD")));
        var plan = ArgumentCaptor.forClass(List.class);
        verify(ddlExecutor).executeDdl(same(connection), plan.capture(), same(before), any());
        assertEquals(2, plan.getValue().size());
        assertTrue(plan.getValue().getFirst().toString().startsWith("DROP TABLE"));
        assertTrue(plan.getValue().getLast().toString().startsWith("CREATE TABLE"));
        verify(ddlExecutor, never()).dropTable(any(), any());
        verify(attemptStore).finish(attempt, "SUCCESS", "已核验任务输入部署成功");
    }

    @Test void unsupportedProductCannotReserveOrExecute() {
        prepared(); connection.setProductType(ProductTypeEnum.MYSQL);
        assertEquals(MODEL_MATERIALIZED_PRODUCT_UNSUPPORTED.getCode(), assertThrows(ServiceException.class,
                () -> service.publish(request("REBUILD"))).getCode());
        verifyNoInteractions(attemptStore);
        verify(ddlExecutor, never()).observeTable(any(), any(), any());
    }

    @Test void sourceModelUsesPublishedSnapshotAndTheSameVerifiedDeploymentChain() {
        prepared();
        current.setTableType("SOURCE");

        assertEquals(100L, service.publish(request("REBUILD")));

        verify(typeConfigValidator).validateOnPublish(eq("SOURCE"), any(), anyList());
        verify(ddlExecutor).executeDdl(same(connection), anyList(), same(before), any());
        verify(attemptStore).finish(same(attempt), eq("SUCCESS"), anyString());
    }

    @Test void targetAlreadyOwnedPreventsSecondExternalExecution() {
        prepared();
        doThrow(new ServiceException(MODEL_MATERIALIZED_TARGET_BUSY)).when(attemptStore).reserve(any(), any());
        assertThrows(ServiceException.class, () -> service.publish(request("REBUILD")));
        verify(ddlExecutor, never()).executeDdl(any(), anyList(), any(), any());
    }

    @Test void unknownCommitRetainsTargetOccupationAndRejectsDirectRepublish() {
        prepared();
        doThrow(new ModelDdlExecutor.DdlExecutionException(ModelDdlExecutor.DdlOutcome.UNKNOWN)).when(ddlExecutor)
                .executeDdl(any(), anyList(), any(), any());
        service.publish(request("REBUILD"));
        verify(attemptStore).finish(eq(attempt), eq("UNKNOWN"), anyString());
        var row = ModelMaterializedDO.builder().id(100L).status("UNKNOWN").build();
        when(materializedMapper.selectById(100L)).thenReturn(row);
        var repeat = new ModelMaterializedRepublishReqVO(); repeat.setId(100L); repeat.setPublishMode("REBUILD");
        assertEquals(MODEL_MATERIALIZED_OUTCOME_UNKNOWN.getCode(), assertThrows(ServiceException.class, () -> service.republish(repeat)).getCode());
        verify(attemptStore, times(1)).reserve(any(), any());
    }

    @Test void confirmedRollbackIsRecordedAsFailure() {
        prepared();
        doThrow(new ModelDdlExecutor.DdlExecutionException(ModelDdlExecutor.DdlOutcome.ROLLED_BACK)).when(ddlExecutor)
                .executeDdl(any(), anyList(), any(), any());
        service.publish(request("REBUILD"));
        verify(attemptStore).finish(eq(attempt), eq("FAIL"), anyString());
    }

    @Test void failedPostDeploymentVerificationCannotPretendDdlWasRolledBack() {
        prepared(); locked(after, false);
        service.publish(request("REBUILD"));
        verify(attemptStore).finish(eq(attempt), eq("UNKNOWN"), anyString());
    }

    void recovery(ModelDdlExecutor.TableObservation observed, boolean matches) {
        when(materializedMapper.selectById(100L)).thenReturn(ModelMaterializedDO.builder().id(100L).attemptId(10L).status("UNKNOWN").build());
        when(attemptStore.claimRecovery(10L)).thenReturn(attempt);
        when(databaseResolver.resolve(7L)).thenReturn(connection);
        when(ddlExecutor.physicalTargetKey(connection, "dwd_customer")).thenReturn("target");
        locked(observed, matches);
    }

    @Test void recoveryCanConfirmRecreatedDesiredTargetWithoutRepeatingDdl() {
        recovery(after, true);
        assertEquals("SUCCESS", service.reconcile(100L));
        verify(attemptStore).finish(eq(attempt), eq("SUCCESS"), anyString());
        verify(ddlExecutor, never()).executeDdl(any(), anyList(), any(), any());
    }

    @Test void recoveryConfirmsOriginalRelationshipIdentityAsNotApplied() {
        recovery(before, false);
        assertEquals("FAIL", service.reconcile(100L));
        verify(attemptStore).finish(eq(attempt), eq("FAIL"), anyString());
    }

    @Test void differentTargetDoesNotReleaseUnknownOwnership() {
        recovery(after, false);
        assertEquals("UNKNOWN", service.reconcile(100L));
        verify(attemptStore).finish(eq(attempt), eq("UNKNOWN"), anyString());
    }

    @Test void unknownWithoutAttemptEvidenceCannotReconcileOrExecute() {
        when(materializedMapper.selectById(100L)).thenReturn(ModelMaterializedDO.builder().id(100L).status("UNKNOWN").build());
        assertEquals(MODEL_MATERIALIZED_OUTCOME_UNKNOWN.getCode(), assertThrows(ServiceException.class, () -> service.reconcile(100L)).getCode());
        verifyNoInteractions(attemptStore, databaseResolver, ddlExecutor);
    }

    private ModelMaterializedPublishReqVO request(String mode) {
        var request = new ModelMaterializedPublishReqVO(); request.setModelId(2L); request.setDatasourceId(7L); request.setPublishMode(mode); return request;
    }
    private ModelDdlExecutor.TableObservation observation(String relation, String hash) {
        return new ModelDdlExecutor.TableObservation(true, false, hash, Instant.now(), fields,
                new SchemaPrecondition("public", "dwd_customer", "scope", relation, true, false, hash));
    }
}
