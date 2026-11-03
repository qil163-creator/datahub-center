package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 已批准申请的字段范围只能来自批准当时冻结的物理列；任何缺失、损坏或空集合都不能退回“全部字段”。
 */
class MarketFrozenScopeTest {

    @Test
    void effectiveScopeNeverFallsBackToOriginalRequest() {
        for (String snapshot : List.of("{}", "{\"frozenColumns\":[]}", "broken")) {
            var apply = new cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO();
            apply.setSnapshot(snapshot);
            apply.setFieldScope("");
            assertThatThrownBy(() -> MarketFieldScope.effectiveScope(apply)).isInstanceOf(ServiceException.class);
            apply.setFieldScope("id");
            assertThatThrownBy(() -> MarketFieldScope.effectiveScope(apply)).isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void malformedFrozenElementsCannotBecomePhysicalIdentifiers() {
        for (String snapshot : List.of("{\"frozenColumns\":[1]}", "{\"frozenColumns\":[true]}",
                "{\"frozenColumns\":[{\"name\":\"id\"}]}", "{\"frozenColumns\":[\"id\",\"id\"]}",
                "{\"frozenColumns\":\"id,name\"}")) {
            assertThatThrownBy(() -> MarketFieldScope.requireFrozenColumns(snapshot)).isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void corruptOrEmptyApprovalCannotFallBackToAll() {
        for (String snapshot : List.of("{}", "{\"frozenColumns\":[]}", "broken",
                "{\"frozenColumns\":\"\"}", "{\"frozenColumns\":null}", "{\"fieldScope\":\"id,name\"}", " ")) {
            assertThatThrownBy(() -> MarketFieldScope.requireFrozenColumns(snapshot))
                    .as(snapshot)
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(1_500_026_024);
        }
        assertThatThrownBy(() -> MarketFieldScope.requireFrozenColumns(null)).isInstanceOf(ServiceException.class);
    }

    @Test
    void frozenColumnsKeepTheirPhysicalIdentifiers() {
        Set<String> columns = MarketFieldScope.requireFrozenColumns(
                "{\"frozenColumns\":[\"id\",\"Phone\",\" name \"]}");
        assertThat(columns).containsExactlyInAnyOrder("id", "Phone", " name ");
        assertThatThrownBy(() -> columns.add("salary")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void wildcardOrBlankEntriesAreNotApprovalEvidence() {
        assertThatThrownBy(() -> MarketFieldScope.requireFrozenColumns("{\"frozenColumns\":[\"*\"]}"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MarketFieldScope.requireFrozenColumns("{\"frozenColumns\":[\"\",\"  \"]}"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MarketFieldScope.requireFrozenColumns("{\"frozenColumns\":[\"id\",null]}"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void parseStaysAvailableForUnapprovedFormSelection() {
        // 未批准的表单选择仍可使用宽松解析，但它不是批准证据。
        assertThat(MarketFieldScope.parse("id,name").getColumns()).containsExactly("id", "name");
        assertThat(MarketFieldScope.parse(null).getMode()).isEqualTo(MarketFieldScope.Mode.ALL);
    }
}
