package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTableVersionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableColumnMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeStrategyEnum;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTableVersionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_TARGET_SCHEMA_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FillTableReleaseTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FillTableServiceImpl fillTableService;
    @InjectMocks
    private FillTableVersionServiceImpl tableVersionService;
    @Mock
    private FillTableMapper tables;
    @Mock
    private FillTableColumnMapper columns;
    @Mock
    private FillTableVersionMapper versions;
    @Mock
    private AccessDatabaseResolver databaseResolver;
    @Mock(answer = RETURNS_DEEP_STUBS)
    private DbSwitchRuntime dbSwitchRuntime;
    @Mock
    private MetaDataByJdbcServiceImpl targetDatabase;

    private final FillTableDO table = FillTableDO.builder()
            .id(1L).databaseId(2L).tableEngName("students").tableCnName("学生信息").status(0).build();

    private final FillTableColumnDO studentName = FillTableColumnDO.builder()
            .name("student_name").fieldCode("student_name").cnName("学生姓名")
            .fieldType(ColumnMetaData.TYPE_STRING).fieldLength(50).build();

    private final FillTableColumnDO studentId = FillTableColumnDO.builder()
            .name("student_id").fieldCode("student_id").cnName("学生编号")
            .fieldType(ColumnMetaData.TYPE_INTEGER).fieldLength(9).pk(1).build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fillTableService, "tableVersionService", tableVersionService);
        when(tables.selectById(1L)).thenReturn(table);
        when(columns.selectListByTableId(1L)).thenReturn(List.of(studentName, studentId));
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema("public");
        when(databaseResolver.resolve(2L, FILL_TABLE_DATABASE_NOT_EXISTS)).thenReturn(connection);
        when(dbSwitchRuntime.products().metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(new DatabasePostgresImpl());
        when(dbSwitchRuntime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(targetDatabase);
    }

    @Test
    void releasingTableAlsoSavesCurrentSnapshot() {
        when(tables.selectByIdForUpdate(1L)).thenReturn(table);
        when(versions.insert(any(FillTableVersionDO.class))).thenAnswer(invocation -> {
            invocation.<FillTableVersionDO>getArgument(0).setId(3L);
            return 1;
        });

        fillTableService.releaseFillTable(1L);

        var savedSnapshot = ArgumentCaptor.forClass(FillTableVersionDO.class);
        var releasedTable = ArgumentCaptor.forClass(FillTableDO.class);
        var order = inOrder(targetDatabase, versions, tables);
        order.verify(targetDatabase).executeSql(isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.startsWith("CREATE TABLE"));
        order.verify(versions).insert(savedSnapshot.capture());
        order.verify(tables).updateById(releasedTable.capture());
        assertEquals(1, savedSnapshot.getValue().getVersionNo());
        assertEquals(FillMergeStrategyEnum.REPLACE_SCOPE.getStrategy(), savedSnapshot.getValue().getMergeStrategy());
        assertTrue(savedSnapshot.getValue().getFieldSchema().contains("student_name"));
        assertEquals(3L, releasedTable.getValue().getCurrentVersionId());
        assertEquals(1, releasedTable.getValue().getStatus());
    }

    @Test
    void ddlFailureLeavesTableAndSnapshotUnpublished() {
        doThrow(new IllegalStateException("建表失败")).when(targetDatabase)
                .executeSql(isNull(), isNull(), isNull(), anyString());

        assertThrows(IllegalStateException.class, () -> fillTableService.releaseFillTable(1L));

        verifyNoInteractions(versions);
        verify(tables, never()).updateById(any(FillTableDO.class));
    }

    @Test
    void republishingReusesCompatibleTargetAndUnchangedSnapshot() {
        var latest = tableVersionService.buildReleaseSnapshot(table, List.of(studentName, studentId));
        latest.setId(3L);
        latest.setVersionNo(1);
        table.setCurrentVersionId(3L);
        stubExistingTarget(existingColumns());
        when(tables.selectByIdForUpdate(1L)).thenReturn(table);
        when(versions.selectLatestByTableId(1L)).thenReturn(latest);

        fillTableService.releaseFillTable(1L);

        var releasedTable = ArgumentCaptor.forClass(FillTableDO.class);
        verify(tables).updateById(releasedTable.capture());
        assertEquals(1, releasedTable.getValue().getStatus());
        assertEquals(3L, releasedTable.getValue().getCurrentVersionId());
        verify(versions, never()).insert(any(FillTableVersionDO.class));
        verify(targetDatabase, never()).executeSql(any(), any(), any(), anyString());
    }

    @Test
    void republishingRefusesTargetWithoutSystemColumn() {
        var physicalColumns = existingColumns();
        physicalColumns.removeLast();
        stubExistingTarget(physicalColumns);

        var failure = assertThrows(ServiceException.class, () -> fillTableService.releaseFillTable(1L));

        assertEquals(FILL_TABLE_TARGET_SCHEMA_MISMATCH.getCode(), failure.getCode());
        assertTrue(failure.getMessage().contains("source_row_id"));
        verifyNoInteractions(versions);
        verify(tables, never()).updateById(any(FillTableDO.class));
        verify(targetDatabase, never()).executeSql(any(), any(), any(), anyString());
    }

    @Test
    void republishingRefusesTargetWithChangedColumnWidth() {
        var physicalColumns = existingColumns();
        physicalColumns.getFirst().setPrecisionSize(12);
        stubExistingTarget(physicalColumns);

        var failure = assertThrows(ServiceException.class, () -> fillTableService.releaseFillTable(1L));

        assertEquals(FILL_TABLE_TARGET_SCHEMA_MISMATCH.getCode(), failure.getCode());
        assertTrue(failure.getMessage().contains("student_name"));
        verifyNoInteractions(versions);
        verify(tables, never()).updateById(any(FillTableDO.class));
        verify(targetDatabase, never()).executeSql(any(), any(), any(), anyString());
    }

    private void stubExistingTarget(List<ColumnDescription> physicalColumns) {
        var physicalTable = new TableDescription();
        physicalTable.setSchemaName("public");
        physicalTable.setTableName("students");
        physicalTable.setTableType("TABLE");
        when(targetDatabase.queryTableList(null, null, null, "public")).thenReturn(List.of(physicalTable));
        when(targetDatabase.queryTableColumnsAndKeys(null, null, null, "public", "students"))
                .thenReturn(new BasicTableMetadata(physicalColumns, List.of("student_id")));
    }

    private List<ColumnDescription> existingColumns() {
        var physicalStudentId = observedColumn("student_id", "int8", 19);
        physicalStudentId.setNullable(false);
        return new ArrayList<>(List.of(
                observedColumn("student_name", "varchar", 50),
                physicalStudentId,
                observedColumn("source_org_id", "float8", 17),
                observedColumn("period_key", "varchar", 32),
                observedColumn("source_submission_id", "float8", 17),
                observedColumn("source_row_id", "varchar", 64)));
    }

    private ColumnDescription observedColumn(String name, String type, int precision) {
        var observed = new ColumnDescription();
        observed.setFieldName(name);
        observed.setFieldTypeName(type);
        observed.setPrecisionSize(precision);
        observed.setNullable(true);
        return observed;
    }

}
