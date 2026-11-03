package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo.LogicTableCatNodeRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import cn.iocoder.yudao.module.modeling.service.logictable.LogicTableService;
import cn.iocoder.yudao.module.modeling.service.planning.DwLayerService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证发布展示使用真实部署审计、冻结字段和脱敏连接，不把设计草稿误当作已部署内容。 */
@ExtendWith(MockitoExtension.class)
class ModelMaterializedPresentationTest {
    @Mock ModelMaterializedMapper materializedMapper;
    @Mock ModelMaterializedLogMapper logMapper;
    @Mock LogicModelMapper logicModelMapper;
    @Mock LogicModelColumnService columnService;
    @Mock ModelDeploymentAttemptMapper deploymentAttemptMapper;
    @Mock LogicModelService logicModelService;
    @Mock LogicTableService logicTableService;
    @Mock DwLayerService dwLayerService;
    @Mock AdminUserApi adminUserApi;
    @Mock ModelHierarchyResolver hierarchyResolver;
    @Mock ModelingDatabaseResolver databaseResolver;
    @InjectMocks ModelMaterializedServiceImpl service;

    @BeforeAll static void tableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LogicModelDO.class);
    }

    @Test void unpublishedDetailUsesPublishedFieldsInsteadOfNewerDraft() {
        long id = 9_007_199_254_740_993L;
        var model = model(id);
        when(logicModelService.getModel(id)).thenReturn(model);
        when(logicModelMapper.selectById(id)).thenReturn(model);
        when(materializedMapper.selectListByModelIds(List.of(id))).thenReturn(List.of());
        model.setStatus("PUBLISHED");
        when(columnService.listByModelId(id)).thenReturn(List.of(field("published_field")));

        var result = service.getByModelId(id);

        assertEquals(id, result.getModelId());
        assertNull(result.getId());
        assertEquals("UNPUBLISHED", result.getPublishStatus());
        assertEquals("published_field", result.getFields().getFirst().getFieldName());
        assertEquals(2, result.getTableCase());
        assertNull(result.getPublisher());
        assertNull(result.getPublishTime());
        verifyNoInteractions(deploymentAttemptMapper, adminUserApi);
    }

    @Test void deployedDetailUsesExactFrozenVersionAndOriginalPublisherRatherThanModelCreator() {
        var model = model(2L); model.setCreator("1"); model.setUpdater("2");
        when(logicModelService.getModel(2L)).thenReturn(model);
        var target = target(10L, 2L, 7L, "发布目标");
        target.setDesignRevision(3L); target.setAttemptId(100L);
        when(materializedMapper.selectById(10L)).thenReturn(target);
        var attempt = new ModelDeploymentAttemptDO(); attempt.setId(100L); attempt.setModelId(2L); attempt.setCreator("3");
        attempt.setFieldsJson(JsonUtils.toJsonString(List.of(field("frozen_field"))));
        when(deploymentAttemptMapper.selectById(100L)).thenReturn(attempt);
        attempt.setCreateTime(LocalDateTime.of(2026, 10, 20, 10, 0));
        attempt.setFinishedTime(attempt.getCreateTime().plusSeconds(45));
        when(deploymentAttemptMapper.selectBatchIds(Set.of(100L))).thenReturn(List.of(attempt));
        when(adminUserApi.getUserMap(Set.of(1L, 2L, 3L))).thenReturn(Map.of(
                1L, user(1L, "模型作者"), 2L, user(2L, "模型更新人"), 3L, user(3L, "部署发起人")));

        var result = service.get(10L);

        assertEquals("frozen_field", result.getFields().getFirst().getFieldName());
        assertEquals("模型作者", result.getCreatorName());
        assertEquals("模型更新人", result.getUpdaterName());
        assertEquals("3", result.getPublisher());
        assertEquals("部署发起人", result.getPublisherName());
        assertEquals(attempt.getFinishedTime(), result.getPublishTime());
        assertEquals(45_000L, result.getPublishDuration());
        verify(columnService, never()).listByModelId(anyLong());
    }

    @Test void legacyArtifactUsesOnlyItsKnownFieldsWithoutBorrowingCurrentDesign() {
        when(logicModelService.getModel(2L)).thenReturn(model(2L));
        var target = target(10L, 2L, 7L, "历史目标");
        target.setFieldsJson(JsonUtils.toJsonString(List.of(field("historical_field"))));
        when(materializedMapper.selectById(10L)).thenReturn(target);
        assertEquals("historical_field", service.get(10L).getFields().getFirst().getFieldName());
        verifyNoInteractions(columnService);
    }

    @Test void missingLegacyArtifactInputIsShownAsUnknownWithoutCurrentFallback() {
        when(logicModelService.getModel(2L)).thenReturn(model(2L));
        when(materializedMapper.selectById(10L)).thenReturn(target(10L, 2L, 7L, "历史目标"));
        assertTrue(service.get(10L).getFields().isEmpty());
        verifyNoInteractions(columnService);
    }

    @Test void listKeepsLatestDatasourceNameAndTypeTogetherInsteadOfCombiningOldTargets() {
        var model = model(2L); model.setDescription("模型定义");
        var request = new ModelMaterializedPageReqVO();
        when(logicModelMapper.selectPage(eq(request), any(LambdaQueryWrapperX.class)))
                .thenReturn(new PageResult<>(List.of(model), 1L));
        var latest = target(10L, 2L, 7L, "新目标"); latest.setDatasourceType("POSTGRESQL"); latest.setDescription("本次发布说明");
        var older = target(9L, 2L, 6L, "旧目标"); older.setDatasourceType("MYSQL");
        when(materializedMapper.selectListByModelIds(List.of(2L))).thenReturn(List.of(latest, older));

        var result = service.getPage(request).getList().getFirst();

        assertEquals(7L, result.getDatasourceId());
        assertEquals("新目标", result.getDatasourceName());
        assertEquals("POSTGRESQL", result.getDatasourceType());
        assertEquals("模型定义", result.getDescription());
        assertNull(result.getPublishTime());
        assertNull(result.getPublisher());
    }

    @Test void pageCombinesTypeLayerAndSourceFiltersWithLatestPublishStatus() {
        var request = new ModelMaterializedPageReqVO();
        request.setTableType("DIMENSION"); request.setLayerIds(List.of(3L, 4L)); request.setSourceSystemId(8L);
        request.setPublishStatus("SUCCESS"); request.setDatasourceId(7L);
        var latestFailure = target(11L, 2L, 7L, "目标"); latestFailure.setStatus("FAIL");
        var olderSuccess = target(10L, 2L, 7L, "目标");
        when(materializedMapper.selectAllForLatestStatus()).thenReturn(List.of(latestFailure, olderSuccess));

        assertEquals(0, service.getPage(request).getTotal());
        verify(logicModelMapper, never()).selectPage(eq(request), any(LambdaQueryWrapperX.class));

        request.setPublishStatus("FAIL");
        when(logicModelMapper.selectPage(eq(request), any(LambdaQueryWrapperX.class))).thenAnswer(call -> {
            LambdaQueryWrapperX<LogicModelDO> query = call.getArgument(1);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("table_type =")); assertTrue(sql.contains("layer_id IN"));
            assertTrue(sql.contains("source_system_id ="));
            assertTrue(query.getParamNameValuePairs().containsValue("DIMENSION"));
            assertTrue(query.getParamNameValuePairs().containsValue(8L));
            return PageResult.empty();
        });
        service.getPage(request);
    }

    @Test void databaseOptionsCannotSerializeCredentialFieldsEvenIfProviderSuppliesThem() {
        var source = new DatabaseConnectionRespDTO(); source.setId(7L); source.setName("测试目标");
        source.setHost("database.invalid:5432"); source.setProductType("POSTGRESQL");
        source.setJdbcUrl("jdbc:postgresql://internal.invalid/db?password=test-secret");
        source.setUsername("test-user"); source.setPassword("test-secret");
        when(databaseResolver.getDatabaseList()).thenReturn(List.of(source));

        var result = service.getDatabaseList();
        String json = JsonUtils.toJsonString(result);

        assertEquals("database.invalid:5432", result.getFirst().getDatabaseAddress());
        assertFalse(json.contains("jdbcUrl")); assertFalse(json.contains("username")); assertFalse(json.contains("password"));
        assertFalse(json.contains("test-secret")); assertFalse(json.contains("internal.invalid"));
    }

    @Test void catalogUsesRealLayerHierarchyAndProducesUniqueFilterKeys() {
        var subject = new LogicTableCatNodeRespVO("subject-9", "预警主题", "SUBJECT_DOMAIN", null, 5L, 9L, List.of(), null);
        var domain = new LogicTableCatNodeRespVO("domain-5", "水利板块", "DATA_DOMAIN", null, 5L, null, List.of(subject), null);
        var source = new LogicTableCatNodeRespVO("source-8", "来源系统", "SOURCE_SYSTEM", 8L, null, null, List.of(), null);
        when(logicTableService.getCatTree("DETAIL")).thenReturn(List.of(domain));
        when(logicTableService.getCatTree("SOURCE")).thenReturn(List.of(source));
        when(dwLayerService.getDwLayerList(any())).thenReturn(List.of(
                DwLayerDO.builder().id(1L).parentId(0L).nodeType("CATEGORY").layerName("公共层").build(),
                DwLayerDO.builder().id(2L).parentId(1L).nodeType("LAYER").layerName("维度层").defaultTableType("DIMENSION").build(),
                DwLayerDO.builder().id(3L).parentId(1L).nodeType("LAYER").layerName("贴源层").defaultTableType("SOURCE").build()));

        var result = service.getCatTree();
        var byLayer = result.get(1).getChildren().getFirst();
        var layerDomain = byLayer.getChildren().getFirst().getChildren().getFirst();

        assertEquals(List.of("按数据板块", "按数仓分层", "按来源系统"), result.stream().map(ModelMaterializedCatNodeRespVO::getName).toList());
        assertEquals(List.of(2L, 3L), byLayer.getLayerIds());
        assertEquals(2L, layerDomain.getLayerId()); assertEquals(5L, layerDomain.getDataDomainId());
        assertEquals("DIMENSION", layerDomain.getTableType());
        var sourceChild = byLayer.getChildren().get(1).getChildren().getFirst();
        assertEquals("SOURCE_SYSTEM", sourceChild.getType()); assertEquals("SOURCE", sourceChild.getTableType());
        assertEquals(3L, sourceChild.getLayerId()); assertEquals(8L, sourceChild.getSourceSystemId());
        assertNotEquals(result.getFirst().getChildren().getFirst().getKey(), layerDomain.getKey());
        assertEquals(8L, result.get(2).getChildren().getFirst().getSourceSystemId());
    }

    @Test void logsResolveTargetAndOperatorWithoutConfusingModelCreator() {
        var request = new ModelMaterializedLogPageReqVO();
        var log = ModelMaterializedLogDO.builder().id(50L).modelId(2L).materializedId(10L).operator("3")
                .attemptId(100L).modelName("original_model").modelAlias("原始模型").datasourceId(7L)
                .datasourceName("发布时目标").datasourceType("POSTGRESQL").description("原始发布说明")
                .startTime(LocalDateTime.of(2026, 10, 20, 10, 0)).endTime(LocalDateTime.of(2026, 10, 20, 10, 1)).build();
        when(logMapper.selectPage(request)).thenReturn(new PageResult<>(List.of(log), 1L));
        when(adminUserApi.getUserMap(Set.of(3L))).thenReturn(Map.of(3L, user(3L, "执行人")));

        var result = service.getLogPage(request).getList().getFirst();

        assertEquals("发布时目标", result.getDatasourceName()); assertEquals(7L, result.getDatasourceId());
        assertEquals(100L, result.getAttemptId()); assertEquals("original_model", result.getModelName());
        assertEquals("原始发布说明", result.getDescription());
        assertEquals("执行人", result.getOperatorName()); assertEquals(60_000L, result.getPublishDuration());
        verifyNoInteractions(materializedMapper);
    }

    @Test void historicalLogWithoutSnapshotDoesNotBorrowCurrentMaterializedFields() {
        var request = new ModelMaterializedLogPageReqVO();
        var log = ModelMaterializedLogDO.builder().id(49L).modelId(2L).materializedId(10L).build();
        when(logMapper.selectPage(request)).thenReturn(new PageResult<>(List.of(log), 1L));

        var result = service.getLogPage(request).getList().getFirst();

        assertNull(result.getAttemptId()); assertNull(result.getModelName()); assertNull(result.getModelAlias());
        assertNull(result.getDatasourceId()); assertNull(result.getDatasourceName()); assertNull(result.getDatasourceType());
        assertNull(result.getDescription());
        verifyNoInteractions(materializedMapper, deploymentAttemptMapper, adminUserApi);
    }

    private LogicModelDO model(Long id) {
        return LogicModelDO.builder().id(id).name("测试模型").code("test_model").tableCase(2).build();
    }

    private ModelMaterializedDO target(Long id, Long modelId, Long datasourceId, String datasourceName) {
        return ModelMaterializedDO.builder().id(id).modelId(modelId).datasourceId(datasourceId)
                .datasourceName(datasourceName).modelName("test_model").modelAlias("测试模型").status("SUCCESS").build();
    }

    private ModelFieldItem field(String name) {
        var result = new ModelFieldItem(); result.setFieldName(name); result.setDataType("bigint"); return result;
    }

    private AdminUserRespDTO user(Long id, String name) {
        var result = new AdminUserRespDTO(); result.setId(id); result.setNickname(name); return result;
    }
}
