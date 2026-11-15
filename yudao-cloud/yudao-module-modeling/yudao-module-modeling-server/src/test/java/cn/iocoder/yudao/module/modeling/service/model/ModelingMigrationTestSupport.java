package cn.iocoder.yudao.module.modeling.service.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.regex.Pattern;

/** 仅用于一次性测试库；从已交付基线读取真实前置表，测试当前增量，不重造已删除的旧迁移。 */
final class ModelingMigrationTestSupport {
    static final String MIGRATION = "sql/postgresql/migrations/0079_modeling_closed-loop-schema.sql";
    static final String STRUCTURED_FIELDS_MIGRATION =
            "sql/postgresql/migrations/0110_modeling_planning-structured-fields.sql";
    static final String REVIEW_WORKSPACE_MIGRATION =
            "sql/postgresql/migrations/0112_modeling_review-workspace.sql";

    private ModelingMigrationTestSupport() { }

    static void resetBaselineTables(Connection connection) throws Exception {
        resetBaselineTables(connection, "datahub-center");
    }

    static void resetBaselineTables(Connection connection, String namespace) throws Exception {
        schemaSql("", namespace);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + namespace + "\" CASCADE");
            statement.execute("CREATE SCHEMA \"" + namespace + "\"");
        }
        createMissingBaselineTables(connection, namespace);
    }

    static void createMissingBaselineTables(Connection connection) throws Exception {
        createMissingBaselineTables(connection, "datahub-center");
    }

    private static void createMissingBaselineTables(Connection connection, String namespace) throws Exception {
        String baseline = Files.readString(repoFile("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        var matcher = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"(dh_modeling_[^\"]+)\" \\(.*?\n\\)\\s*;",
                Pattern.DOTALL).matcher(baseline);
        try (Statement statement = connection.createStatement()) {
            int count = 0;
            while (matcher.find()) {
                String table = matcher.group(1);
                boolean exists;
                try (var result = statement.executeQuery("SELECT to_regclass('\"" + namespace + "\"." + table + "') IS NOT NULL")) {
                    result.next();
                    exists = result.getBoolean(1);
                }
                if (!exists) statement.execute(schemaSql(matcher.group()
                        .replaceAll(" DEFAULT nextval\\([^\n]*?::regclass\\)", ""), namespace));
                count++;
            }
            if (count == 0) throw new AssertionError("未在基线找到 Modeling 前置表");
        }
    }

    static void migrate(Connection connection) throws Exception {
        migrate(connection, "datahub-center");
    }

    static void migrate(Connection connection, String namespace) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(schemaSql(migrationSql(MIGRATION), namespace));
            statement.execute(schemaSql(migrationSql(STRUCTURED_FIELDS_MIGRATION), namespace));
            statement.execute(schemaSql(migrationSql(REVIEW_WORKSPACE_MIGRATION), namespace));
        }
    }

    private static String schemaSql(String sql, String namespace) {
        if (!"datahub-center".equals(namespace) && !namespace.matches("modeling_fixture_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("仅允许隔离测试 schema");
        }
        return sql.replace("\"datahub-center\"", "\"" + namespace + "\"")
                .replace("'datahub-center'", "'" + namespace + "'");
    }

    static void applyBaselineExtension(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(baselineBlock("-- Migration: 0079 建模平台结构与发布闭环"));
            statement.execute(baselineBlock("-- Migration: 0110 "));
            statement.execute(baselineBlock("-- Migration: 0112\n"));
        }
    }

    /** 基线中以迁移头开始、以首个 COMMIT 结束的区块，与对应迁移文件逐字一致。 */
    static String baselineBlock(String header) throws Exception {
        String baseline = Files.readString(repoFile("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        int start = baseline.indexOf(header);
        if (start < 0) throw new AssertionError("基线缺少 Modeling 扩展：" + header);
        return blockFrom(baseline, start, header);
    }

    /**
     * 读取编号迁移。0133 之前的迁移已归档，迁移目录只保留 0133 起的文件；
     * 已归档的迁移改读基线逐字收录的同号区块，目录中仍存在的迁移照常读文件。
     */
    static String migrationSql(String relative) throws Exception {
        Path file = findRepoFile(relative);
        if (file != null) return Files.readString(file);
        String name = Path.of(relative).getFileName().toString();
        if (!name.matches("[0-9]{4}_.*[.]sql")) throw new IllegalStateException("Repository file not found: " + relative);
        String baseline = Files.readString(repoFile("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        var header = Pattern.compile("(?m)^-- Migration: " + name.substring(0, 4) + "(?=[ \n])").matcher(baseline);
        if (!header.find()) throw new IllegalStateException("迁移已归档且基线未收录：" + relative);
        return blockFrom(baseline, header.start(), relative);
    }

    private static String blockFrom(String baseline, int start, String label) {
        int end = baseline.indexOf("\nCOMMIT;", start);
        if (end < 0) throw new AssertionError("基线 Modeling 扩展事务未闭合：" + label);
        return baseline.substring(start, end + "\nCOMMIT;".length());
    }

    static Path repoFile(String relative) {
        Path file = findRepoFile(relative);
        if (file == null) throw new IllegalStateException("Repository file not found: " + relative);
        return file;
    }

    private static Path findRepoFile(String relative) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        return null;
    }
}
