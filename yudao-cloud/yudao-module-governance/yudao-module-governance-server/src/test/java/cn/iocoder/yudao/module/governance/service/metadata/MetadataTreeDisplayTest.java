package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.MetadataTreeSourceRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataTreeDisplayTest {
    @Test
    void deletedSourceLosesOnlyEmptyDatabasesAndTaskPlaceholders() {
        var dbs = mock(MetaDbMapper.class);
        when(dbs.selectListForTree()).thenReturn(List.of(
                db(1, 11, 0), db(2, 11, 1), db(3, 12, 0), db(4, 13, 0)));
        var deleted = new MetadataTreeSourceRespDTO();
        deleted.setId(11L);
        deleted.setDeleted(true);
        deleted.setName("已移除的连接");
        var disabled = new MetadataTreeSourceRespDTO();
        disabled.setId(12L);
        disabled.setDeleted(false);
        disabled.setName("停用连接");
        var sources = mock(DatabaseApi.class);
        when(sources.getMetadataTreeSources(anyList())).thenThrow(new IllegalStateException("服务不可用"))
                .thenReturn(CommonResult.success(List.of(deleted, disabled)));
        var tasks = mock(MetaCollectTaskMapper.class);
        when(tasks.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(
                List.of(MetaCollectTaskDO.builder().datasourceId(11L).sourceSystemId(999L).build()));
        var service = new MetaObjectServiceImpl();
        ReflectionTestUtils.setField(service, "collectTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "treeCatalog", new MetadataTreeCatalog(dbs, sources));

        assertEquals(2, service.getObjectTree().size()); // 服务暂不可用时保留所有现有节点
        var tree = service.getObjectTree();
        assertEquals(1, tree.size());
        var connections = tree.getFirst().getChildren();
        assertEquals(List.of(11L, 12L, 13L), connections.stream().map(n -> n.getId()).toList());
        assertEquals("已移除的连接", connections.getFirst().getName());
        assertEquals(List.of(2L), connections.getFirst().getChildren().stream().map(n -> n.getId()).toList());
        assertEquals(1, connections.getFirst().getChildren().getFirst().getTableCount());
        assertEquals("停用连接", connections.get(1).getName());
        assertEquals("数据源#13", connections.get(2).getName()); // 离线导入，DataForge 无该连接
    }

    private MetaDbDO db(long id, long datasource, int tableCount) {
        return MetaDbDO.builder().id(id).datasourceId(datasource).dbName("db_" + id)
                .sourceSystemId(1L).tableCount(tableCount).build();
    }

    @Test
    void tableTreeIncludesTaskConnectionsBeforeTheyHaveCollectedAnyDatabase() {
        var dbs = mock(MetaDbMapper.class);
        when(dbs.selectListForTree()).thenReturn(List.of(MetaDbDO.builder().id(1L).datasourceId(11L)
                .sourceSystemId(1L).sourceSystemName("库表系统").dbName("ODS").dbType("Doris").build()));
        var tasks = mock(MetaCollectTaskMapper.class);
        when(tasks.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(
                MetaCollectTaskDO.builder().id(2L).sourceSystemId(2L).sourceSystemName("文件系统")
                        .datasourceId(20L).datasourceName("文件连接").dbType("HDFS").build(),
                MetaCollectTaskDO.builder().id(1L).sourceSystemId(1L).sourceSystemName("库表系统")
                        .datasourceId(12L).datasourceName("尚未采集的连接").dbType("MySQL").build()));
        var service = new MetaObjectServiceImpl();
        var sources = mock(DatabaseApi.class);
        when(sources.getMetadataTreeSources(anyList())).thenReturn(CommonResult.success(List.of()));
        ReflectionTestUtils.setField(service, "treeCatalog", new MetadataTreeCatalog(dbs, sources));
        ReflectionTestUtils.setField(service, "collectTaskMapper", tasks);
        var tree = service.getObjectTree();
        assertEquals(List.of(1L, 2L), tree.stream().map(node -> node.getId()).toList());
        assertEquals(2, tree.getFirst().getChildren().size());
        assertEquals("MySQL", tree.getFirst().getChildren().get(1).getDbType());
        assertTrue(tree.get(1).getChildren().getFirst().getChildren().isEmpty());
    }

    @Test
    void fileTreeKeepsEmptyDatabaseSourceRootsAndUsesImportedProductForFileConnections() {
        var dbs = mock(MetaDbMapper.class);
        when(dbs.selectListForTree()).thenReturn(List.of(MetaDbDO.builder().sourceSystemId(1L).sourceSystemName("库表系统").build()));
        var files = mock(MetaFileMapper.class);
        when(files.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(
                MetaFileDO.builder().id(1L).sourceSystemId(2L).sourceSystemName("文件系统").datasourceId(20L)
                        .datasourceName("文件连接").parentPath("/").path("/a.csv")
                        .displaySnapshot("{\"source\":\"qdata\",\"dbType\":\"FTP\"}").build()));
        var service = new MetaFileService();
        ReflectionTestUtils.setField(service, "mapper", files); ReflectionTestUtils.setField(service, "treeCatalog", new MetadataTreeCatalog(dbs, mock(DatabaseApi.class)));
        var tree = service.tree();
        assertEquals(2, tree.size()); assertTrue(tree.getFirst().getChildren().isEmpty());
        assertEquals("FTP", tree.get(1).getChildren().getFirst().getDbType());
    }
}
