package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MasterDistributeCategoryTest {
    private final MasterDistributeServiceImpl service = new MasterDistributeServiceImpl();
    private final MasterDistributeMapper tasks = mock(MasterDistributeMapper.class);
    private final MasterModelMapper models = mock(MasterModelMapper.class);
    private final MasterPushCategoryService categories = mock(MasterPushCategoryService.class);
    private final MasterDistributeRunCoordinator coordinator = mock(MasterDistributeRunCoordinator.class);

    MasterDistributeCategoryTest() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "master-distribute-category"),
                MasterDistributeDO.class);
        ReflectionTestUtils.setField(service, "distributeMapper", tasks);
        ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "categoryService", categories);
        ReflectionTestUtils.setField(service, "coordinator", coordinator);
        ReflectionTestUtils.setField(service, "securityFrameworkService", mock(SecurityFrameworkService.class));
        var model = new MasterModelDO();
        model.setStatus(MasterModelDO.STATUS_RELEASED);
        when(models.selectById(10L)).thenReturn(model);
        when(models.selectForUpdate(10L)).thenReturn(model);
    }

    private MasterDistributeSaveReqVO request(Long categoryId) {
        var request = new MasterDistributeSaveReqVO();
        request.setId(1L);
        request.setMasterModelId(10L);
        request.setName("推送任务");
        request.setDistributeType(1);
        request.setTaskType(1);
        request.setCategoryId(categoryId);
        var config = new MasterDistributeConfigVO();
        var db = new MasterDistributeConfigVO.DbConfig();
        db.setDatabaseId(20L);
        config.setDb(db);
        request.setConfig(config);
        return request;
    }

    @Test
    void createChecksAndPersistsCategory() {
        var request = request(123L);
        service.createDistribute(request);
        verify(categories).validateReference(123L, null);
        verify(tasks).insert(argThat((MasterDistributeDO task) -> Long.valueOf(123L).equals(task.getCategoryId())));
    }

    @Test
    void updateCanClearPreviousCategory() {
        var old = MasterDistributeDO.builder().id(1L).categoryId(123L).distributeType(1).status(0).build();
        when(coordinator.withIdleTask(eq(1L), any())).thenAnswer(call -> {
            Function<MasterDistributeDO, ?> action = call.getArgument(1);
            return action.apply(old);
        });
        service.updateDistribute(request(null));
        verify(tasks).updateById(argThat((MasterDistributeDO task) -> task.getCategoryId() == null));
        verify(tasks).update(isNull(), any());
        verifyNoInteractions(categories);
    }

    @Test
    void pageExpandsSelectedCategoryAndKeepsAllTasksByDefault() {
        var request = new MasterDistributePageReqVO();
        when(tasks.selectPage(request)).thenReturn(new PageResult<>(List.of(), 0L));
        assertEquals(0, service.getDistributePage(request).getTotal());
        verifyNoInteractions(categories);
        request.setCategoryId(1L);
        when(categories.descendants(1L)).thenReturn(List.of(1L, 2L));
        when(tasks.selectPage(request, List.of(1L, 2L))).thenReturn(new PageResult<>(List.of(), 0L));
        assertEquals(0, service.getDistributePage(request).getTotal());
        verify(tasks).selectPage(request, List.of(1L, 2L));
    }
}
