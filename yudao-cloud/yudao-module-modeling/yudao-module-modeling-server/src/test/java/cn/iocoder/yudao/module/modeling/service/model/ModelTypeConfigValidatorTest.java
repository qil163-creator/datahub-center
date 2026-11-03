package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专属配置：草稿可不完整，发布按表类型要求最低完整性，引用的字段必须存在。
 */
class ModelTypeConfigValidatorTest {

    private final ModelTypeConfigValidator validator = new ModelTypeConfigValidator();

    private ModelFieldItem field(String name) {
        ModelFieldItem item = new ModelFieldItem();
        item.setFieldName(name);
        item.setDataType("varchar");
        return item;
    }

    private final List<ModelFieldItem> fields = List.of(
            field("event_id"), field("district_code"), field("accept_time"), field("accept_date"));

    private ServiceException publishError(String tableType, ModelTypeConfigVO config) {
        return assertThrows(ServiceException.class,
                () -> validator.validateOnPublish(tableType, config, fields));
    }

    @Test
    void publish_shouldIgnoreUnknownOrBlankTableType() {
        assertDoesNotThrow(() -> validator.validateOnPublish(null, null, fields));
        assertDoesNotThrow(() -> validator.validateOnPublish("WHATEVER", null, fields));
    }

    @Test
    void publish_detail_shouldListAllMissingItemsWhenConfigAbsent() {
        ServiceException ex = publishError("DETAIL", null);
        assertEquals(MODEL_TYPE_CONFIG_INCOMPLETE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("明细表"));
        assertTrue(ex.getMessage().contains("粒度描述"));
        assertTrue(ex.getMessage().contains("业务时间字段"));
    }

    @Test
    void publish_detail_shouldPassWhenComplete() {
        ModelTypeConfigVO config = new ModelTypeConfigVO();
        ModelTypeConfigVO.DetailConfig detail = new ModelTypeConfigVO.DetailConfig();
        detail.setOrganizationMode("TRANSACTION");
        detail.setGrainDescription("一个去重后的受理事件");
        detail.setGrainFields(List.of("event_id"));
        detail.setBusinessTimeField("accept_time");
        config.setDetail(detail);

        assertDoesNotThrow(() -> validator.validateOnPublish("DETAIL", config, fields));
    }

    @Test
    void publish_shouldRejectReferenceToMissingField() {
        ModelTypeConfigVO config = new ModelTypeConfigVO();
        ModelTypeConfigVO.DetailConfig detail = new ModelTypeConfigVO.DetailConfig();
        detail.setOrganizationMode("TRANSACTION");
        detail.setGrainDescription("一个受理事件");
        detail.setGrainFields(List.of("Event_ID"));
        // 字段列表里没有 not_exists
        detail.setBusinessTimeField("not_exists");
        config.setDetail(detail);

        ServiceException ex = publishError("DETAIL", config);
        assertEquals(MODEL_TYPE_CONFIG_FIELD_NOT_EXISTS.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("not_exists"));
        // 大小写不同不算缺失
        assertTrue(!ex.getMessage().contains("event_id"));
    }

    @Test
    void publish_summary_shouldRequireStaleGroupStrategyForOverwriteRange() {
        ModelTypeConfigVO config = new ModelTypeConfigVO();
        ModelTypeConfigVO.SummaryConfig summary = new ModelTypeConfigVO.SummaryConfig();
        summary.setGrainFields(List.of("district_code", "accept_date"));
        summary.setTimeGranularity("DAY");
        summary.setBusinessTimeField("受理时间");
        summary.setMeasureDefinition("去重后的有效受理事件数");
        summary.setWriteMode("OVERWRITE_RANGE");
        config.setSummary(summary);

        ServiceException ex = publishError("SUMMARY", config);
        assertEquals(MODEL_TYPE_CONFIG_INCOMPLETE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("失效分组"));

        summary.setStaleGroupStrategy("DELETE");
        assertDoesNotThrow(() -> validator.validateOnPublish("SUMMARY", config, fields));
    }

    @Test
    void publish_dimension_shouldRequireEffectiveFieldsForScd2() {
        ModelTypeConfigVO config = new ModelTypeConfigVO();
        ModelTypeConfigVO.DimensionConfig dimension = new ModelTypeConfigVO.DimensionConfig();
        dimension.setBusinessKeyFields(List.of("district_code"));
        dimension.setHistoryStrategy("SCD2");
        config.setDimension(dimension);

        ServiceException ex = publishError("DIMENSION", config);
        assertEquals(MODEL_TYPE_CONFIG_INCOMPLETE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("生效时间字段"));

        dimension.setHistoryStrategy("OVERWRITE");
        assertDoesNotThrow(() -> validator.validateOnPublish("DIMENSION", config, fields));
    }

    @Test
    void publish_source_shouldRequireEventSequenceForCdc() {
        ModelTypeConfigVO config = new ModelTypeConfigVO();
        ModelTypeConfigVO.SourceConfig source = new ModelTypeConfigVO.SourceConfig();
        source.setIngestMode("CDC_EVENT");
        source.setSourceKeyFields(List.of("event_id"));
        config.setSource(source);

        ServiceException ex = publishError("SOURCE", config);
        assertEquals(MODEL_TYPE_CONFIG_INCOMPLETE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("事件序列"));
    }

    @Test
    void publish_application_shouldRequireSceneAndGrain() {
        ServiceException ex = publishError("APPLICATION", new ModelTypeConfigVO());
        assertEquals(MODEL_TYPE_CONFIG_INCOMPLETE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("消费场景"));
    }

    @Test
    void save_shouldOnlyRejectIllegalEnumValues() {
        ModelTypeConfigVO config = new ModelTypeConfigVO();
        ModelTypeConfigVO.DetailConfig detail = new ModelTypeConfigVO.DetailConfig();
        // 草稿允许什么都不填
        config.setDetail(detail);
        assertDoesNotThrow(() -> validator.validateOnSave(config));
        assertDoesNotThrow(() -> validator.validateOnSave(null));

        detail.setOrganizationMode("SOMETHING_ELSE");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validateOnSave(config));
        assertEquals(MODEL_TYPE_CONFIG_INVALID_VALUE.getCode(), ex.getCode());
    }

}
