package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTableRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaFileDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaFileMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetaImportedDisplayTest {
    private static final String DISPLAY = """
            {"source":"qdata","bundleId":"qdata-metadata-20261022","sourceId":1,
             "datasourceName":"原始库","dbType":"Doris","ownerName":"源端负责人",
             "businessLeaderName":"源端业务人员","creatorName":"源端创建人员"}
            """;

    @Test
    void sourceNamesAreOnlyFallbackAndRawSnapshotIsNotReturned() {
        assertEquals("本平台人员", MetaImportedDisplay.read(DISPLAY).fallback("本平台人员", "creatorName"));
        assertEquals("源端创建人员", MetaImportedDisplay.read(DISPLAY).fallback(null, "creatorName"));
        assertNull(MetaImportedDisplay.read("{\"source\":\"unknown\",\"creatorName\":\"伪造\"}").value("creatorName"));
        assertNull(MetaImportedDisplay.read("invalid").value("creatorName"));
        var response = new MetaTableRespVO(); response.setDisplaySnapshot(DISPLAY);
        assertFalse(JsonUtils.toJsonString(response).contains("bundleId"));
    }

    @Test
    void importedTaskInstanceAndFileDoNotResolveRemoteUserIdsIntoPlatformAccounts() {
        var users = mock(AdminUserApi.class);
        var sources = mock(MetadataCollectionApi.class);
        when(sources.options(any())).thenThrow(new IllegalStateException("未映射的导入连接"));
        var collect = new MetaCollectDisplay();
        ReflectionTestUtils.setField(collect, "users", users);
        ReflectionTestUtils.setField(collect, "sources", sources);
        ReflectionTestUtils.setField(collect, "instances", mock(MetaCollectInstanceMapper.class));
        var task = MetaCollectTaskDO.builder().id(1L).datasourceId(90220001L).displaySnapshot(DISPLAY).build();
        task.setCreator("qdata-import");
        var taskView = collect.tasks(List.of(task)).getFirst();
        assertEquals("源端创建人员", taskView.getCreatorName());
        assertEquals("Doris", taskView.getDbType());
        assertNull(taskView.getUsername());
        var instance = MetaCollectInstanceDO.builder().id(1L).displaySnapshot(DISPLAY).build();
        instance.setCreator("qdata-import");
        assertEquals("Doris", collect.instances(List.of(instance)).getFirst().getDbType());
        var file = MetaFileDO.builder().id(1L).datasourceId(90220001L).displaySnapshot(DISPLAY).build();
        file.setCreator("qdata-import");
        var files = new MetaFileService();
        var mapper = mock(MetaFileMapper.class); when(mapper.selectById(1L)).thenReturn(file);
        ReflectionTestUtils.setField(files, "mapper", mapper);
        ReflectionTestUtils.setField(files, "users", users);
        ReflectionTestUtils.setField(files, "sources", sources);
        assertEquals("源端业务人员", files.get(1L).getBusinessLeaderName());
        verifyNoInteractions(users);
    }

    @Test
    void mappedPlatformPeopleKeepTheirWholeContactGroupAndNormalInstancesUseTaskProduct() {
        String oldDisplay = DISPLAY.replace("\"creatorName\":\"源端创建人员\"",
                "\"creatorName\":\"源端创建人员\",\"creatorPhone\":\"旧电话\",\"ownerPhone\":\"旧电话\","
                        + "\"updaterName\":\"源端维护人员\",\"updaterPhone\":\"旧电话\",\"businessLeaderPhone\":\"旧电话\"");
        var user = new cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO();
        user.setId(77L); user.setNickname("本平台人员");
        var users = mock(AdminUserApi.class); when(users.getUserMap(anyCollection())).thenReturn(java.util.Map.of(77L, user));
        var sources = mock(MetadataCollectionApi.class);
        when(sources.options(any())).thenThrow(new IllegalStateException("未映射的导入连接"));
        var collect = new MetaCollectDisplay();
        ReflectionTestUtils.setField(collect, "users", users);
        ReflectionTestUtils.setField(collect, "sources", sources);
        ReflectionTestUtils.setField(collect, "instances", mock(MetaCollectInstanceMapper.class));
        var tasks = mock(cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectTaskMapper.class);
        ReflectionTestUtils.setField(collect, "tasks", tasks);
        var task = MetaCollectTaskDO.builder().id(1L).ownerUserId(77L).datasourceId(90220001L).displaySnapshot(oldDisplay).build();
        task.setCreator("77"); task.setUpdater("77");
        var taskView = collect.tasks(List.of(task)).getFirst();
        assertEquals("本平台人员", taskView.getOwnerName()); assertNull(taskView.getOwnerPhone());
        assertEquals("本平台人员", taskView.getCreatorName()); assertNull(taskView.getCreatorPhone());
        assertEquals("本平台人员", taskView.getUpdaterName()); assertNull(taskView.getUpdaterPhone());
        when(tasks.selectByIds(anyCollection())).thenReturn(List.of(MetaCollectTaskDO.builder().id(1L).dbType("PostgreSQL").build()));
        var instance = MetaCollectInstanceDO.builder().id(1L).taskId(1L).build();
        assertEquals("PostgreSQL", collect.instances(List.of(instance)).getFirst().getDbType());
        var file = MetaFileDO.builder().id(1L).datasourceId(90220001L).businessLeaderUserId(77L).displaySnapshot(oldDisplay).build();
        var files = new MetaFileService(); var mapper = mock(MetaFileMapper.class); when(mapper.selectById(1L)).thenReturn(file);
        ReflectionTestUtils.setField(files, "mapper", mapper); ReflectionTestUtils.setField(files, "users", users);
        ReflectionTestUtils.setField(files, "sources", sources);
        var fileView = files.get(1L);
        assertEquals("本平台人员", fileView.getBusinessLeaderName()); assertNull(fileView.getBusinessLeaderPhone());
    }
}
