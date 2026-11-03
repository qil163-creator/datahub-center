package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.util.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyColumnAcceptance;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.dm.DmWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class MigrationPostgresDmIdentityTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private Connection dm,pg;private String schema;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        dm=openDm();pg=runtime.openConnection(ProductTypeEnum.POSTGRESQL,null,pgUrl(),pgUser(),pgPassword());schema="DM_X_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        sql(dm,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE TABLE "+t("S")+"(\"ID\" BIGINT PRIMARY KEY,\"V\" VARCHAR(30))");
        sql(pg,"INSERT INTO "+t("S")+" VALUES(9007199254740993,'source'),(2,'second')");
    }
    @AfterEach void cleanup()throws Exception {
        try {if(pg!=null)try(var owned=pg){if(schema!=null)sql(pg,"DROP SCHEMA "+q(schema)+" CASCADE");}}
        finally {try {if(dm!=null)try(var owned=dm){if(schema!=null)sql(dm,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}
    }
    @ParameterizedTest @ValueSource(strings={"TRUNCATE","REPLACE","APPEND"})
    void pgSourceCannotChangeExistingDmIdentityEvenWithExplicitReplacement(String mode)throws Exception {
        String target="t'\";.";sql(dm,"CREATE TABLE "+t(target)+"(ID BIGINT IDENTITY(7,3) PRIMARY KEY,V VARCHAR(30))");
        sql(dm,"SET IDENTITY_INSERT "+t(target)+" ON");sql(dm,"INSERT INTO "+t(target)+"(ID,V)VALUES(100,'sentinel')");sql(dm,"SET IDENTITY_INSERT "+t(target)+" OFF");
        String before=DmRelationIdentity.physicalFingerprint(dm,schema,target,15);
        var request=request(target);request.getTarget().setTargetDrop(mode.equals("REPLACE"));request.getTarget().setExclusiveTargetDdlOwnership(true);request.getTarget().setTargetTruncateData(mode.equals("TRUNCATE"));
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertFalse(result.getIfAllSuccess().get());
        assertEquals(before,DmRelationIdentity.physicalFingerprint(dm,schema,target,15));assertEquals(List.of("100:sentinel"),rows(dm,target));
        sql(dm,"INSERT INTO "+t(target)+"(V)VALUES('next')");assertEquals(List.of("100:sentinel","103:next"),rows(dm,target));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void pgSourceStillWritesOrdinaryExistingDmTargets(boolean truncate)throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(ID BIGINT PRIMARY KEY,V VARCHAR(30))");sql(dm,"INSERT INTO "+t("T")+" VALUES(9,'sentinel')");
        var request=request("T");request.getTarget().setTargetTruncateData(truncate);var result=new MigrationService(request,ignored->{},null,runtime).run();
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertEquals(truncate?List.of("2:second","9007199254740993:source"):List.of("2:second","9:sentinel","9007199254740993:source"),rows(dm,"T"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingTextEqualityAcknowledgementPreservesOrdinaryTarget(boolean truncate)throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(ID BIGINT PRIMARY KEY,V VARCHAR(30))");sql(dm,"INSERT INTO "+t("T")+" VALUES(9,'sentinel')");
        var request=request("T");request.setTypeMappingAcknowledgements(List.of());request.getTarget().setTargetTruncateData(truncate);
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertFalse(result.getIfAllSuccess().get());
        assertEquals(List.of("9:sentinel"),rows(dm,"T"));
        assertTrue(result.getTableResultList().getFirst().getTypeMappingPlan().acceptedLossyColumns().isEmpty());
    }
    @Test void autoIncrementTargetsAlsoFailBeforeAnExplicitTruncate()throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(ID BIGINT AUTO_INCREMENT PRIMARY KEY,V VARCHAR(30))");sql(dm,"INSERT INTO "+t("T")+"(ID,V) VALUES(100,'sentinel')");
        var request=request("T");request.getTarget().setTargetTruncateData(true);var result=new MigrationService(request,ignored->{},null,runtime).run();assertFalse(result.getIfAllSuccess().get());assertEquals(List.of("100:sentinel"),rows(dm,"T"));
    }
    @Test void failedIdentityReprepareCannotReusePreviousWritableTarget()throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(ID BIGINT PRIMARY KEY,V VARCHAR(30))");sql(dm,"CREATE TABLE "+t("I")+"(ID BIGINT IDENTITY(7,3),V VARCHAR(30))");
        var writer=new DmWriterImpl(new AbstractDataSource(){@Override public Connection getConnection()throws SQLException{return openDm();}@Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit principal required");}});
        writer.prepareWrite(schema,"T",List.of("ID","V"));assertThrows(IllegalStateException.class,()->writer.prepareWrite(schema,"I",List.of("ID","V")));
        assertThrows(RuntimeException.class,()->writer.write(List.of("ID","V"),Collections.singletonList(new Object[]{1L,"blocked"})));
        assertTrue(rows(dm,"T").isEmpty());assertTrue(rows(dm,"I").isEmpty());
    }
    private MigrationRequest request(String target){var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.POSTGRESQL);s.setUrl(pgUrl());s.setUsername(pgUser());s.setPassword(pgPassword());s.setSourceSchema(schema);s.setSourceIncludes("S");s.setPoolSize(1);s.setRegexTableMapper(List.of(new PatternMapper("^S$",target)));r.getSource().add(s);var t=r.getTarget();t.setTargetProductType(ProductTypeEnum.DM);t.setUrl(dmUrl());t.setUsername("SYSDBA");t.setPassword(dmPassword());t.setTargetSchema(schema);t.setPoolSize(1);t.setWriterEngineInsert(true);
        var metadata=runtime.metadata(new org.springframework.jdbc.datasource.DriverManagerDataSource(pgUrl(),pgUser(),pgPassword()),ProductTypeEnum.POSTGRESQL);
        var plan=runtime.products().typeMappings().plan(ProductTypeEnum.DM,metadata.queryTableColumnMetaWithNativeEvidence(schema,"S"),List.of("ID"),LossyColumnAcceptance.rejectAll());
        var text=plan.columns().stream().filter(column->column.column().equals("V")).findFirst().orElseThrow();
        assertEquals(Set.of("PAD_SPACE_EQUALITY_CHANGED"),text.lossCodes());
        r.setTypeMappingAcknowledgements(List.of(LossyMappingAcknowledgement.forColumn(schema,target,text)));return r;}
    private List<String> rows(Connection c,String table)throws SQLException{var values=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT \"ID\",\"V\" FROM "+t(table)+" ORDER BY \"ID\"")){while(r.next())values.add(r.getString(1)+":"+r.getString(2));}return values;}
    private Connection openDm()throws SQLException{return runtime.openConnection(ProductTypeEnum.DM,null,dmUrl(),"SYSDBA",dmPassword());}
    private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private String t(String table){return q(schema)+'.'+q(table);}private static String q(String name){return DmSqlNames.quote(name);}
    private static String dmUrl(){return System.getProperty("datahub.test.dm.url");}private static String dmPassword(){return System.getenv("DATAHUB_DM_TEST_PASSWORD");}
    private static String pgUrl(){return System.getProperty("datahub.test.postgres.url");}private static String pgUser(){return System.getProperty("datahub.test.postgres.user","dataforge_test");}private static String pgPassword(){return System.getProperty("datahub.test.postgres.password","");}
}
