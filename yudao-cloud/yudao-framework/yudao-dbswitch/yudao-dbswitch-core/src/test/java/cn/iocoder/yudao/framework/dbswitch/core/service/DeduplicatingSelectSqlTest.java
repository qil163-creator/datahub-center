package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DeduplicatingSelectSqlTest {
    private static final Map<String, String> FIELDS = new LinkedHashMap<>(Map.of("id", "BIGINT", "name", "TEXT", "amount", "NUMERIC"));
    private static DeduplicatingSelectSql.Options keys() {
        return new DeduplicatingSelectSql.Options(false, List.of("name"), Set.of("name"), List.of(new DeduplicatingSelectSql.Order("id", false)), List.of("id"));
    }
    @Test void onlyStructuredVerifiedPostgresQueriesCanUseTheDedupBoundary() {
        assertThrows(IllegalArgumentException.class, () -> render("DELETE FROM source", keys()));
        assertThrows(IllegalArgumentException.class, () -> render("SELECT * FROM source; SELECT * FROM other", keys()));
        assertThrows(IllegalArgumentException.class, () -> DeduplicatingSelectSql.render(ProductTypeEnum.MYSQL, "SELECT * FROM source", "public", FIELDS, keys()));
        assertThrows(IllegalArgumentException.class, () -> render("SELECT * FROM source", new DeduplicatingSelectSql.Options(false, List.of("missing"), Set.of(), keys().orderBy(), List.of("id"))));
        assertThrows(IllegalArgumentException.class, () -> render("SELECT * FROM source", new DeduplicatingSelectSql.Options(false, List.of("id"), Set.of("id"), keys().orderBy(), List.of("id"))));
        assertThrows(IllegalArgumentException.class, () -> render("SELECT * FROM source", new DeduplicatingSelectSql.Options(false, List.of("name"), Set.of(), List.of(), List.of("id"))));
        String sql = render("SELECT * FROM source WHERE id > 0", keys());
        assertTrue(sql.contains("PARTITION BY LOWER(\"name\") ORDER BY \"id\" ASC NULLS LAST"), sql);
        assertTrue(sql.contains("DataHub ordering key is not unique"));
        assertTrue(sql.contains("\"public\".\"source\""));
    }
    @Test void writesDisposablePostgresExecutionFixtureForValuesNullsPrecisionAndDuplicateOrderFailure() throws Exception {
        String query = DeduplicatingSelectSql.render(ProductTypeEnum.POSTGRESQL, "SELECT * FROM source", "pg_temp", FIELDS, keys());
        String distinct = render("SELECT * FROM source", new DeduplicatingSelectSql.Options(true, List.of(), Set.of(), List.of(), List.of()));
        // 实际执行夹具仅使用临时表与事务，不连接平台业务表。
        String fixture = "BEGIN; CREATE TEMP TABLE source(id bigint,name text,amount numeric(30,6));\n"
                + "INSERT INTO source VALUES(9007199254740993,'A',9007199254740993.125000),(9007199254740994,'a',2),(9007199254740995,NULL,3),(9007199254740996,NULL,4);\n"
                + "CREATE TEMP TABLE dedup_result AS " + query + ";\n"
                + "DO $$ BEGIN IF (SELECT count(*) FROM dedup_result) <> 2 OR NOT EXISTS(SELECT 1 FROM dedup_result WHERE id=9007199254740993 AND name='A' AND amount=9007199254740993.125000) THEN RAISE EXCEPTION 'dedup values mismatch'; END IF; END $$;\n"
                + "INSERT INTO source VALUES(9007199254740993,'B',5);\n"
                + "DO $$ BEGIN BEGIN PERFORM * FROM (" + query + ") probe; RAISE EXCEPTION 'duplicate order accepted'; EXCEPTION WHEN invalid_text_representation THEN NULL; END; END $$; ROLLBACK;\n";
        Path output = Path.of("target", "dedup-postgres-fixture.sql"); Files.createDirectories(output.getParent()); Files.writeString(output, fixture);
        assertTrue(distinct.startsWith("SELECT DISTINCT"));
    }
    private static String render(String sql, DeduplicatingSelectSql.Options options) {
        return DeduplicatingSelectSql.render(ProductTypeEnum.POSTGRESQL, sql, "public", FIELDS, options);
    }

    @Test void writesTheSameQueriesForActualDataxReaderAndWriterAcceptance() throws Exception {
        var fields = new LinkedHashMap<String, String>(); fields.put("id", "BIGINT"); fields.put("name", "TEXT"); fields.put("amount", "NUMERIC");
        Path directory = Path.of("target", "dedup-datax-fixture"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("fields.txt"), String.join(",", fields.keySet()));
        Files.writeString(directory.resolve("all.sql"), DeduplicatingSelectSql.render(ProductTypeEnum.POSTGRESQL, "SELECT * FROM source", "qa_dedup", fields,
                new DeduplicatingSelectSql.Options(true, List.of(), Set.of(), List.of(), List.of())));
        Files.writeString(directory.resolve("keys.sql"), DeduplicatingSelectSql.render(ProductTypeEnum.POSTGRESQL, "SELECT * FROM source", "qa_dedup", fields, keys()));
    }
}
