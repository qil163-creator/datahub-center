package cn.iocoder.yudao.module.governance.service.standardcodeitem;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemTreeReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.service.standardcodeitem.StandardCodeItemServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StandardCodeItemHierarchyServiceTest {

    @Mock
    private StandardCodeItemMapper itemMapper;
    @Mock
    private StandardCodeSetMapper codeSetMapper;

    private StandardCodeItemServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        service = new StandardCodeItemServiceImpl(itemMapper, codeSetMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "valueMappings", mock(cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper.class));
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

        StandardCodeItemSaveReqVO req = item(null, "COUNTRY", null, "CN");
        Integer id = service.createStandardCodeItem(req);

        assertThat(id).isEqualTo(11);
        verify(itemMapper).insert(argThat((StandardCodeItemDO inserted) ->
                inserted.getCodeSetId().equals(7)
                        && inserted.getDictType().equals("COUNTRY")
                        && inserted.getLevelNo().equals(0)));
    }

    @Test
    void createShouldRejectConflictingCodeSetIdAndDictType() {
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        when(codeSetMapper.selectById(7)).thenReturn(codeSet);
        when(codeSetMapper.selectByType("CURRENCY"))
                .thenReturn(StandardCodeSetDO.builder().id(8).type("CURRENCY").build());

        assertThatThrownBy(() -> service.createStandardCodeItem(item(7, "CURRENCY", null, "CN")))
                .hasMessageContaining("不一致");
        verifyNoInteractions(itemMapper);
    }

    @Test
    void createShouldRejectParentFromAnotherCodeSet() {
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        when(codeSetMapper.selectById(7)).thenReturn(codeSet);
        when(codeSetMapper.selectByIdForUpdate(7)).thenReturn(codeSet);
        when(itemMapper.selectById(20)).thenReturn(StandardCodeItemDO.builder()
                .id(20).codeSetId(8).dictType("CURRENCY").levelNo(0).build());

        assertThatThrownBy(() -> service.createStandardCodeItem(item(7, null, 20, "CN")))
                .hasMessageContaining("同一码表");
    }

    @Test
    void updateShouldRejectAncestorCycle() {
        StandardCodeItemDO current = StandardCodeItemDO.builder()
                .id(10).codeSetId(7).dictType("COUNTRY").parentId(null).levelNo(0).value("CN").build();
        StandardCodeItemDO child = StandardCodeItemDO.builder()
                .id(11).codeSetId(7).dictType("COUNTRY").parentId(10).levelNo(1).build();
        when(itemMapper.selectById(10)).thenReturn(current);
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        when(codeSetMapper.selectById(7)).thenReturn(codeSet);
        when(codeSetMapper.selectByIdForUpdate(7)).thenReturn(codeSet);
        when(itemMapper.selectByIdForUpdate(10)).thenReturn(current);
        when(itemMapper.selectById(11)).thenReturn(child);

        StandardCodeItemSaveReqVO req = item(7, null, 11, "CN");
        req.setId(10);
        assertThatThrownBy(() -> service.updateStandardCodeItem(req)).hasMessageContaining("循环");
    }

    @Test
    void updateShouldRecalculateDescendantLevelsAfterReparenting() {
        StandardCodeItemDO current = StandardCodeItemDO.builder()
                .id(10).codeSetId(7).dictType("COUNTRY").parentId(null).levelNo(0).value("CN").build();
        StandardCodeItemDO newParent = StandardCodeItemDO.builder()
                .id(20).codeSetId(7).dictType("COUNTRY").parentId(null).levelNo(0).build();
        StandardCodeItemDO child = StandardCodeItemDO.builder()
                .id(11).codeSetId(7).dictType("COUNTRY").parentId(10).levelNo(1).build();
        StandardCodeItemDO grandchild = StandardCodeItemDO.builder()
                .id(12).codeSetId(7).dictType("COUNTRY").parentId(11).levelNo(2).build();
        when(itemMapper.selectById(10)).thenReturn(current);
        StandardCodeSetDO codeSet = StandardCodeSetDO.builder().id(7).type("COUNTRY").build();
        when(codeSetMapper.selectById(7)).thenReturn(codeSet);
        when(codeSetMapper.selectByIdForUpdate(7)).thenReturn(codeSet);
        when(itemMapper.selectByIdForUpdate(10)).thenReturn(current);
        when(itemMapper.selectById(20)).thenReturn(newParent);
        when(itemMapper.selectByCodeSetIdAndValue(7, "CN")).thenReturn(current);
        when(itemMapper.selectListByParentId(10)).thenReturn(List.of(child));
        when(itemMapper.selectListByParentId(11)).thenReturn(List.of(grandchild));
        when(itemMapper.selectListByParentId(12)).thenReturn(List.of());

        StandardCodeItemSaveReqVO req = item(7, null, 20, "CN");
        req.setId(10);
        service.updateStandardCodeItem(req);

        verify(itemMapper).updateById(argThat((StandardCodeItemDO update) ->
                update.getId().equals(10) && update.getLevelNo().equals(1)));
        verify(itemMapper).updateById(argThat((StandardCodeItemDO update) ->
                update.getId().equals(11) && update.getLevelNo().equals(2)));
        verify(itemMapper).updateById(argThat((StandardCodeItemDO update) ->
                update.getId().equals(12) && update.getLevelNo().equals(3)));
    }

    @Test
    void deleteShouldRejectItemWithChildren() {
        StandardCodeItemDO item = StandardCodeItemDO.builder().id(10).codeSetId(7).build();
        when(itemMapper.selectById(10)).thenReturn(item);
        when(codeSetMapper.selectById(7)).thenReturn(StandardCodeSetDO.builder().id(7).type("COUNTRY").build());
        when(codeSetMapper.selectByIdForUpdate(7))
                .thenReturn(StandardCodeSetDO.builder().id(7).type("COUNTRY").build());
        when(itemMapper.selectByIdForUpdate(10)).thenReturn(item);
        when(itemMapper.selectCountByParentId(10)).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteStandardCodeItem(10)).hasMessageContaining("子码值");


    }

    @Test
    void treeShouldDefaultToCurrentActiveViewWithRootLevelZero() {
        when(codeSetMapper.selectById(7)).thenReturn(StandardCodeSetDO.builder().id(7).build());
        StandardCodeItemDO root = StandardCodeItemDO.builder().id(10).codeSetId(7).levelNo(0).build();
        when(itemMapper.selectTree(eq(7), any(), eq(false))).thenReturn(List.of(root));
        StandardCodeItemTreeReqVO req = new StandardCodeItemTreeReqVO();
        req.setCodeSetId(7);

        List<StandardCodeItemDO> result = service.getStandardCodeItemTree(req);

        assertThat(result).singleElement().extracting(StandardCodeItemDO::getLevelNo).isEqualTo(0);
        verify(itemMapper).selectTree(eq(7), any(), eq(false));
    }

    private static StandardCodeItemSaveReqVO item(Integer codeSetId, String dictType,
                                                   Integer parentId, String value) {
        StandardCodeItemSaveReqVO req = new StandardCodeItemSaveReqVO();
        req.setCodeSetId(codeSetId);
        req.setDictType(dictType);
        req.setParentId(parentId);
        req.setValue(value);
        req.setLabel(value);
        req.setSort(1);
        req.setStatus((short) 0);
        return req;
    }
}
