package cn.iocoder.yudao.runtime.datax;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertSql;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertHooks;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertPublication;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.plugin.rdbms.util.DataBaseType;
import com.alibaba.datax.plugin.rdbms.writer.CommonRdbmsWriter;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** 复用官方 DataX 读取/类型绑定，仅以 DB Switch 的原子 UPSERT 代替追加；任何批次错误整批失败。 */
public final class DatahubPostgresqlWriter extends Writer {
    public static final class Job extends Writer.Job {
        private Configuration config;
        private CommonRdbmsWriter.Job delegate;
        private PostgresUpsertPublication publication;
        private String jdbcUrl;
        private String username;
        private String password;
        @Override public void init() {
            config = getPluginJobConf();
            List<String> pre=config.getList("preSql", Collections.<String>emptyList(), String.class);
            List<String> post=config.getList("postSql", Collections.<String>emptyList(), String.class);
            if(PostgresUpsertHooks.CONTRACT.equals(config.getString("writeContract"))) {
                List<Configuration> connections=config.getListConfiguration("connection");
                if(connections.size()!=1 || connections.get(0).getList("table",String.class).size()!=1)
                    throw new IllegalArgumentException("事务钩子只支持单目标表");
                Configuration connection=connections.get(0);
                if(!PostgresUpsertSql.target(config.getString("upsertSchema"),config.getString("upsertTable"))
                        .equals(connection.getList("table",String.class).get(0)))
                    throw new IllegalArgumentException("目标表与冻结的事务钩子身份不一致");
                jdbcUrl=connection.getString("jdbcUrl"); username=config.getString("username"); password=config.getString("password");
                List<String> fields=config.getList("column",String.class).stream().map(DatahubPostgresqlWriter::unquote).collect(Collectors.toList());
                publication=new PostgresUpsertPublication(config.getString("upsertExecutionIdentity"),config.getString("upsertSourceIdentity")+"\0"+jdbcUrl+"\0"+username,
                        config.getString("upsertSchema"),config.getString("upsertTable"),fields,config.getList("upsertKeys",String.class),pre,post,
                        message -> org.slf4j.LoggerFactory.getLogger(DatahubPostgresqlWriter.class).info(message));
                try(Connection database=DBUtil.getConnection(DataBaseType.PostgreSQL,jdbcUrl,username,password)) { publication.prepare(database); }
                catch(SQLException failure) { throw new IllegalStateException("事务钩子暂存区准备失败",failure); }
                // 官方前后置回调不得看到用户钩子；它们只允许在最终原子发布事务中执行。
                config.set("preSql",Collections.emptyList()); config.set("postSql",Collections.emptyList());
                config.set("upsertTable",publication.getStageTable());
                config.set("connection[0].table",Collections.singletonList(PostgresUpsertSql.target(config.getString("upsertSchema"),publication.getStageTable())));
            } else if (!"POSTGRESQL_UPSERT_V1".equals(config.getString("writeContract")) || !pre.isEmpty() || !post.isEmpty())
                throw new IllegalArgumentException("幂等写入契约无效，带钩子必须使用 V2 原子发布");
            delegate = new CommonRdbmsWriter.Job(DataBaseType.PostgreSQL);
            delegate.init(config);
        }
        @Override public void prepare() { delegate.prepare(config); }
        @Override public List<Configuration> split(int count) { return delegate.split(config, count); }
        @Override public void post() {
            delegate.post(config);
            if(publication!=null) {
                try(Connection connection=DBUtil.getConnection(DataBaseType.PostgreSQL,jdbcUrl,username,password)) { publication.publish(connection); }
                catch(SQLException failure) { throw new IllegalStateException("事务钩子发布失败，允许原身份恢复重放",failure); }
            }
        }
        @Override public void destroy() { delegate.destroy(config); }
    }

    private static String unquote(String name) {
        return name.startsWith("\"") && name.endsWith("\"") ? name.substring(1,name.length()-1).replace("\"\"","\"") : name;
    }

    public static final class Task extends Writer.Task {
        private Configuration config;
        private UpsertTask delegate;
        @Override public void init() { config = getPluginJobConf(); delegate = new UpsertTask(config); delegate.init(config); }
        @Override public void prepare() { delegate.prepare(config); }
        @Override public void startWrite(RecordReceiver receiver) { delegate.startWrite(receiver, config, getTaskPluginCollector()); }
        @Override public void post() { delegate.post(config); }
        @Override public void destroy() { delegate.destroy(config); }
    }

    private static final class UpsertTask extends CommonRdbmsWriter.Task {
        private final String schema;
        private final String target;
        private final List<String> keys;
        private final boolean staging;
        private List<String> fields;
        UpsertTask(Configuration config) {
            super(DataBaseType.PostgreSQL);
            schema = config.getString("upsertSchema"); target = config.getString("upsertTable");
            keys = config.getList("upsertKeys", String.class);
            staging = PostgresUpsertHooks.CONTRACT.equals(config.getString("writeContract"));
        }
        @Override public void init(Configuration config) {
            super.init(config);
            if (!PostgresUpsertSql.target(schema, target).equals(table)) throw new IllegalArgumentException("目标表与冻结的幂等身份不一致");
            fields = columns.stream().map(name -> name.startsWith("\"") && name.endsWith("\"")
                    ? name.substring(1, name.length() - 1).replace("\"\"", "\"") : name).collect(Collectors.toList());
        }
        @Override public void prepare(Configuration config) {
            super.prepare(config);
            try (Connection connection = DBUtil.getConnection(DataBaseType.PostgreSQL, jdbcUrl, username, password)) {
                connection.setAutoCommit(false); verify(connection); connection.rollback();
            } catch (SQLException failure) { throw new IllegalStateException("幂等目标核验失败：需要非空普通唯一键、普通表且无用户触发器/RLS", failure); }
        }
        private void verify(Connection connection) throws SQLException {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()) || connection.getMetaData().getDatabaseMajorVersion() < 12)
                throw new SQLException("幂等写入要求 PostgreSQL 12 及以上");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(60);
                statement.execute("SET LOCAL lock_timeout='60s'");
                statement.execute("LOCK TABLE " + PostgresUpsertSql.target(schema, target) + " IN ROW EXCLUSIVE MODE");
            }
            PostgresUpsertSql.verifyUniqueKey(connection, schema, target, keys, 60);
            try (PreparedStatement statement = connection.prepareStatement("SELECT c.relrowsecurity OR c.relforcerowsecurity OR EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal AND t.tgenabled<>'D') FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind='r'")) {
                statement.setString(1, schema); statement.setString(2, target); statement.setQueryTimeout(60);
                try (ResultSet rows = statement.executeQuery()) { if (!rows.next() || rows.getBoolean(1)) throw new SQLException("目标含未验证的触发器或行策略"); }
            }
            if (!fields.containsAll(keys)) throw new SQLException("源投影必须包含所有目标唯一键");
        }
        @Override protected String calcValueHolder(String type) {
            // 官方批写器先传空串探测是否需要按实际元数据重新生成占位符；该返回值不会用于真实列。
            if (type.isEmpty()) return "?::text";
            String normalized = type.toLowerCase(Locale.ROOT);
            if (!Arrays.asList("int2","int4","int8","smallint","integer","bigint","serial","bigserial","float4","float8","real","double precision","numeric","decimal","bool","boolean","text","varchar","character varying","char","bpchar","character","date","timestamp","timestamp without time zone","timestamp with time zone","timestamptz","bytea").contains(normalized))
                throw new IllegalArgumentException("幂等写入尚未验证该 PostgreSQL 字段类型：" + normalized);
            if (normalized.equals("serial")) normalized = "int4";
            if (normalized.equals("bigserial")) normalized = "int8";
            return "?::" + normalized;
        }
        @Override protected void doBatchInsert(Connection connection, List<Record> rows) throws SQLException {
            connection.setAutoCommit(false);
            try {
                verify(connection);
                String sql = PostgresUpsertSql.sql(schema, target, fields, keys,
                        resultSetMetaData.getRight().stream().map(this::calcValueHolder).collect(Collectors.toList()));
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(60);
                    for (Record row : rows) { fillPreparedStatement(statement, row); statement.addBatch(); }
                    statement.executeBatch(); connection.commit();
                }
                if(staging) LOG.info("UPSERT_HOOKS_V2 stage-batch 暂存批次已提交，业务目标尚未发布，输入行数={}",rows.size());
                else LOG.info("POSTGRESQL_UPSERT_V1 批次已提交，输入行数={}，允许同键重放",rows.size());
            } catch (Exception failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                // 不采用官方逐行降级/脏数据吞掉路径；提交结果未知时交由同键重放恢复。
                throw new SQLException("幂等批次失败或提交结果未知；必须重放原唯一键", failure);
            }
        }
    }
}
