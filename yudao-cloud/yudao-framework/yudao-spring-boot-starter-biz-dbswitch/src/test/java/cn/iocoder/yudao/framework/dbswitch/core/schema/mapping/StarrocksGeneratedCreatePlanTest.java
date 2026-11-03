package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

class StarrocksGeneratedCreatePlanTest {
    private final DbSwitchProductRegistry registry=DbSwitchProductRegistry.discover();

    @Test void acceptedGeneratedCreateRetainsOrdinaryQuotedNamesAndExactlyOneExternalKeyModel() {
        var columns=List.of(LogicalTypeMappingTest.pg("select","bigint",Types.BIGINT),LogicalTypeMappingTest.pg("part","smallint",Types.SMALLINT));
        String ddl=GenerateSqlUtils.getDDLCreateTableSQL(registry.metadata(STARROCKS),columns,List.of("select","part"),"Schema74","Target74",false,TypeMappingOptions.of(registry.typeMappings()));
        assertTrue(ddl.startsWith("CREATE TABLE `Schema74`.`Target74`"),ddl);
        assertTrue(ddl.contains("`select` bigint"),ddl);
        assertEquals(1,ddl.split("PRIMARY KEY",-1).length-1,ddl);
        assertTrue(ddl.indexOf("PRIMARY KEY")>ddl.indexOf(')'),ddl);
        assertTrue(ddl.contains("DISTRIBUTED BY HASH"),ddl);
        assertThrows(UnsupportedOperationException.class,()->SqlScriptPlan.parse(STARROCKS,ddl));
        assertThrows(UnsupportedOperationException.class,()->SqlScriptPlan.parse(STARROCKS,"DROP TABLE db.t"));
        var unsupportedName=LogicalTypeMappingTest.pg("key`ID","bigint",Types.BIGINT);
        assertThrows(IllegalArgumentException.class,()->GenerateSqlUtils.getDDLCreateTableSQL(registry.metadata(STARROCKS),List.of(unsupportedName),List.of("key`ID"),"db","t",false,TypeMappingOptions.of(registry.typeMappings())));
    }

    @Test void lossyOrUnsupportedColumnCannotReachCreateAssemblyWithoutMatchingDecision() {
        var key=LogicalTypeMappingTest.pg("id","bigint",Types.BIGINT);
        var amount=LogicalTypeMappingTest.pg("amount","numeric(38,18)",Types.NUMERIC);
        var mappings=TypeMappingOptions.of(registry.typeMappings());
        assertThrows(UnsupportedOperationException.class,()->GenerateSqlUtils.getDDLCreateTableSQL(registry.metadata(STARROCKS),List.of(key,amount),List.of("id"),"db","t",false,mappings));
        var unsupported=LogicalTypeMappingTest.pg("value","uuid",Types.OTHER);
        assertThrows(UnsupportedOperationException.class,()->GenerateSqlUtils.getDDLCreateTableSQL(registry.metadata(STARROCKS),List.of(key,unsupported),List.of("id"),"db","t",false,mappings));
    }
}
