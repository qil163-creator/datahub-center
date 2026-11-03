package cn.iocoder.yudao.module.governance.api.standard;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardterm.StandardTermMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StandardReferenceApiImplTest {
    @Mock StandardTermMapper terms;
    @Mock StandardElementMapper elements;
    @Mock MetaTableMapper tables;
    @Mock MetaTableSnapshotMapper sourceSnapshots;
    @InjectMocks StandardReferenceApiImpl api;

    @Test void changedTermMeaningInvalidatesThePreviouslyFixedDefinition() {
        var term=new StandardTermDO();term.setId(9007199254740993L);term.setName("常住人口");term.setDefinition("在本市居住半年及以上的居民");term.setStatus((short)0);
        when(terms.selectById(term.getId())).thenReturn(term);
        String version=api.resolve("TERM",term.getId(),"initial").getCheckedData().getVersion();
        assertTrue(api.resolve("TERM",term.getId(),version).getCheckedData().isActive());
        term.setDefinition("统计期末在本市居住半年及以上的居民");
        var changed=api.resolve("TERM",term.getId(),version).getCheckedData();
        assertFalse(changed.isActive());assertNotEquals(version,changed.getEvidenceHash());assertEquals(term.getId(),changed.getId());
    }
    @Test void standardReferenceUsesCurrentDefinitionAndDetectsChanges() {
        var head=new StandardElementDO();head.setId(11);head.setName("行政区划代码");head.setStatus((short)0);head.setLifecycleStatus("PUBLISHED");
        when(elements.selectById(11)).thenReturn(head);
        var first=api.resolve("STANDARD",11L,"initial").getCheckedData();
        var resolved=api.resolve("STANDARD",11L,first.getVersion()).getCheckedData();
        assertTrue(resolved.isActive());assertEquals("行政区划代码",resolved.getName());
        head.setName("行政区域编码");
        assertFalse(api.resolve("STANDARD",11L,first.getVersion()).getCheckedData().isActive());
        assertEquals("行政区域编码",api.resolve("STANDARD",11L,first.getVersion()).getCheckedData().getName());
        head.setLifecycleStatus("DEPRECATED");assertFalse(api.resolve("STANDARD",11L,first.getVersion()).getCheckedData().isActive());
    }
    @Test void sourceMustHaveARealPublishedSnapshotBelongingToItsOwnTable() {
        var table=table();when(tables.selectById(20L)).thenReturn(table);
        assertFalse(api.resolve("SOURCE",20L,"30").getCheckedData().isActive());
        var snapshot=new MetaTableSnapshotDO();snapshot.setId(30L);snapshot.setTableId(99L);snapshot.setSnapshot("{\"tableName\":\"wrong_table\"}");
        when(sourceSnapshots.selectById(30L)).thenReturn(snapshot);
        assertFalse(api.resolve("SOURCE",20L,"30").getCheckedData().isActive());
        snapshot.setTableId(20L);assertTrue(api.resolve("SOURCE",20L,"30").getCheckedData().isActive());
        assertFalse(api.resolve("SOURCE",20L,"29").getCheckedData().isActive());
    }
    @Test void sourceEvidenceAlsoBindsBusinessDefinitionAndSnapshotContent() {
        var table=table();when(tables.selectById(20L)).thenReturn(table);
        var snapshot=new MetaTableSnapshotDO();snapshot.setId(30L);snapshot.setTableId(20L);snapshot.setSnapshot("{\"fields\":[\"id_number\"]}");
        when(sourceSnapshots.selectById(30L)).thenReturn(snapshot);
        String first=api.resolve("SOURCE",20L,"30").getCheckedData().getEvidenceHash();
        table.setBusinessDefinition("人口登记采集的身份信息");
        String second=api.resolve("SOURCE",20L,"30").getCheckedData().getEvidenceHash();assertNotEquals(first,second);
        snapshot.setSnapshot("{\"fields\":[\"id_number\",\"resident_area_code\"]}");
        assertNotEquals(second,api.resolve("SOURCE",20L,"30").getCheckedData().getEvidenceHash());
    }
    private static MetaTableDO table(){var table=new MetaTableDO();table.setId(20L);table.setTableName("population_register");table.setPublishStatus(1);table.setObjectStatus(1);table.setPublishedSnapshotId(30L);return table;}
}
