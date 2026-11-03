package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 将已经完整落库的暂存批次与事务钩子原子发布；连接、凭据和恢复身份由调用方提供。 */
public final class PostgresUpsertPublication {
    private final String schema;
    private final String target;
    private final List<String> fields;
    private final List<String> keys;
    private final List<String> pre;
    private final List<String> post;
    private final String executionKey;
    private final String configHash;
    private final String stage;
    private final Consumer<String> log;

    public PostgresUpsertPublication(String identity, String sourceIdentity, String schema, String target,
                                    List<String> fields, List<String> keys, List<String> pre, List<String> post,
                                    Consumer<String> log) {
        if (identity == null || identity.trim().isEmpty() || identity.length() > 2048)
            throw new IllegalArgumentException("缺少稳定的工作流执行身份");
        this.schema=schema; this.target=target; this.fields=new ArrayList<>(fields); this.keys=new ArrayList<>(keys);
        this.pre=pre == null ? Collections.emptyList() : new ArrayList<>(pre);
        this.post=post == null ? Collections.emptyList() : new ArrayList<>(post);
        this.log=log == null ? ignored -> { } : log;
        PostgresUpsertSql.sql(schema,target,fields,keys,fields.stream().map(ignored -> "?").collect(Collectors.toList()));
        PostgresUpsertHooks.validate(this.pre,schema); PostgresUpsertHooks.validate(this.post,schema);
        if (target.toLowerCase(Locale.ROOT).startsWith("_datahub_")) throw new IllegalArgumentException("目标不得占用运行时保留表名");
        executionKey=hash(Collections.singletonList(identity));
        List<String> fingerprint=new ArrayList<>(Arrays.asList("v2",sourceIdentity,schema,target));
        fingerprint.addAll(fields); fingerprint.add("keys"); fingerprint.addAll(keys);
        fingerprint.add("pre"); fingerprint.addAll(this.pre); fingerprint.add("post"); fingerprint.addAll(this.post);
        configHash=hash(fingerprint);
        stage="_datahub_upsert_stage_"+UUID.randomUUID().toString().replace("-", "");
    }

    public String getStageTable() { return stage; }
    public String getExecutionKey() { return executionKey; }

    /** 首次准备即持久化冻结配置；每个物理尝试使用全新暂存表，不复用失败尝试的残行。 */
    public void prepare(Connection connection) throws SQLException {
        begin(connection);
        try {
            try(PreparedStatement lock=connection.prepareStatement("SELECT pg_advisory_xact_lock(?,?)")) {
                lock.setInt(1,0x44485550); lock.setInt(2,schema.hashCode()); lock.setQueryTimeout(60); lock.execute();
            }
            execute(connection,"CREATE TABLE IF NOT EXISTS "+ledger()+" (execution_key varchar(64) PRIMARY KEY, config_hash varchar(64) NOT NULL, "
                    + "data_hash varchar(64), state varchar(16) NOT NULL, stage_name text, completed_at timestamp with time zone) USING heap");
            PostgresUpsertHooks.verifyTable(connection,schema,PostgresUpsertHooks.LEDGER,60);
            try (PreparedStatement statement=connection.prepareStatement("INSERT INTO "+ledger()+" (execution_key,config_hash,state) VALUES (?,?,'PREPARED') ON CONFLICT (execution_key) DO NOTHING")) {
                statement.setString(1,executionKey); statement.setString(2,configHash); statement.setQueryTimeout(60); statement.executeUpdate();
            }
            readLedger(connection);
            verifyTarget(connection);
            execute(connection,"CREATE TABLE "+PostgresUpsertSql.target(schema,stage)+" USING heap AS SELECT "+quoted(fields)+" FROM "+PostgresUpsertSql.target(schema,target)+" WITH NO DATA");
            for(String key:keys) execute(connection,"ALTER TABLE "+PostgresUpsertSql.target(schema,stage)+" ALTER COLUMN "+PostgresUpsertSql.quote(key)+" SET NOT NULL");
            execute(connection,"CREATE UNIQUE INDEX "+PostgresUpsertSql.quote("_datahub_stage_key_"+stage.substring(stage.length()-32))+" ON "+PostgresUpsertSql.target(schema,stage)+" ("+quoted(keys)+")");
            connection.commit();
            log.accept("UPSERT_HOOKS_V2 stage-ready execution="+executionKey+" stage="+stage);
        } catch(Exception failure) { rollback(connection,failure); throw wrap("准备暂存区失败",failure); }
    }

    /** 唯一发布事务：前置、目标 UPSERT、后置、完成凭据同一次提交；失败时全部回滚。 */
    public void publish(Connection connection) throws SQLException {
        begin(connection); String phase="verify";
        try {
            PostgresUpsertHooks.verifyTable(connection,schema,PostgresUpsertHooks.LEDGER,60);
            String[] receipt=readLedger(connection);
            String dataHash=contentHash(connection);
            if("COMPLETED".equals(receipt[0])) {
                if(!dataHash.equals(receipt[1])) throw new SQLException("同一执行身份的源数据已变化；请创建新工作流实例，不能作为恢复重放");
                connection.rollback(); log.accept("UPSERT_HOOKS_V2 replay-skipped execution="+executionKey); return;
            }
            verifyTarget(connection);
            PostgresUpsertHooks.verifyTables(connection,schema,pre,60);
            PostgresUpsertHooks.verifyTables(connection,schema,post,60);
            phase="pre"; hooks(connection,pre,phase);
            phase="merge";
            String holders=fields.stream().map(ignored -> "?").collect(Collectors.joining(","));
            String merge=PostgresUpsertSql.sql(schema,target,fields,keys,Collections.nCopies(fields.size(),"?"))
                    .replace("VALUES ("+holders+")", "SELECT "+quoted(fields)+" FROM "+PostgresUpsertSql.target(schema,stage)+" WHERE TRUE");
            int changed=execute(connection,merge);
            log.accept("UPSERT_HOOKS_V2 merge-complete execution="+executionKey+" changed="+changed);
            phase="post"; hooks(connection,post,phase);
            phase="receipt";
            try(PreparedStatement statement=connection.prepareStatement("UPDATE "+ledger()+" SET data_hash=?,state='COMPLETED',stage_name=?,completed_at=CURRENT_TIMESTAMP WHERE execution_key=? AND config_hash=? AND state='PREPARED'")) {
                statement.setString(1,dataHash); statement.setString(2,stage); statement.setString(3,executionKey); statement.setString(4,configHash); statement.setQueryTimeout(60);
                if(statement.executeUpdate()!=1) throw new SQLException("执行凭据状态发生冲突");
            }
            phase="commit"; connection.commit();
            log.accept("UPSERT_HOOKS_V2 publish-committed execution="+executionKey+" dataHash="+dataHash);
        } catch(Exception failure) {
            rollback(connection,failure);
            log.accept("UPSERT_HOOKS_V2 publish-rollback execution="+executionKey+" phase="+phase);
            throw wrap("事务钩子发布失败或提交结果未知，phase="+phase+"；请以原执行身份重放",failure);
        }
    }

    private void verifyTarget(Connection connection) throws SQLException {
        execute(connection,"LOCK TABLE "+PostgresUpsertSql.target(schema,target)+" IN SHARE ROW EXCLUSIVE MODE");
        PostgresUpsertSql.verifyUniqueKey(connection,schema,target,keys,60);
        PostgresUpsertHooks.verifyTables(connection,schema,Collections.singletonList("DELETE FROM "+PostgresUpsertSql.target(schema,target)+" WHERE FALSE"),60);
    }

    private String[] readLedger(Connection connection) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT config_hash,state,data_hash FROM "+ledger()+" WHERE execution_key=? FOR UPDATE")) {
            statement.setString(1,executionKey); statement.setQueryTimeout(60);
            try(ResultSet rows=statement.executeQuery()) {
                if(!rows.next() || !configHash.equals(rows.getString(1))) throw new SQLException("同一执行身份的冻结配置不一致；不能恢复到修改后的配置");
                String state=rows.getString(2);
                if(!"PREPARED".equals(state) && !"COMPLETED".equals(state)) throw new SQLException("执行凭据状态无效");
                return new String[]{state,rows.getString(3)};
            }
        }
    }

    private String contentHash(Connection connection) throws SQLException {
        MessageDigest digest=digest();
        String sql="SELECT row_to_json(dh_row)::text FROM (SELECT "+quoted(fields)+" FROM "+PostgresUpsertSql.target(schema,stage)+" ORDER BY "+quoted(keys)+") dh_row";
        try(Statement statement=connection.createStatement()) {
            statement.setFetchSize(1000); statement.setQueryTimeout(60);
            try(ResultSet rows=statement.executeQuery(sql)) { while(rows.next()) update(digest,rows.getString(1)); }
        }
        return hex(digest.digest());
    }

    private void hooks(Connection connection,List<String> statements,String phase) throws SQLException {
        log.accept("UPSERT_HOOKS_V2 "+phase+"-start execution="+executionKey+" count="+statements.size());
        for(int i=0;i<statements.size();i++) {
            try { execute(connection,statements.get(i)); }
            catch(SQLException failure) { throw new SQLException("事务钩子失败 phase="+phase+" index="+i,failure); }
        }
        log.accept("UPSERT_HOOKS_V2 "+phase+"-complete execution="+executionKey);
    }

    private String ledger() { return PostgresUpsertSql.target(schema,PostgresUpsertHooks.LEDGER); }
    private static void begin(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        execute(connection,"SET LOCAL search_path=pg_catalog");
        execute(connection,"SET LOCAL standard_conforming_strings=on");
        execute(connection,"SET LOCAL lock_timeout='60s'");
        execute(connection,"SET LOCAL statement_timeout='60s'");
    }
    private static int execute(Connection connection,String sql) throws SQLException {
        try(Statement statement=connection.createStatement()) { statement.setQueryTimeout(60); return statement.executeUpdate(sql); }
    }
    private static void rollback(Connection connection,Exception failure) { try { connection.rollback(); } catch(SQLException problem) { failure.addSuppressed(problem); } }
    private static SQLException wrap(String message,Exception failure) { return new SQLException(message,failure); }
    private static String quoted(List<String> names) { return names.stream().map(PostgresUpsertSql::quote).collect(Collectors.joining(",")); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch(Exception impossible) { throw new IllegalStateException(impossible); } }
    private static void update(MessageDigest digest,String value) {
        byte[] bytes=String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[]{(byte)(bytes.length>>>24),(byte)(bytes.length>>>16),(byte)(bytes.length>>>8),(byte)bytes.length}); digest.update(bytes);
    }
    private static String hash(List<String> values) { MessageDigest digest=digest(); for(String value:values) update(digest,value); return hex(digest.digest()); }
    private static String hex(byte[] bytes) { StringBuilder text=new StringBuilder(); for(byte item:bytes) text.append(String.format(Locale.ROOT,"%02x",item&255)); return text.toString(); }
}
