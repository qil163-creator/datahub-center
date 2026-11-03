package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtomicBatchContractTest {
    final AtomicBatchTarget target = new AtomicBatchTarget("schema", "table", "batch", "ordinal", "status");
    final AtomicBatchKey key = new AtomicBatchKey("scope", "batch-1");

    @Test void typedCodecPreservesLargeIntegersDecimalsDatesBytesAndNullWithoutJsonNumberLoss() {
        List<String> fields = List.of("batch", "ordinal", "status", "big", "decimal", "day", "local", "offset", "instant", "bytes", "null", "uuid", "time", "offsettime");
        Object[] row = {key.batchKey(), 1, "PASS", 9007199254740993L, new BigDecimal("12345678901234567890.1234500"),
                LocalDate.of(2026, 10, 3), LocalDateTime.parse("2026-10-03T12:34:56.123456789"),
                OffsetDateTime.parse("2026-10-03T12:34:56.123456789+08:00"), Instant.parse("2026-10-03T01:02:03.123456789Z"),
                new byte[]{0, 1, -1}, null, UUID.randomUUID(), LocalTime.parse("12:34:56.123456789"), OffsetTime.parse("12:34:56+08:00")};
        var request = AtomicBatchRequest.create(target, key, fields, List.<Object[]>of(row));
        var restored = AtomicBatchCodec.decode(AtomicBatchCodec.encode(request));
        assertEquals(request.payloadHash(), restored.payloadHash()); assertEquals(target, restored.target()); assertEquals(key, restored.key());
        assertArrayEquals(request.rows().getFirst(), restored.rows().getFirst());
        assertEquals(9007199254740993L, restored.rows().getFirst()[3]);
        assertEquals(new BigDecimal("12345678901234567890.12345"), restored.rows().getFirst()[4]);
    }

    @Test void frozenPayloadAndReturnedCopiesCannotAlterHashOrWriteValues() {
        byte[] bytes = {1, 2}; Object[] row = {key.batchKey(), 1, "PASS", bytes};
        var fields = new ArrayList<>(List.of("batch", "ordinal", "status", "binary"));
        var request = AtomicBatchRequest.create(target, key, fields, List.<Object[]>of(row));
        String hash = request.payloadHash(); bytes[0] = 9; row[2] = "OTHER"; fields.set(0, "different");
        ((byte[]) request.rows().getFirst()[3])[0] = 8;
        assertEquals("PASS", request.rows().getFirst()[2]); assertArrayEquals(new byte[]{1, 2}, (byte[]) request.rows().getFirst()[3]);
        assertEquals(hash, request.payloadHash()); assertEquals(hash, AtomicBatchCodec.decode(AtomicBatchCodec.encode(request)).payloadHash());
    }

    @Test void finiteFloatingScalarsCanonicalizeOnceAndRoundTripWithSameHash() {
        for (Number value : List.of(1.0f, 1.0d, 0.1f, 0.1d, -0.0d)) {
            var request = AtomicBatchRequest.create(target, key, List.of("batch", "ordinal", "status", "number"),
                    List.<Object[]>of(new Object[]{key.batchKey(), 1, "PASS", value}));
            assertEquals(request.payloadHash(), AtomicBatchCodec.decode(AtomicBatchCodec.encode(request)).payloadHash());
        }
    }

    @Test void codecRejectsUnknownVersionTypeTruncationTrailingBytesAndChangedHash() {
        var request = AtomicBatchRequest.create(target, key, List.of("batch", "ordinal", "status"),
                List.<Object[]>of(new Object[]{key.batchKey(), 1, "PASS"}));
        byte[] original = Base64.getDecoder().decode(AtomicBatchCodec.encode(request));
        byte[] version = original.clone(); ByteBuffer.wrap(version).putInt(4, 2); reject(version);
        reject(Arrays.copyOf(original, original.length - 1)); reject(Arrays.copyOf(original, original.length + 1));
        byte[] hash = original.clone(); int hashOffset = 8;
        for (int i = 0; i < 7; i++) hashOffset += 4 + ByteBuffer.wrap(hash, hashOffset, 4).getInt();
        hash[hashOffset + 4] = hash[hashOffset + 4] == 'a' ? (byte) 'b' : (byte) 'a'; reject(hash);
        byte[] type = original.clone(); int at = 8;
        for (int i = 0; i < 8; i++) at += 4 + ByteBuffer.wrap(type, at, 4).getInt();
        int fields = ByteBuffer.wrap(type, at, 4).getInt(); at += 4;
        for (int i = 0; i < fields; i++) at += 4 + ByteBuffer.wrap(type, at, 4).getInt();
        at += 4; type[at] = 127; reject(type);
        assertThrows(IllegalArgumentException.class, () -> AtomicBatchCodec.decode("!not-base64"));
        assertThrows(IllegalArgumentException.class, () -> AtomicBatchCodec.decode("A".repeat(24 * 1024 * 1024)));
    }

    @Test void invalidTechnicalValuesDuplicateFieldsAndOpaqueValuesFailBeforeConnecting() {
        var fields = List.of("batch", "ordinal", "status", "value");
        for (Object[] row : List.of(new Object[]{"wrong", 1, "PASS", 1}, new Object[]{key.batchKey(), 0, "PASS", 1},
                new Object[]{key.batchKey(), 1, "", 1}, new Object[]{key.batchKey(), 1, "PASS", new Object()}))
            assertThrows(IllegalArgumentException.class, () -> AtomicBatchRequest.create(target, key, fields, List.<Object[]>of(row)));
        assertThrows(IllegalArgumentException.class, () -> AtomicBatchRequest.create(target, key, List.of("batch", "batch"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> AtomicBatchRequest.create(target, key, fields,
                List.of(new Object[]{key.batchKey(), 1, "PASS", 1}, new Object[]{key.batchKey(), 1, "PASS", 2})));
    }

    @Test void registryOffersCapabilityOnlyForRegisteredPostgresqlProviderWithoutOpeningConnection() {
        var registry = DbSwitchProductRegistry.discover(); var source = mock(DataSource.class);
        assertInstanceOf(PostgresAtomicBatchWriter.class, registry.atomicBatchWriter(source, ProductTypeEnum.POSTGRESQL));
        assertThrows(UnsupportedOperationException.class, () -> registry.atomicBatchWriter(source, ProductTypeEnum.MYSQL));
        assertDoesNotThrow(() -> registry.product(ProductTypeEnum.POSTGRESQL).require(DatabaseOperation.WRITE_ATOMIC_BATCH));
        verifyNoInteractions(source);
    }
    private static void reject(byte[] bytes) {
        assertThrows(IllegalArgumentException.class, () -> AtomicBatchCodec.decode(Base64.getEncoder().encodeToString(bytes)));
    }
}
