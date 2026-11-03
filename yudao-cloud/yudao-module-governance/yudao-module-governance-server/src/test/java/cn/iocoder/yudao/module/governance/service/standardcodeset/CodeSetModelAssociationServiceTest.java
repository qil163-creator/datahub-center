package cn.iocoder.yudao.module.governance.service.standardcodeset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.modeling.api.model.ModelFieldApi;
import cn.iocoder.yudao.module.modeling.api.model.dto.ModelStandardFieldRespDTO;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeSetModelAssociationServiceTest {
    private final StandardCodeSetMapper codeSets = mock(StandardCodeSetMapper.class);
    private final StandardElementMapper elements = mock(StandardElementMapper.class);
    private final ModelFieldApi models = mock(ModelFieldApi.class);
    private final CodeSetModelAssociationService service = new CodeSetModelAssociationService(codeSets, elements, models);

    @Test void filtersOnlyFieldsOfElementsActuallyBoundToCodeSet() {
        when(codeSets.selectById(7)).thenReturn(StandardCodeSetDO.builder().id(7).build());
        when(elements.selectList(any(SFunction.class), eq(7))).thenReturn(List.of(StandardElementDO.builder().id(10).build()));
        var related = field(10L);
        var direct = field(12L);
        when(models.getFieldListByCodeTableId(7L)).thenReturn(CommonResult.success(List.of(direct, related)));
        when(models.getStandardBoundFieldList()).thenReturn(CommonResult.success(List.of(related, field(11L))));
        assertEquals(List.of(direct, related), service.getFields(7));
    }

    @Test void emptyAndFailuresAreDistinguished() {
        assertThrows(ServiceException.class, () -> service.getFields(7));
        when(codeSets.selectById(7)).thenReturn(StandardCodeSetDO.builder().id(7).build());
        when(elements.selectList(any(SFunction.class), eq(7))).thenReturn(List.of());
        when(models.getFieldListByCodeTableId(7L)).thenReturn(CommonResult.success(List.of()));
        assertTrue(service.getFields(7).isEmpty());
        verify(models, never()).getStandardBoundFieldList();
        when(elements.selectList(any(SFunction.class), eq(7))).thenReturn(List.of(StandardElementDO.builder().id(10).build()));
        when(models.getStandardBoundFieldList()).thenThrow(new IllegalStateException("建模服务不可用"));
        assertThrows(IllegalStateException.class, () -> service.getFields(7));
    }

    private static ModelStandardFieldRespDTO field(Long standardId) {
        var field = new ModelStandardFieldRespDTO();
        field.setStandardId(standardId);
        field.setModelId(1L);
        field.setFieldUid(standardId.toString());
        return field;
    }
}
