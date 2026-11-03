package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableMeta;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabasePreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableMetaRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableRespVO;
import cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTableBrowseSupportTest {

    @Test
    void resolveTableNameUsesCatalogNameIgnoringCase() {
        DatabaseTableRespVO table = new DatabaseTableRespVO();
        table.setTableName("ODS_USER");
        assertEquals("ODS_USER",
                DatabaseTableBrowseSupport.resolveTableName(List.of(table), "ods_user"));
    }

    @Test
    void resolveTableNameRejectsUnknownOrBlank() {
        DatabaseTableRespVO table = new DatabaseTableRespVO();
        table.setTableName("t_user");
        ServiceException missing = assertThrows(ServiceException.class,
                () -> DatabaseTableBrowseSupport.resolveTableName(List.of(table), "secret"));
        assertEquals(ErrorCodeConstants.DATABASE_TABLE_NOT_EXISTS.getCode(), missing.getCode());
        ServiceException blank = assertThrows(ServiceException.class,
                () -> DatabaseTableBrowseSupport.resolveTableName(List.of(table), "  "));
        assertEquals(ErrorCodeConstants.DATABASE_TABLE_NOT_EXISTS.getCode(), blank.getCode());
    }

    @Test
    void resolveLimitClampsToPreviewBounds() {
        assertEquals(20, DatabaseTableBrowseSupport.resolveLimit(null));
        assertEquals(1, DatabaseTableBrowseSupport.resolveLimit(0));
        assertEquals(100, DatabaseTableBrowseSupport.resolveLimit(500));
        assertEquals(15, DatabaseTableBrowseSupport.resolveLimit(15));
    }

    @Test
    void toMetaVOMapsPhysicalTableAndColumns() {
        SchemaTableMeta meta = new SchemaTableMeta();
        meta.setSchemaName("public");
        meta.setTableName("t_user");
        meta.setTableType("TABLE");
        meta.setRemarks("用户");
        meta.setPrimaryKeys(List.of("id"));
        meta.setCreateSql("CREATE TABLE t_user (id bigint)");
        ColumnDescription column = new ColumnDescription();
        column.setFieldName("id");
        column.setFieldTypeName("bigint");
        column.setRemarks("主键");
        column.setNullable(false);
        column.setPk(true);
        column.setAutoIncrement(true);
        column.setLength(19);
        column.setPrecision(19);
        column.setScaleSize(0);
        column.setDefaultValue("1");
        meta.setColumns(List.of(column));

        DatabaseTableMetaRespVO vo = DatabaseTableBrowseSupport.toMetaVO(meta, "fallback");
        assertEquals("public", vo.getSchemaName());
        assertEquals("t_user", vo.getTableName());
        assertEquals("BASE TABLE", vo.getTableType());
        assertEquals("用户", vo.getRemarks());
        assertEquals(List.of("id"), vo.getPrimaryKeys());
        assertEquals("CREATE TABLE t_user (id bigint)", vo.getCreateSql());
        DatabaseTableColumnRespVO mapped = vo.getColumns().get(0);
        assertEquals("id", mapped.getFieldName());
        assertEquals("bigint", mapped.getFieldTypeName());
        assertEquals("主键", mapped.getRemarks());
        assertFalse(mapped.getNullable());
        assertTrue(mapped.getPk());
        assertTrue(mapped.getAutoIncrement());
        assertEquals(19, mapped.getLength());
        assertEquals("1", mapped.getDefaultValue());
    }

    @Test
    void toMetaVOMapsViewAndFallbackSchema() {
        SchemaTableMeta meta = new SchemaTableMeta();
        meta.setTableName("v_user");
        meta.setTableType("VIEW");
        DatabaseTableMetaRespVO vo = DatabaseTableBrowseSupport.toMetaVO(meta, "ods");
        assertEquals("ods", vo.getSchemaName());
        assertEquals("VIEW", vo.getTableType());
    }

    @Test
    void toPreviewVOTruncatesAndDeduplicatesColumns() {
        SchemaTableData data = new SchemaTableData();
        data.setColumns(List.of("id", "id", "name"));
        data.setRows(List.of(
                List.of(1, 2, "a"),
                List.of(3, 4, "b"),
                List.of(5, 6, "c")));
        DatabasePreviewRespVO vo = DatabaseTableBrowseSupport.toPreviewVO(data, 2);
        assertEquals(List.of("id", "id_2", "name"),
                vo.getColumns().stream().map(DatabasePreviewRespVO.Column::getName).toList());
        assertEquals(2, vo.getRows().size());
        assertTrue(vo.getTruncated());
        assertEquals(1, vo.getRows().get(0).get("id"));
        assertEquals(2, vo.getRows().get(0).get("id_2"));
        assertEquals("a", vo.getRows().get(0).get("name"));
    }

    @Test
    void isConnectionFailureRecognizesJdbcAndNetworkErrors() {
        assertTrue(DatabaseTableBrowseSupport.isConnectionFailure(
                new RuntimeException(new java.net.ConnectException("Connection refused"))));
        assertTrue(DatabaseTableBrowseSupport.isConnectionFailure(
                new java.sql.SQLException("The connection attempt failed", "08001")));
        assertTrue(DatabaseTableBrowseSupport.isConnectionFailure(
                new RuntimeException("password authentication failed for user \"ods\"")));
        assertFalse(DatabaseTableBrowseSupport.isConnectionFailure(
                new RuntimeException("column xyz does not exist")));
    }

    @Test
    void failureDetailUsesRootCauseAndTruncates() {
        assertEquals("Connection refused", DatabaseTableBrowseSupport.failureDetail(
                new RuntimeException(new java.net.ConnectException("Connection refused"))));
        assertEquals("未知错误", DatabaseTableBrowseSupport.failureDetail(new RuntimeException()));
        String longText = "x".repeat(400);
        String truncated = DatabaseTableBrowseSupport.failureDetail(new RuntimeException(longText));
        assertTrue(truncated.length() < longText.length());
        assertTrue(truncated.length() <= 303);
    }

    @Test
    void normalizeCellKeepsNumbersAndTruncatesLongText() {
        assertNull(DatabaseTableBrowseSupport.normalizeCell(null));
        assertEquals(12, DatabaseTableBrowseSupport.normalizeCell(12));
        assertEquals(true, DatabaseTableBrowseSupport.normalizeCell(true));
        String longText = "x".repeat(DatabaseTableBrowseSupport.MAX_CELL_CHARS + 10);
        String normalized = (String) DatabaseTableBrowseSupport.normalizeCell(longText);
        assertEquals(DatabaseTableBrowseSupport.MAX_CELL_CHARS, normalized.length());
    }

}
