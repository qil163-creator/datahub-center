package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 模型结构比对：对齐规则、属性归一化与汇总。 */
class ModelFieldDiffTest {

    private static ModelFieldItem field(String uid, String name, String cnName, String type, Integer length) {
        ModelFieldItem item = new ModelFieldItem();
        item.setFieldUid(uid);
        item.setFieldName(name);
        item.setCnName(cnName);
        item.setDataType(type);
        item.setDataLength(length);
        return item;
    }

    @Test
    void alignsByUidFirstSoRenameIsModificationNotAddRemove() {
        var before = List.of(field("u1", "cust_name", "客户名称", "VARCHAR", 50));
        var after = List.of(field("u1", "customer_name", "客户名称", "varchar", 100));

        ModelDiffRespVO diff = ModelFieldDiff.compare(null, before, null, after, ModelFieldDiff.FIELD_ATTRIBUTES);

        assertEquals(1, diff.getFields().size());
        var change = diff.getFields().getFirst();
        assertEquals(ModelDiffRespVO.MODIFIED, change.getChangeType());
        assertEquals(List.of("fieldName", "dataLength"), change.getChanges().stream()
                .map(ModelDiffRespVO.AttributeChange::getKey).toList(), "数据类型大小写不算变化");
        assertEquals("50", change.getChanges().get(1).getBefore());
        assertEquals("100", change.getChanges().get(1).getAfter());
        assertEquals(1, diff.getSummary().getModified());
        assertTrue(diff.getHeaderChanges().isEmpty());
    }

    @Test
    void fallsBackToCaseInsensitiveNameWhenUidMissingAndReportsAddedAndRemoved() {
        var current = List.of(field("u1", "ID", "编号", "BIGINT", null),
                field("u2", "old_col", "旧字段", "VARCHAR", 20));
        var source = List.of(field(null, "id", "id", "BIGINT", null),
                field(null, "new_col", "new_col", "TEXT", null));

        ModelDiffRespVO diff = ModelFieldDiff.compare(null, current, null, source,
                ModelFieldDiff.STRUCTURE_ATTRIBUTES);

        assertEquals(List.of(ModelDiffRespVO.MODIFIED, ModelDiffRespVO.ADDED, ModelDiffRespVO.REMOVED),
                diff.getFields().stream().map(ModelDiffRespVO.FieldChange::getChangeType).toList());
        assertEquals("u1", diff.getFields().getFirst().getFieldUid(), "按名称对齐时沿用原字段标识");
        assertEquals(List.of("fieldName"), diff.getFields().getFirst().getChanges().stream()
                .map(ModelDiffRespVO.AttributeChange::getKey).toList(), "结构比对不比较中文名");
        assertEquals("old_col", diff.getFields().get(2).getFieldName());
        assertEquals(1, diff.getSummary().getAdded());
        assertEquals(1, diff.getSummary().getRemoved());
    }

    @Test
    void flagDefaultsAndNumericTypesDoNotProduceFalseChanges() {
        var before = field("u1", "amount", "金额", "DECIMAL", null);
        before.setNullableFlag(null);
        before.setPkFlag(null);
        var after = field("u1", "amount", "金额", "DECIMAL", null);
        after.setNullableFlag(true);
        after.setPkFlag(false);

        ModelDiffRespVO diff = ModelFieldDiff.compare(Map.of("layerId", 3), List.of(before),
                Map.of("layerId", 3L), List.of(after), ModelFieldDiff.FIELD_ATTRIBUTES);

        assertEquals(ModelDiffRespVO.UNCHANGED, diff.getFields().getFirst().getChangeType());
        assertEquals(1, diff.getSummary().getUnchanged());
        assertTrue(diff.getHeaderChanges().isEmpty(), "整数与长整数编号视为相同");
    }

    @Test
    void headerChangesAreLabelledAndCountedInSummary() {
        ModelDiffRespVO diff = ModelFieldDiff.compare(Map.of("name", "客户", "description", "旧"), List.of(),
                Map.of("name", "客户主题", "description", "旧"), List.of(), ModelFieldDiff.FIELD_ATTRIBUTES);

        assertEquals(1, diff.getHeaderChanges().size());
        assertEquals("中文名称", diff.getHeaderChanges().getFirst().getLabel());
        assertEquals(Map.of("added", 0, "removed", 0, "modified", 0, "headerChanged", 1),
                ModelFieldDiff.summaryOf(diff));
    }

    @Test
    void booleanChangesAreShownAsYesNo() {
        var before = field("u1", "id", "编号", "BIGINT", null);
        var after = field("u1", "id", "编号", "BIGINT", null);
        after.setPkFlag(true);

        var change = ModelFieldDiff.compare(null, List.of(before), null, List.of(after),
                ModelFieldDiff.FIELD_ATTRIBUTES).getFields().getFirst().getChanges().getFirst();

        assertEquals("主键", change.getLabel());
        assertEquals("否", change.getBefore());
        assertEquals("是", change.getAfter());
    }
}
