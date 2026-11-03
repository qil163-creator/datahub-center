package cn.iocoder.yudao.module.governance.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Collection;

/**
 * 旧版结构片段组装完成后执行真实迁移，确保当前 Mapper 测试使用最终单租户结构。
 */
public final class SingleTenantPostgreSqlFixture {

    private SingleTenantPostgreSqlFixture() {
    }

    public static void upgrade(JdbcTemplate database, String schema) throws Exception {
        database.execute(migration(schema));
    }

    public static void upgrade(Connection connection, String schema) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(migration(schema));
        }
    }

    /**
     * 回环 PostgreSQL 测试库须先导入当前完整基线；各用例只复制结构并使用自己的序列。
     */
    public static void copyCurrentTables(JdbcTemplate database, String schema, Collection<String> tables) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("测试 schema 名称无效");
        for (String table : tables) {
            if (!table.matches("dh_[a-z0-9_]+")) throw new IllegalArgumentException("测试业务表名称无效");
            Integer tenantColumns = database.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema='datahub-center' AND table_name=? AND column_name='tenant_id'", Integer.class, table);
            if (tenantColumns == null || tenantColumns != 0) throw new IllegalStateException("测试库未导入最终单租户基线：" + table);
            database.execute("CREATE TABLE " + schema + "." + table + " (LIKE \"datahub-center\"." + table + " INCLUDING ALL)");
            var sourceIndexes = database.queryForList("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='datahub-center' AND tablename=?", table);
            var copiedIndexes = database.queryForList("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname=? AND tablename=?", schema, table);
            var renamedIndexes = new java.util.HashSet<String>();
            for (var sourceIndex : sourceIndexes) {
                String sourceDefinition = ((String) sourceIndex.get("indexdef")).replace("\"datahub-center\".", schema + ".");
                String sourceSuffix = sourceDefinition.substring(sourceDefinition.indexOf(" ON "));
                for (var copiedIndex : copiedIndexes) {
                    String copiedName = (String) copiedIndex.get("indexname");
                    if (renamedIndexes.contains(copiedName)) continue;
                    String copiedDefinition = (String) copiedIndex.get("indexdef");
                    if (copiedDefinition.substring(copiedDefinition.indexOf(" ON ")).equals(sourceSuffix)) {
                        if (!copiedName.equals(sourceIndex.get("indexname"))) {
                            database.execute("ALTER INDEX " + schema + ".\"" + copiedName
                                    + "\" RENAME TO \"" + sourceIndex.get("indexname") + "\"");
                        }
                        renamedIndexes.add(copiedName);
                        break;
                    }
                }
            }
            var defaults = database.queryForList("SELECT column_name,column_default FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name=? AND column_default LIKE 'nextval(%'", schema, table);
            for (var column : defaults) {
                String name = (String) column.get("column_name");
                String sequence = schema + "." + table + ("id".equals(name) ? "_seq" : "_" + name + "_seq");
                database.execute("CREATE SEQUENCE " + sequence);
                database.execute("ALTER TABLE " + schema + "." + table + " ALTER COLUMN " + name
                        + " SET DEFAULT nextval('" + sequence + "')");
            }
        }
    }

    public static void copyCurrentTables(Connection connection, String schema, Collection<String> tables) {
        copyCurrentTables(new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
                schema, tables);
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
