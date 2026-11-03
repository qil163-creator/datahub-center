package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.TableIndexEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserverImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexFieldMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GenerateSqlUtils#getCreateIndexSqlList} 的方言 golden 输出：
 * 标识符引用与 CREATE TABLE 一致，索引名由目标表名和源索引名确定（ix_&lt;前缀&gt;_&lt;12位摘要&gt;），
 * 与主键相同或键序重复的索引按冗余跳过，不能建的索引整体跳过并保留原因。
 */
class GenerateSqlUtilsIndexDdlTest {

    // ---------------------------------------------------------------- PostgreSQL

    @Test
    void postgresGoldenStatementsKeepUniqueAndDescAndAreDeterministic() {
        var db = new DatabasePostgresImpl();
        var columns = postgresColumns();
        var indexes = List.of(
                index("ux_name", TableIndexEnum.UNIQUE, field("name", 1, Boolean.TRUE)),
                index("ix_created_desc", TableIndexEnum.NORMAL, field("created", 1, Boolean.FALSE)));

        var plan = GenerateSqlUtils.getCreateIndexSqlList(db, ProductTypeEnum.POSTGRESQL,
                columns, List.of("id"), "app", "sample", indexes);

        assertEquals(List.of(
                "CREATE UNIQUE INDEX \"ix_ux_name_dfee0dd7ab03\" ON \"app\".\"sample\" (\"name\")",
                "CREATE INDEX \"ix_ix_created_d_7bb85139368d\" ON \"app\".\"sample\" (\"created\" DESC)"),
                plan.statements());
        assertEquals(List.of(), plan.skipped());

        var again = GenerateSqlUtils.getCreateIndexSqlList(db, ProductTypeEnum.POSTGRESQL,
                postgresColumns(), List.of("id"), "app", "sample", indexes);
        assertEquals(plan.statements(), again.statements());
    }

    @Test
    void indexEqualToThePrimaryKeyIsSkippedAsRedundant() {
        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabasePostgresImpl(), ProductTypeEnum.POSTGRESQL,
                postgresColumns(), List.of("id"), "app", "sample",
                List.of(index("ux_id", TableIndexEnum.UNIQUE, field("id", 1, Boolean.TRUE))));

        assertEquals(List.of(), plan.statements());
        assertEquals(1, plan.skipped().size());
        var skipped = plan.skipped().get(0);
        assertEquals("ux_id", skipped.indexName());
        assertEquals("与主键列相同，已由主键约束保证", skipped.reason());
        assertTrue(skipped.redundant());
    }

    @Test
    void duplicateKeySignatureIsRetainedOnceWithUniquePreferred() {
        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabasePostgresImpl(), ProductTypeEnum.POSTGRESQL,
                postgresColumns(), List.of("id"), "app", "sample",
                List.of(index("ix_dup", TableIndexEnum.NORMAL, field("name", 1, Boolean.TRUE)),
                        index("ux_dup", TableIndexEnum.UNIQUE, field("name", 1, Boolean.TRUE))));

        assertEquals(List.of(
                "CREATE UNIQUE INDEX \"ix_ux_dup_5b3706004978\" ON \"app\".\"sample\" (\"name\")"),
                plan.statements());
        assertEquals(1, plan.skipped().size());
        var skipped = plan.skipped().get(0);
        assertEquals("ix_dup", skipped.indexName());
        assertEquals("与唯一索引 ux_dup 键序相同", skipped.reason());
        assertTrue(skipped.redundant());
    }

    @Test
    void nonIndexableClobLengthColumnSkipsTheIndexWithAReason() {
        var payload = column("payload", ColumnMetaData.TYPE_STRING, AbstractDatabase.CLOB_LENGTH, true);
        assertFalse(new DatabasePostgresImpl().canCreateIndex(payload.getMetaData()));

        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabasePostgresImpl(), ProductTypeEnum.POSTGRESQL,
                List.of(column("id", ColumnMetaData.TYPE_INTEGER, 9, false), payload), List.of("id"),
                "app", "sample",
                List.of(index("ix_payload", TableIndexEnum.NORMAL, field("payload", 1, Boolean.TRUE))));

        assertEquals(List.of(), plan.statements());
        assertEquals(1, plan.skipped().size());
        var skipped = plan.skipped().get(0);
        assertEquals("ix_payload", skipped.indexName());
        assertEquals("列 payload 的目标类型不能建索引", skipped.reason());
        assertFalse(skipped.redundant());
    }

    // ------------------------------------------------- unique index over a nullable column

    @Test
    void uniqueNullableColumnIsAllowedWhenSourceAndTargetArePostgres() {
        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabasePostgresImpl(), ProductTypeEnum.POSTGRESQL,
                postgresColumns(), List.of("id"), "app", "sample",
                List.of(index("ux_nullable", TableIndexEnum.UNIQUE, field("name", 1, Boolean.TRUE))));

        assertEquals(List.of(
                "CREATE UNIQUE INDEX \"ix_ux_nullable_9059bb516c35\" ON \"app\".\"sample\" (\"name\")"),
                plan.statements());
        assertEquals(List.of(), plan.skipped());
    }

    @Test
    void uniqueNullableColumnIsAllowedWhenTheTargetIsLessStrictThanTheSource() {
        // SQL Server 把 NULL 视为相等（更严格），迁到 PostgreSQL（多个 NULL 不冲突）不会拒绝已有数据
        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabasePostgresImpl(), ProductTypeEnum.SQLSERVER,
                postgresColumns(), List.of("id"), "app", "sample",
                List.of(index("ux_nullable", TableIndexEnum.UNIQUE, field("name", 1, Boolean.TRUE))));

        assertEquals(List.of(
                "CREATE UNIQUE INDEX \"ix_ux_nullable_9059bb516c35\" ON \"app\".\"sample\" (\"name\")"),
                plan.statements());
        assertEquals(List.of(), plan.skipped());
    }

    @Test
    void uniqueNullableColumnIsRejectedWhenTheTargetIsStricterThanTheSource() {
        // PostgreSQL 源允许多个 NULL；SQL Server 目标把 NULL 视为相等，重建会拒绝合法的源数据
        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabaseSqlserverImpl(), ProductTypeEnum.POSTGRESQL,
                postgresColumns(), List.of("id"), "app", "sample",
                List.of(index("ux_nullable", TableIndexEnum.UNIQUE, field("name", 1, Boolean.TRUE))));

        assertEquals(List.of(), plan.statements());
        assertEquals(1, plan.skipped().size());
        var skipped = plan.skipped().get(0);
        assertEquals("ux_nullable", skipped.indexName());
        assertEquals("唯一索引含可空列，目标产品对 NULL 的唯一性判断比源端更严格", skipped.reason());
        assertFalse(skipped.redundant());
    }

    // ---------------------------------------------------------------- MySQL

    @Test
    void mysqlGoldenStatementUsesBackticksAndALowercasedName() {
        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabaseMysqlImpl(), ProductTypeEnum.MYSQL,
                List.of(column("id", ColumnMetaData.TYPE_INTEGER, 9, false),
                        column("name", ColumnMetaData.TYPE_STRING, 50, true)),
                List.of("id"), "app", "sample",
                List.of(index("IX_Name", TableIndexEnum.NORMAL, field("name", 1, Boolean.TRUE))));

        assertEquals(List.of(
                "CREATE INDEX `ix_ix_name_c0be6c0ab13b` ON `app`.`sample` (`name`)"),
                plan.statements());
        assertEquals(List.of(), plan.skipped());
    }

    // ---------------------------------------------------------------- Oracle

    @Test
    void oracleGoldenStatementQualifiesTheIndexNameWithTheSchemaAndUppercasesIt() {
        var plan = GenerateSqlUtils.getCreateIndexSqlList(new DatabaseOracleImpl(), ProductTypeEnum.ORACLE,
                List.of(column("ID", ColumnMetaData.TYPE_INTEGER, 9, false),
                        column("NAME", ColumnMetaData.TYPE_STRING, 50, false)),
                List.of("ID"), "SCHEMA", "SAMPLE",
                List.of(index("IX_NAME", TableIndexEnum.NORMAL, field("NAME", 1, Boolean.TRUE))));

        assertEquals(List.of(
                "CREATE INDEX \"SCHEMA\".\"IX_IX_NAME_05E8753C348C\" ON \"SCHEMA\".\"SAMPLE\" (\"NAME\")"),
                plan.statements());
        assertEquals(List.of(), plan.skipped());
    }

    // ---------------------------------------------------------------- fixtures

    private static List<ColumnDescription> postgresColumns() {
        return List.of(
                column("id", ColumnMetaData.TYPE_INTEGER, 9, false),
                column("name", ColumnMetaData.TYPE_STRING, 50, true),
                column("created", ColumnMetaData.TYPE_TIMESTAMP, 0, true));
    }

    private static ColumnDescription column(String name, int valType, int length, boolean nullable) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setValType(valType);
        column.setLength(length);
        column.setNullable(nullable);
        return column;
    }

    private static IndexFieldMeta field(String name, int ordinal, Boolean ascending) {
        return new IndexFieldMeta(name, ordinal, ascending);
    }

    private static IndexDescription index(String name, TableIndexEnum type, IndexFieldMeta... fields) {
        return new IndexDescription(name, type, List.of(fields), null);
    }
}
