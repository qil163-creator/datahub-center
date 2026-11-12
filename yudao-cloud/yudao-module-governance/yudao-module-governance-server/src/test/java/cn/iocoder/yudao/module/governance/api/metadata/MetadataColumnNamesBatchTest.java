package cn.iocoder.yudao.module.governance.api.metadata;

import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsBatchReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableKeyDTO;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableKey;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableMatch;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataColumnNamesBatchTest {

    @Test
    void responsesFollowRequestOrderAndEchoKeys() {
        var resolver = mock(MetaTableKeyResolver.class);
        var api = new MetadataApiImpl();
        ReflectionTestUtils.setField(api, "tableKeyResolver", resolver);
        TableKey orders = new TableKey(1L, "ods", "ORDERS");
        when(resolver.resolve(anyCollection())).thenReturn(Map.of(orders,
                new TableMatch(100L, 10L, 1L, "ods", "ods", "orders", "订单")));
        when(resolver.loadColumnNames(anyCollection())).thenReturn(Map.of(100L, List.of("id", "amount")));
        var req = new MetadataTableColumnsBatchReqDTO();
        req.setTables(new ArrayList<>(List.of(new MetadataTableKeyDTO(1L, "ods", "missing"),
                new MetadataTableKeyDTO(1L, "ods", "ORDERS"))));

        var result = api.getColumnNamesBatch(req).getCheckedData();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFound()).isFalse();
        assertThat(result.get(0).getTableName()).isEqualTo("missing");
        assertThat(result.get(0).getColumnNames()).isEmpty();
        assertThat(result.get(1).getFound()).isTrue();
        assertThat(result.get(1).getTableName()).isEqualTo("ORDERS");
        assertThat(result.get(1).getColumnNames()).containsExactly("id", "amount");
    }

    @Test
    void rejectsMoreThanOneHundredTables() {
        var api = new MetadataApiImpl();
        ReflectionTestUtils.setField(api, "tableKeyResolver", mock(MetaTableKeyResolver.class));
        var req = new MetadataTableColumnsBatchReqDTO();
        for (int i = 0; i <= MetadataTableColumnsBatchReqDTO.MAX_TABLES; i++) {
            req.getTables().add(new MetadataTableKeyDTO(1L, null, "t" + i));
        }
        assertThatThrownBy(() -> api.getColumnNamesBatch(req)).isInstanceOf(ServiceException.class);
    }

}
