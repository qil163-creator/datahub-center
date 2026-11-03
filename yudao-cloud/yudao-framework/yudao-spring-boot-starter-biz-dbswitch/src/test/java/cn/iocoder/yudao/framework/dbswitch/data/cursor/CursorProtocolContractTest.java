package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CursorProtocolContractTest {
    private final String hash = "a".repeat(64);
    private final TypedCursor applied = new TypedCursor(1, List.of(TypedCursorValue.of(java.sql.Types.BIGINT, "int8", 1L)));
    private final TransactionCommitReceipt witness = new TransactionCommitReceipt(1, "POSTGRESQL_XID8_V1", "123", "b".repeat(64));

    @Test void jdbcAcknowledgementNeverInventsAServerTransactionWitness() {
        var result = new BatchWriteResult(2, null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
        assertNull(result.receipt());
        assertEquals(CommitAcknowledgement.NONE, new BatchWriteResult(2, null).commitAcknowledgement());
        assertThrows(IllegalArgumentException.class, () -> new BatchWriteResult(2, null, CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED));
        assertThrows(IllegalArgumentException.class, () -> new BatchWriteResult(2, witness, CommitAcknowledgement.JDBC_COMMIT_RETURNED));
    }
    @Test void v1PostgresJsonRetainsItsOriginalIdentityAndCommitWitness() throws Exception {
        var v1 = new CursorCheckpoint(1, hash, hash, hash, applied, applied, 1, true, witness);
        var object = JsonUtils.getObjectMapper().readValue(JsonUtils.toJsonString(v1), java.util.Map.class);
        object.remove("lastCommitAcknowledgement"); object.remove("sourceProduct"); object.remove("targetProduct");
        var restored = JsonUtils.getObjectMapper().readValue(JsonUtils.toJsonString(object), CursorCheckpoint.class);
        assertEquals(ProductTypeEnum.POSTGRESQL, restored.sourceProduct());
        assertEquals(CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED, restored.lastCommitAcknowledgement());
        assertEquals(v1.schemaFingerprint(), restored.schemaFingerprint()); assertEquals(witness, restored.lastCommit());
    }
    @Test void v2RequiresExplicitCommitAndProductEvidenceAndSurvivesJson() throws Exception {
        var v2 = new CursorCheckpoint(2, hash, hash, hash, applied, applied, 1, true, null,
                CommitAcknowledgement.JDBC_COMMIT_RETURNED, ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL);
        assertEquals(v2, JsonUtils.getObjectMapper().readValue(JsonUtils.toJsonString(v2), CursorCheckpoint.class));
        assertThrows(IllegalArgumentException.class, () -> new CursorCheckpoint(2, hash, hash, hash, applied, applied, 1, true, null,
                null, ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> new CursorCheckpoint(2, hash, hash, hash, applied, applied, 1, true, null,
                CommitAcknowledgement.JDBC_COMMIT_RETURNED, null, ProductTypeEnum.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> new CursorCheckpoint(1, hash, hash, hash, applied, applied, 1, true, null,
                CommitAcknowledgement.JDBC_COMMIT_RETURNED, ProductTypeEnum.MYSQL, ProductTypeEnum.MYSQL));
    }
    @Test void numericKeyMappingRequiresContainmentIncludingUnsignedMaximumAndScale() {
        var uint64 = contract(ProductTypeEnum.MYSQL, CursorKeyContract.Domain.integer(64, true));
        assertDoesNotThrow(() -> uint64.requireReplayCompatible(contract(ProductTypeEnum.POSTGRESQL, CursorKeyContract.Domain.decimal(20, 0, false))));
        assertThrows(IllegalArgumentException.class, () -> uint64.requireReplayCompatible(contract(ProductTypeEnum.POSTGRESQL, CursorKeyContract.Domain.integer(64, false))));
        var decimal = contract(ProductTypeEnum.MYSQL, CursorKeyContract.Domain.decimal(20, 8, false));
        assertThrows(IllegalArgumentException.class, () -> decimal.requireReplayCompatible(contract(ProductTypeEnum.POSTGRESQL, CursorKeyContract.Domain.decimal(20, 7, false))));
        assertThrows(IllegalArgumentException.class, () -> contract(ProductTypeEnum.MYSQL, CursorKeyContract.Domain.nativeOnly())
                .requireReplayCompatible(contract(ProductTypeEnum.POSTGRESQL, CursorKeyContract.Domain.nativeOnly())));
    }
    @Test void v2AcknowledgementCannotExistWithoutAnAppliedPositionOrPositiveSequence() {
        assertThrows(IllegalArgumentException.class,()->new CursorCheckpoint(2,hash,hash,hash,null,null,0,false,null,
                CommitAcknowledgement.JDBC_COMMIT_RETURNED,ProductTypeEnum.MYSQL,ProductTypeEnum.MYSQL));
        assertThrows(IllegalArgumentException.class,()->new CursorCheckpoint(2,hash,hash,hash,applied,applied,0,false,null,
                CommitAcknowledgement.JDBC_COMMIT_RETURNED,ProductTypeEnum.MYSQL,ProductTypeEnum.MYSQL));
        assertThrows(IllegalArgumentException.class,()->new CursorCheckpoint(2,hash,hash,hash,null,applied,1,false,null,
                CommitAcknowledgement.JDBC_COMMIT_RETURNED,ProductTypeEnum.MYSQL,ProductTypeEnum.MYSQL));
    }
    @Test void changedDeploymentGenerationCannotConstructARecoveryBinding() {
        var previous = new CursorCheckpoint(1, hash, hash, hash, applied, applied, 1, true, witness);
        assertThrows(IllegalArgumentException.class, () -> new CursorRunBinding("b".repeat(64), previous, next -> true));
    }
    @Test void visibleStreamLoadKeepsItsMechanismAcrossCheckpointJson() throws Exception {
        var result = new BatchWriteResult(2, null, CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE);
        assertNull(result.receipt());
        assertThrows(IllegalArgumentException.class, () -> new BatchWriteResult(2, witness,
                CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE));
        for (var target : List.of(ProductTypeEnum.DORIS, ProductTypeEnum.STARROCKS)) {
            var checkpoint = new CursorCheckpoint(2, hash, hash, hash, applied, applied, 1, true, null,
                    result.commitAcknowledgement(), ProductTypeEnum.POSTGRESQL, target);
            String json = JsonUtils.toJsonString(checkpoint);
            assertTrue(json.contains("OLAP_STREAM_LOAD_VISIBLE"));
            assertEquals(checkpoint, JsonUtils.getObjectMapper().readValue(json, CursorCheckpoint.class));
        }
    }
    @Test void streamLoadAcknowledgementCannotCertifyAnotherProductOrUnappliedCursor() {
        for (var target : ProductTypeEnum.values()) {
            if (target == ProductTypeEnum.DORIS || target == ProductTypeEnum.STARROCKS) continue;
            assertThrows(IllegalArgumentException.class, () -> new CursorCheckpoint(2, hash, hash, hash,
                    applied, applied, 1, true, null, CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE,
                    ProductTypeEnum.POSTGRESQL, target), target.name());
        }
        assertThrows(IllegalArgumentException.class, () -> new CursorCheckpoint(2, hash, hash, hash,
                null, null, 0, false, null, CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE,
                ProductTypeEnum.POSTGRESQL, ProductTypeEnum.DORIS));
        assertThrows(IllegalArgumentException.class, () -> new CursorCheckpoint(1, hash, hash, hash,
                applied, applied, 1, true, null, CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE,
                ProductTypeEnum.POSTGRESQL, ProductTypeEnum.POSTGRESQL));
    }
    private CursorKeyContract contract(ProductTypeEnum product, CursorKeyContract.Domain domain) {
        return new CursorKeyContract(product, hash, List.of(domain));
    }
}
