package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DorisTableLayoutsTest {
    static DorisCreateTableOptions rangeOptions() {
        return new DorisCreateTableOptions(new DorisCreateTableOptions.Partition("RANGE", List.of("id"), List.of(
                new DorisCreateTableOptions.PartitionEntry("p_low", List.of(List.of("0"), List.of("100"))),
                new DorisCreateTableOptions.PartitionEntry("p_high", List.of(List.of("100"), List.of("MAXVALUE"))))),
                "HASH", List.of("id"), "FIXED", 2, 1);
    }
    static List<ColumnDescription> columns() {
        var id=new ColumnDescription();id.setFieldName("id");id.setFieldType(Types.INTEGER);id.setFieldTypeName("integer");id.setPrecisionSize(10);id.setSigned(true);id.setPk(true);
        var value=new ColumnDescription();value.setFieldName("note");value.setFieldType(Types.VARCHAR);value.setFieldTypeName("varchar");value.setPrecisionSize(20);value.setDisplaySize(20);value.setNullable(true);
        return List.of(id,value);
    }
    static String create(DorisCreateTableOptions options,String schema,String table) {
        return GenerateSqlUtils.getDDLCreateTableSQL(new DatabaseDorisImpl(),columns(),List.of("id"),schema,table,true,"configured",false,
                options==null?Map.of():Map.of(DorisCreateTableOptions.TABLE_PROPERTY,options.toJson()));
    }
    @Test void generatedDdlUsesStructuredRangeHashBucketsAndReplication() {
        var ddl=create(rangeOptions(),"db","dest");var parsed=DorisNativeSchema.parse(ddl);
        assertEquals(List.of("id"),parsed.distribution());assertEquals(2,parsed.buckets());
        assertEquals("RANGE",parsed.partitioning().kind());assertEquals("p_high",parsed.partitioning().partitions().getLast().name());
        assertEquals("tag.location.default: 1",parsed.properties().get("replication_allocation"));
        assertEquals("configured",parsed.comment());assertTrue(ddl.startsWith("CREATE TABLE `db`.`dest`"));
    }
    @Test void absentOptionsRetainLegacyDdl() {
        String ddl=create(null,"db","dest");assertTrue(ddl.contains("BUCKETS 10"));assertFalse(ddl.contains("PARTITION BY"));
    }
    @Test void listRandomAndAutoAreRenderedThroughExistingNativeModel() {
        var options=new DorisCreateTableOptions(new DorisCreateTableOptions.Partition("LIST",List.of("note"),List.of(
                new DorisCreateTableOptions.PartitionEntry("p_city",List.of(List.of("Beijing"),List.of("Shanghai"))))),"RANDOM",List.of(),"AUTO",null,1);
        var table=DorisNativeSchema.parse(create(options,"db","dest"));assertEquals(0,table.buckets());assertTrue(table.distribution().isEmpty());assertEquals("LIST",table.partitioning().kind());
    }
    @Test void invalidTypesOverlapUnknownColumnsAndSqlPayloadAreRejected() {
        var unknown=new DorisCreateTableOptions(null,"HASH",List.of("missing"),"FIXED",1,1);
        assertThrows(IllegalArgumentException.class,()->create(unknown,"db","dest"));
        var overlap=new DorisCreateTableOptions(new DorisCreateTableOptions.Partition("RANGE",List.of("id"),List.of(
                new DorisCreateTableOptions.PartitionEntry("p1",List.of(List.of("0"),List.of("20"))),
                new DorisCreateTableOptions.PartitionEntry("p2",List.of(List.of("10"),List.of("30"))))),"HASH",List.of("id"),"FIXED",1,1);
        assertThrows(IllegalArgumentException.class,()->create(overlap,"db","dest"));
        var injection=new DorisCreateTableOptions(new DorisCreateTableOptions.Partition("RANGE",List.of("id"),List.of(
                new DorisCreateTableOptions.PartitionEntry("p1",List.of(List.of("0"),List.of("1'); DROP TABLE t; --"))))),"HASH",List.of("id"),"FIXED",1,1);
        assertThrows(IllegalArgumentException.class,()->create(injection,"db","dest"));
    }
    @Test void nativeUniqueModelRejectsRandomAndNonKeyDistribution() {
        var table=DorisNativeSchema.parse(DorisPartitionSchemaTest.RANGE);
        assertThrows(IllegalArgumentException.class,()->DorisTableLayouts.apply(table,null,new DorisCreateTableOptions(null,"RANDOM",List.of(),"AUTO",null,1)));
        assertThrows(IllegalArgumentException.class,()->DorisTableLayouts.apply(table,null,new DorisCreateTableOptions(null,"HASH",List.of("v"),"AUTO",null,1)));
    }
    @Test void jsonRoundTripAndMapperSnapshotKeepImmutableOptions() {
        var options=rangeOptions();assertEquals(options,DorisCreateTableOptions.fromJson(options.toJson()));
        var mapping=new MapperConfig();mapping.setTargetTableName("explicit");mapping.setTargetTableComment("");mapping.setDorisOptions(options);
        var copied=mapping.copy();mapping.setTargetTableName("later");assertEquals("explicit",copied.getTargetTableName());assertEquals("",copied.getTargetTableComment());assertEquals(options,copied.getDorisOptions());
        assertThrows(UnsupportedOperationException.class,()->options.distributionColumns().add("other"));
        assertThrows(IllegalArgumentException.class,()->DorisCreateTableOptions.fromJson(options.toJson().replace("\"HASH\"","\"RANDOM\"")));
    }
    @Test void finalNameOverridesRegexPrefixAndCaseForBothMappingModes() {
        var source=new SourceDataSourceProperties();source.setRegexTableMapper(List.of(new PatternMapper("source","legacy")));
        var target=new TargetDataSourceProperties();target.setTargetProductType(ProductTypeEnum.DORIS);target.setTablePrefix("ods_");target.setLowercase(true);
        assertEquals("ods_legacy",MigrationTableNames.resolve(source,target,"source"));
        var mapping=new MapperConfig();mapping.setTargetTableName("ChosenName");source.setConfigMap(Map.of("source",mapping));
        for(String mode:List.of("ALL","SINGLE")){source.setMapperType(mode);assertEquals("ChosenName",MigrationTableNames.resolve(source,target,"source"));}
        mapping.setTargetTableName("bad\0name");assertThrows(IllegalArgumentException.class,()->MigrationTableNames.resolve(source,target,"source"));
    }
}
