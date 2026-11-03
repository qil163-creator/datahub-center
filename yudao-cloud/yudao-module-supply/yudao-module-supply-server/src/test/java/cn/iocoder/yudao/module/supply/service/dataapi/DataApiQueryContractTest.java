package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataApiQueryContractTest {

    @Test
    void t26UnsupportedDynamicConditionFailsClosed() {
        DataApiExecutor executor = new DataApiExecutor();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(executor, "processDynamicTags",
                "<if test=\"amount > 0\">AND amount > 0</if>", Map.of("amount", 1)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不支持的动态条件");
    }

    @Test
    void t26SupportedNullCheckStillRenders() {
        DataApiExecutor executor = new DataApiExecutor();
        String sql = ReflectionTestUtils.invokeMethod(executor, "processDynamicTags",
                "SELECT 1 <where><if test=\"name != null\">AND name = #{name}</if></where>",
                Map.of("name", "a"));
        assertThat(sql).contains("WHERE").contains("name = #{name}");
    }

    @Test
    void t28ExtraSqlColumnsAreNotLeaked() {
        DataApiExecutor executor = new DataApiExecutor();
        DataApiSaveReqVO.FieldItem id = new DataApiSaveReqVO.FieldItem();
        id.setName("id");
        List<Map<String, Object>> rows = List.of(new LinkedHashMap<>(Map.of(
                "id", 1, "secret", "hidden", "phone", "138")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projected = ReflectionTestUtils.invokeMethod(
                executor, "applyFieldModels", rows, List.of(id), false);
        assertThat(projected).hasSize(1);
        assertThat(projected.get(0)).containsOnlyKeys("id");
        assertThat(projected.get(0)).doesNotContainKey("secret");
    }

    @Test
    void t28DuplicateOutputNamesAreRejected() {
        DataApiSaveReqVO.FieldItem a = new DataApiSaveReqVO.FieldItem();
        a.setName("col_a");
        a.setOutputName("id");
        DataApiSaveReqVO.FieldItem b = new DataApiSaveReqVO.FieldItem();
        b.setName("col_b");
        b.setOutputName("id");
        assertThatThrownBy(() -> DataApiExecutor.assertUniqueOutputNames(List.of(a, b)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("输出名重复");
    }

    @Test
    void t27DecimalStaysPlainStringAndUnsafeLongSurvivesJson() {
        DataApiExecutor executor = new DataApiExecutor();
        Object decimal = ReflectionTestUtils.invokeMethod(executor, "convertParam", "123.4500", "DECIMAL");
        assertThat(decimal).isEqualTo("123.4500");
        List<Map<String, Object>> rows = DataApiExecutor.canonicalizeRowValues(List.of(
                new LinkedHashMap<>(Map.of("amt", new BigDecimal("123.4500"), "id", 9_007_199_254_740_993L))));
        assertThat(rows.get(0).get("amt")).isEqualTo("123.4500");
        DataApiExecuteRespVO vo = new DataApiExecuteRespVO();
        vo.setRows(rows);
        vo.setTotal(1L);
        String json = JsonUtils.toJsonString(vo);
        assertThat(json).contains("123.4500");
        assertThat(json).contains("9007199254740993");
    }

    @Test
    void t30PageSizeIsBounded() {
        DataApiDO api = DataApiDO.builder().resultFormat(DataApiDO.RESULT_FORMAT_PAGE).build();
        int[] bound = DataApiExecutor.resolvePageBound(api, 2, 50_000);
        assertThat(bound[0]).isEqualTo(2);
        assertThat(bound[1]).isEqualTo(1000);
    }

    @Test
    void t22ConfigHashChangesAfterSqlEdit() {
        DataApiDO published = DataApiDO.builder()
                .configType("SQL").apiType("QUERY").sqlText("SELECT id FROM t").build();
        DataApiDO draft = DataApiDO.builder()
                .configType("SQL").apiType("QUERY").sqlText("SELECT id, secret FROM t").build();
        assertThat(DataApiContract.configHash(draft)).isNotEqualTo(DataApiContract.configHash(published));
        assertThat(DataApiContract.contractHash(draft)).isNotEqualTo(DataApiContract.contractHash(published));
    }
}
