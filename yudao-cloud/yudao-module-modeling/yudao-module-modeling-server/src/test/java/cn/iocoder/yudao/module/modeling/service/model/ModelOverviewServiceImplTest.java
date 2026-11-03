package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer;
import cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.model.ModelOverviewController;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelOverviewRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelOverviewMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelOverviewServiceImplTest {

    @Mock private DataDomainMapper dataDomainMapper;
    @Mock private SubjectDomainMapper subjectDomainMapper;
    @Mock private BizCategoryMapper bizCategoryMapper;
    @Mock private LogicModelMapper logicModelMapper;
    @Mock private ModelOverviewMapper overviewMapper;
    @Mock private SecurityFrameworkService securityFrameworkService;
    @InjectMocks private ModelOverviewServiceImpl service;

    @BeforeAll
    static void tableMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataDomainDO.class);
        TableInfoHelper.initTableInfo(assistant, SubjectDomainDO.class);
        TableInfoHelper.initTableInfo(assistant, LogicModelDO.class);
    }

    @Test
    void noResourcePermissionDoesNotQueryOrExposeAnyModelData() {
        var response = service.getModelOverview();

        verifyNoInteractions(dataDomainMapper, subjectDomainMapper, bizCategoryMapper, logicModelMapper, overviewMapper);
        assertEquals(6, response.getMetrics().size());
        assertTrue(response.getMetrics().stream().allMatch(item -> !item.isAvailable() && item.getCount() == null));
        assertFalse(response.isLayerDistributionAvailable());
        assertFalse(response.isMaterializedDistributionAvailable());
        assertFalse(response.isRecentActivitiesAvailable());
        assertTrue(response.getRecentActivities().isEmpty());
        // 旧的统计面板保留数值契约，新页面根据 available 区分无权限与真实零值。
        assertEquals(0L, response.getModelCount());
        assertEquals(0L, response.getDataDomainCount());
        assertEquals(0L, response.getMaterializedCount());
    }

    @Test
    void planningOnlyPermissionCannotReadModelNamesOrDistribution() {
        grantPermissions("modeling:data-domain:query");
        when(dataDomainMapper.selectCount()).thenReturn(5L);
        when(dataDomainMapper.selectCount(any(LambdaQueryWrapperX.class))).thenReturn(2L);

        var response = service.getModelOverview();

        assertEquals(5L, response.getDataDomainCount());
        assertEquals(2L, response.getMetrics().getFirst().getRecentCount());
        assertTrue(response.getMetrics().getFirst().isAvailable());
        verifyNoInteractions(subjectDomainMapper, logicModelMapper, overviewMapper);
    }

    @Test
    void modelPermissionDoesNotGrantPhysicalDeploymentVisibility() {
        grantPermissions("modeling:model:query");
        when(logicModelMapper.selectCountByStatusNe("DISCARDED")).thenReturn(7L);
        when(logicModelMapper.selectCountByStatus("PUBLISHED")).thenReturn(3L);
        when(logicModelMapper.selectCount(any(LambdaQueryWrapperX.class))).thenReturn(2L);
        when(overviewMapper.selectRecentlyPublishedCount(any(), any())).thenReturn(1L);
        when(overviewMapper.selectLayerDistribution()).thenReturn(List.of(layer("DWD", 7)));

        var response = service.getModelOverview();

        assertEquals(7L, response.getModelCount());
        assertEquals(3L, response.getPublishedCount());
        assertTrue(response.isLayerDistributionAvailable());
        assertFalse(response.isMaterializedDistributionAvailable());
        verify(overviewMapper, never()).selectMaterializedDistribution();
        verify(overviewMapper).selectRecentlyPublishedCount(response.getGeneratedAt().minusDays(7), response.getGeneratedAt());
        assertNull(response.getMetrics().get(2).getCount());
        assertEquals("暂无查询权限", response.getMetrics().get(4).getDescription());
    }

    @Test
    void materializationOnlyReaderCanSeePhysicalDistributionWithoutLogicalModelsOrActivities() {
        grantPermissions("modeling:model-materialized:query");
        when(overviewMapper.selectMaterializedDistribution()).thenReturn(List.of(layer("DIM", 2)));

        var response = service.getModelOverview();

        assertTrue(response.isMaterializedDistributionAvailable());
        assertEquals(2L, response.getMaterializedCount());
        assertEquals(2L, response.getDimensionCount());
        assertFalse(response.isLayerDistributionAvailable());
        assertFalse(response.isRecentActivitiesAvailable());
        assertTrue(response.getRecentActivities().isEmpty());
        assertTrue(response.getMetrics().stream().allMatch(metric -> !metric.isAvailable() && metric.getCount() == null));
        verifyNoInteractions(logicModelMapper, dataDomainMapper, subjectDomainMapper, bizCategoryMapper);
        verify(overviewMapper, never()).selectLayerDistribution();
        verify(overviewMapper, never()).selectRecentActivities();
        verify(overviewMapper, never()).selectRecentlyPublishedCount(any(), any());
    }

    @Test
    void materializedCountersUseSuccessfulTargetsIncludingCustomAndUnassignedLayers() {
        grantPermissions("modeling:model:query", "modeling:model-materialized:query");
        when(overviewMapper.selectMaterializedDistribution()).thenReturn(List.of(
                layer("DWD", 4), layer("DIM", 2), layer("CUSTOM", 3), layer(null, 1)));
        var event = new ModelOverviewMapper.RecentActivity();
        event.setId("publish-9007199254740993");
        event.setModelId(9007199254740993L);
        event.setType("PUBLISH");
        event.setModelName("客户");
        event.setOccurredAt(LocalDateTime.of(2026, 10, 14, 10, 0));
        when(overviewMapper.selectRecentActivities()).thenReturn(List.of(event));

        var response = service.getModelOverview();

        assertEquals(10L, response.getMaterializedCount());
        assertEquals(4L, response.getFactCount());
        assertEquals(2L, response.getDimensionCount());
        assertEquals("发布「客户」逻辑模型", response.getRecentActivities().getFirst().getTitle());
        assertEquals(event.getModelId(), response.getRecentActivities().getFirst().getModelId());
        assertEquals(event.getOccurredAt(), response.getRecentActivities().getFirst().getOccurredAt());
    }

    @Test
    void endpointRequiresAnExistingResourceQueryPermission() throws Exception {
        String expression = ModelOverviewController.class.getMethod("getModelOverview")
                .getAnnotation(PreAuthorize.class).value();
        assertTrue(expression.contains("hasAnyPermissions"));
        assertTrue(expression.contains("modeling:model:query"));
        assertTrue(expression.contains("modeling:subject-domain:query"));
    }

    @Test
    void wirePreservesUnavailableCountsLargeModelIdsAndTimestampDates() {
        var serializers = new SimpleModule()
                .addSerializer(Long.class, NumberSerializer.INSTANCE)
                .addSerializer(LocalDateTime.class, TimestampLocalDateTimeSerializer.INSTANCE);
        var mapper = JsonMapper.builder().addModule(serializers)
                .changeDefaultPropertyInclusion(value -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)).build();
        var response = service.getModelOverview();
        var activity = new ModelOverviewRespVO.Activity();
        activity.setModelId(9007199254740993L);
        activity.setOccurredAt(LocalDateTime.of(2026, 10, 14, 10, 0));
        response.setRecentActivities(List.of(activity));

        var wire = mapper.readTree(mapper.writeValueAsString(response));

        assertTrue(wire.get("metrics").get(0).get("count").isNull());
        assertTrue(wire.get("metrics").get(0).get("recentCount").isNull());
        assertEquals("9007199254740993", wire.get("recentActivities").get(0).get("modelId").asString());
        assertTrue(wire.get("recentActivities").get(0).get("occurredAt").isIntegralNumber());
        assertTrue(wire.get("generatedAt").isIntegralNumber());
        assertTrue(wire.get("modelCount").isIntegralNumber());
    }

    private ModelOverviewMapper.LayerCount layer(String code, long count) {
        var row = new ModelOverviewMapper.LayerCount();
        row.setKey(code == null ? "unassigned" : code);
        row.setCode(code);
        row.setName(code == null ? "未分层" : code);
        row.setCount(count);
        return row;
    }

    @Test
    void conceptAndIndicatorPermissionsExposeOnlyTheirOwnRealCounts() {
        grantPermissions("modeling:concept-model:query", "modeling:indicator-model:query");
        var concepts = new ModelOverviewMapper.ModelCount();
        concepts.setTotal(4L);
        concepts.setRecent(1L);
        var indicators = new ModelOverviewMapper.ModelCount();
        indicators.setTotal(0L);
        indicators.setRecent(0L);
        when(overviewMapper.selectConceptCounts(any(), any())).thenReturn(concepts);
        when(overviewMapper.selectIndicatorCounts(any(), any())).thenReturn(indicators);
        var response = service.getModelOverview();
        assertEquals(4L, response.getMetrics().get(2).getCount());
        assertEquals(1L, response.getMetrics().get(2).getRecentCount());
        assertEquals(0L, response.getMetrics().get(4).getCount());
        assertTrue(response.getMetrics().get(4).isAvailable());
        assertFalse(response.isRecentActivitiesAvailable());
        verify(overviewMapper, never()).selectRecentActivities();
        verify(overviewMapper).selectConceptCounts(response.getGeneratedAt().minusDays(7), response.getGeneratedAt());
        verifyNoInteractions(logicModelMapper, dataDomainMapper, subjectDomainMapper, bizCategoryMapper);
    }

    private void grantPermissions(String... permissions) {
        when(securityFrameworkService.hasPermission(anyString()))
                .thenAnswer(invocation -> List.of(permissions).contains(invocation.<String>getArgument(0)));
    }
}
