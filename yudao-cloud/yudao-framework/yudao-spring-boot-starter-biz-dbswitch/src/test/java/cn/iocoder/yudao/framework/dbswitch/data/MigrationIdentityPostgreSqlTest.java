package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationIdentityPostgreSqlTest {
    private final String source = "identity_src_" + UUID.randomUUID().toString().replace("-", "");
    private final String target = "identity_dst_" + UUID.randomUUID().toString().replace("-", "");
    @BeforeEach void setup() throws Exception { sql("CREATE SCHEMA " + source, "CREATE SCHEMA " + target); }
    @AfterEach void cleanup() throws Exception { sql("DROP SCHEMA IF EXISTS " + source + " CASCADE", "DROP SCHEMA IF EXISTS " + target + " CASCADE"); }

    @ParameterizedTest @CsvSource({"true,ALWAYS", "false,ALWAYS", "true,BY DEFAULT", "false,BY DEFAULT"})
    void actualMigrationPreservesExplicitValuesAndAlignsSampledSequence(boolean insert, String kind) throws Exception {
        sql("CREATE TABLE " + source + ".source(id bigint GENERATED " + kind + " AS IDENTITY (START WITH 7 INCREMENT BY 3 CACHE 4), note text, PRIMARY KEY(id))",
                "INSERT INTO " + source + ".source OVERRIDING SYSTEM VALUE VALUES(7,'a'),(100,'b')");
        // Source position is sampled independently of MVCC row contents, and must not be rewound.
        sql("ALTER SEQUENCE " + source + ".source_id_seq RESTART WITH 202");
        var request = request(insert); assertSuccess(request);
        try (var connection = open(); var statement = connection.createStatement()) {
            var metadata = NativeSchemaReader.read(connection, ProductTypeEnum.POSTGRESQL, target, "copy", 10);
            assertEquals(List.of("id"), metadata.primaryKeys());
            var sequence = metadata.columns().getFirst().getMetadataEvidence().sequence().value();
            assertNotNull(sequence); assertEquals(7, sequence.start()); assertEquals(3, sequence.increment()); assertEquals(4, sequence.cache());
            assertTrue(metadata.columns().getFirst().getMetadataEvidence().identity().value().contains("GENERATED " + kind));
            try(var rows = statement.executeQuery("SELECT id,note FROM " + target + ".copy ORDER BY id")) {
                assertTrue(rows.next()); assertEquals(7, rows.getLong(1)); assertEquals("a", rows.getString(2));
                assertTrue(rows.next()); assertEquals(100, rows.getLong(1)); assertEquals("b", rows.getString(2)); assertFalse(rows.next());
            }
            try(var rows = statement.executeQuery("INSERT INTO " + target + ".copy(note) VALUES('generated') RETURNING id")) {
                assertTrue(rows.next()); assertEquals(202, rows.getLong(1));
            }
        }
    }

    @ParameterizedTest @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void descendingNonDefaultStartSupportsEmptyAndPopulatedTables(boolean insert, boolean empty) throws Exception {
        sql("CREATE TABLE " + source + ".source(id bigint GENERATED ALWAYS AS IDENTITY (START WITH -7 INCREMENT BY -3 MINVALUE -1000 MAXVALUE -1), note text, PRIMARY KEY(id))");
        if (!empty) sql("INSERT INTO " + source + ".source OVERRIDING SYSTEM VALUE VALUES(-100,'copied')");
        assertSuccess(request(insert));
        try (var connection=open(); var statement=connection.createStatement();
             var row=statement.executeQuery("INSERT INTO " + target + ".copy(note) VALUES('generated') RETURNING id")) {
            assertTrue(row.next()); assertEquals(empty ? -7 : -103, row.getLong(1));
        }
    }

    @ParameterizedTest @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void ownedSerialDefaultUsesIndependentTargetSequenceAndPreservesOptions(boolean insert, boolean syncDefaults) throws Exception {
        sql("CREATE SEQUENCE " + source + ".owned AS integer START WITH 11 INCREMENT BY 2 MINVALUE 1 MAXVALUE 10000 CACHE 1",
                "CREATE TABLE " + source + ".source(id int DEFAULT nextval('" + source + ".owned'::regclass) PRIMARY KEY, note text)",
                "ALTER SEQUENCE " + source + ".owned OWNED BY " + source + ".source.id",
                "INSERT INTO " + source + ".source VALUES(50,'copied')");
        var request = request(insert); request.getTarget().setSyncDefaultVal(syncDefaults); assertSuccess(request);
        try(var connection=open(); var statement=connection.createStatement()) {
            var facts=NativeSchemaReader.read(connection,ProductTypeEnum.POSTGRESQL,target,"copy",10).columns().getFirst().getMetadataEvidence();
            assertEquals(target,facts.sequence().value().sourceSchema()); assertFalse(facts.defaultExpression().value().contains(source));
            var definition=facts.sequence().value();
            assertEquals(11,definition.start());assertEquals(2,definition.increment());assertEquals(1,definition.cache());
            assertEquals(1,definition.minimum());assertEquals(10000,definition.maximum());assertFalse(definition.cycle());
            try(var row=statement.executeQuery("SELECT id,note FROM " + target + ".copy")) {
                assertTrue(row.next());assertEquals(50,row.getLong(1));assertEquals("copied",row.getString(2));assertFalse(row.next());
            }
            try(var row=statement.executeQuery("INSERT INTO " + target + ".copy(note) VALUES('generated') RETURNING id")) {
                assertTrue(row.next()); assertEquals(51,row.getLong(1));
            }
            try(var row=statement.executeQuery("SELECT last_value,is_called FROM " + source + ".owned")) {
                assertTrue(row.next()); assertEquals(11,row.getLong(1)); assertFalse(row.getBoolean(2));
            }
        }
    }

    @Test void copyOwnedDefaultPreservesDescendingBoundsAndCacheWithoutTouchingSourcePosition() throws Exception {
        sql("CREATE SEQUENCE " + source + ".owned AS integer START WITH -11 INCREMENT BY -2 MINVALUE -10000 MAXVALUE -1 CACHE 4 NO CYCLE",
                "CREATE TABLE " + source + ".source(id integer DEFAULT nextval('" + source + ".owned'::regclass) PRIMARY KEY,note text)",
                "ALTER SEQUENCE " + source + ".owned OWNED BY " + source + ".source.id",
                "INSERT INTO " + source + ".source VALUES(-50,'copied descending')");
        assertSuccess(request(false));
        try(var connection=open();var statement=connection.createStatement()) {
            var definition=NativeSchemaReader.read(connection,ProductTypeEnum.POSTGRESQL,target,"copy",10)
                    .columns().getFirst().getMetadataEvidence().sequence().value();
            assertEquals(target,definition.sourceSchema());assertEquals(-11,definition.start());assertEquals(-2,definition.increment());
            assertEquals(-10000,definition.minimum());assertEquals(-1,definition.maximum());assertEquals(4,definition.cache());assertFalse(definition.cycle());
            try(var row=statement.executeQuery("SELECT id,note FROM " + target + ".copy")) {
                assertTrue(row.next());assertEquals(-50,row.getLong(1));assertEquals("copied descending",row.getString(2));assertFalse(row.next());
            }
            try(var row=statement.executeQuery("INSERT INTO " + target + ".copy(note) VALUES('generated') RETURNING id")) {
                assertTrue(row.next());assertEquals(-51,row.getLong(1));
            }
            try(var row=statement.executeQuery("SELECT last_value,is_called FROM " + source + ".owned")) {
                assertTrue(row.next());assertEquals(-11,row.getLong(1));assertFalse(row.getBoolean(2));
            }
        }
    }

    @ParameterizedTest @CsvSource({"APPEND", "TRUNCATE", "DROP"})
    void existingTargetAheadIsNeverRewound(String mode) throws Exception {
        sql("CREATE TABLE " + source + ".source(id bigint GENERATED BY DEFAULT AS IDENTITY (START WITH 9 INCREMENT BY 4), note text, PRIMARY KEY(id))",
                "INSERT INTO " + source + ".source VALUES(13,'copied')",
                "CREATE TABLE " + target + ".copy(id bigint GENERATED BY DEFAULT AS IDENTITY (START WITH 9 INCREMENT BY 4), note text, PRIMARY KEY(id))",
                "ALTER SEQUENCE " + target + ".copy_id_seq RESTART WITH 1001");
        var request=request(true); request.getTarget().setTargetTruncateData(mode.equals("TRUNCATE"));
        request.getTarget().setTargetDrop(mode.equals("DROP"));assertSuccess(request);
        try(var connection=open();var statement=connection.createStatement();
            var row=statement.executeQuery("INSERT INTO " + target + ".copy(note) VALUES('generated') RETURNING id")) {
            assertTrue(row.next()); assertEquals(1001,row.getLong(1));
        }
    }

    @Test void conflictingTargetSequenceFailsBeforeExplicitTruncate() throws Exception {
        sql("CREATE TABLE " + source + ".source(id bigint GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 3), note text)",
                "CREATE TABLE " + target + ".copy(id bigint GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 7), note text)",
                "INSERT INTO " + target + ".copy(note) VALUES('retain')");
        var request=request(true); request.getTarget().setTargetTruncateData(true);
        assertFalse(run(request));
        try(var connection=open();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT note FROM " + target + ".copy")) {
            assertTrue(rows.next());assertEquals("retain",rows.getString(1));assertFalse(rows.next());
        }
    }

    @Test void dependentDdlFailureRollsBackEntireCreatePlan() throws Exception {
        sql("CREATE SEQUENCE " + source + ".owned AS integer START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 10000 CACHE 1",
                "CREATE TABLE " + source + ".source(id int DEFAULT nextval('" + source + ".owned'::regclass), note text)",
                "ALTER SEQUENCE " + source + ".owned OWNED BY " + source + ".source.id");
        // A failing final ownership operation happens after CREATE SEQUENCE and CREATE TABLE in the same transaction.
        var request=request(true);
        request.getTarget().setCustomDdlMap(Map.of("copy", "CREATE SEQUENCE " + target + ".rollback_seq AS integer START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 10000 CACHE 1 NO CYCLE;"
                + "CREATE TABLE " + target + ".copy(id int,note text);ALTER SEQUENCE " + target + ".rollback_seq OWNED BY " + target + ".copy.missing"));
        assertFalse(run(request));
        try(var connection=open();var statement=connection.createStatement();var row=statement.executeQuery("SELECT to_regclass('" + target + ".copy'),to_regclass('" + target + ".rollback_seq')")) {
            assertTrue(row.next());assertNull(row.getObject(1));assertNull(row.getObject(2));
        }
    }

    @Test void replacementDdlFailureRestoresOriginalTableAndData() throws Exception {
        sql("CREATE TABLE " + source + ".source(id bigint GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 3), note text)",
                "CREATE TABLE " + target + ".copy(id bigint GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 3), note text)",
                "INSERT INTO " + target + ".copy(note) VALUES('retain-original')");
        var request=request(true);request.getTarget().setTargetDrop(true);
        request.getTarget().setCustomDdlMap(Map.of("copy", "CREATE SEQUENCE " + target + ".rollback_seq AS bigint START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 10000 CACHE 1 NO CYCLE;"
                + "CREATE TABLE " + target + ".copy(id bigint,note text);ALTER SEQUENCE " + target + ".rollback_seq OWNED BY " + target + ".copy.missing"));
        assertFalse(run(request));
        try(var connection=open();var statement=connection.createStatement();var row=statement.executeQuery("SELECT id,note FROM "+target+".copy")) {
            assertTrue(row.next());assertEquals(5,row.getLong(1));assertEquals("retain-original",row.getString(2));assertFalse(row.next());
        }
    }

    @ParameterizedTest @CsvSource({"true", "false"})
    void sourceOwnedSequenceWithAnotherDefaultReferenceFailsBeforeReplacement(boolean identity) throws Exception {
        sql("CREATE TABLE " + source + ".source(id " + (identity ? "bigint GENERATED ALWAYS AS IDENTITY" : "bigserial") + ", note text)",
                "CREATE TABLE " + source + ".peer(id bigint DEFAULT nextval('" + source + ".source_id_seq'::regclass))",
                "CREATE TABLE " + target + ".copy(id bigint, note text)",
                "INSERT INTO " + target + ".copy VALUES(99,'retain')");
        var request=request(true);request.getTarget().setTargetDrop(true);
        assertThrows(IllegalArgumentException.class,()->run(request),"Resource dependency closure must fail before any table is scheduled");
        try(var connection=open();var statement=connection.createStatement();var row=statement.executeQuery("SELECT id,note FROM "+target+".copy")) {
            assertTrue(row.next());assertEquals(99,row.getLong(1));assertEquals("retain",row.getString(2));assertFalse(row.next());
        }
        request.getTarget().setOnlyCreate(true);request.getTarget().setPreserveIdentityValues(false);request.getTarget().setExclusiveTargetOwnership(false);
        assertFalse(run(request),"Structure-only cannot silently detach another default from the same owned sequence");
    }

    @ParameterizedTest @CsvSource({"true", "false"})
    void sharedTargetSequenceFailsBeforeTruncateEvenIfSourceIsPrivate(boolean identity) throws Exception {
        String column = identity ? "bigint GENERATED ALWAYS AS IDENTITY" : "bigserial";
        sql("CREATE TABLE " + source + ".source(id " + column + ", note text)",
                "CREATE TABLE " + target + ".copy(id " + column + ", note text)",
                "CREATE TABLE " + target + ".peer(id bigint DEFAULT nextval('" + target + ".copy_id_seq'::regclass))",
                "INSERT INTO " + target + ".copy(note) VALUES('retain')");
        var request=request(true);request.getTarget().setTargetTruncateData(true);
        assertFalse(run(request));
        try(var connection=open();var statement=connection.createStatement();var row=statement.executeQuery("SELECT id,note FROM "+target+".copy")) {
            assertTrue(row.next());assertEquals(1,row.getLong(1));assertEquals("retain",row.getString(2));assertFalse(row.next());
        }
    }

    private MigrationRequest request(boolean insert) {
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.POSTGRESQL); input.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        input.setUrl(url());input.setUsername(user());input.setPassword(password());input.setSourceSchema(source);
        input.setSourceIncludes("source");input.setPoolSize(1);input.setFetchSize(1);
        input.setRegexTableMapper(List.of(new PatternMapper("^source$","copy")));request.getSource().add(input);
        var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        output.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());output.setUrl(url());output.setUsername(user());output.setPassword(password());
        output.setTargetSchema(target);output.setTargetTable("copy");output.setPoolSize(1);output.setSyncDefaultVal(true);
        output.setPreserveIdentityValues(true);output.setExclusiveTargetOwnership(true);output.setWriterEngineInsert(insert);
        return request;
    }
    private static boolean run(MigrationRequest request) throws Exception {
        return runResult(request).getIfAllSuccess().get();
    }
    private static DbSwitchResult runResult(MigrationRequest request) throws Exception {
        try(var runtime=DbSwitchRuntime.standalone()) { return new MigrationService(request,ignored->{},null,runtime).run(); }
    }
    private static void assertSuccess(MigrationRequest request) throws Exception {
        var result=runResult(request);
        assertTrue(result.getIfAllSuccess().get(),()->"Identity migration must complete including sequence alignment"
                + "; failedTables=" + result.getTotalTableFailCount() + "; termination=" + result.getTermination()
                + "; copyPlanRejected=" + result.getTableResultList().stream().anyMatch(table -> table.getErrorMsg()!=null
                    && table.getErrorMsg().contains("PostgreSQL WRITE_COPY planned native target definition is not verified")));
    }
    private static void sql(String... statements) throws Exception { try(var connection=open();var statement=connection.createStatement()){for(String sql:statements)statement.execute(sql);} }
    private static Connection open() throws Exception { return DriverManager.getConnection(url(),user(),password()); }
    private static String url(){return System.getProperty("datahub.test.postgres.url");}
    private static String user(){return System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(){return System.getProperty("datahub.test.postgres.password","");}
}
