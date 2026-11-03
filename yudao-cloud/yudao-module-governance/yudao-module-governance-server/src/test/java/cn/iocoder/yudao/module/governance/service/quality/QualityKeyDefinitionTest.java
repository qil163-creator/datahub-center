package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class QualityKeyDefinitionTest {
    @Test void singleAndAbsentKeysKeepExistingContract() {
        assertThat(QualityKeyDefinition.from(Map.of()).configured()).isFalse();
        assertThat(QualityKeyDefinition.from(Map.of("pkColumn", "  ")).configured()).isFalse();
        var single = QualityKeyDefinition.from(Map.of("pkColumn", " id "));
        assertThat(single.column()).isEqualTo("id"); assertThat(single.valid()).isTrue();
    }

    @Test void compositeOrderIsPreservedAndCannotBeMutatedByCaller() {
        var input = new java.util.ArrayList<>(List.of("source_id", "id"));
        var definition = QualityKeyDefinition.from(Map.of("pkColumns", input));
        input.clear();
        assertThat(definition.configured()).isTrue(); assertThat(definition.valid()).isTrue();
        assertThat(definition.columns()).containsExactly("source_id", "id");
        assertThatThrownBy(() -> definition.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void malformedOrAmbiguousDeclarationsStillRequireProof() {
        for (String config : List.of("{\"pkColumns\":null}", "{\"pkColumns\":[]}", "{\"pkColumns\":[\"id\"]}",
                "{\"pkColumns\":[\"id\",\"ID\"]}", "{\"pkColumns\":[\"id\",null]}", "{\"pkColumns\":[\"id\",1]}",
                "{\"pkColumns\":\"id,source_id\"}", "{\"pkColumn\":\"id\",\"pkColumns\":[\"id\",\"source_id\"]}",
                "{\"pkColumns\":[\"id\",\"a;DELETE\"]}", "{\"pkColumn\":[\"id\"]}")) {
            @SuppressWarnings("unchecked") Map<String, Object> parameters = JsonUtils.parseObject(config, Map.class);
            var definition = QualityKeyDefinition.from(parameters);
            assertThat(definition.configured()).as(config).isTrue(); assertThat(definition.valid()).as(config).isFalse();
        }
    }
}
