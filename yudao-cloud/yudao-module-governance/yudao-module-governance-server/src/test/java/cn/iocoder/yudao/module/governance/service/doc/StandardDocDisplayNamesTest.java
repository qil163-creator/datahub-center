package cn.iocoder.yudao.module.governance.service.doc;

import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StandardDocDisplayNamesTest {
    private final CategoryDatametaMapper categories = mock(CategoryDatametaMapper.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private final StandardCatalogService service = new StandardCatalogService(categories, mock(StandardDocMapper.class), users);

    @Test
    void userServiceFailureKeepsDocumentReadableWithRawCreatorAndCategory() {
        var row = document();
        when(users.getUserMap(Set.of(7L))).thenThrow(new IllegalStateException("模拟用户服务不可用"));
        when(categories.selectByIds(List.of(2L)))
                .thenReturn(List.of(CategoryDatametaDO.builder().id(2L).categoryName("基础通用").build()));

        service.fillDocuments(List.of(row));

        assertThat(row.getCreatorName()).isEqualTo("7");
        assertThat(row.getCategoryName()).isEqualTo("基础通用");
        assertThat(row.getNameCn()).isEqualTo("测试标准");
    }

    @Test
    void availableUserServiceFillsNamesInBatch() {
        var row = document();
        var creator = new AdminUserRespDTO();
        creator.setId(7L);
        creator.setNickname("测试创建人");
        when(users.getUserMap(Set.of(7L))).thenReturn(Map.of(7L, creator));
        when(categories.selectByIds(List.of(2L)))
                .thenReturn(List.of(CategoryDatametaDO.builder().id(2L).categoryName("基础通用").build()));

        service.fillDocuments(List.of(row, document()));

        assertThat(row.getCreatorName()).isEqualTo("测试创建人");
        assertThat(row.getCategoryName()).isEqualTo("基础通用");
        verify(users, times(1)).getUserMap(Set.of(7L));
        verify(categories, times(1)).selectByIds(List.of(2L));
    }

    @Test
    void emptyDocumentPageDoesNotCallUsersOrCategories() {
        service.fillDocuments(List.of());
        verifyNoInteractions(users, categories);
    }

    private StandardDocRespVO document() {
        var row = new StandardDocRespVO();
        row.setId(10L);
        row.setNameCn("测试标准");
        row.setCreator("7");
        row.setCategoryId(2L);
        return row;
    }
}
