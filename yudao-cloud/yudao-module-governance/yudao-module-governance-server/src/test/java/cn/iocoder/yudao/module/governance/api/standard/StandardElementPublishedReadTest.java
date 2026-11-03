package cn.iocoder.yudao.module.governance.api.standard;

import cn.iocoder.yudao.module.governance.api.standard.dto.StandardElementRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class StandardElementPublishedReadTest {

    @Mock
    private StandardElementMapper standardElementMapper;
    @InjectMocks
    private StandardElementApiImpl api;

    @Test
    void identifiersOutsideIntegerDomainNeverWrapToAnUnrelatedStandard() {
        assertThat(api.getElementListByIds(List.of(4294967303L, -1L, 0L, Long.MAX_VALUE)).getData()).isEmpty();
        verifyNoInteractions(standardElementMapper);
    }

    @Test
    void consumerReadsCurrentElement() {
        StandardElementDO head = StandardElementDO.builder()
                .id(7).name("草稿姓名").columnType("TEXT").dataLength(999).build();
        when(standardElementMapper.selectById(7)).thenReturn(head);

        List<StandardElementRespDTO> list = api.getElementListByIds(List.of(7L)).getData();
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().getName()).isEqualTo("草稿姓名");
        assertThat(list.getFirst().getColumnType()).isEqualTo("TEXT");
        assertThat(list.getFirst().getDataLength()).isEqualTo(999);
    }

}
