package cn.iocoder.yudao.module.governance.service.statictask;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.PostgresSchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.service.PostgresDmlTargetGuard;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskFieldRuleVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskingSpec;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 执行实际覆盖路径，用可观测事务替身区分目标已提交内容与事务内暂存内容。 */
class SafetyStaticTaskOverwriteTest {
    private final SafetyStaticTaskExecutor executor = new SafetyStaticTaskExecutor();
    private final MasterDatabaseResolver resolver = mock(MasterDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class, RETURNS_DEEP_STUBS);
    private final HikariDataSource source = mock(HikariDataSource.class);
    private final HikariDataSource target = mock(HikariDataSource.class);
    private final Connection sourceConnection = mock(Connection.class);
    private final Connection targetConnection = mock(Connection.class);
    private final IDatabaseOperator reader = mock(IDatabaseOperator.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final PreparedStatement insert = mock(PreparedStatement.class);
    private final Statement delete = mock(Statement.class);
    private final List<String> committed = new ArrayList<>(List.of("旧目标数据"));
    private final List<String> pending = new ArrayList<>(committed);
    private final AtomicBoolean autoCommit = new AtomicBoolean(true);
    private final MasterDatabaseResolver.ConnectionInfo sourceInfo = info(1L, "source_reader");
    private final MasterDatabaseResolver.ConnectionInfo targetInfo = info(2L, "target_writer");
    private final SafetyStaticTaskDO task = SafetyStaticTaskDO.builder().sourceDatabaseId(1L).targetDatabaseId(2L)
            .sourceTableName("source_rows").targetTableName("target_rows").writeMode("OVERWRITE").batchSize(1).build();
    private MockedStatic<PostgresSchemaChanges> schemas;
    private MockedStatic<PostgresDmlTargetGuard> dmlTargets;

    @BeforeEach
    void configure() throws Exception {
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        when(resolver.resolve(eq(1L), any())).thenReturn(sourceInfo);
        when(resolver.resolve(eq(2L), any())).thenReturn(targetInfo);
        when(resolver.createDataSource(sourceInfo)).thenReturn(source);
        when(resolver.createDataSource(targetInfo)).thenReturn(target);
        when(source.getConnection()).thenReturn(sourceConnection);
        when(target.getConnection()).thenReturn(targetConnection);
        var api = mock(DatabaseApi.class);
        var column = new DatabaseColumnRespDTO(); column.setColumnName("mobile");
        when(api.getTableColumnList(1L, "source_rows")).thenReturn(CommonResult.success(List.of(column)));
        ReflectionTestUtils.setField(executor, "databaseApi", api);
        var rules = mock(SafetyRuleService.class);
        var spec = SafetyMaskingSpec.builder().ruleId(1L).methodCode("MASK")
                .config(Map.of()).replaceItems(List.of()).build();
        when(rules.resolveUsableMaskingSpec(1L)).thenReturn(spec);
        ReflectionTestUtils.setField(executor, "ruleService", rules);
        when(runtime.reader(any(javax.sql.DataSource.class), eq(ProductTypeEnum.POSTGRESQL))).thenAnswer(call -> {
            var dataSource = call.getArgument(0, javax.sql.DataSource.class);
            assertThat(dataSource).isInstanceOf(org.springframework.jdbc.datasource.SingleConnectionDataSource.class);
            var borrowed = dataSource.getConnection();
            assertThat(borrowed).isInstanceOf(org.springframework.jdbc.datasource.ConnectionProxy.class);
            assertThat(((org.springframework.jdbc.datasource.ConnectionProxy) borrowed).getTargetConnection())
                    .isSameAs(sourceConnection);
            borrowed.close();
            verify(sourceConnection, never()).close();
            return reader;
        });
        var stream = mock(StatementResultSet.class);
        when(stream.getResultset()).thenReturn(rows);
        when(reader.queryTableData("fixture", "source_rows", List.of("mobile"))).thenReturn(stream);
        when(rows.getObject("mobile")).thenReturn("测试值");
        when(rows.next()).thenReturn(true, true, false);
        when(targetConnection.createStatement()).thenReturn(delete);
        when(targetConnection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
        doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; })
                .when(targetConnection).setAutoCommit(anyBoolean());
        when(targetConnection.prepareStatement(startsWith("INSERT INTO"))).thenReturn(insert);
        when(delete.executeUpdate(anyString())).thenAnswer(call -> { clearTarget(call.getArgument(0)); return 1; });
        when(delete.execute(anyString())).thenAnswer(call -> { clearTarget(call.getArgument(0)); return false; });
        when(insert.executeBatch()).thenAnswer(call -> { pending.add("脱敏值"); return new int[]{1}; });
        doAnswer(call -> { committed.clear(); committed.addAll(pending); return null; }).when(targetConnection).commit();
        doAnswer(call -> { pending.clear(); pending.addAll(committed); return null; }).when(targetConnection).rollback();
        schemas = mockStatic(PostgresSchemaChanges.class);
        dmlTargets = mockStatic(PostgresDmlTargetGuard.class);
        schemas.when(() -> PostgresSchemaChanges.physicalTargetFingerprint(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(call -> call.getArgument(0) == sourceConnection ? "source-physical" : "target-physical");
    }

    @AfterEach
    void clear() { schemas.close(); dmlTargets.close(); }

    @Test
    void differentPlatformIdsAndAccountsCannotOverwriteTheSamePhysicalTable() throws Exception {
        schemas.when(() -> PostgresSchemaChanges.physicalTargetFingerprint(any(), any(), anyString(), anyString(), any()))
                .thenReturn("same-physical-table");
        assertThatThrownBy(this::run).isInstanceOf(ServiceException.class);
        verify(targetConnection, never()).commit();
        verifyNoInteractions(delete, insert, reader);
        assertThat(committed).containsExactly("旧目标数据");
    }

    @Test
    void sourceOpeningFailureRollsBackWithoutClearingTarget() throws Exception {
        when(reader.queryTableData(anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("测试源读取失败"));
        assertThatThrownBy(this::run).isInstanceOf(Exception.class);
        verify(delete, never()).executeUpdate(anyString());
        verify(targetConnection, never()).commit();
        verify(targetConnection).rollback();
        assertThat(committed).containsExactly("旧目标数据");
    }

    @Test
    void sourceFailureAfterACompletedBatchRestoresAllOldRows() throws Exception {
        when(rows.next()).thenReturn(true).thenThrow(new SQLException("测试源游标中断"));
        assertThatThrownBy(this::run).isInstanceOf(Exception.class);
        verify(insert).executeBatch();
        assertRolledBack();
    }

    @Test
    void secondBatchFailureCannotLeaveAPartiallyOverwrittenTarget() throws Exception {
        when(insert.executeBatch()).thenAnswer(call -> { pending.add("首批脱敏值"); return new int[]{1}; })
                .thenThrow(new SQLException("测试第二批写入失败"));
        assertThatThrownBy(this::run).isInstanceOf(Exception.class);
        verify(insert, times(2)).executeBatch();
        assertRolledBack();
    }

    @Test
    void completeOverwriteCommitsOnlyAfterEveryBatchSucceeds() throws Exception {
        assertThat(run()).isEqualTo(2L);
        verify(insert, times(2)).executeBatch();
        verify(targetConnection).commit();
        verify(targetConnection, never()).rollback();
        assertThat(committed).containsExactly("脱敏值", "脱敏值");
    }

    @Test
    void appendKeepsTheTargetLockUntilEveryBatchHasSucceeded() throws Exception {
        task.setWriteMode("APPEND");
        assertThat(run()).isEqualTo(2L);
        verify(insert, times(2)).executeBatch();
        verify(targetConnection).commit();
        verify(delete, never()).executeUpdate(anyString());
        assertThat(committed).containsExactly("旧目标数据", "脱敏值", "脱敏值");
    }

    @Test
    void nonPostgresOverwriteIsRejectedBeforeMetadataOrConnectionSideEffects() {
        sourceInfo.setProductType(ProductTypeEnum.MYSQL);
        assertThatThrownBy(this::run).isInstanceOf(ServiceException.class);
        verify(resolver, never()).createDataSource(any());
        verifyNoInteractions(reader, delete, insert);
        assertThat(committed).containsExactly("旧目标数据");
    }

    @Test
    void nonPostgresAppendCannotBypassThePhysicalSourceBoundary() {
        task.setWriteMode("APPEND");
        targetInfo.setProductType(ProductTypeEnum.MYSQL);
        assertThatThrownBy(this::run).isInstanceOf(ServiceException.class);
        verify(resolver, never()).createDataSource(any());
        verifyNoInteractions(reader, delete, insert);
    }

    private Long run() {
        var rule = new SafetyStaticTaskFieldRuleVO(); rule.setColumnName("mobile"); rule.setRuleId(1L);
        return ReflectionTestUtils.invokeMethod(executor, "doExecute", task, List.of(rule));
    }
    private void assertRolledBack() throws SQLException {
        verify(targetConnection).rollback(); verify(targetConnection, never()).commit();
        assertThat(committed).containsExactly("旧目标数据"); assertThat(pending).containsExactly("旧目标数据");
    }
    private void clearTarget(String sql) {
        if (sql.startsWith("DELETE FROM") || sql.startsWith("TRUNCATE")) {
            pending.clear();
            if (autoCommit.get()) committed.clear();
        }
    }
    private static MasterDatabaseResolver.ConnectionInfo info(Long id, String user) {
        var value = new MasterDatabaseResolver.ConnectionInfo(); value.setDatabaseId(id); value.setSchema("fixture");
        value.setProductType(ProductTypeEnum.POSTGRESQL); value.setUsername(user); return value;
    }
}
