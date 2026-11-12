package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLineageAnalyzerTest {

    // ==================== PostgreSQL ====================

    @Test
    void postgresInsertSelectWithJoinProducesTableAndColumnLineage() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, "public", """
                INSERT INTO dw.fact_order (order_id, amount)
                SELECT o.id, o.price * o.qty
                FROM ods.orders o JOIN ods.order_items i ON o.id = i.order_id
                WHERE i.status = 'PAID'
                """);
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.orders -> dw.fact_order", "ods.order_items -> dw.fact_order"), tables(result));
        assertEquals(Set.of(
                "ods.orders.id -> dw.fact_order.order_id IDENTITY",
                "ods.orders.price -> dw.fact_order.amount TRANSFORMATION",
                "ods.orders.qty -> dw.fact_order.amount TRANSFORMATION",
                "ods.orders.id -> dw.fact_order.* JOIN",
                "ods.order_items.order_id -> dw.fact_order.* JOIN",
                "ods.order_items.status -> dw.fact_order.* FILTER"), columns(result));
        assertEquals(List.of(new LineageTable("dw", "fact_order")), result.writeTables());
    }

    @Test
    void postgresFoldsUnquotedIdentifiersAndKeepsQuotedOnesAndAppliesDefaultSchema() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, "stage", """
                INSERT INTO DW."FactOrder" (ID) SELECT Src.ID FROM Orders Src
                """);
        assertEquals(Set.of("stage.orders -> dw.FactOrder"), tables(result));
        assertEquals(Set.of("stage.orders.id -> dw.FactOrder.id IDENTITY"), columns(result));
    }

    @Test
    void cteSubqueryAndAggregationComposeThroughDerivedRelations() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                WITH s AS (SELECT user_id, sum(amt) AS total FROM ods.pay GROUP BY user_id)
                INSERT INTO dw.user_total (uid, total)
                SELECT t.user_id, t.total FROM (SELECT * FROM s) t
                """);
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.pay -> dw.user_total"), tables(result));
        assertEquals(Set.of(
                "ods.pay.user_id -> dw.user_total.uid IDENTITY",
                "ods.pay.amt -> dw.user_total.total AGGREGATION",
                "ods.pay.user_id -> dw.user_total.* GROUP_BY"), columns(result));
    }

    @Test
    void unionBranchesMergeSourcesByPosition() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (a) SELECT x FROM ods.s1 UNION ALL SELECT y FROM ods.s2
                """);
        assertEquals(Set.of("ods.s1 -> dw.t", "ods.s2 -> dw.t"), tables(result));
        assertEquals(Set.of("ods.s1.x -> dw.t.a IDENTITY", "ods.s2.y -> dw.t.a IDENTITY"), columns(result));
    }

    @Test
    void unqualifiedColumnInJoinIsRecordedAgainstEveryCandidateAndMarkedAmbiguous() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (a) SELECT col FROM ods.s1 JOIN ods.s2 ON s1.id = s2.id
                """);
        assertEquals(SqlLineageStatus.PARTIAL, result.status());
        assertTrue(codes(result).contains("AMBIGUOUS_COLUMN"));
        assertTrue(columns(result).contains("ods.s1.col -> dw.t.a IDENTITY ambiguous"));
        assertTrue(columns(result).contains("ods.s2.col -> dw.t.a IDENTITY ambiguous"));
    }

    @Test
    void catalogResolvesUnqualifiedColumnOwnership() {
        LineageCatalog catalog = table -> switch (table.name()) {
            case "s1" -> Optional.of(List.of("id", "col"));
            case "s2" -> Optional.of(List.of("id", "other"));
            default -> Optional.empty();
        };
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (a) SELECT col FROM ods.s1 JOIN ods.s2 ON s1.id = s2.id
                """).withCatalog(catalog));
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertTrue(columns(result).contains("ods.s1.col -> dw.t.a IDENTITY"));
        assertFalse(columns(result).stream().anyMatch(edge -> edge.startsWith("ods.s2.col")));
    }

    @Test
    void wildcardWithoutCatalogKeepsVirtualStarAndDegrades() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, "INSERT INTO dw.t SELECT * FROM ods.s");
        assertEquals(SqlLineageStatus.PARTIAL, result.status());
        assertEquals(Set.of("ods.s -> dw.t"), tables(result));
        assertEquals(Set.of("ods.s.* -> dw.t.* IDENTITY"), columns(result));
        assertTrue(codes(result).containsAll(Set.of("WILDCARD_UNEXPANDED", "TARGET_COLUMNS_UNKNOWN")));
    }

    @Test
    void catalogExpandsWildcardAndMapsInsertWithoutColumnListByPosition() {
        LineageCatalog catalog = table -> switch (table.displayName()) {
            case "ods.s" -> Optional.of(List.of("a", "b"));
            case "dw.t" -> Optional.of(List.of("x", "y"));
            default -> Optional.empty();
        };
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, null,
                "INSERT INTO dw.t SELECT * FROM ods.s").withCatalog(catalog));
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.s.a -> dw.t.x IDENTITY", "ods.s.b -> dw.t.y IDENTITY"), columns(result));
    }

    @Test
    void insertWithoutColumnListAndUnknownTargetKeepsTableLevelOnly() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, "INSERT INTO dw.t SELECT a, b FROM ods.s");
        assertEquals(Set.of("ods.s -> dw.t"), tables(result));
        assertTrue(columns(result).isEmpty());
        assertTrue(codes(result).contains("TARGET_COLUMNS_UNKNOWN"));
    }

    @Test
    void columnCountMismatchMapsOnlyAlignedPrefix() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, "INSERT INTO dw.t (x) SELECT a, b FROM ods.s");
        assertEquals(Set.of("ods.s.a -> dw.t.x IDENTITY"), columns(result));
        assertTrue(codes(result).contains("COLUMN_COUNT_MISMATCH"));
    }

    @Test
    void temporaryTablesAreCollapsedSoUpstreamConnectsToFinalTarget() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                CREATE TEMPORARY TABLE tmp_users AS SELECT id, name FROM ods.users WHERE active = 1;
                INSERT INTO dw.user_dim (id, name) SELECT id, upper(name) FROM tmp_users;
                """);
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.users -> dw.user_dim"), tables(result));
        assertEquals(Set.of(
                "ods.users.id -> dw.user_dim.id IDENTITY",
                "ods.users.name -> dw.user_dim.name TRANSFORMATION",
                "ods.users.active -> dw.user_dim.* FILTER"), columns(result));
        assertEquals(List.of(new LineageTable("dw", "user_dim")), result.writeTables());
    }

    @Test
    void tableDroppedAfterCreationIsTransientButDropBeforeCreateIsPersistent() {
        var collapsed = analyze(ProductTypeEnum.POSTGRESQL, null, """
                CREATE TABLE ods.mid AS SELECT id FROM ods.src;
                INSERT INTO dw.dst (id) SELECT id FROM ods.mid;
                DROP TABLE ods.mid;
                """);
        assertEquals(Set.of("ods.src -> dw.dst"), tables(collapsed));

        var persistent = analyze(ProductTypeEnum.POSTGRESQL, null, """
                DROP TABLE IF EXISTS ods.mid;
                CREATE TABLE ods.mid AS SELECT id FROM ods.src;
                INSERT INTO dw.dst (id) SELECT id FROM ods.mid;
                """);
        assertEquals(Set.of("ods.src -> ods.mid", "ods.mid -> dw.dst"), tables(persistent));
        assertTrue(columns(persistent).contains("ods.mid.id -> dw.dst.id IDENTITY"));
    }

    @Test
    void createdTableColumnsAreReusedForLaterPositionalInsert() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                CREATE TABLE dw.t (x int, y text);
                INSERT INTO dw.t SELECT a, b FROM ods.s;
                """);
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.s.a -> dw.t.x IDENTITY", "ods.s.b -> dw.t.y IDENTITY"), columns(result));
    }

    @Test
    void fixedParametersAreSubstitutedAndRemainingPlaceholdersAreRestoredInTableNames() {
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t_${dt} (id) SELECT id FROM ods.s WHERE biz_date = '${bizdate}' AND n > ${limit}
                """).withParameters(Map.of("bizdate", "20240101")));
        assertEquals(Set.of("ods.s -> dw.t_${dt}"), tables(result));
        assertTrue(columns(result).contains("ods.s.id -> dw.t_${dt}.id IDENTITY"));
        assertTrue(codes(result).contains("PARAMETERIZED_TABLE"));
        assertFalse(codes(result).contains("UNRESOLVED_COLUMN"), result.issues().toString());
    }

    @Test
    void caseWindowAndMaskingAreClassified() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (a, b, c)
                SELECT CASE WHEN s.flag = 1 THEN s.x ELSE s.y END,
                       row_number() OVER (PARTITION BY s.g ORDER BY s.o),
                       md5(s.phone)
                FROM ods.s
                """);
        assertEquals(Set.of(
                "ods.s.x -> dw.t.a TRANSFORMATION",
                "ods.s.y -> dw.t.a TRANSFORMATION",
                "ods.s.flag -> dw.t.* CONDITIONAL",
                "ods.s.g -> dw.t.* WINDOW",
                "ods.s.o -> dw.t.* WINDOW",
                "ods.s.phone -> dw.t.c TRANSFORMATION masking"), columns(result));
    }

    @Test
    void scalarAndInSubqueriesContributeReadsAndSources() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (a)
                SELECT (SELECT max(v) FROM ods.s2 WHERE s2.k = s1.k) FROM ods.s1
                WHERE s1.id IN (SELECT id FROM ods.s3)
                """);
        assertEquals(Set.of("ods.s1 -> dw.t", "ods.s2 -> dw.t", "ods.s3 -> dw.t"), tables(result));
        assertTrue(columns(result).contains("ods.s2.v -> dw.t.a AGGREGATION"));
        assertTrue(columns(result).contains("ods.s3.id -> dw.t.* FILTER"));
        assertTrue(columns(result).contains("ods.s1.id -> dw.t.* FILTER"));
    }

    @Test
    void existsWildcardDoesNotDegradeResult() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (a) SELECT s.a FROM ods.s WHERE EXISTS (SELECT * FROM ods.x WHERE x.id = s.id)
                """);
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.s -> dw.t", "ods.x -> dw.t"), tables(result));
    }

    @Test
    void postgresUpdateFromRecordsOtherTablesAsSources() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                UPDATE dw.t SET name = s.name FROM ods.s WHERE t.id = s.id
                """);
        assertEquals(Set.of("ods.s -> dw.t"), tables(result));
        assertTrue(columns(result).contains("ods.s.name -> dw.t.name IDENTITY"));
        assertTrue(columns(result).contains("ods.s.id -> dw.t.* FILTER"));
    }

    @Test
    void updateUsingOnlyItsOwnColumnsHasNoLineage() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, "UPDATE dw.t SET n = n + 1 WHERE id = 3");
        assertTrue(tables(result).isEmpty());
        assertTrue(columns(result).isEmpty());
    }

    @Test
    void postgresTempHeaderIsRewrittenForParsingButUnloggedStaysPersistent() {
        var temp = analyze(ProductTypeEnum.POSTGRESQL, null, """
                CREATE TEMP TABLE t1 AS SELECT id FROM ods.a;
                INSERT INTO dw.b (id) SELECT id FROM t1;
                """);
        assertEquals(SqlLineageStatus.SUCCESS, temp.status(), temp.issues().toString());
        assertEquals(Set.of("ods.a -> dw.b"), tables(temp));

        var unlogged = analyze(ProductTypeEnum.POSTGRESQL, null, """
                CREATE UNLOGGED TABLE dw.mid AS SELECT id FROM ods.a;
                INSERT INTO dw.b (id) SELECT id FROM dw.mid;
                """);
        assertEquals(Set.of("ods.a -> dw.mid", "dw.mid -> dw.b"), tables(unlogged));
    }

    @Test
    void postgresMaterializedViewSelectIntoAndOnConflict() {
        var view = analyze(ProductTypeEnum.POSTGRESQL, null, "CREATE MATERIALIZED VIEW dw.mv AS SELECT id FROM ods.s");
        assertEquals(Set.of("ods.s.id -> dw.mv.id IDENTITY"), columns(view));

        var into = analyze(ProductTypeEnum.POSTGRESQL, null, "SELECT id, name AS nm INTO dw.newt FROM ods.s");
        assertEquals(Set.of("ods.s.id -> dw.newt.id IDENTITY", "ods.s.name -> dw.newt.nm IDENTITY"), columns(into));

        var upsert = analyze(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (id) SELECT s.id::bigint FROM ods.s ON CONFLICT (id) DO UPDATE SET id = EXCLUDED.id
                """);
        assertEquals(Set.of("ods.s -> dw.t"), tables(upsert));
        assertTrue(columns(upsert).contains("ods.s.id -> dw.t.id TRANSFORMATION"));
    }

    @Test
    void createViewUsesOutputNames() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, "CREATE VIEW dw.v AS SELECT a, b AS bb FROM ods.s");
        assertEquals(Set.of("ods.s.a -> dw.v.a IDENTITY", "ods.s.b -> dw.v.bb IDENTITY"), columns(result));
    }

    // ==================== MySQL ====================

    @Test
    void mysqlPreservesCaseAndCountStarHasNoColumnSource() {
        var result = analyze(ProductTypeEnum.MYSQL, "ods", """
                INSERT INTO dw.Stat (Cat, Cnt) SELECT s.Cat, COUNT(*) FROM Sales s GROUP BY s.Cat
                """);
        assertEquals(Set.of("ods.Sales -> dw.Stat"), tables(result));
        assertEquals(Set.of("ods.Sales.Cat -> dw.Stat.Cat IDENTITY", "ods.Sales.Cat -> dw.Stat.* GROUP_BY"),
                columns(result));
    }

    @Test
    void mysqlMultiTableUpdateAndReplace() {
        var update = analyze(ProductTypeEnum.MYSQL, null, """
                UPDATE dw.t t JOIN ods.s s ON t.id = s.id SET t.name = s.name
                """);
        assertEquals(Set.of("ods.s -> dw.t"), tables(update));
        assertTrue(columns(update).contains("ods.s.name -> dw.t.name IDENTITY"));
        assertTrue(columns(update).contains("ods.s.id -> dw.t.* JOIN"));

        var replace = analyze(ProductTypeEnum.MYSQL, null, "REPLACE INTO dw.t (a) SELECT b FROM ods.s");
        assertEquals(Set.of("ods.s.b -> dw.t.a IDENTITY"), columns(replace));
    }

    @Test
    void useStatementChangesDefaultSchema() {
        var result = analyze(ProductTypeEnum.MYSQL, "init", "USE dw; INSERT INTO t (a) SELECT a FROM ods.s;");
        assertEquals(Set.of("ods.s -> dw.t"), tables(result));
    }

    @Test
    void mysqlTemporaryTableIsCollapsed() {
        var result = analyze(ProductTypeEnum.MYSQL, null, """
                CREATE TEMPORARY TABLE tmp AS SELECT id FROM ods.a;
                INSERT INTO dw.b (id) SELECT id FROM tmp;
                """);
        assertEquals(Set.of("ods.a -> dw.b"), tables(result));
        assertEquals(Set.of("ods.a.id -> dw.b.id IDENTITY"), columns(result));
    }

    // ==================== Hive / Spark SQL ====================

    @Test
    void hiveInsertOverwriteSkipsStaticPartitionColumns() {
        LineageCatalog catalog = table -> "dw.t".equals(table.displayName())
                ? Optional.of(List.of("id", "name", "dt")) : Optional.empty();
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.HIVE, null, """
                INSERT OVERWRITE TABLE dw.t PARTITION (dt = '2024-01-01') SELECT id, name FROM ods.s
                """).withCatalog(catalog));
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.s.id -> dw.t.id IDENTITY", "ods.s.name -> dw.t.name IDENTITY"), columns(result));
    }

    @Test
    void sparkTemporaryViewIsCollapsedAndDynamicPartitionMapsByPosition() {
        LineageCatalog catalog = table -> "dw.t".equals(table.displayName())
                ? Optional.of(List.of("id", "amt", "dt")) : Optional.empty();
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.HIVE, null, """
                CREATE OR REPLACE TEMPORARY VIEW v AS SELECT id, amount, biz_dt FROM ods.pay;
                INSERT OVERWRITE TABLE dw.t PARTITION (dt) SELECT id, amount * 100, biz_dt FROM v;
                """).withCatalog(catalog));
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(Set.of("ods.pay -> dw.t"), tables(result));
        assertEquals(Set.of("ods.pay.id -> dw.t.id IDENTITY", "ods.pay.amount -> dw.t.amt TRANSFORMATION",
                "ods.pay.biz_dt -> dw.t.dt IDENTITY"), columns(result));
    }

    @Test
    void hiveLateralViewExplodeTracksArraySource() {
        var result = analyze(ProductTypeEnum.HIVE, null, """
                INSERT INTO TABLE dw.t (id, tag) SELECT s.id, x FROM ods.s s LATERAL VIEW explode(s.tags) e AS x
                """);
        assertEquals(Set.of("ods.s.id -> dw.t.id IDENTITY", "ods.s.tags -> dw.t.tag TRANSFORMATION"),
                columns(result));
    }

    @Test
    void hiveMultiInsertSharesFrom() {
        var result = analyze(ProductTypeEnum.HIVE, null, """
                FROM ods.s
                INSERT OVERWRITE TABLE dw.a SELECT id
                INSERT OVERWRITE TABLE dw.b SELECT name
                """);
        assertEquals(Set.of("ods.s -> dw.a", "ods.s -> dw.b"), tables(result));
    }

    // ==================== Oracle / 达梦 ====================

    @Test
    void oracleMergeFoldsToUpperCase() {
        var result = analyze(ProductTypeEnum.ORACLE, null, """
                MERGE INTO dw.t t USING ods.s s ON (t.id = s.id)
                WHEN MATCHED THEN UPDATE SET t.name = s.name
                WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)
                """);
        assertEquals(Set.of("ODS.S -> DW.T"), tables(result));
        assertEquals(Set.of(
                "ODS.S.NAME -> DW.T.NAME IDENTITY",
                "ODS.S.ID -> DW.T.ID IDENTITY",
                "ODS.S.ID -> DW.T.* JOIN"), columns(result));
    }

    @Test
    void oracleSlashSeparatedScript() {
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.ORACLE, "APP", """
                INSERT INTO dw.a (x) SELECT x FROM ods.s
                /
                INSERT INTO dw.b (y) SELECT y FROM ods.s
                /
                """).withStatementSeparator("/"));
        assertEquals(2, result.statementCount());
        assertEquals(Set.of("ODS.S -> DW.A", "ODS.S -> DW.B"), tables(result));
    }

    @Test
    void oracleAndDamengGlobalTemporaryTablesAreCollapsed() {
        for (ProductTypeEnum product : List.of(ProductTypeEnum.ORACLE, ProductTypeEnum.DM)) {
            var result = analyze(product, null, """
                    CREATE GLOBAL TEMPORARY TABLE tmp ON COMMIT PRESERVE ROWS AS SELECT id FROM ods.s;
                    INSERT INTO dw.t (id) SELECT id FROM tmp;
                    """);
            assertEquals(SqlLineageStatus.SUCCESS, result.status(), product + " " + result.issues());
            assertEquals(Set.of("ODS.S -> DW.T"), tables(result), product.name());
            assertEquals(Set.of("ODS.S.ID -> DW.T.ID IDENTITY"), columns(result), product.name());
        }
    }

    @Test
    void mysqlOnDuplicateKeyUpdateKeepsSelectLineage() {
        var result = analyze(ProductTypeEnum.MYSQL, null, """
                INSERT INTO dw.t (id) SELECT id FROM ods.s ON DUPLICATE KEY UPDATE id = VALUES(id)
                """);
        assertEquals(Set.of("ods.s.id -> dw.t.id IDENTITY"), columns(result));
    }

    @Test
    void damengInsertSelect() {
        var result = analyze(ProductTypeEnum.DM, "SYSDBA", "INSERT INTO t (a) SELECT b FROM s WHERE c > 0");
        assertEquals(Set.of("SYSDBA.S -> SYSDBA.T"), tables(result));
        assertEquals(Set.of("SYSDBA.S.B -> SYSDBA.T.A IDENTITY", "SYSDBA.S.C -> SYSDBA.T.* FILTER"),
                columns(result));
    }

    // ==================== 健壮性 ====================

    @Test
    void parseErrorOnlyAffectsItsStatementAndDoesNotLeakSql() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, """
                INSERT INTO dw.t (a) SELECT a FROM ods.s;
                THIS IS NOT SQL 'p@ssw0rd';
                """);
        assertEquals(SqlLineageStatus.PARTIAL, result.status());
        assertEquals(Set.of("ods.s -> dw.t"), tables(result));
        LineageIssue issue = result.issues().stream().filter(i -> "PARSE_ERROR".equals(i.code())).findFirst()
                .orElseThrow();
        assertEquals(2, issue.statementIndex());
        assertFalse(result.issues().toString().contains("p@ssw0rd"));

        var failed = analyze(ProductTypeEnum.POSTGRESQL, null, "NOT SQL AT ALL 'p@ssw0rd'");
        assertEquals(SqlLineageStatus.FAILED, failed.status());
        assertFalse(failed.issues().toString().contains("p@ssw0rd"));
    }

    @Test
    void productsOutsideTheVerifiedMatrixAreUnsupported() {
        var supported = EnumSet.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.KINGBASE,
                ProductTypeEnum.GREENPLUM, ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB,
                ProductTypeEnum.DORIS, ProductTypeEnum.STARROCKS, ProductTypeEnum.HIVE, ProductTypeEnum.ORACLE,
                ProductTypeEnum.DM);
        for (ProductTypeEnum product : ProductTypeEnum.values()) {
            assertEquals(supported.contains(product), SqlLineageAnalyzer.supports(product), product.name());
        }
        var result = analyze(ProductTypeEnum.SQLSERVER, null, "INSERT INTO t SELECT a FROM s");
        assertEquals(SqlLineageStatus.UNSUPPORTED, result.status());
        assertTrue(result.tableEdges().isEmpty());
    }

    @Test
    void procedureCallsDegradeResult() {
        var result = analyze(ProductTypeEnum.MYSQL, null, "CALL dw.refresh_all()");
        assertEquals(SqlLineageStatus.PARTIAL, result.status());
        assertTrue(codes(result).contains("UNSUPPORTED_STATEMENT"));
    }

    @Test
    void edgeLimitsTruncateAndDegrade() {
        var limits = new SqlLineageLimits(10_000, 100, 100, 2, 50, 100_000);
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, null,
                "INSERT INTO dw.t (a, b, c) SELECT a, b, c FROM ods.s").withLimits(limits));
        assertEquals(2, result.columnEdges().size());
        assertTrue(codes(result).contains("TRUNCATED"));
        assertEquals(SqlLineageStatus.PARTIAL, result.status());
    }

    @Test
    void scriptTooLargeFailsWithoutParsing() {
        var limits = new SqlLineageLimits(10, 100, 100, 100, 50, 100_000);
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, null,
                "INSERT INTO dw.t (a) SELECT a FROM ods.s").withLimits(limits));
        assertEquals(SqlLineageStatus.FAILED, result.status());
        assertEquals(Set.of("SCRIPT_TOO_LARGE"), codes(result));
    }

    @Test
    void failingCatalogDegradesOnceAndDoesNotAbort() {
        LineageCatalog broken = table -> {
            throw new IllegalStateException("connection refused: jdbc:secret");
        };
        var result = SqlLineageAnalyzer.analyze(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, null,
                "INSERT INTO dw.t SELECT * FROM ods.s").withCatalog(broken));
        assertEquals(Set.of("ods.s -> dw.t"), tables(result));
        assertEquals(1, result.issues().stream().filter(i -> "CATALOG_UNAVAILABLE".equals(i.code())).count());
        assertFalse(result.issues().toString().contains("jdbc:secret"));
    }

    @Test
    void selectOnlyScriptRecordsReadsWithoutEdges() {
        var result = analyze(ProductTypeEnum.POSTGRESQL, null, "SELECT s.a FROM ods.s JOIN ods.x ON s.id = x.id");
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertTrue(result.tableEdges().isEmpty());
        assertEquals(List.of(new LineageTable("ods", "s"), new LineageTable("ods", "x")), result.readTables());
    }

    // ==================== 单条查询 ====================

    @Test
    void analyzeQueryReturnsOrderedOutputSources() {
        var result = SqlLineageAnalyzer.analyzeQuery(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, "ods", """
                SELECT o.id AS oid, upper(o.name) nm FROM orders o WHERE o.amount > 10
                """));
        assertEquals(SqlLineageStatus.SUCCESS, result.status(), result.issues().toString());
        assertEquals(2, result.columns().size());
        assertEquals("oid", result.columns().get(0).name());
        assertEquals(List.of(new LineageColumnSource(new LineageTable("ods", "orders"), "id",
                LineageTransform.IDENTITY, false, false)), result.columns().get(0).sources());
        assertEquals(LineageTransform.TRANSFORMATION, result.columns().get(1).sources().getFirst().transform());
        assertEquals(List.of(new LineageColumnSource(new LineageTable("ods", "orders"), "amount",
                LineageTransform.FILTER, false, false)), result.indirectSources());
        assertEquals(List.of(new LineageTable("ods", "orders")), result.readTables());
    }

    @Test
    void analyzeQueryRejectsWrites() {
        var result = SqlLineageAnalyzer.analyzeQuery(SqlLineageRequest.of(ProductTypeEnum.POSTGRESQL, null,
                "INSERT INTO t SELECT a FROM s"));
        assertEquals(SqlLineageStatus.FAILED, result.status());
        assertEquals(Set.of("NOT_A_QUERY"), result.issues().stream().map(LineageIssue::code)
                .collect(java.util.stream.Collectors.toSet()));
    }

    // ==================== 工具 ====================

    private static SqlLineageResult analyze(ProductTypeEnum product, String schema, String sql) {
        return SqlLineageAnalyzer.analyze(SqlLineageRequest.of(product, schema, sql));
    }

    private static Set<String> tables(SqlLineageResult result) {
        Set<String> edges = new TreeSet<>();
        result.tableEdges().forEach(edge -> edges.add(edge.upstream().displayName() + " -> "
                + edge.downstream().displayName()));
        return edges;
    }

    private static Set<String> columns(SqlLineageResult result) {
        Set<String> edges = new TreeSet<>();
        result.columnEdges().forEach(edge -> edges.add(edge.upstream().displayName() + "." + edge.upstreamColumn()
                + " -> " + edge.downstream().displayName() + "." + edge.downstreamColumn() + " "
                + edge.transform() + (edge.ambiguous() ? " ambiguous" : "") + (edge.masking() ? " masking" : "")));
        return edges;
    }

    private static Set<String> codes(SqlLineageResult result) {
        Set<String> codes = new TreeSet<>();
        result.issues().forEach(issue -> codes.add(issue.code()));
        return codes;
    }
}
