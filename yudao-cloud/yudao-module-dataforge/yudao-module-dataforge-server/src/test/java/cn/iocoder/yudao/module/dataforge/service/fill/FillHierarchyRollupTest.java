package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillChildrenRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逐级汇总的计算：明细汇集保留来源、分组合计逐级正确、汇总状态与规则校验
 */
class FillHierarchyRollupTest {

    private static FillSubmissionRowDO row(String rowId, Map<String, Object> data) {
        return FillSubmissionRowDO.builder().rowId(rowId).data(JsonUtils.toJsonString(data)).build();
    }

    private static final FillHierarchyService.RollupRule DETAIL =
            new FillHierarchyService.RollupRule("DETAIL", List.of(), Map.of());

    @Test
    void detailKeepsOriginalEntryLocationAcrossLevels() {
        // 下级 21 自己录入的一行 + 下级 21 从它的下级 31 汇入的一行
        FillSubmissionRowDO own = row("r1", Map.of("name", "甲", "amount", "1.10"));
        FillSubmissionRowDO nested = row("ru9", Map.of("name", "乙", "amount", "2"));
        nested.setSourceKind(FillSubmissionRowDO.SOURCE_DETAIL);
        nested.setSourceOrgId(301L);
        nested.setSourceWorkItemId(31L);
        nested.setSourceRowId("leaf-row");
        nested.setSourceSubmissionId(310L);

        var rows = FillHierarchyService.buildRollupRows(DETAIL, 11L, 101L,
                List.of(new FillHierarchyService.ChildRows(21L, 201L, 210L, List.of(own, nested))));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(r -> {
            assertThat(r.data()).isEqualTo(Map.of("name", "甲", "amount", "1.10"));
            assertThat(r.sourceKind()).isEqualTo(FillSubmissionRowDO.SOURCE_DETAIL);
            assertThat(r.sourceOrgId()).isEqualTo(201L);
            assertThat(r.sourceWorkItemId()).isEqualTo(21L);
            assertThat(r.sourceRowId()).isEqualTo("r1");
            assertThat(r.sourceSubmissionId()).isEqualTo(210L);
        });
        // 来源组织与原始录入位置沿用最底层；来源提交是直接下级的提交
        assertThat(rows.get(1)).satisfies(r -> {
            assertThat(r.sourceOrgId()).isEqualTo(301L);
            assertThat(r.sourceWorkItemId()).isEqualTo(31L);
            assertThat(r.sourceRowId()).isEqualTo("leaf-row");
            assertThat(r.sourceSubmissionId()).isEqualTo(210L);
        });
    }

    @Test
    void groupTotalsAreExactAndCountsAddUpAcrossLevels() {
        var rule = new FillHierarchyService.RollupRule("GROUP", List.of("region"),
                Map.of("amount", "SUM", "peak", "MAX", "low", "MIN", "people", "COUNT", "note", "FIRST"));
        // 下级 A：两行明细；下级 B：一行已经分组合计过的结果（计数 3）
        var childA = new FillHierarchyService.ChildRows(21L, 201L, 210L, List.of(
                row("a1", Map.of("region", "东", "amount", "0.1", "peak", 5, "low", 5, "people", "张三", "note", "")),
                row("a2", Map.of("region", "东", "amount", "0.2", "peak", 9, "low", 2, "people", "李四", "note", "首个"))));
        FillSubmissionRowDO grouped = row("g1", Map.of("region", "东", "amount", "10", "peak", 7, "low", 1,
                "people", "3", "note", "其他"));
        grouped.setSourceKind(FillSubmissionRowDO.SOURCE_GROUP);
        var childB = new FillHierarchyService.ChildRows(22L, 202L, 220L, List.of(grouped,
                row("b2", Map.of("region", "西", "amount", "abc"))));

        var rows = FillHierarchyService.buildRollupRows(rule, 11L, 101L, List.of(childA, childB));

        assertThat(rows).hasSize(2);
        Map<String, Object> east = rows.get(0).data();
        assertThat(east).containsEntry("region", "东").containsEntry("amount", "10.3").containsEntry("peak", "9")
                .containsEntry("low", "1").containsEntry("people", "5").containsEntry("note", "首个");
        assertThat(rows.get(0).sourceKind()).isEqualTo(FillSubmissionRowDO.SOURCE_GROUP);
        assertThat(rows.get(0).sourceOrgId()).isEqualTo(101L);
        assertThat(rows.get(0).sourceWorkItemId()).isEqualTo(11L);
        // 非数值跳过，没有值的计数为 0
        assertThat(rows.get(1).data()).containsEntry("region", "西").containsEntry("people", "0");
        assertThat(rows.get(1).data().get("amount")).isNull();
        // 分组键摘要稳定：重复汇总得到相同的数据身份
        assertThat(FillHierarchyService.buildRollupRows(rule, 11L, 101L, List.of(childA, childB)).get(0).sourceRowId())
                .isEqualTo(rows.get(0).sourceRowId());
    }

    private static FillWorkItemDO child(long id, FillWorkItemStatusEnum status, Long submissionId) {
        return FillWorkItemDO.builder().id(id).parentWorkItemId(11L).status(status.getStatus())
                .currentSubmissionId(submissionId).build();
    }

    @Test
    void readyOnlyWhenAllChildrenRolledUpAndRollupIsCurrent() {
        List<FillWorkItemDO> children = List.of(child(21L, FillWorkItemStatusEnum.ROLLED_UP, 210L),
                child(22L, FillWorkItemStatusEnum.APPROVING, 220L),
                child(23L, FillWorkItemStatusEnum.CANCELLED, null));

        FillChildrenRespVO.RollupState waiting = FillHierarchyService.rollupState("DETAIL", children,
                Map.of(21L, 210L), null);
        assertThat(waiting.getReadyToSubmit()).isFalse();
        assertThat(waiting.getTotalCount()).isEqualTo(2);
        assertThat(waiting.getReviewingCount()).isEqualTo(1);
        assertThat(waiting.getBlockReason()).contains("1 个下级未审核通过");

        List<FillWorkItemDO> done = List.of(child(21L, FillWorkItemStatusEnum.ROLLED_UP, 211L),
                child(22L, FillWorkItemStatusEnum.ROLLED_UP, 220L));
        // 21 重新提交后审核通过（提交编号变了），汇总过期
        FillChildrenRespVO.RollupState stale = FillHierarchyService.rollupState("DETAIL", done,
                Map.of(21L, 210L, 22L, 220L), null);
        assertThat(stale.getStale()).isTrue();
        assertThat(stale.getReadyToSubmit()).isFalse();
        assertThat(stale.getBlockReason()).contains("汇总下级数据");

        assertThat(FillHierarchyService.rollupState("DETAIL", done, Map.of(21L, 211L, 22L, 220L), null)
                .getReadyToSubmit()).isTrue();
        // 下级全部撤销：不需要汇总
        assertThat(FillHierarchyService.rollupState("DETAIL",
                List.of(child(23L, FillWorkItemStatusEnum.CANCELLED, null)), Map.of(), null).getReadyToSubmit())
                .isTrue();
    }

    @Test
    void ruleIsParsedFromFrozenVersionAndDefaultsToDetail() {
        assertThat(FillHierarchyService.parseRule(null).mode()).isEqualTo("DETAIL");
        var rule = FillHierarchyService.parseRule(FillTableVersionDO.builder()
                .rollupSchema("{\"mode\":\"GROUP\",\"groupFields\":[\"region\"],\"funcs\":{\"amount\":\"SUM\"}}").build());
        assertThat(rule.mode()).isEqualTo("GROUP");
        assertThat(rule.groupFields()).containsExactly("region");
        assertThat(rule.funcs()).containsEntry("amount", "SUM");
    }

    @Test
    void dispatchTemplateAssigneesKeepLongIdsAsStrings() {
        assertThat(FillHierarchyService.parseAssignees(
                "[{\"userId\":\"9007199254740993\",\"userName\":\"张三\",\"deptId\":\"5\"},{\"userId\":\"x\"}]"))
                .containsExactly(new FillHierarchyService.AssigneeRef(9007199254740993L, "张三", 5L));
        assertThat(FillHierarchyService.parseAssignees("not json")).isEmpty();
    }

    private static FillTableColumnDO column(String code, int fieldType, String func) {
        return FillTableColumnDO.builder().fieldCode(code).cnName(code).fieldType(fieldType).rollupFunc(func).build();
    }

    @Test
    void groupRuleIsValidatedAtPublish() {
        List<FillTableColumnDO> columns = List.of(column("region", 2, ""), column("amount", 1, "SUM"),
                column("name", 2, "COUNT"));
        FillTableDO table = FillTableDO.builder().rollupMode("GROUP").rollupGroupFields("region").build();
        assertThat(FillTableVersionServiceImpl.buildRollupSchema(table, columns))
                .contains("\"groupFields\":[\"region\"]").contains("\"amount\":\"SUM\"");
        assertThat(FillTableVersionServiceImpl.buildRollupSchema(
                FillTableDO.builder().rollupMode("DETAIL").build(), columns)).isNull();
        // 文本字段不能求和；分组字段不能设计算方式；必须有分组字段
        assertThatThrownBy(() -> FillTableVersionServiceImpl.buildRollupSchema(table,
                List.of(column("region", 2, ""), column("name", 2, "SUM")))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> FillTableVersionServiceImpl.buildRollupSchema(table,
                List.of(column("region", 2, "COUNT"), column("amount", 1, "SUM")))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> FillTableVersionServiceImpl.buildRollupSchema(
                FillTableDO.builder().rollupMode("GROUP").rollupGroupFields("").build(), columns))
                .isInstanceOf(ServiceException.class);
    }

}
