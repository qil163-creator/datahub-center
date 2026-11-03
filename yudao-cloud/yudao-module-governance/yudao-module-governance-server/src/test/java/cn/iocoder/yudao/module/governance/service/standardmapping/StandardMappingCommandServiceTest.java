package cn.iocoder.yudao.module.governance.service.standardmapping;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;

@ExtendWith(MockitoExtension.class)
class StandardMappingCommandServiceTest {

    @Mock private StandardMappingMapper mappingMapper;
    @Mock private StandardElementMapper elementMapper;
    @Mock private MetaColumnMapper columnMapper;
    @InjectMocks private StandardMappingCommandService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "metadata-column"), MetaColumnDO.class);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void t12SecondConfirmRevokesPreviousAndKeepsOneActiveMapping() {
        StandardElementDO element = StandardElementDO.builder()
                .id(7).lifecycleStatus("PUBLISHED").status((short) 0).build();
        StandardMappingDO current = StandardMappingDO.builder()
                .id(80L).elementId(6)
                .mappingStatus(StandardMappingDO.STATUS_CONFIRMED).build();
        when(elementMapper.selectByIdForUpdate(7)).thenReturn(element);
        when(mappingMapper.selectConfirmedByTargetForUpdate("METADATA_COLUMN", "urn:col:10"))
                .thenReturn(current);
        when(mappingMapper.updateById(any(StandardMappingDO.class))).thenReturn(1);
        when(columnMapper.selectByIdForUpdate(10L)).thenReturn(MetaColumnDO.builder()
                .id(10L).resourceUrn("urn:col:10").revision(1).objectStatus(1).build());
        when(columnMapper.update(isNull(), any())).thenReturn(1);

        service.confirm("METADATA_COLUMN", "urn:col:10", 7, 10L,
                StandardMappingDO.SOURCE_MANUAL, "改绑", "cust_code");

        ArgumentCaptor<StandardMappingDO> revoked = ArgumentCaptor.forClass(StandardMappingDO.class);
        verify(mappingMapper).updateById(revoked.capture());
        assertThat(revoked.getValue().getId()).isEqualTo(80L);
        assertThat(revoked.getValue().getMappingStatus()).isEqualTo(StandardMappingDO.STATUS_REVOKED);
        verify(mappingMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (StandardMappingDO created) -> StandardMappingDO.STATUS_CONFIRMED.equals(created.getMappingStatus())
                        && created.getElementId().equals(7)
                        && created.getColumnId().equals(10L)));
        verify(columnMapper).update(isNull(), any());
    }
}
