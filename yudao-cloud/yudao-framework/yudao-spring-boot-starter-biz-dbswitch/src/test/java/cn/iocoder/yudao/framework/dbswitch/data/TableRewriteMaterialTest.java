package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableRewriteMaterialTest {
    static final TableRewriteLimits LIMITS=new TableRewriteLimits(new BatchLimits(1,4096,2048,100,1000),20,20,65536);
    static final String SCOPE="a".repeat(64),RESOURCE="b".repeat(64),BINDING="c".repeat(64),OWNER="table_owner_0001";
    static final TableRewriteIdentity OLD=new TableRewriteIdentity("fixture-v1","{\"generation\":1}");
    static final TableRewriteIdentity NEXT=new TableRewriteIdentity("fixture-v1","{\"generation\":2}");
    static final NativeTableDefinition DEFINITION=new NativeTableDefinition(ProductTypeEnum.INFLUXDB3,"fixture-roles-v1","{\"roles\":[\"tag\",\"time\",\"field\",\"field\"]}",List.of("key","time","unsigned","amount"));
    static Object[] row(String key,String amount){return new Object[]{key,Instant.parse("2026-10-04T00:00:00.123456789Z"),new BigInteger("18446744073709551615"),new BigDecimal(amount)};}
    static TableRewriteMaterial material(){return TableRewriteMaterial.create(BINDING,"iox","rows",OLD,DEFINITION,List.of("key","time"),List.of(row("a","1.00"),row("b","2.00")),List.of(row("b","3.000"),row("c","4.00")),LIMITS);}
    static TableRewriteKey key(){return new TableRewriteKey(SCOPE,RESOURCE,"d".repeat(64));}
    @Test void actualPagingPreservesUpdatesInsertsDeletesExactTypesAndFrozenSchema() {
        var value=material();assertEquals(4,value.pages().size());assertEquals(2,value.rows(TableRewriteMaterial.Image.AFTER));
        var restored=TableRewriteMaterial.decode(value.manifest(),value.pages().stream().map(TableRewriteMaterial.Page::encoded).toList(),LIMITS);
        assertEquals(value.hash(),restored.hash());assertEquals(DEFINITION,restored.definition());assertEquals(OLD,restored.oldIdentity());
        var updated=restored.rows(restored.pages().get(2),LIMITS).getFirst();assertArrayEquals(row("b","3.000"),updated);
    }
    @Test void incomingRowsManifestAndPageArraysCannotMutateFrozenMaterial() {
        Object[] input=row("a","1.00");var value=TableRewriteMaterial.create(BINDING,"iox","rows",OLD,DEFINITION,List.of("key"),Collections.singletonList(input),List.of(),LIMITS);
        input[0]="changed";byte[] manifest=value.manifest();manifest[0]^=1;byte[] page=value.pages().getFirst().encoded();page[0]^=1;
        var row=value.rows(value.pages().getFirst(),LIMITS).getFirst();assertEquals("a",row[0]);row[0]="again";
        assertEquals("a",value.rows(value.pages().getFirst(),LIMITS).getFirst()[0]);assertFalse(value.toString().contains("rows"));
    }
    @Test void missingReorderedModifiedPagesAndManifestAreRejected() {
        var value=material();var swapped=new ArrayList<>(value.pages().stream().map(TableRewriteMaterial.Page::encoded).toList());
        Collections.swap(swapped,0,1);assertThrows(IllegalArgumentException.class,()->TableRewriteMaterial.decode(value.manifest(),swapped,LIMITS));
        var altered=new ArrayList<>(value.pages().stream().map(TableRewriteMaterial.Page::encoded).toList());altered.getFirst()[12]^=1;
        assertThrows(IllegalArgumentException.class,()->TableRewriteMaterial.decode(value.manifest(),altered,LIMITS));
        assertThrows(IllegalArgumentException.class,()->TableRewriteMaterial.decode(value.manifest(),List.of(),LIMITS));
        byte[] changed=value.manifest();changed[changed.length-1]^=1;
        assertThrows(IllegalArgumentException.class,()->TableRewriteMaterial.decode(changed,value.pages().stream().map(TableRewriteMaterial.Page::encoded).toList(),LIMITS));
    }
    @Test void duplicateKeysAcrossPagesNullKeysAndCombinedWholeTableBudgetFailBeforeIntent() {
        assertThrows(IllegalArgumentException.class,()->TableRewriteMaterial.create(BINDING,"iox","rows",OLD,DEFINITION,List.of("key"),List.of(row("a","1"),row("a","2")),List.of(),LIMITS));
        assertThrows(IllegalArgumentException.class,()->TableRewriteMaterial.create(BINDING,"iox","rows",OLD,DEFINITION,List.of("key"),Collections.singletonList(row(null,"1")),List.of(),LIMITS));
        var one=new TableRewriteLimits(LIMITS.page(),1,20,65536);
        assertThrows(IllegalArgumentException.class,()->TableRewriteMaterial.create(BINDING,"iox","rows",OLD,DEFINITION,List.of("key"),Collections.singletonList(row("a","1")),Collections.singletonList(row("b","2")),one));
    }
    @Test void createUnknownAndAmbiguousIntentCannotBecomeConfirmedByAdoptingAName() {
        var prepared=new TableRewriteRecord(key(),0,OWNER,TableRewriteRecord.Phase.PREPARED,null,material());
        var dropped=prepared.advance(TableRewriteRecord.Phase.DROP_INTENT,null).advance(TableRewriteRecord.Phase.DROP_CONFIRMED,null);
        var creating=dropped.advance(TableRewriteRecord.Phase.CREATE_INTENT,null);assertFalse(creating.permitsAutomaticReconciliation());
        var unknown=creating.advance(TableRewriteRecord.Phase.CREATE_UNKNOWN,null);
        assertFalse(unknown.permitsAutomaticReconciliation());assertThrows(IllegalArgumentException.class,()->unknown.claimed("table_owner_0002"));
        assertThrows(IllegalArgumentException.class,()->unknown.advance(TableRewriteRecord.Phase.CREATE_CONFIRMED,NEXT));
        assertThrows(IllegalArgumentException.class,()->creating.advance(TableRewriteRecord.Phase.CREATE_CONFIRMED,OLD));
        var confirmed=creating.advance(TableRewriteRecord.Phase.CREATE_CONFIRMED,NEXT);
        assertTrue(confirmed.permitsAutomaticReconciliation());assertEquals(NEXT,confirmed.claimed("table_owner_0002").newIdentity());
    }
    @Test void emptyBeforeAndAfterAreRepresentableWithoutInventingRows() {
        var value=TableRewriteMaterial.create(BINDING,"iox","rows",OLD,DEFINITION,List.of("key"),List.of(),List.of(),LIMITS);
        assertTrue(value.pages().isEmpty());assertEquals(value.hash(),TableRewriteMaterial.decode(value.manifest(),List.of(),LIMITS).hash());
    }
}
