package cn.iocoder.yudao.module.governance.service.standardcodeitem;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper;
import cn.iocoder.yudao.module.governance.service.standardcodeitem.StandardCodeItemServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StandardCodeItemServiceTest {

    @Mock
    private StandardCodeItemMapper itemMapper;
    @Mock
    private StandardCodeSetMapper codeSetMapper;
    @Mock
    private StandardCodeMappingMapper valueMappings;

    private StandardCodeItemServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        service = new StandardCodeItemServiceImpl(itemMapper, codeSetMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "valueMappings", valueMappings);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createShouldResolveLegacyDictTypeToStableCodeSetId() {
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        when(codeSetMapper.selectByType("COUNTRY")).thenReturn(codeSet);
        when(codeSetMapper.selectByIdForUpdate(7)).thenReturn(codeSet);
        when(itemMapper.selectByCodeSetIdAndValue(7, "CN")).thenReturn(null);
        doAnswer(invocation -> {
            StandardCodeItemDO item = invocation.getArgument(0);
            item.setId(11);
            return 1;
        }).when(itemMapper).insert(any(StandardCodeItemDO.class));

        StandardCodeItemSaveReqVO req = item(null, "COUNTRY", "CN");
        Integer id = service.createStandardCodeItem(req);

        assertThat(id).isEqualTo(11);
        verify(itemMapper).insert(argThat((StandardCodeItemDO inserted) ->
                inserted.getCodeSetId().equals(7)
                        && inserted.getDictType().equals("COUNTRY")));
    }

    @Test
    void createShouldRejectConflictingCodeSetIdAndDictType() {
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        when(codeSetMapper.selectById(7)).thenReturn(codeSet);
        when(codeSetMapper.selectByType("CURRENCY"))
                .thenReturn(StandardCodeSetDO.builder().id(8).type("CURRENCY").build());

        assertThatThrownBy(() -> service.createStandardCodeItem(item(7, "CURRENCY", "CN")))
                .hasMessageContaining("不一致");
        verifyNoInteractions(itemMapper);
    }

    @Test
    void updateShouldPersistTheCodeItem() {
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        StandardCodeItemDO current = StandardCodeItemDO.builder()
                .id(10).codeSetId(7).dictType("COUNTRY").value("CN").build();
        when(itemMapper.selectById(10)).thenReturn(current);
        when(codeSetMapper.selectById(7)).thenReturn(codeSet);
        when(codeSetMapper.selectByIdForUpdate(7)).thenReturn(codeSet);
        when(itemMapper.selectByIdForUpdate(10)).thenReturn(current);
        when(itemMapper.selectByCodeSetIdAndValue(7, "CN")).thenReturn(current);
        StandardCodeItemSaveReqVO req = item(7, null, "CN");
        req.setId(10);
        req.setLabel("中国");

        service.updateStandardCodeItem(req);

        verify(itemMapper).updateById(argThat((StandardCodeItemDO updated) ->
                updated.getId().equals(10) && updated.getLabel().equals("中国")
                        && updated.getCodeSetId().equals(7)));
    }

    @Test
    void deleteShouldRejectCodeItemUsedByMapping() {
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        StandardCodeItemDO current = StandardCodeItemDO.builder().id(10).codeSetId(7).build();
        when(itemMapper.selectById(10)).thenReturn(current);
        when(codeSetMapper.selectById(7)).thenReturn(codeSet);
        when(codeSetMapper.selectByIdForUpdate(7)).thenReturn(codeSet);
        when(itemMapper.selectByIdForUpdate(10)).thenReturn(current);
        when(valueMappings.selectCount(any(), eq(10))).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteStandardCodeItem(10)).hasMessageContaining("引用");
        verify(itemMapper, never()).deleteById(any(Integer.class));
    }

    private static StandardCodeItemSaveReqVO item(Integer codeSetId, String dictType,
                                                   String value) {
        StandardCodeItemSaveReqVO req = new StandardCodeItemSaveReqVO();
        req.setCodeSetId(codeSetId);
        req.setDictType(dictType);
        req.setValue(value);
        req.setLabel(value);
        req.setSort(1);
        req.setStatus((short) 0);
        return req;
    }
}
