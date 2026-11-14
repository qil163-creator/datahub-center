package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeStrategyEnum;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTableVersionServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_COLUMN_RULE_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_EXPRESSION_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_MERGE_CONFIG_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板版本快照：系统生成主键与合并策略
 */
class FillTableVersionSnapshotTest {

    private final FillTableVersionServiceImpl tableVersionService = new FillTableVersionServiceImpl();

    private final FillTableDO table = FillTableDO.builder()
            .id(1L).databaseId(2L).tableEngName("students").tableCnName("学生信息").status(0).build();

    private final FillTableColumnDO studentName = FillTableColumnDO.builder()
            .name("student_name").fieldCode("student_name").cnName("学生姓名")
            .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(50).build();

    private final FillTableColumnDO studentId = FillTableColumnDO.builder()
            .name("student_id").fieldCode("student_id").cnName("学生编号")
            .fieldType(ColumnMetaData.TYPE_INTEGER).fieldLength(9).pk(1).build();

    // ---------------------------------------------------------------- 系统生成主键与合并策略

    private final FillTableColumnDO generatedId = FillTableColumnDO.builder()
            .name("record_id").fieldCode("record_id").cnName("记录编号")
            .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(32).pk(1).pkType(1).nullable(0).build();

    @Test
    void generatedUuidKeyIsHiddenReadOnlyAndNotRequired() {
        var snapshot = tableVersionService.buildReleaseSnapshot(table, List.of(generatedId, studentName));

        List<?> fields = JsonUtils.parseObject(
                snapshot.getFieldSchema(), List.class);
        var key = (java.util.Map<?, ?>) fields.getFirst();
        assertEquals("UUID", key.get("generated"));
        assertEquals(Boolean.TRUE, key.get("hidden"));
        assertEquals(Boolean.FALSE, key.get("editable"));
        assertEquals(Boolean.FALSE, key.get("required"));
        assertTrue(!snapshot.getValidationSchema().contains("record_id"), snapshot.getValidationSchema());
    }

    @Test
    void generatedUuidKeyMustBeLongEnoughString() {
        var shortKey = FillTableColumnDO.builder().name("record_id").fieldCode("record_id").cnName("记录编号")
                .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(16).pk(1).pkType(1).build();
        var numericKey = FillTableColumnDO.builder().name("record_id").fieldCode("record_id").cnName("记录编号")
                .fieldType(ColumnMetaData.TYPE_INTEGER).pk(1).pkType(1).build();

        for (var column : List.of(shortKey, numericKey)) {
            var failure = assertThrows(ServiceException.class,
                    () -> tableVersionService.buildReleaseSnapshot(table, List.of(column)));
            assertEquals(FILL_TABLE_COLUMN_RULE_INVALID.getCode(), failure.getCode());
        }
    }

    @Test
    void upsertStrategyFreezesValidatedBusinessKeys() {
        var requiredName = FillTableColumnDO.builder().name("student_name").fieldCode("student_name")
                .cnName("学生姓名").fieldType(ColumnMetaData.TYPE_STRING).fieldLength(50).nullable(0).build();
        table.setMergeStrategy("UPSERT");
        table.setBusinessKeyFields(" student_name , student_name ");

        var snapshot = tableVersionService.buildReleaseSnapshot(table, List.of(requiredName, studentId));

        assertEquals(FillMergeStrategyEnum.UPSERT.getStrategy(), snapshot.getMergeStrategy());
        assertEquals("student_name", snapshot.getBusinessKeyFields());
        table.setMergeStrategy("APPEND");
        var append = tableVersionService.buildReleaseSnapshot(table, List.of(requiredName, studentId));
        assertEquals("APPEND", append.getMergeStrategy());
        assertEquals(null, append.getBusinessKeyFields());
    }

    @Test
    void upsertStrategyRejectsMissingOptionalOrGeneratedKeys() {
        table.setMergeStrategy("UPSERT");
        for (String keys : new String[]{"", "unknown_field", "student_name", "record_id"}) {
            table.setBusinessKeyFields(keys);
            var failure = assertThrows(ServiceException.class,
                    () -> tableVersionService.buildReleaseSnapshot(table, List.of(studentName, generatedId)));
            assertEquals(FILL_TABLE_MERGE_CONFIG_INVALID.getCode(), failure.getCode(), keys);
        }
    }


    // ---------------------------------------------------------------- 公式与布局

    private static FillTableColumnDO decimal(String code, String formula) {
        return FillTableColumnDO.builder().name(code).fieldCode(code).cnName(code)
                .fieldType(ColumnMetaData.TYPE_NUMBER).fieldLength(18).fieldScale(2).nullable(0)
                .formulaExpr(formula).columnWidth(code.equals("price") ? 120 : null)
                .headerGroup(code.equals("price") || code.equals("qty") ? "明细" : null).build();
    }

    @Test
    void formulaColumnsAreReadOnlyOrderedByDependencyAndLayoutIsFrozen() {
        table.setFreezeColumnCount(1);
        var snapshot = tableVersionService.buildReleaseSnapshot(table, List.of(
                decimal("total", "subtotal + tax"), decimal("subtotal", "price * qty"),
                decimal("price", null), decimal("qty", null), decimal("tax", null)));

        List<?> formulas = (List<?>) JsonUtils.parseObject(snapshot.getFormulaSchema(), java.util.Map.class).get("formulas");
        assertEquals(List.of("subtotal", "total"),
                formulas.stream().map(item -> ((java.util.Map<?, ?>) item).get("fieldCode")).toList());
        List<?> fields = JsonUtils.parseObject(snapshot.getFieldSchema(), List.class);
        var total = (java.util.Map<?, ?>) fields.getFirst();
        assertEquals(Boolean.FALSE, total.get("editable"));
        assertEquals(Boolean.FALSE, total.get("required"));
        assertEquals("subtotal + tax", total.get("formula"));
        var price = (java.util.Map<?, ?>) fields.get(2);
        assertEquals(120, price.get("width"));
        assertEquals("明细", price.get("headerGroup"));
        assertTrue(snapshot.getLayoutSnapshot().contains("\"freezeColumnCount\":1"), snapshot.getLayoutSnapshot());
        assertTrue(!snapshot.getValidationSchema().contains("\"fieldCode\":\"total\""), snapshot.getValidationSchema());
    }

    @Test
    void invalidFormulasAreRejectedAtRelease() {
        for (var columns : List.of(
                List.of(decimal("a", "b + 1"), decimal("b", "a + 1")),
                List.of(decimal("a", "missing * 2")),
                List.of(decimal("a", "a + 1")),
                List.of(decimal("a", "SUM(b)"), decimal("b", null)))) {
            var failure = assertThrows(ServiceException.class,
                    () -> tableVersionService.buildReleaseSnapshot(table, columns));
            assertEquals(FILL_TABLE_EXPRESSION_INVALID.getCode(), failure.getCode());
        }
    }

    @Test
    void templatesWithoutDesignerSettingsKeepTheirContentHash() {
        var plain = tableVersionService.buildReleaseSnapshot(table, List.of(studentName, studentId));
        assertEquals("{\"engineVersion\":\"1.0\",\"formulas\":[]}", plain.getFormulaSchema());
        assertEquals("{}", plain.getLayoutSnapshot());
        String legacyHash = cn.hutool.crypto.digest.DigestUtil.sha256Hex(plain.getFieldSchema() + "|"
                + plain.getTargetMapping() + "|" + plain.getValidationSchema() + "|REPLACE_SCOPE|");
        assertEquals(legacyHash, plain.getContentHash());
    }


    @Test
    void multiSelectColumnFreezesOptionsAndMultipleFlag() {
        var tags = FillTableColumnDO.builder().name("tags").fieldCode("tags").cnName("标签")
                .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(200).fillType(3).fillValues("甲,乙").build();

        var snapshot = tableVersionService.buildReleaseSnapshot(table, List.of(tags));

        var field = (java.util.Map<?, ?>) JsonUtils.parseObject(snapshot.getFieldSchema(), List.class).getFirst();
        assertEquals(Boolean.TRUE, field.get("multiple"));
        assertEquals(List.of("甲", "乙"), field.get("dictOptions"));
    }


    @Test
    void tableSourceCascadeAndIdControlsAreFrozenIntoFieldSchema() {
        var loader = org.mockito.Mockito.mock(cn.iocoder.yudao.module.dataforge.service.fill.FillReferenceOptionLoader.class);
        org.springframework.test.util.ReflectionTestUtils.setField(tableVersionService, "referenceOptionLoader", loader);
        var province = FillTableColumnDO.builder().name("province").fieldCode("province").cnName("省")
                .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(20).fillType(2).fillValues("浙江,江苏").build();
        var city = FillTableColumnDO.builder().name("city").fieldCode("city").cnName("市")
                .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(20).fillType(2).refDatabaseId(9L)
                .refTableName("md_city").refValueColumn("name").refParentField("province").refParentColumn("province").build();
        var owner = FillTableColumnDO.builder().name("owner").fieldCode("owner").cnName("负责人")
                .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(32).fillType(4).refScope("ALL").build();
        var photo = FillTableColumnDO.builder().name("photo").fieldCode("photo").cnName("照片")
                .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(500).fillType(7).build();
        org.mockito.Mockito.when(loader.load(city)).thenReturn(new cn.iocoder.yudao.module.dataforge.service.fill
                .FillReferenceOptionLoader.Options(List.of("杭州", "南京"), java.util.Map.of("浙江", List.of("杭州"))));

        var snapshot = tableVersionService.buildReleaseSnapshot(table, List.of(province, city, owner, photo));

        List<?> fields = JsonUtils.parseObject(snapshot.getFieldSchema(), List.class);
        var cityField = (java.util.Map<?, ?>) fields.get(1);
        assertEquals(List.of("杭州", "南京"), cityField.get("dictOptions"));
        assertEquals("province", cityField.get("cascadeParent"));
        assertEquals(java.util.Map.of("浙江", List.of("杭州")), cityField.get("cascadeOptions"));
        var ownerField = (java.util.Map<?, ?>) fields.get(2);
        assertEquals("USER", ownerField.get("refKind"));
        assertEquals("ALL", ownerField.get("refScope"));
        var photoField = (java.util.Map<?, ?>) fields.get(3);
        assertEquals("IMAGE", photoField.get("refKind"));
        assertEquals(Boolean.TRUE, photoField.get("multiple"));
    }

    @Test
    void idControlsRequireLongEnoughStringFields() {
        var shortPhoto = FillTableColumnDO.builder().name("photo").fieldCode("photo").cnName("照片")
                .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(50).fillType(6).build();
        var numericOwner = FillTableColumnDO.builder().name("owner").fieldCode("owner").cnName("负责人")
                .fieldType(ColumnMetaData.TYPE_INTEGER).fillType(4).build();
        for (var column : List.of(shortPhoto, numericOwner)) {
            var failure = assertThrows(ServiceException.class,
                    () -> tableVersionService.buildReleaseSnapshot(table, List.of(column)));
            assertEquals(FILL_TABLE_COLUMN_RULE_INVALID.getCode(), failure.getCode());
        }
    }

}
