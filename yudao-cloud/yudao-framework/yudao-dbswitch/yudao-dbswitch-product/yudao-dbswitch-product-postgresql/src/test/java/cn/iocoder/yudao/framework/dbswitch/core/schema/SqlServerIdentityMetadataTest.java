package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.core.model.SqlServerIdentityMetadata;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlServerIdentityMetadataTest {
    @Test void freshAndTruncatedReseedUseCurrentWhileDeletedAndRolledBackAdvance() {
        assertEquals(n("41"),state("int","5","3","41",false).nextObservedValue());
        assertEquals(n("44"),state("int","5","3","41",true).nextObservedValue());
    }
    @Test void mergesSourceReplacementAndCurrentWithoutRewindingPositiveProgression() {
        var source=state("bigint","5","3","101",true);
        var target=state("bigint","5","3","11",true);
        var before=state("bigint","5","3","1001",true);
        assertEquals(n("1004"),source.alignedNext(target,before,n("55")));
        assertEquals(n("3003"),source.alignedNext(target,before,n("3000")));
    }
    @Test void negativeProgressionPreservesLowestObservedPosition() {
        var source=state("bigint","-5","-3","-101",true);
        var target=state("bigint","-5","-3","-11",true);
        var before=state("bigint","-5","-3","-1001",true);
        assertEquals(n("-1004"),source.alignedNext(target,before,n("-55")));
        assertEquals(n("-3003"),source.alignedNext(target,before,n("-3000")));
    }
    @Test void decimal38CounterNeverNarrowsToLong() {
        var source=state("numeric(38,0)","9007199254740993","7","99999999999999999999999999999999999980",true);
        var target=state("numeric(38,0)","9007199254740993","7","9007199254740993",false);
        assertEquals(n("99999999999999999999999999999999999987"),source.alignedNext(target,null,null));
    }
    @Test void rejectsExhaustionZeroIncrementAndDifferentDefinitions() {
        var exhausted=state("tinyint","1","1","255",true);
        assertThrows(IllegalArgumentException.class,()->exhausted.alignedNext(exhausted,null,null));
        assertThrows(IllegalArgumentException.class,()->state("int","1","0","1",false));
        assertThrows(IllegalArgumentException.class,()->state("decimal(38,1)","1","1","1",false));
        assertThrows(IllegalArgumentException.class,()->state("int","1","1","1",false)
                .alignedNext(state("int","2","1","2",false),null,null));
    }
    @Test void negativeAndNumericPrecisionBoundsAreExact() {
        assertThrows(IllegalArgumentException.class,()->state("numeric(3,0)","1000","1","1",false));
        assertThrows(IllegalArgumentException.class,()->state("tinyint","-1","1","1",false));
        assertThrows(IllegalArgumentException.class,()->state("bigint","-9223372036854775809","1","1",false));
        assertThrows(IllegalArgumentException.class,()->state("decimal(38,0)","1","100000000000000000000000000000000000000","1",false));
        var exhausted=state("numeric(3,0)","-1","-7","-998",true);
        assertThrows(IllegalArgumentException.class,()->exhausted.alignedNext(exhausted,null,null));
        var currentOutsideDomain=state("smallint","1","1","32768",false);
        assertThrows(IllegalArgumentException.class,()->currentOutsideDomain.alignedNext(currentOutsideDomain,null,null));
    }
    @Test void productIdentityConfigurationCannotBeSilentlyConsumedByAnotherProduct() {
        var ds=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var options=new cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions(true,true,java.util.Map.of(),
                java.util.Map.of("id",state("int","1","1","1",false)));
        assertThrows(IllegalArgumentException.class,()->new cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresInsertWriterImpl(ds)
                .configureIdentityTransfer(options));
        org.mockito.Mockito.verifyNoInteractions(ds);
    }
    @Test void legacySqlServerCannotOptIntoUnprovenNativeIdentityTransfer() {
        var request=new cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties();
        request.setTargetProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER2000);
        request.setPreserveIdentityValues(true);request.setExclusiveTargetOwnership(true);
        assertThrows(IllegalArgumentException.class,request::copy);
    }
    private static SqlServerIdentityMetadata state(String type,String seed,String step,String current,boolean called) {
        return new SqlServerIdentityMetadata(type,n(seed),n(step),n(current),called,false);
    }
    private static BigInteger n(String value){return new BigInteger(value);}
}
