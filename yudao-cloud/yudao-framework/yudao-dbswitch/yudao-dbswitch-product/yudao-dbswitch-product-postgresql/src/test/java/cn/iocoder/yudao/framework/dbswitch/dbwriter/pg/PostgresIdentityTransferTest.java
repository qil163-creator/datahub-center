package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class PostgresIdentityTransferTest {
    private static ColumnSequenceMetadata sequence(long start,long step,long last,boolean called) {
        return new ColumnSequenceMetadata(ColumnSequenceMetadata.Kind.IDENTITY_ALWAYS,"s","seq","bigint",start,step,
                Long.MIN_VALUE,Long.MAX_VALUE,1,false, MetadataValue.value(new ColumnSequenceMetadata.Position(last,called)));
    }
    @Test void alignmentPreservesProgressionAndBothObservedPositions() {
        assertEquals(103,PostgresIdentityTransfer.alignedNext(sequence(7,3,7,false),sequence(7,3,7,false),BigInteger.valueOf(100)));
        assertEquals(1001,PostgresIdentityTransfer.alignedNext(sequence(9,4,9,false),sequence(9,4,1001,false),BigInteger.valueOf(13)));
        assertEquals(205,PostgresIdentityTransfer.alignedNext(sequence(7,3,202,true),sequence(7,3,7,false),BigInteger.valueOf(100)));
        assertEquals(-103,PostgresIdentityTransfer.alignedNext(sequence(-7,-3,-7,false),sequence(-7,-3,-7,false),BigInteger.valueOf(-100)));
        assertEquals(-7,PostgresIdentityTransfer.alignedNext(sequence(-7,-3,-7,false),sequence(-7,-3,-7,false),null));
    }
    @Test void exhaustedSignedBoundaryNeverOverflowsOrRewinds() {
        assertThrows(IllegalArgumentException.class,()->PostgresIdentityTransfer.alignedNext(
                sequence(1,1,1,false),sequence(1,1,Long.MAX_VALUE,true),null));
        assertThrows(IllegalArgumentException.class,()->PostgresIdentityTransfer.alignedNext(
                sequence(-1,-1,-1,false),sequence(-1,-1,Long.MIN_VALUE,true),null));
    }
    @Test void sequenceAstAcceptsOnlyBoundedOperationsAndQualifiedNames() {
        var sql="CREATE SEQUENCE \"s\".\"q\" AS bigint START WITH -5 INCREMENT BY -2 MINVALUE -999 MAXVALUE -1 CACHE 3 NO CYCLE;"
                +"ALTER SEQUENCE \"s\".\"q\" OWNED BY \"s\".\"t\".\"id\";ALTER SEQUENCE \"s\".\"q\" CACHE 3;ALTER SEQUENCE \"s\".\"q\" RESTART WITH -55";
        assertEquals(4,SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,sql).steps().size());
        for(String bad:new String[]{"ALTER SEQUENCE s.q RESTART WITH random()", "ALTER SEQUENCE q RESTART WITH 1",
                "ALTER SEQUENCE s.q OWNED BY NONE", "ALTER SEQUENCE s.q OWNER TO another_role",
                "ALTER SEQUENCE s.q CACHE 0", "ALTER SEQUENCE s.q RESTART WITH 9223372036854775808",
                "CREATE SEQUENCE s.q AS bigint START WITH 1 INCREMENT BY 0 MINVALUE 1 MAXVALUE 10 CACHE 1 NO CYCLE"})
            assertThrows(IllegalArgumentException.class,()->SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,bad),bad);
    }
    @Test void targetFlagsAreExplicitAndSnapshotted() {
        var target=new TargetDataSourceProperties();target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setPreserveIdentityValues(true);assertThrows(IllegalArgumentException.class,target::copy);
        target.setExclusiveTargetOwnership(true);var snapshot=target.copy();target.setPreserveIdentityValues(false);
        assertTrue(snapshot.getPreserveIdentityValues());assertTrue(snapshot.getExclusiveTargetOwnership());
        snapshot.setChangeDataSync(true);assertThrows(IllegalArgumentException.class,snapshot::copy);
    }

    @Test void sequenceBoundsMustFitTheDeclaredSequenceDataType() {
        assertThrows(IllegalArgumentException.class,()->new ColumnSequenceMetadata(ColumnSequenceMetadata.Kind.OWNED_DEFAULT,
                "s","seq","smallint",1,1,1,32768,1,false,MetadataValue.unknown()));
        assertThrows(IllegalArgumentException.class,()->new ColumnSequenceMetadata(ColumnSequenceMetadata.Kind.OWNED_DEFAULT,
                "s","seq",null,1,1,1,100,1,false,MetadataValue.unknown()));
    }
    @Test void identityAndSequenceCatalogDisagreementFailsBeforeGeneratingDdl() {
        var column=new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription();column.setFieldName("id");
        var sequence=sequence(5,3,5,false);
        column.setMetadataEvidence(new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(ProductTypeEnum.POSTGRESQL,"PostgreSQL","16","NATIVE_CATALOG_V1",java.time.Instant.now()),
                MetadataValue.value("bigint"),MetadataValue.absent(),MetadataValue.value(false),MetadataValue.value(19),MetadataValue.value(0),MetadataValue.absent(),
                MetadataValue.value("GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 7 MINVALUE -9223372036854775808 MAXVALUE 9223372036854775807 CACHE 1 NO CYCLE)"),
                MetadataValue.absent(),MetadataValue.absent(),MetadataValue.absent(),MetadataValue.value(sequence)));
        assertThrows(IllegalArgumentException.class,()->cn.iocoder.yudao.framework.dbswitch.core.schema.NativeColumnSql.definition(ProductTypeEnum.POSTGRESQL,column));
    }
    @Test void observedSequencePositionIsExcludedFromStructuralIdentity() {
        assertEquals(sequence(5,3,5,false).structuralIdentity(),sequence(5,3,101,true).structuralIdentity());
        assertNotEquals(sequence(5,3,5,false).structuralIdentity(),sequence(5,7,5,false).structuralIdentity());
    }
}
