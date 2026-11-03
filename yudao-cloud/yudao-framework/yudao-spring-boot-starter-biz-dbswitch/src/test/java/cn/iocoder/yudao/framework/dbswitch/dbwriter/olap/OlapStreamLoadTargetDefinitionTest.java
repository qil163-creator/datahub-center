package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OlapStreamLoadTargetDefinitionTest {
    private static String ddl(String columns, String suffix) {
        return "CREATE TABLE `db`.`target` (" + columns + ") " + suffix
                + " DISTRIBUTED BY HASH(`id`) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\");";
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void generatedFullRowScalarPlanIsPureAndExact(boolean doris) {
        var source=mock(DataSource.class); IDatabaseWriter writer=doris?new DorisWriterImpl(source):new StarrocksWriterImpl(source);
        String value=doris?"STRING":"VARBINARY";
        String sql=ddl("`id` BIGINT NOT NULL,`payload` "+value+" NULL","DUPLICATE KEY(`id`)");
        assertDoesNotThrow(()->writer.preflightTargetDefinition("db","target",List.of(sql),List.of("payload","id")));
        verifyNoInteractions(source);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void unsupportedNativePlanCannotReachAnyConnection(boolean doris) {
        var source=mock(DataSource.class); IDatabaseWriter writer=doris?new DorisWriterImpl(source):new StarrocksWriterImpl(source);
        String good=ddl("`id` BIGINT NOT NULL,`payload` VARCHAR(20) NULL","DUPLICATE KEY(`id`)");
        for(String sql:List.of(
                good.replace("VARCHAR(20)","JSON"),good.replace("VARCHAR(20)","ARRAY<INT>"),
                good.replace("VARCHAR(20) NULL","VARCHAR(20) DEFAULT 'hidden'"),
                good.replace("`id` BIGINT","`id` FLOAT"),good.replace("`id` BIGINT","`id` DOUBLE"),
                good.replace("`id` BIGINT","`id` "+(doris?"STRING":"VARBINARY")),
                good.replace("`id` BIGINT","`id` DECIMAL(30,9)"),
                good.replace("BIGINT NOT NULL","BIGINT NULL").replace("DUPLICATE",doris?"UNIQUE":"PRIMARY"),
                good.replace("DUPLICATE KEY(`id`)","DUPLICATE KEY(`payload`)"),
                good.replace("`db`.`target`","`db`.`another`"),good.replace("`db`.`target`","`another`.`target`"),
                good+"DROP TABLE `db`.`sentinel`;",good.replace("replication_num","dynamic_partition.enable"),
                good.replace("BUCKETS 1","BUCKETS 0")))
            assertThrows(IllegalArgumentException.class,()->writer.preflightTargetDefinition("db","target",List.of(sql),List.of("id","payload")));
        assertThrows(IllegalArgumentException.class,()->writer.preflightTargetDefinition("db","target",List.of(good),List.of("id")));
        assertThrows(IllegalArgumentException.class,()->writer.preflightTargetDefinition("db","target",List.of(good,good),List.of("id","payload")));
        verifyNoInteractions(source);
    }
}
