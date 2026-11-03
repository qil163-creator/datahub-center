package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataAccessIdentityConfigTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void publicSaveReadAndExecutionSnapshotKeepOnlyExplicitDeclarations(boolean declared)throws Exception {
        var fixture=new Fixture();
        var mapper=new tools.jackson.databind.ObjectMapper();
        var request=mapper.readValue("{\"taskName\":\"identity config\",\"engineType\":1,\"taskType\":2,\"sourceType\":1,"
                +"\"sourceDatabaseId\":11,\"targetDatabaseId\":12,\"sourceSelectedTables\":[\"orders\"]"
                +(declared?",\"identitySourceQuiesced\":true,\"preserveIdentityValues\":true,\"exclusiveTargetOwnership\":true":"")+"}",DataAccessSaveReqVO.class);
        assertEquals(4L,fixture.service.createDataAccess(request));
        var captured=ArgumentCaptor.forClass(DataAccessDO.class);verify(fixture.tasks).insert(captured.capture());
        var saved=captured.getValue();String frozen=AccessRunSnapshot.freeze(saved);
        var runtime=AccessTaskConfigMapper.toRuntime(AccessRunSnapshot.restore(frozen).getDataAccessJson(),12L);
        assertEquals(declared,runtime.getSource().getFirst().getIdentitySourceQuiesced());
        assertEquals(declared,runtime.getTarget().getPreserveIdentityValues());
        assertEquals(declared,runtime.getTarget().getExclusiveTargetOwnership());
        when(fixture.tasks.selectById(4L)).thenReturn(saved);
        var response=fixture.service.getDataAccess(4L);
        assertEquals(declared,response.isIdentitySourceQuiesced());assertEquals(declared,response.isPreserveIdentityValues());
        assertEquals(declared,response.isExclusiveTargetOwnership());
        String responseJson=mapper.writeValueAsString(response);
        assertTrue(responseJson.contains("\"identitySourceQuiesced\":"+declared));
        assertTrue(responseJson.contains("\"preserveIdentityValues\":"+declared));
        assertTrue(responseJson.contains("\"exclusiveTargetOwnership\":"+declared));
        verify(fixture.tasks).selectById(4L);
        verifyNoMoreInteractions(fixture.tasks);
    }
    @Test void unpairedPreserveDeclarationFailsBeforePersistingTask() {
        var fixture=new Fixture();var request=new DataAccessSaveReqVO();request.setSourceDatabaseId(11L);request.setTargetDatabaseId(12L);
        request.setTaskType(2);request.setSourceType(1);request.setSourceSelectedTables(List.of("orders"));request.setPreserveIdentityValues(true);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->fixture.service.createDataAccess(request));
        verify(fixture.tasks,never()).insert(any(DataAccessDO.class));
    }
    private static final class Fixture {
        final DataAccessMapper tasks=mock(DataAccessMapper.class);
        final DataAccessServiceImpl service=new DataAccessServiceImpl();
        Fixture() {
            var databases=mock(DatabaseMapper.class);
            var source=DatabaseDO.builder().id(11L).name("source").build();var target=DatabaseDO.builder().id(12L).name("target").build();
            when(databases.selectById(11L)).thenReturn(source);when(databases.selectById(12L)).thenReturn(target);
            when(databases.selectByIds(anyCollection())).thenReturn(List.of(source,target));
            when(tasks.insert(any(DataAccessDO.class))).thenAnswer(call->{DataAccessDO saved=call.getArgument(0);saved.setId(4L);return 1;});
            ReflectionTestUtils.setField(service,"dataAccessMapper",tasks);ReflectionTestUtils.setField(service,"databaseMapper",databases);
            ReflectionTestUtils.setField(service,"deptApi",mock(cn.iocoder.yudao.module.system.api.dept.DeptApi.class));
            ReflectionTestUtils.setField(service,"adminUserApi",mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class));
        }
    }
}
