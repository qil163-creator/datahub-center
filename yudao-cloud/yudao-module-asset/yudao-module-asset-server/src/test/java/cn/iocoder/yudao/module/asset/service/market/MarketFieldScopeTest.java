package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryColumnRespVO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketFieldScopeTest {

    @Test
    void physicalNamesAreNotLowercasedSplitOrAuthorizedByDisplayLabels() {
        var apply = new cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO();
        apply.setSnapshot("{\"frozenColumns\":[\"Name\",\"family,name\",\" name \"]}");
        var scope = MarketFieldScope.effectiveScope(apply);
        var request = new DatabasePreviewReqDTO();
        MarketFieldScope.applyToPreviewRequest(request, scope);
        assertEquals(List.of("Name", "family,name", " name "), request.getColumns());
        var preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("Name", "姓名", "varchar"),
                new DatabasePreviewRespDTO.Column("name", "Name", "varchar"),
                new DatabasePreviewRespDTO.Column("secret", "Name", "varchar")));
        preview.setRows(List.of(Map.of("Name", "allowed", "name", "denied", "secret", "denied")));
        new MarketAccessServiceImpl().filterPreview(preview, scope);
        assertEquals(Map.of("Name", "allowed"), preview.getRows().getFirst());
        assertThrows(ServiceException.class, () -> new MarketAccessServiceImpl()
                .assertQueryColumnsAllowed(scope, false, List.of("name")));
    }

    @Test
    void emptyPreviewScopeIsRejectedEvenWithoutFilters() {
        assertThrows(ServiceException.class, () -> MarketFieldScope.applyToPreviewRequest(
                new DatabasePreviewReqDTO(), MarketFieldScope.parse(",,,")));
    }

    @Test
    void frozenSnapshotColumnsDoNotGrantLaterFields() {
        cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO apply =
                new cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO();
        apply.setFieldScope("");
        apply.setSnapshot("{\"frozenColumns\":[\"id\",\"name\"]}");
        assertEquals(java.util.Set.of("id", "name"), MarketFieldScope.effectiveScope(apply).getColumns());
        assertTrue(!MarketFieldScope.effectiveScope(apply).allows("secret"));
    }

    @Test
    void blankScopeMeansAllCurrentFields() {
        assertEquals(MarketFieldScope.Mode.ALL, MarketFieldScope.parse(null).getMode());
        assertEquals(MarketFieldScope.Mode.ALL, MarketFieldScope.parse("  ").getMode());
    }

    @Test
    void punctuationOnlyScopeIsEmptyRestriction() {
        assertEquals(MarketFieldScope.Mode.EMPTY, MarketFieldScope.parse(" ,，、 ").getMode());
        assertTrue(MarketFieldScope.parse("id,name").allows("NAME"));
        assertTrue(!MarketFieldScope.parse("id,name").allows("secret"));
    }

    @Test
    void unmatchedPreviewIsRejectedInsteadOfReturningAllColumns() {
        DatabasePreviewRespDTO preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(
                new DatabasePreviewRespDTO.Column("id", "id", "int"),
                new DatabasePreviewRespDTO.Column("name", "name", "varchar")));
        preview.setRows(List.of(Map.of("id", 1, "name", "a")));
        assertThrows(ServiceException.class,
                () -> new MarketAccessServiceImpl().filterPreview(preview, MarketFieldScope.parse("secret_col")));
    }

    @Test
    void matchedPreviewKeepsAllowedColumns() {
        DatabasePreviewRespDTO preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(
                new DatabasePreviewRespDTO.Column("id", "id", "int"),
                new DatabasePreviewRespDTO.Column("name", "name", "varchar")));
        preview.setRows(List.of(Map.of("id", 1, "name", "a")));
        new MarketAccessServiceImpl().filterPreview(preview, MarketFieldScope.parse("NAME"));
        assertEquals(1, preview.getColumns().size());
        assertEquals("name", preview.getColumns().getFirst().getName());
        assertEquals(Map.of("name", "a"), preview.getRows().getFirst());
    }

    @Test
    void unmatchedQueryColumnsAreRejected() {
        DataQueryColumnRespVO id = new DataQueryColumnRespVO();
        id.setColumnName("id");
        DataQueryColumnRespVO name = new DataQueryColumnRespVO();
        name.setColumnName("name");
        assertThrows(ServiceException.class, () -> new MarketAccessServiceImpl()
                .filterPreview(new ArrayList<>(List.of(id, name)), MarketFieldScope.parse("missing")));
    }

    @Test
    void filterOnDeniedColumnIsRejected() {
        DatabasePreviewReqDTO request = new DatabasePreviewReqDTO();
        DatabasePreviewReqDTO.Filter filter = new DatabasePreviewReqDTO.Filter();
        filter.setColumn("secret");
        filter.setOperator("eq");
        filter.setValue("1");
        request.setFilters(List.of(filter));
        assertThrows(ServiceException.class,
                () -> MarketFieldScope.applyToPreviewRequest(request, MarketFieldScope.parse("id,name")));
    }

    @Test
    void approvedUnionPreservesPhysicalCaseSpacesAndCommas() {
        var first = new cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO();
        first.setSnapshot("{\"frozenColumns\":[\"Id\",\"column name\"]}");
        var second = new cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO();
        second.setSnapshot("{\"frozenColumns\":[\"Id\",\"id\",\"last,first\"]}");
        var scope = MarketFieldScope.unionApproved(List.of(MarketFieldScope.effectiveScope(first),
                MarketFieldScope.effectiveScope(second)));
        assertEquals(List.of("Id", "column name", "id", "last,first"), new ArrayList<>(scope.getColumns()));
        assertTrue(!scope.allows("ID", "column", "last", "first"));
    }

    @Test
    void approvedUnionDoesNotAcceptFormAllOrUnprovenTextScopes() {
        assertThrows(ServiceException.class, () -> MarketFieldScope.unionApproved(List.of(MarketFieldScope.parse(""))));
        assertThrows(ServiceException.class, () -> MarketFieldScope.unionApproved(List.of(MarketFieldScope.parse("id"))));
        assertThrows(ServiceException.class, () -> MarketFieldScope.unionApproved(List.of()));
    }

}
