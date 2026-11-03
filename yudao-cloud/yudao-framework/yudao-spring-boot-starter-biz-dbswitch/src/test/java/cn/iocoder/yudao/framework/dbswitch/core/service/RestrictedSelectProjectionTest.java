package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行前投影：所有被引用的列（SELECT/WHERE/ORDER BY）都必须在允许集合内，通配符在执行前展开为明确列。
 * 组件只做技术限制，不知道审批、用户或租户。
 */
class RestrictedSelectProjectionTest {

    private static final List<String> PHYSICAL = List.of("id", "name", "phone", "salary");

    private static RestrictedSelectProjection restrict(String sql, Set<String> allowed) {
        return RestrictedSelectGuard.restrictProjection(ProductTypeEnum.POSTGRESQL, sql,
                "public", "citizen", PHYSICAL, allowed);
    }

    @Test
    void cannotFilterByAnUnapprovedColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen where salary > 10000", Set.of("id", "name")));
    }

    @Test
    void cannotOrderByAnUnapprovedColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen order by phone", Set.of("id", "name")));
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen order by c.phone desc", Set.of("id", "name")));
    }

    @Test
    void approvedFilterAndOrderAreKeptWithTheirReferences() {
        RestrictedSelectProjection projection =
                restrict("select id from public.citizen where name = 'x' order by name desc", Set.of("id", "name"));
        assertEquals(Set.of("id"), projection.selectedColumns());
        assertEquals(Set.of("id", "name"), projection.referencedColumns());
        assertTrue(projection.sql().contains("\"name\""));
        assertTrue(projection.sql().toUpperCase().contains("ORDER BY"));
        assertFalse(projection.sql().contains("salary"));
    }

    @Test
    void wildcardIsExpandedToApprovedColumnsBeforeExecution() {
        RestrictedSelectProjection projection = restrict("select * from public.citizen", Set.of("id", "phone"));
        assertFalse(projection.sql().contains("*"));
        assertEquals(Set.of("id", "phone"), projection.selectedColumns());
        assertEquals(Set.of("id", "phone"), projection.referencedColumns());
        assertTrue(projection.sql().contains("\"id\""));
        assertTrue(projection.sql().contains("\"phone\""));
        assertFalse(projection.sql().contains("name"));
        assertFalse(projection.sql().contains("salary"));
    }

    @Test
    void qualifiedWildcardIsAlsoExpanded() {
        RestrictedSelectProjection projection = restrict("select c.* from public.citizen c", Set.of("id"));
        assertFalse(projection.sql().contains("*"));
        assertEquals(Set.of("id"), projection.selectedColumns());
    }

    @Test
    void aliasCannotShadowAnUnapprovedPhysicalColumn() {
        // 输出别名叫 name 不能让未批准的 salary 通过。
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select salary as name from public.citizen", Set.of("id", "name")));
        // 别名与另一物理列同名时，排序引用不得被解释成该别名。
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id as phone from public.citizen order by phone", Set.of("id")));
    }

    @Test
    void unknownQualifierIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select other.id from public.citizen c", Set.of("id")));
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen c where other.name = 'x'", Set.of("id", "name")));
    }

    @Test
    void schemaOrTableMismatchIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from other.citizen", Set.of("id")));
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.resident", Set.of("id")));
        // 未写 schema 时按调用方给定的绑定执行，不猜测其他 schema。
        RestrictedSelectProjection projection = restrict("select id from citizen", Set.of("id"));
        assertTrue(projection.sql().contains("\"public\".\"citizen\""));
    }

    @Test
    void postgresFoldsUnquotedIdentifiersAndMatchesQuotedOnesExactly() {
        // 带引号的 "ID" 在 PostgreSQL 中不是 id，必须拒绝；不带引号的 ID 按库语义折叠为 id。
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select \"ID\" from public.citizen", Set.of("id")));
        assertEquals(Set.of("id"), restrict("select ID from public.citizen", Set.of("id")).selectedColumns());
        RestrictedSelectProjection projection = restrict("select \"id\" from \"public\".\"citizen\"", Set.of("id"));
        assertEquals(Set.of("id"), projection.selectedColumns());
        assertTrue(projection.sql().contains("\"id\""));
    }

    @Test
    void mixedCasePhysicalColumnsAreNotLowercased() {
        List<String> physical = List.of("id", "Phone");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select \"Phone\" from public.citizen",
                "public", "citizen", physical, Set.of("id", "Phone"));
        assertEquals(Set.of("Phone"), projection.selectedColumns());
        assertTrue(projection.sql().contains("\"Phone\""));
        // 不带引号的 Phone 会被数据库折叠成 phone，元数据中没有该列，必须拒绝而不是猜测。
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select Phone from public.citizen",
                "public", "citizen", physical, Set.of("id", "Phone")));
    }

    @Test
    void mysqlColumnNamesAreCaseInsensitiveButAmbiguousMetadataIsRejected() {
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.MYSQL, "select `ID` from app.citizen",
                "app", "citizen", PHYSICAL, Set.of("id"));
        assertEquals(Set.of("id"), projection.selectedColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.MYSQL, "select id from app.citizen",
                "app", "citizen", List.of("id", "ID"), Set.of("id", "ID")));
    }

    @Test
    void dorisSchemaAndTableNamesMustMatchTheirPhysicalCase() {
        for (String sql : List.of(
                "select id from App.citizen",
                "select id from app.Citizen",
                "select id from `App`.`citizen`",
                "select id from `app`.`Citizen`")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                    ProductTypeEnum.DORIS, sql, "app", "citizen", PHYSICAL, Set.of("id")), sql);
        }
        for (String sql : List.of(
                "select id from app.Citizen",
                "select id from App.citizen",
                "select id from `app`.`Citizen`",
                "select id from `App`.`citizen`")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                    ProductTypeEnum.DORIS, sql, "App", "Citizen", PHYSICAL, Set.of("id")), sql);
        }
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DORIS, "select id from `App`.`Citizen`",
                "App", "Citizen", PHYSICAL, Set.of("id"));
        assertTrue(projection.sql().contains("`App`.`Citizen`"), projection.sql());
    }

    @Test
    void dorisRelationCaseDoesNotChangeColumnOrAliasMatching() {
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DORIS,
                "select c.ID, c.NAME from App.Citizen c where c.Name = 'row' order by c.ID",
                "App", "Citizen", PHYSICAL, Set.of("id", "name"));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertEquals(Set.of("id", "name"), projection.referencedColumns());
        assertTrue(projection.sql().contains("`App`.`Citizen`"), projection.sql());
        assertTrue(projection.sql().contains("`id`"), projection.sql());
        assertTrue(projection.sql().contains("`name`"), projection.sql());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DORIS, "select ID from App.Citizen",
                "App", "Citizen", List.of("id", "ID"), Set.of("id")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DORIS, "select ID from App.Citizen where SALARY > 0",
                "App", "Citizen", PHYSICAL, Set.of("id", "name")));
    }

    @Test
    void dorisQuotedRelationNamesAreDecodedWithoutFoldingOrSplitting() {
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DORIS, "select ID from `Gov.Db`.`A``.B`",
                "Gov.Db", "A`.B", PHYSICAL, Set.of("id"));
        assertEquals(Set.of("id"), projection.selectedColumns());
        assertTrue(projection.sql().contains("`Gov.Db`.`A``.B`"), projection.sql());
        for (String sql : List.of(
                "select id from `Gov.db`.`A``.B`",
                "select id from `Gov.Db`.`a``.B`")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                    ProductTypeEnum.DORIS, sql, "Gov.Db", "A`.B", PHYSICAL, Set.of("id")), sql);
        }
    }

    @Test
    void dorisUnqualifiedRelationStillUsesTheAuthorizedSchema() {
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DORIS, "select ID from Citizen",
                "App", "Citizen", PHYSICAL, Set.of("id"));
        assertTrue(projection.sql().contains("`App`.`Citizen`"), projection.sql());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DORIS, "select ID from citizen",
                "App", "Citizen", PHYSICAL, Set.of("id")));
    }

    @Test
    void dorisRelationMatchingDoesNotOpenExactAuthorizationApis() {
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseBound(
                ProductTypeEnum.DORIS, "select id from citizen", "app"));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseTableReference(
                ProductTypeEnum.DORIS, "app.citizen", "app"));
    }

    @Test
    void unsupportedSyntaxIsRejectedInsteadOfPartiallyChecked() {
        for (String sql : List.of(
                "select count(id) from public.citizen",
                "select id from public.citizen group by id",
                "select id from public.citizen group by id having count(id) > 1",
                "select distinct on (id) id from public.citizen",
                "select id, row_number() over (order by id) from public.citizen",
                "select id from public.citizen order by 1",
                "select id from public.citizen for update",
                "select id from public.citizen where lower(name) = 'x'",
                "select id from public.citizen where id = (select max(id) from public.citizen)",
                "select id from public.citizen -- comment\n; select id from public.citizen")) {
            assertThrows(IllegalArgumentException.class, () -> restrict(sql, Set.of("id", "name")), sql);
        }
    }

    @Test
    void allowedColumnsMustBeConsistentWithPhysicalMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen", Set.of("id", "unknown_column")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select id from public.citizen",
                "public", "citizen", List.of("id", "id"), Set.of("id")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select id from public.citizen",
                "public", "citizen", List.of(), Set.of("id")));
    }

    @Test
    void bindingArgumentsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select id from citizen", " ", "citizen", PHYSICAL, Set.of("id")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select id from citizen", "public", null, PHYSICAL, Set.of("id")));
    }

    @Test
    void parametersAndLiteralsAreKept() {
        RestrictedSelectProjection projection =
                restrict("select id from public.citizen where name = ? and id > 10", Set.of("id", "name"));
        assertTrue(projection.sql().contains("?"));
        assertEquals(Set.of("id", "name"), projection.referencedColumns());
    }

    @Test
    void mysqlProducesItsOwnQuotedProjection() {
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.MYSQL, "select * from app.citizen where name = 'x'",
                "app", "citizen", PHYSICAL, Set.of("id", "name"));
        assertTrue(projection.sql().contains("`id`"));
        assertTrue(projection.sql().contains("`citizen`"));
        assertFalse(projection.sql().contains("\""));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
    }

    @Test
    void clickHouseUsesExactNamesAndRejectsEngineClauses() {
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.CLICKHOUSE, "select id, name from app.citizen where name = 'row' order by id limit 1",
                "app", "citizen", PHYSICAL, Set.of("id", "name"));
        assertTrue(projection.sql().contains("`id`"));
        assertTrue(projection.sql().contains("`citizen`"));
        assertFalse(projection.sql().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.CLICKHOUSE, "select id from app.citizen where name = \"row\"",
                "app", "citizen", PHYSICAL, Set.of("id", "name")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.CLICKHOUSE, "select Id from app.citizen",
                "app", "citizen", PHYSICAL, Set.of("id", "name")));
        for (String sql : List.of(
                "select id from app.citizen final",
                "select id from app.citizen prewhere id > 0",
                "select id from app.citizen settings max_threads = 1",
                "select id from app.citizen format JSON",
                "select id from app.citizen where name = 'x' -- secret",
                "select id from app.citizen array join secret")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                    ProductTypeEnum.CLICKHOUSE, sql, "app", "citizen", PHYSICAL, Set.of("id", "name")), sql);
        }
    }

    @Test
    void hiveUsesCaseInsensitiveNamesAndRejectsEngineClauses() {
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.HIVE, "select Id, Name from app.Citizen where name = 'row' order by id limit 1",
                "app", "citizen", PHYSICAL, Set.of("id", "name"));
        assertTrue(projection.sql().contains("`id`"));
        assertTrue(projection.sql().contains("`name`"));
        assertTrue(projection.sql().contains("`citizen`"));
        assertFalse(projection.sql().contains("secret"));
        assertFalse(projection.sql().contains("\""));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.HIVE, "select id from app.citizen where name = \"secret\"",
                "app", "citizen", PHYSICAL, Set.of("id", "name")));
        for (String sql : List.of(
                "select id from app.citizen sort by secret",
                "select id from app.citizen cluster by secret",
                "select id from app.citizen distribute by secret",
                "select id from app.citizen lateral view explode(secret) v as item")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                    ProductTypeEnum.HIVE, sql, "app", "citizen", PHYSICAL, Set.of("id", "name")), sql);
        }
    }

    @Test
    void greenplumFoldsUnquotedNamesAndKeepsQuotedCase() {
        List<String> physical = List.of("id", "name", "secret");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.GREENPLUM,
                "select Id, Name from app.Citizen where name = 'row' order by id limit 1",
                "app", "citizen", physical, Set.of("id", "name"));
        assertTrue(projection.sql().contains("\"id\""));
        assertTrue(projection.sql().contains("\"name\""));
        assertTrue(projection.sql().contains("\"citizen\""));
        assertFalse(projection.sql().contains("secret"));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.GREENPLUM, "select \"Id\" from app.citizen",
                "app", "citizen", physical, Set.of("id", "name")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.GREENPLUM, "select id from app.citizen where name = \"secret\"",
                "app", "citizen", physical, Set.of("id", "name")));
        List<String> mixed = List.of("Id", "id", "name");
        assertEquals(Set.of("id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.GREENPLUM, "select id from app.citizen",
                "app", "citizen", mixed, Set.of("id", "name")).selectedColumns());
        assertEquals(Set.of("Id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.GREENPLUM, "select \"Id\" from app.citizen",
                "app", "citizen", mixed, Set.of("Id", "name")).selectedColumns());
    }

    @Test
    void damengFoldsUnquotedNamesToUppercase() {
        List<String> physical = List.of("ID", "NAME", "SECRET");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DM, "select id, name from SYSDBA.citizen where name = 'row' order by id",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME"));
        assertTrue(projection.sql().contains("\"ID\""));
        assertTrue(projection.sql().contains("\"NAME\""));
        assertTrue(projection.sql().contains("\"CITIZEN\""));
        assertFalse(projection.sql().toUpperCase().contains("SECRET"));
        assertEquals(Set.of("ID", "NAME"), projection.selectedColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DM, "select \"Id\" from SYSDBA.citizen",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.DM, "select id from SYSDBA.citizen where secret is not null",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME")));
    }

    @Test
    void kingbaseMatchesQuotedAndUnquotedNamesCaseInsensitively() {
        List<String> physical = List.of("id", "name", "secret");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.KINGBASE,
                "select Id, Name from public.Citizen where name = 'row' order by id limit 1",
                "public", "citizen", physical, Set.of("id", "name"));
        assertTrue(projection.sql().contains("\"id\""));
        assertTrue(projection.sql().contains("\"name\""));
        assertTrue(projection.sql().contains("\"citizen\""));
        assertFalse(projection.sql().contains("secret"));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertEquals(Set.of("id", "name"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.KINGBASE, "select \"Id\", \"Name\" from public.citizen",
                "public", "citizen", physical, Set.of("id", "name")).selectedColumns());
        IllegalArgumentException unauthorized = assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.restrictProjection(
                        ProductTypeEnum.KINGBASE, "select \"Secret\" from public.citizen",
                        "public", "citizen", physical, Set.of("id", "name")));
        assertTrue(unauthorized.getMessage().contains("未授权"));
        List<String> quotedStored = List.of("Id", "name", "secret");
        assertEquals(Set.of("Id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.KINGBASE, "select id from public.citizen",
                "public", "citizen", quotedStored, Set.of("Id", "name")).selectedColumns());
        assertEquals(Set.of("Id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.KINGBASE, "select \"ID\" from public.citizen",
                "public", "citizen", quotedStored, Set.of("Id", "name")).selectedColumns());
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.restrictProjection(
                        ProductTypeEnum.KINGBASE, "select id from public.citizen",
                        "public", "citizen", List.of("Id", "id", "name"), Set.of("id")));
        assertTrue(duplicate.getMessage().contains("仅大小写不同"));
    }

    @Test
    void oscarFoldsUnquotedNamesToUppercase() {
        List<String> physical = List.of("ID", "NAME", "SECRET");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.OSCAR, "select id, name from SYSDBA.citizen where name = 'row' order by id",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME"));
        assertTrue(projection.sql().contains("\"ID\""));
        assertTrue(projection.sql().contains("\"NAME\""));
        assertTrue(projection.sql().contains("\"CITIZEN\""));
        assertFalse(projection.sql().toUpperCase().contains("SECRET"));
        assertEquals(Set.of("ID", "NAME"), projection.selectedColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.OSCAR, "select \"Id\" from SYSDBA.citizen",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.OSCAR, "select id from SYSDBA.citizen where secret is not null",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.OSCAR, "select `SECRET` from SYSDBA.citizen",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.OSCAR, "select id from SYSDBA.citizen for share",
                "SYSDBA", "CITIZEN", physical, Set.of("ID", "NAME")));
    }

    @Test
    void highgoFoldsUnquotedNamesAndKeepsQuotedCase() {
        List<String> physical = List.of("id", "name", "secret");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.HIGHGO,
                "select Id, Name from public.Citizen where name = 'row' order by id limit 1",
                "public", "citizen", physical, Set.of("id", "name"));
        assertTrue(projection.sql().contains("\"id\""));
        assertTrue(projection.sql().contains("\"name\""));
        assertTrue(projection.sql().contains("\"citizen\""));
        assertFalse(projection.sql().contains("secret"));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.HIGHGO, "select \"Id\" from public.citizen",
                "public", "citizen", physical, Set.of("id", "name")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.HIGHGO, "select `secret` from public.citizen",
                "public", "citizen", physical, Set.of("id", "name")));
        List<String> mixed = List.of("Id", "id", "name");
        assertEquals(Set.of("id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.HIGHGO, "select id from public.citizen",
                "public", "citizen", mixed, Set.of("id", "name")).selectedColumns());
        assertEquals(Set.of("Id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.HIGHGO, "select \"Id\" from public.citizen",
                "public", "citizen", mixed, Set.of("Id", "name")).selectedColumns());
    }

    @Test
    void uxdbFoldsUnquotedNamesAndKeepsQuotedCase() {
        List<String> physical = List.of("id", "name", "secret");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.UXDB,
                "select Id, Name from public.Citizen where name = 'row' order by id limit 1",
                "public", "citizen", physical, Set.of("id", "name"));
        assertTrue(projection.sql().contains("\"id\""));
        assertTrue(projection.sql().contains("\"name\""));
        assertTrue(projection.sql().contains("\"citizen\""));
        assertFalse(projection.sql().contains("secret"));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.UXDB, "select \"Id\" from public.citizen",
                "public", "citizen", physical, Set.of("id", "name")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.UXDB, "select `secret` from public.citizen",
                "public", "citizen", physical, Set.of("id", "name")));
        List<String> mixed = List.of("Id", "id", "name");
        assertEquals(Set.of("id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.UXDB, "select id from public.citizen",
                "public", "citizen", mixed, Set.of("id", "name")).selectedColumns());
        assertEquals(Set.of("Id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.UXDB, "select \"Id\" from public.citizen",
                "public", "citizen", mixed, Set.of("Id", "name")).selectedColumns());
    }

    @Test
    void gbase8aMatchesNamesCaseInsensitivelyAndUsesBackticks() {
        List<String> physical = List.of("id", "name", "secret");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.GBASE8A,
                "select Id, Name from app.Citizen where name = 'row' order by id limit 1",
                "app", "citizen", physical, Set.of("id", "name"));
        assertTrue(projection.sql().contains("`id`"));
        assertTrue(projection.sql().contains("`name`"));
        assertTrue(projection.sql().contains("`citizen`"));
        assertFalse(projection.sql().contains("secret"));
        assertFalse(projection.sql().contains("\""));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertEquals(Set.of("id", "name"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.GBASE8A, "select `Id`, `Name` from app.citizen",
                "app", "citizen", physical, Set.of("id", "name")).selectedColumns());
        IllegalArgumentException unauthorized = assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.restrictProjection(
                        ProductTypeEnum.GBASE8A, "select \"Secret\" from app.citizen",
                        "app", "citizen", physical, Set.of("id", "name")));
        assertTrue(unauthorized.getMessage().contains("未授权"));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.restrictProjection(
                        ProductTypeEnum.GBASE8A, "select id from app.citizen",
                        "app", "citizen", List.of("Id", "id", "name"), Set.of("id")));
        assertTrue(duplicate.getMessage().contains("仅大小写不同"));
    }

    @Test
    void sybaseKeepsIdentifierCaseAndUsesDoubleQuotes() {
        List<String> physical = List.of("id", "name", "secret");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SYBASE,
                "select id, name from dbo.citizen where name = 'row' order by id",
                "dbo", "citizen", physical, Set.of("id", "name"));
        assertTrue(projection.sql().contains("\"id\""));
        assertTrue(projection.sql().contains("\"name\""));
        assertTrue(projection.sql().contains("\"citizen\""));
        assertFalse(projection.sql().toLowerCase().contains("secret"));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SYBASE, "select ID from dbo.citizen",
                "dbo", "citizen", physical, Set.of("id", "name")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SYBASE, "select [secret] from dbo.citizen",
                "dbo", "citizen", physical, Set.of("id", "name")));
        RestrictedSelectProjection locked = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SYBASE, "select id from dbo.citizen holdlock",
                "dbo", "citizen", physical, Set.of("id", "name"));
        assertFalse(locked.sql().toLowerCase().contains("holdlock"));
        List<String> mixed = List.of("Id", "id", "name");
        assertEquals(Set.of("id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SYBASE, "select id from dbo.citizen",
                "dbo", "citizen", mixed, Set.of("id", "name")).selectedColumns());
        assertEquals(Set.of("Id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SYBASE, "select \"Id\" from dbo.citizen",
                "dbo", "citizen", mixed, Set.of("Id", "name")).selectedColumns());
    }

    @Test
    void sqlServer2000MatchesNamesCaseInsensitivelyAndUsesDoubleQuotes() {
        List<String> physical = List.of("id", "name", "secret");
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SQLSERVER2000,
                "select Id, Name from dbo.citizen where name = 'row' order by id",
                "dbo", "citizen", physical, Set.of("id", "name"));
        assertTrue(projection.sql().contains("\"id\""));
        assertTrue(projection.sql().contains("\"name\""));
        assertTrue(projection.sql().contains("\"citizen\""));
        assertFalse(projection.sql().toLowerCase().contains("secret"));
        assertFalse(projection.sql().toLowerCase().contains("limit"));
        assertEquals(Set.of("id", "name"), projection.selectedColumns());
        assertEquals(Set.of("id"), RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SQLSERVER2000, "select \"Id\" from dbo.citizen",
                "dbo", "citizen", physical, Set.of("id", "name")).selectedColumns());
        RestrictedSelectProjection top = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SQLSERVER2000, "select top 1 id from dbo.citizen order by id",
                "dbo", "citizen", physical, Set.of("id", "name"));
        assertTrue(top.sql().toLowerCase().contains("top"));
        assertFalse(top.sql().toLowerCase().contains("limit"));
        IllegalArgumentException unauthorized = assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.restrictProjection(
                        ProductTypeEnum.SQLSERVER2000, "select \"Secret\" from dbo.citizen",
                        "dbo", "citizen", physical, Set.of("id", "name")));
        assertTrue(unauthorized.getMessage().contains("未授权"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.SQLSERVER2000, "select [secret] from dbo.citizen",
                "dbo", "citizen", physical, Set.of("id", "name")));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.restrictProjection(
                        ProductTypeEnum.SQLSERVER2000, "select id from dbo.citizen",
                        "dbo", "citizen", List.of("Id", "id", "name"), Set.of("id")));
        assertTrue(duplicate.getMessage().contains("仅大小写不同"));
    }

    @Test
    void unverifiedProductFailsClosed() {
        // MongoDB 产品驱动把语句交给 Graal JavaScript，没有 SELECT 翻译。
        // Elasticsearch 产品驱动把语句文本当成索引名去做空查询，不调用 /_sql。
        for (ProductTypeEnum product : List.of(ProductTypeEnum.MONGODB, ProductTypeEnum.ELASTICSEARCH)) {
            UnsupportedOperationException rejected = assertThrows(UnsupportedOperationException.class,
                    () -> RestrictedSelectGuard.restrictProjection(
                            product, "select id from citizen", "app", "citizen", PHYSICAL, Set.of("id")),
                    product.name());
            assertTrue(rejected.getMessage().contains("尚未验证"), product.name());
        }
    }

    @Test
    void projectionResultIsImmutable() {
        RestrictedSelectProjection projection = restrict("select id from public.citizen", Set.of("id", "name"));
        assertThrows(UnsupportedOperationException.class, () -> projection.selectedColumns().add("salary"));
        assertThrows(UnsupportedOperationException.class, () -> projection.referencedColumns().add("salary"));
    }

    @Test
    void restrictedProjectionIsStableAcrossRepeatedCalls() {
        String sql = "select * from public.citizen where name = 'x' order by id";
        String first = restrict(sql, Set.of("id", "name")).sql();
        String second = restrict(sql, Set.of("id", "name")).sql();
        assertEquals(first, second);
    }
}
