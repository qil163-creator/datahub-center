package cn.iocoder.yudao.module.asset.service.assettag;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagDO;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagRelMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetMaintenanceAuthorization;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

class AssetTagServiceImplTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AssetTagServiceImpl service;
    @Mock
    private AssetTagMapper tagMapper;
    @Mock
    private AssetTagRelMapper tagRelMapper;
    @Mock
    private AssetUserNameResolver userNameResolver;
    @Mock
    private AssetCatalogService catalog;
    @Mock
    private AssetMaintenanceAuthorization authorization;

    @Test
    void pageReturnsTagsWithoutACategory() {
        var request = new AssetTagPageReqVO();
        var tag = new AssetTagDO();
        tag.setId(42L);
        tag.setName("未分类标签");
        var page = new Page<AssetTagDO>(1, 10, 1).setRecords(List.of(tag));
        when(catalog.prepareMaintenanceAuthorization()).thenReturn(authorization);
        when(authorization.getUserId()).thenReturn(9L);
        when(authorization.getDeptId()).thenReturn(4L);
        when(tagMapper.scopedPage(any(), eq(request), isNull(), isNull(), eq(9L), eq(4L), eq("9"), eq(false)))
                .thenReturn(page);

        var result = service.getTagPage(request);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getList()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(42L);
            assertThat(row.getCategoryId()).isNull();
            assertThat(row.getCategoryName()).isNull();
        });
    }
}
