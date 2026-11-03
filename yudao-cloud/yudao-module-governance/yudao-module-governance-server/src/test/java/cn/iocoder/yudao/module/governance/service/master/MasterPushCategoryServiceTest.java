package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import org.springframework.test.util.ReflectionTestUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterPushCategorySaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterPushCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterPushCategoryMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterPushCategoryServiceTest {
    private final MasterPushCategoryMapper categories = mock(MasterPushCategoryMapper.class);
    private final MasterDistributeMapper tasks = mock(MasterDistributeMapper.class);
    private final MasterPushCategoryService service = new MasterPushCategoryService(categories, tasks);

    private MasterPushCategoryDO row(long id, long parent, int status) {
        var value = new MasterPushCategoryDO();
        value.setId(id); value.setParentId(parent); value.setStatus(status); value.setName("目录" + id);
        return value;
    }

    @Test
    void taskCountsRequireTaskQueryPermission() {
        var security = mock(SecurityFrameworkService.class);
        ReflectionTestUtils.setField(service, "security", security);
        when(categories.selectOrdered()).thenReturn(List.of(row(1, 0, 0)));
        assertNull(service.list().getFirst().getTaskCount());
        verify(tasks, never()).selectCategoryTaskCounts();
        when(security.hasPermission("governance:master-distribute:query")).thenReturn(true);
        when(tasks.selectCategoryTaskCounts()).thenReturn(List.of(new MasterDistributeMapper.CategoryTaskCount(1L, 7L)));
        assertEquals(7L, service.list().getFirst().getTaskCount());
    }

    @Test
    void descendantSelectionIncludesNestedCategoriesAndRejectsUnknown() {
        when(categories.selectOrdered()).thenReturn(List.of(row(1, 0, 0), row(2, 1, 0), row(3, 2, 0), row(4, 0, 0)));
        assertEquals(List.of(1L, 2L, 3L), service.descendants(1L));
        assertThrows(ServiceException.class, () -> service.descendants(99L));
    }

    @Test
    void reassignmentCannotIntroduceCycle() {
        when(categories.selectById(1L)).thenReturn(row(1, 0, 0));
        when(categories.selectById(2L)).thenReturn(row(2, 1, 0));
        var request = new MasterPushCategorySaveReqVO();
        request.setId(1L); request.setParentId(2L); request.setName("目录1");
        assertThrows(ServiceException.class, () -> service.update(request));
        verify(categories).lockTree();
        verify(categories, never()).updateById(any(MasterPushCategoryDO.class));
    }

    @Test
    void disabledCategoryMayRemainOnExistingTaskButCannotBeNewlyAssigned() {
        when(categories.selectById(2L)).thenReturn(row(2, 0, 1));
        assertDoesNotThrow(() -> service.validateReference(2L, 2L));
        assertThrows(ServiceException.class, () -> service.validateReference(2L, null));
    }

    @Test
    void batchDeleteRejectsReferencedTask() {
        when(categories.selectOrdered()).thenReturn(List.of(row(1, 0, 1)));
        when(tasks.selectCount(any(SFunction.class), eq(1L))).thenReturn(1L);
        assertThrows(ServiceException.class, () -> service.delete(List.of(1L)));
        verify(categories, never()).deleteById(anyLong());
    }

    @Test
    void batchDeleteRejectsUnselectedChild() {
        when(categories.selectOrdered()).thenReturn(List.of(row(1, 0, 1), row(2, 1, 1)));
        assertThrows(ServiceException.class, () -> service.delete(List.of(1L)));
        verify(categories, never()).deleteById(anyLong());
    }
}
