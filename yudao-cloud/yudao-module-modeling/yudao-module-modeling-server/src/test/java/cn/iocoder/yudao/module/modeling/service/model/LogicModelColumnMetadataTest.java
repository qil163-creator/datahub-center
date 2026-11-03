package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelColumnDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelColumnMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 字段设计元数据写入后重新读取不能丢失；大编号保持 Long。 */
@ExtendWith(MockitoExtension.class)
class LogicModelColumnMetadataTest {
    @Mock private LogicModelColumnMapper columnMapper;
    @InjectMocks private LogicModelColumnServiceImpl service;

    @Test void metadataSurvivesFieldTableRoundTrip() {
        ModelFieldItem field = new ModelFieldItem(); field.setFieldUid("stable-field"); field.setFieldName("station_code");
        field.setDataType("VARCHAR"); field.setFieldCategory("DIMENSION"); field.setDimensionId(9007199254740993L);
        field.setDimensionName("测站"); field.setDataClassificationId(9007199254740995L);
        field.setDataClassificationName("公开水情"); field.setDataGrade("PUBLIC");
        field.setCodeTableId(9007199254740997L); field.setCodeTableName("测站类型"); field.setUniqueFlag(true);
        field.setIndicatorId(9007199254740999L); field.setAtomicIndicatorName("降雨量");
        field.setBusinessCondition("有效测站"); field.setStatisticsPeriod("DAY"); field.setSourceModelName("测站发布快照");
        service.replaceByModelId(1L, List.of(field));
        var saved = ArgumentCaptor.forClass(LogicModelColumnDO.class); verify(columnMapper).insert(saved.capture());
        when(columnMapper.selectListByModelId(1L)).thenReturn(List.of(saved.getValue()));
        ModelFieldItem reloaded = service.listByModelId(1L).getFirst();
        assertThat(reloaded).usingRecursiveComparison().ignoringFields("sort", "pkFlag", "nullableFlag").isEqualTo(field);
    }
}
