package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.LogicModelCreateReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 领域实体绑定、稳定汇总字段与客户端伪造自动字段的边界。 */
@ExtendWith(MockitoExtension.class)
class LogicModelDefinitionServiceTest {
    @Mock private BusinessEntityMapper entities;
    @Mock private IndicatorModelMapper indicators;
    @Mock private cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelService indicatorService;
    @Mock private LogicModelMapper models;
    @Mock private LogicModelColumnService columns;
    @InjectMocks private LogicModelDefinitionService service;

    @BeforeAll static void mapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IndicatorModelDO.class);
    }

    @Test void bindsEntityWithinTheSelectedSubjectAndDerivesItsType() {
        var entity = entity(); entity.setEntitySubtype("HIERARCHICAL");
        when(entities.selectById(7L)).thenReturn(entity);
        LogicModelCreateReqVO request = request("DIMENSION"); request.setEntityId(7L); request.setObjType("forged");
        service.normalize(request);
        assertThat(request.getObjType()).isEqualTo("2");
        assertThat(request.getTableNameNotation()).isEqualTo("DIM_STATION");
        request.setSubjectDomainId(99L);
        assertThrows(ServiceException.class, () -> service.normalize(request));
    }

    @Test void summaryUsesStableTimeAndGrainFieldsAndOnlyMatchingDerivedIndicators() {
        when(entities.selectById(7L)).thenReturn(entity());
        IndicatorModelDO matched = indicator(1L, "DAY");
        IndicatorModelDO wrongTime = indicator(2L, "HOUR");
        when(indicators.selectList(any())).thenReturn(List.of(matched, wrongTime));
        when(models.selectByIds(any())).thenReturn(List.of(sourceModel()));
        when(columns.listByModelIds(any())).thenReturn(java.util.Map.of(11L, sourceFields(false)));
        var atomic = atomic();
        when(indicatorService.isAtomicSourceUsable(any())).thenReturn(true);
        when(indicators.selectByIds(any())).thenReturn(List.of(atomic));
        List<ModelFieldItem> first = service.summaryFields(1L, 2L, "1", List.of(7L));
        List<ModelFieldItem> second = service.summaryFields(1L, 2L, "1", List.of(7L));
        assertThat(first).extracting(ModelFieldItem::getFieldName).containsExactly("ds", "station_id", "metric_1");
        assertThat(first).extracting(ModelFieldItem::getFieldCategory).containsExactly("TIME", "GRAIN", "INDICATOR");
        assertThat(first).extracting(ModelFieldItem::getFieldUid).containsExactlyElementsOf(second.stream().map(ModelFieldItem::getFieldUid).toList());
        assertThat(first.get(1).getDimensionId()).isEqualTo(7L);
        assertThat(first.get(2).getSourceModelName()).isEqualTo("测站事实表当前设计");
        assertThat(first.get(2).getAtomicIndicatorName()).isEqualTo("降雨量");
        assertThat(first.get(2).getBusinessCondition()).isEqualTo("有效测站");
        assertThat(first.get(2).getStatisticsPeriod()).isEqualTo("DAY");
        assertThat(first.get(2).getComment()).isEqualTo("SUM(rain)");
        assertThat(first.get(2).getRemark()).isEqualTo("日降雨指标");
    }

    @Test void summarySaveRecomputesFieldsAndGrainNames() {
        when(entities.selectById(7L)).thenReturn(entity());
        when(indicators.selectList(any())).thenReturn(List.of());
        LogicModelCreateReqVO request = request("SUMMARY"); request.setGrainEntityIds(List.of(7L, 7L));
        request.setStatisticsStr("伪造粒度"); request.setDataTimeliness("1"); request.setFields(List.of(new ModelFieldItem()));
        service.normalize(request);
        assertThat(request.getStatisticsStr()).isEqualTo("测站");
        assertThat(request.getGrainEntityIds()).containsExactly(7L);
        assertThat(request.getFields()).extracting(ModelFieldItem::getFieldName).containsExactly("ds", "station_id");
    }

    @Test void summaryDoesNotBroadenASourceWithAdditionalDimensionsToASmallerGrain() {
        when(entities.selectById(7L)).thenReturn(entity());
        when(indicators.selectList(any())).thenReturn(List.of(indicator(1L, "DAY")));
        when(indicators.selectByIds(any())).thenReturn(List.of(atomic()));
        when(models.selectByIds(any())).thenReturn(List.of(sourceModel()));
        when(columns.listByModelIds(any())).thenReturn(java.util.Map.of(11L, sourceFields(true)));
        assertThat(service.summaryFields(1L, 2L, "1", List.of(7L))).extracting(ModelFieldItem::getFieldName)
                .containsExactly("ds", "station_id");
    }

    @Test void summaryRejectsDisabledActivitiesAndHierarchicalObjects() {
        var entity = entity(); when(entities.selectById(7L)).thenReturn(entity);
        entity.setStatus(1);
        assertThrows(ServiceException.class, () -> service.summaryFields(1L, 2L, "1", List.of(7L)));
        entity.setStatus(0); entity.setEntityType("ACTIVITY"); entity.setEntitySubtype("EVENT");
        assertThrows(ServiceException.class, () -> service.summaryFields(1L, 2L, "1", List.of(7L)));
        entity.setEntityType("OBJECT"); entity.setEntitySubtype("HIERARCHICAL");
        assertThrows(ServiceException.class, () -> service.summaryFields(1L, 2L, "1", List.of(7L)));
    }

    @Test void rejectsDuplicateEntityAndTimelinessBeforeSaving() {
        when(entities.selectById(7L)).thenReturn(entity()); when(models.selectCount(any())).thenReturn(1L);
        var request = request("DIMENSION"); request.setEntityId(7L); request.setDataTimeliness("1");
        assertThrows(ServiceException.class, () -> service.normalize(request));
    }

    @Test void missingGrainListCannotBypassAutomaticTimeFieldGeneration() {
        var request = request("SUMMARY"); request.setDataTimeliness("1"); request.setFields(List.of(new ModelFieldItem()));
        service.normalize(request);
        assertThat(request.getFields()).extracting(ModelFieldItem::getFieldName).containsExactly("ds");
    }

    @Test void taskInputCapturesOnlyRequiredDesignAndIsDetachedFromLaterEdits() {
        var model = sourceModel(); model.setId(9007199254740993L); model.setName("原任务名称");
        model.setOwnerPhone("不应进入任务输入的联系信息");
        String task = ModelDeploymentAttemptStore.taskModelInput(model);
        model.setName("后续修改");
        var restored = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(task, LogicModelDO.class);
        assertThat(restored.getId()).isEqualTo(9007199254740993L);
        assertThat(restored.getName()).isEqualTo("原任务名称");
        assertThat(restored.getOwnerPhone()).isNull();
    }

    private static LogicModelDO sourceModel() {
        return LogicModelDO.builder().id(11L).enabled(1).status("PUBLISHED").name("测站事实表当前设计")
                .dataDomainId(1L).subjectDomainId(2L).entityId(7L).dataTimeliness("1").build();
    }

    private static List<ModelFieldItem> sourceFields(boolean additional) {
        var station = new ModelFieldItem(); station.setFieldUid("value"); station.setFieldName("station_id");
        station.setDataType("VARCHAR"); station.setDimensionId(7L);
        var region = new ModelFieldItem(); region.setFieldUid("region"); region.setDimensionId(8L);
        return additional ? List.of(station, region) : List.of(station);
    }

    private static LogicModelCreateReqVO request(String type) {
        var request = new LogicModelCreateReqVO(); request.setDataDomainId(1L); request.setSubjectDomainId(2L);
        request.setTableType(type); request.setCode("station"); return request;
    }
    private static BusinessEntityDO entity() {
        var entity = new BusinessEntityDO(); entity.setId(7L); entity.setDataDomainId(1L); entity.setSubjectDomainId(2L);
        entity.setCode("STATION"); entity.setName("测站"); entity.setEntityType("OBJECT"); entity.setEntitySubtype("NORMAL"); entity.setStatus(0); return entity;
    }
    private static IndicatorModelDO indicator(Long id, String freshness) {
        var row = new IndicatorModelDO(); row.setId(id); row.setIndicatorType("DERIVED"); row.setCode("metric_" + id);
        row.setName("指标" + id); row.setDataFreshness(freshness); row.setResultDataType("DECIMAL");
        row.setBaseIndicatorId(30L); row.setBusinessCondition("有效测站"); row.setTimePeriod("DAY");
        row.setDefinition("SUM(rain)"); row.setDescription("日降雨指标"); return row;
    }
    private static IndicatorModelDO atomic() {
        var row = new IndicatorModelDO(); row.setId(30L); row.setName("降雨量"); row.setIndicatorType("ATOMIC");
        row.setDataDomainId(1L); row.setStatus("ACTIVE"); row.setDataFreshness("1");
        row.setSourceModelId(11L); row.setValueFieldUid("value"); row.setEntityId(7L); row.setSubjectDomainId(2L); return row;
    }
}
