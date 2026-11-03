package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import org.springframework.test.util.ReflectionTestUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.PushCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.PushCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.PushCategoryMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PushCategoryServiceTest {
    private final PushCategoryMapper categories = mock(PushCategoryMapper.class);
    private final DataPushTaskMapper tasks = mock(DataPushTaskMapper.class);
    private final PushCategoryService service = new PushCategoryService(categories, tasks);

    private PushCategoryDO row(long id, long parent, int status) {
        var value = new PushCategoryDO();
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
        when(security.hasPermission("dataforge:data-push:query")).thenReturn(true);
        when(tasks.selectCategoryTaskCounts()).thenReturn(List.of(new DataPushTaskMapper.CategoryTaskCount(1L, 7L)));
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
        var request = new PushCategorySaveReqVO();
        request.setId(1L); request.setParentId(2L); request.setName("目录1");
        assertThrows(ServiceException.class, () -> service.update(request));
        verify(categories).lockTree();
        verify(categories, never()).updateById(any(PushCategoryDO.class));
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
