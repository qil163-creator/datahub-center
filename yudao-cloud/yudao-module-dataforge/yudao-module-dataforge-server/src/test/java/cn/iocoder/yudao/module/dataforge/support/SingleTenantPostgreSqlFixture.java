package cn.iocoder.yudao.module.dataforge.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/**
 * 旧版结构片段组装完成后执行真实迁移，确保当前 Mapper 测试使用最终单租户结构。
 */
public final class SingleTenantPostgreSqlFixture {

    private SingleTenantPostgreSqlFixture() {
    }

    public static void upgrade(javax.sql.DataSource source) {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_schema()")) {
            if (!rows.next()) throw new IllegalStateException("Missing fixture schema");
            upgrade(connection, rows.getString(1));
        } catch (Exception failure) { throw new IllegalStateException("Cannot upgrade fixture", failure); }
    }

    public static void upgrade(JdbcTemplate database, String schema) throws Exception {
        database.execute(migration(schema));
    }

    public static void upgrade(Connection connection, String schema) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(migration(schema));
        }
    }



    /** Installs authoritative current CREATE definitions and indexes in an already-owned empty schema. */
    public static void createBaselineTables(Connection connection, String schema, String... tables) throws Exception {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid fixture schema");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Missing baseline");
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        try (var statement = connection.createStatement()) {
            for (String table : tables) {
                if (!table.matches("dh_[a-z0-9_]+")) throw new IllegalArgumentException("Invalid fixture table");
                statement.execute("CREATE SEQUENCE IF NOT EXISTS \"" + schema + "\"." + table + "_seq");
                var create = java.util.regex.Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?\"datahub-center\"[.]\"?" + table
                        + "\"? \\(.*?\n\\)\\s*;", java.util.regex.Pattern.DOTALL).matcher(baseline);
                if (!create.find()) throw new IllegalStateException("Missing baseline table " + table);
                statement.execute(create.group().replace("datahub-center", schema));
                var primary = java.util.regex.Pattern.compile("ALTER TABLE \"datahub-center\"[.]\"" + table
                        + "\" ADD CONSTRAINT \"[^\"]+\" PRIMARY KEY [^;]+;", java.util.regex.Pattern.DOTALL).matcher(baseline);
                if (primary.find()) statement.execute(primary.group().replace("datahub-center", schema));
            }
            var indexes = java.util.regex.Pattern.compile("CREATE (?:UNIQUE )?INDEX [^;]+;", java.util.regex.Pattern.DOTALL).matcher(baseline);
            while (indexes.find()) {
                String sql = indexes.group();
                boolean selected = java.util.Arrays.stream(tables).anyMatch(table -> java.util.regex.Pattern.compile(
                        "ON\\s+\"datahub-center\"[.]\"?" + table + "\"?(?=\\s|\\()", java.util.regex.Pattern.DOTALL)
                        .matcher(sql).find());
                if (selected) statement.execute(sql.replace("datahub-center", schema));
            }
        }
        upgrade(connection, schema);
    }

    public static String readStructure(Path path) throws Exception {
        return Files.exists(path) ? Files.readString(path) : baselineStructure(path.getFileName().toString());
    }

    /** Current baseline fragments for runtime fixtures after historical files were consolidated. */
    public static String baselineStructure(String filename) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Missing baseline");
        Path historical = root.resolve("sql/postgresql/migrations").resolve(filename);
        if (Files.exists(historical)) return Files.readString(historical);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        String number = filename.substring(0, 4);
        String start;
        String end;
        switch (number) {
            case "0049", "0051", "0053" -> { return "-- Consolidated into baseline run protocol 0038"; }
            case "0078" -> {
                start = "CREATE SEQUENCE IF NOT EXISTS \"datahub-center\".dh_dataforge_etl_quality_batch_seq;";
                end = "CREATE TABLE IF NOT EXISTS \"datahub-center\".dh_dataforge_push_alert (";
            }
            case "0081" -> {
                start = "CREATE SEQUENCE IF NOT EXISTS \"datahub-center\".dh_dataforge_etl_version_seq;";
                end = "CREATE SEQUENCE IF NOT EXISTS \"datahub-center\".dh_dataforge_etl_quality_batch_seq;";
            }
            case "0115" -> {
                start = "-- ETL 文件输出恢复账本";
                end = "-- ETL 字典质量协同持久化结构";
            }
            case "0116" -> { return "-- Temporary resource columns included in current 0115 baseline structure"; }
            default -> { start = "-- Migration: " + number; end = "COMMIT;"; }
        }
        int from = baseline.indexOf(start);
        int to = from < 0 ? -1 : baseline.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new IllegalStateException("No current baseline structure for " + filename);
        return baseline.substring(from, end.equals("COMMIT;") ? to + end.length() : to);
    }

    private static String migration(String schema) throws Exception {
        if (!schema.equals("datahub-center") && !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("测试 schema 名称无效");
        }
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("找不到仓库迁移目录");
        try (var files = Files.list(root.resolve("sql/postgresql/migrations"))) {
            Path migration = files.filter(path -> path.getFileName().toString().startsWith("0169_"))
                    .findFirst().orElseThrow();
            return Files.readString(migration).replace("datahub-center", schema);
        }
    }
}
