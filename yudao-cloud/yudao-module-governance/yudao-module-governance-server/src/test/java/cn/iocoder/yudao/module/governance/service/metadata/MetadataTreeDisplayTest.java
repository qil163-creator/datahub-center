package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataTreeDisplayTest {
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
        ReflectionTestUtils.setField(service, "dbMapper", dbs);
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
        ReflectionTestUtils.setField(service, "mapper", files); ReflectionTestUtils.setField(service, "dbs", dbs);
        var tree = service.tree();
        assertEquals(2, tree.size()); assertTrue(tree.getFirst().getChildren().isEmpty());
        assertEquals("FTP", tree.get(1).getChildren().getFirst().getDbType());
    }
}
