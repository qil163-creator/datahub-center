package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.LogicRelationSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelRelationFieldPair;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicRelationDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicRelationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.type.TypeReference;

import java.util.List;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_DESIGN_REVISION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedModelRelationTest {
    @Mock private LogicModelMapper modelMapper;
    @Mock private LogicModelColumnService columnService;
    @Mock private LogicRelationMapper relationMapper;
    @InjectMocks private LogicModelServiceImpl service;

    @BeforeAll
    static void configureMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LogicModelDO.class);
    }

    @Test
    void compositeRelationUsesStableUidsAfterRenameAndLocksEndpointsInIdOrder() {
        when(modelMapper.selectByIdForUpdate(1L)).thenReturn(model(1L));
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(model(2L));
        when(columnService.listByModelId(2L)).thenReturn(List.of(
                field("source-station", "renamed_station"), field("source-day", "business_day")));
        when(columnService.listByModelId(1L)).thenReturn(List.of(
                field("target-station", "station_id"), field("target-day", "record_day")));
        when(relationMapper.selectListByModelId(2L)).thenReturn(List.of());
        doAnswer(invocation -> {
            LogicRelationDO saved = invocation.getArgument(0);
            saved.setId(10L);
            assertThat(saved.getSourceField()).isEqualTo("renamed_station");
            assertThat(saved.getTargetField()).isEqualTo("station_id");
            assertThat(JsonUtils.parseObject(saved.getFieldPairs(), new TypeReference<List<ModelRelationFieldPair>>() {}))
                    .containsExactlyElementsOf(request().getFieldPairs());
            return 1;
        }).when(relationMapper).insert(any(LogicRelationDO.class));

        assertThat(service.saveRelation(request())).isEqualTo(10L);
        var locks = inOrder(modelMapper);
        locks.verify(modelMapper).selectByIdForUpdate(1L);
        locks.verify(modelMapper).selectByIdForUpdate(2L);
        verify(modelMapper, times(2)).update(isNull(), any());
    }

    @Test
    void staleEndpointRevisionRejectsBeforeReadingOrWritingRelations() {
        LogicModelDO changed = model(2L);
        changed.setRevision(1L);
        when(modelMapper.selectByIdForUpdate(1L)).thenReturn(model(1L));
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(changed);

        ServiceException failure = assertThrows(ServiceException.class, () -> service.saveRelation(request()));
        assertThat(failure.getCode()).isEqualTo(MODEL_DESIGN_REVISION_CONFLICT.getCode());
        verifyNoInteractions(relationMapper);
        verify(modelMapper, never()).update(isNull(), any());
    }

    private static LogicModelDO model(long id) {
        return LogicModelDO.builder().id(id).revision(0L)
                .status(LogicModelDO.STATUS_DRAFT).build();
    }

    private static ModelFieldItem field(String uid, String name) {
        ModelFieldItem field = new ModelFieldItem();
        field.setFieldUid(uid);
        field.setFieldName(name);
        field.setDataType("VARCHAR");
        return field;
    }

    private static LogicRelationSaveReqVO request() {
        LogicRelationSaveReqVO request = new LogicRelationSaveReqVO();
        request.setSourceModelId(2L);
        request.setTargetModelId(1L);
        request.setSourceRevision(0L);
        request.setTargetRevision(0L);
        request.setSourceField("old_station");
        request.setTargetField("station_id");
        request.setRelationType(LogicRelationDO.TYPE_ONE_TO_MANY);
        ModelRelationFieldPair station = new ModelRelationFieldPair();
        station.setSourceFieldUid("source-station");
        station.setTargetFieldUid("target-station");
        ModelRelationFieldPair day = new ModelRelationFieldPair();
        day.setSourceFieldUid("source-day");
        day.setTargetFieldUid("target-day");
        request.setFieldPairs(List.of(station, day));
        return request;
    }
}
