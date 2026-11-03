package cn.iocoder.yudao.framework.dbswitch.dbchange;

import cn.iocoder.yudao.framework.dbswitch.common.config.DiffSortLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue;
import java.math.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiffRowSortTest {
    @TempDir Path directory;
    private DiffSortLimits limits(long disk) { return new DiffSortLimits(4096, disk, directory.toString()); }
    private final Comparator<Object[]> keys = Comparator.comparing(row -> (String) row[0]);
    private DiffRowSort.Source source(List<Object[]> rows) {
        var iterator = rows.iterator(); return () -> iterator.hasNext() ? iterator.next() : null;
    }
    private void emptyDirectory() throws Exception { try (var children = Files.list(directory)) { assertEquals(0, children.count()); } }

    private List<Object[]> sharedBudgetRows() {
        return java.util.stream.IntStream.range(0,3).mapToObj(i->new Object[]{"key-"+i,"p".repeat(250)}).toList();
    }

    @Test void twoComparisonsShareARealDirectoryBudgetAndDeletionReleasesIt() throws Exception {
        var shared=new DiffSortLimits(8192,4096,directory.toString(),1024);
        try(var first=new DiffRowSort(shared,2,1024,()->{})) {
            first.sort(source(sharedBudgetRows()),keys,new int[]{0});
            try(var second=new DiffRowSort(shared,2,1024,()->{})) {
                assertThrows(IllegalStateException.class,()->second.sort(source(sharedBudgetRows()),keys,new int[]{0}));
            }
            // Different spelling of the same directory cannot create an independent budget.
            var alias=new DiffSortLimits(8192,4096,directory.resolve(".").toString(),1024);
            try(var second=new DiffRowSort(alias,2,1024,()->{})) {
                assertThrows(IllegalStateException.class,()->second.sort(source(sharedBudgetRows()),keys,new int[]{0}));
            }
        }
        try(var next=new DiffRowSort(shared,2,1024,()->{})) {
            assertEquals(3,next.sort(source(sharedBudgetRows()),keys,new int[]{0}).rows());
        }
        emptyDirectory();
    }

    @Test void failedDeletionRemainsChargedUntilCleanupReallySucceeds() throws Exception {
        var shared=new DiffSortLimits(8192,4096,directory.toString(),1024);
        var first=new DiffRowSort(shared,2,1024,()->{});
        var run=first.sort(source(sharedBudgetRows()),keys,new int[]{0});
        Path retained=run.path().resolveSibling("retained-by-test.bin");
        Files.move(run.path(),retained);Files.createDirectory(run.path());
        Path obstacle=Files.writeString(run.path().resolve("obstacle"),"cleanup failure");
        try {
            assertThrows(IllegalStateException.class,first::close);
            try(var second=new DiffRowSort(shared,2,1024,()->{})) {
                assertThrows(IllegalStateException.class,()->second.sort(source(sharedBudgetRows()),keys,new int[]{0}));
            }
        } finally {
            Files.delete(obstacle);Files.delete(run.path());Files.move(retained,run.path());first.close();
        }
        try(var next=new DiffRowSort(shared,2,1024,()->{})) {
            assertEquals(3,next.sort(source(sharedBudgetRows()),keys,new int[]{0}).rows());
        }
        emptyDirectory();
    }

    @Test void activeDirectoryCannotSilentlyChangeItsSharedBudget() throws Exception {
        var firstLimits=new DiffSortLimits(8192,4096,directory.toString(),1024);
        var changedLimits=new DiffSortLimits(8192,4096,directory.toString(),2048);
        try(var first=new DiffRowSort(firstLimits,2,1024,()->{})) {
            assertThrows(IllegalStateException.class,()->new DiffRowSort(changedLimits,2,1024,()->{}));
        }
        try(var next=new DiffRowSort(changedLimits,2,1024,()->{})) {
            assertEquals(3,next.sort(source(sharedBudgetRows()),keys,new int[]{0}).rows());
        }
        emptyDirectory();
    }

    @Test void nativeEnvelopeOwnsPayloadAndKeyWithoutPrintingDocumentContents() {
        byte[] payload = "private-document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] key = {1, 2};
        var value = new NativeCellValue("TEST_V1", payload, key);
        payload[0] = 0; key[0] = 0;
        assertEquals('p', value.encoded()[0]); assertEquals(1, value.matchingKey()[0]);
        value.encoded()[0] = 0; value.matchingKey()[0] = 0;
        assertEquals('p', value.encoded()[0]); assertEquals(1, value.matchingKey()[0]);
        assertFalse(value.toString().contains("private-document"));
        assertThrows(IllegalArgumentException.class, () -> new NativeCellValue("bad format", new byte[]{1}, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> value.compareMatchingKey(new NativeCellValue("OTHER_V1", new byte[]{1}, new byte[]{1})));
    }

    @Test void nativeValuesRetainPayloadTypesWhileCanonicalMatchingKeysCanBeEqual() {
        var integer = new NativeCellValue("TEST_V1", new byte[]{16, 1}, new byte[]{1});
        var decimal = new NativeCellValue("TEST_V1", new byte[]{19, 1}, new byte[]{1});
        assertEquals(0, integer.compareMatchingKey(decimal));
        assertNotEquals(0, integer.compareValue(decimal));
        assertNotEquals(integer, decimal);
        var codec = new DiffRowCodec(3, 1024);
        var restored = codec.decode(codec.encode(new Object[]{integer, MissingCellValue.INSTANCE, null}));
        assertEquals(integer, restored[0]); assertNotSame(integer, restored[0]);
        assertSame(MissingCellValue.INSTANCE, restored[1]); assertNull(restored[2]);
    }

    @Test void duplicateNativeMatchingKeysAcrossRunsFailBeforeOutput() throws Exception {
        Comparator<Object[]> nativeKeys = (left, right) -> ((NativeCellValue) left[0]).compareMatchingKey((NativeCellValue) right[0]);
        var input = new ArrayList<Object[]>();
        for (int i = 0; i < 80; i++) input.add(new Object[]{new NativeCellValue("TEST_V1", new byte[]{16, (byte) i}, new byte[]{(byte) i})});
        input.add(new Object[]{new NativeCellValue("TEST_V1", new byte[]{19, 0}, new byte[]{0})});
        try (var sort = new DiffRowSort(limits(1024 * 1024), 1, 1024, () -> {})) {
            assertThrows(IllegalStateException.class, () -> sort.sort(source(input), nativeKeys, new int[]{0}));
        }
        emptyDirectory();
    }

    @Test void missingMatchingKeyIsRejectedEvenByAnOtherwisePermissiveComparator() throws Exception {
        try (var sort = new DiffRowSort(limits(1024 * 1024), 1, 1024, () -> {})) {
            assertThrows(IllegalStateException.class, () -> sort.sort(source(List.<Object[]>of(new Object[]{MissingCellValue.INSTANCE})), (left, right) -> 0, new int[]{0}));
        }
        emptyDirectory();
    }

    @Test void nativePayloadWithoutMatchingEvidenceIsLosslessButNeverAnAcceptedDiffKey() throws Exception {
        var value = new NativeCellValue("TEST_V1", new byte[]{3, 1, 2}, null);
        assertFalse(value.hasMatchingKey()); assertNull(value.matchingKey());
        var codec = new DiffRowCodec(1, 1024);
        var restored = (NativeCellValue) codec.decode(codec.encode(new Object[]{value}))[0];
        assertEquals(value, restored); assertFalse(restored.hasMatchingKey());
        assertEquals(0, value.compareValue(restored));
        assertThrows(IllegalArgumentException.class, () -> value.compareMatchingKey(restored));
        try (var sort = new DiffRowSort(limits(1024 * 1024), 1, 1024, () -> {})) {
            assertThrows(IllegalStateException.class, () -> sort.sort(source(List.<Object[]>of(new Object[]{value})), (left, right) -> 0, new int[]{0}));
        }
        emptyDirectory();
    }

    @Test void externalMergeUsesOneComparatorAndPreservesAllRowsWithoutPlaintextOnDisk() throws Exception {
        var input = new ArrayList<Object[]>();
        for (int i = 0; i < 150; i++) input.add(new Object[]{String.format("private-payload-%03d-😀-\uE000", i), i});
        Collections.shuffle(input, new Random(19));
        try (var sort = new DiffRowSort(limits(1024 * 1024), 2, 1024, () -> {})) {
            var run = sort.sort(source(input), keys, new int[]{0});
            byte[] bytes = Files.readAllBytes(run.path());
            assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("private-payload"));
            try (var rows = sort.rows(run)) {
                for (int i = 0; i < 150; i++) {
                    Object[] row = rows.next(); assertEquals(i, row[1]);
                    assertEquals(String.format("private-payload-%03d-😀-\uE000", i), row[0]);
                }
                assertNull(rows.next()); assertNull(rows.next());
            }
        }
        emptyDirectory();
    }

    @Test void externallyDeletedRunCanReleaseItsChargeWhenCleanupIsRetried() throws Exception {
        var shared = new DiffSortLimits(8192, 4096, directory.toString(), 1024);
        var first = new DiffRowSort(shared, 2, 1024, () -> {});
        var run = first.sort(source(sharedBudgetRows()), keys, new int[]{0});
        Path retained = run.path().resolveSibling("retained-by-test.bin");
        Files.move(run.path(), retained);
        Files.createDirectory(run.path());
        Path obstacle = Files.writeString(run.path().resolve("obstacle"), "cleanup failure");
        assertThrows(IllegalStateException.class, first::close);
        Files.delete(obstacle); Files.delete(run.path()); Files.delete(retained);
        first.close(); first.close();
        try (var next = new DiffRowSort(shared, 2, 1024, () -> {})) {
            assertEquals(3, next.sort(source(sharedBudgetRows()), keys, new int[]{0}).rows());
        }
        emptyDirectory();
    }

    @Test void replacementDirectoryIsNeverDeletedOrUsedToReleaseOriginalSpoolCharges() throws Exception {
        var shared = new DiffSortLimits(8192, 4096, directory.toString(), 1024);
        var first = new DiffRowSort(shared, 2, 1024, () -> {});
        var run = first.sort(source(sharedBudgetRows()), keys, new int[]{0});
        Path ownedDirectory = run.path().getParent();
        Path retained = ownedDirectory.resolveSibling("retained-original-directory");
        Files.move(ownedDirectory, retained);
        Files.createDirectory(ownedDirectory);
        Path replacement = Files.writeString(ownedDirectory.resolve(run.path().getFileName()), "unrelated replacement");
        try {
            assertThrows(IllegalStateException.class, first::close);
            assertEquals("unrelated replacement", Files.readString(replacement));
            try (var second = new DiffRowSort(shared, 2, 1024, () -> {})) {
                assertThrows(IllegalStateException.class, () -> second.sort(source(sharedBudgetRows()), keys, new int[]{0}));
            }
        } finally {
            Files.delete(replacement); Files.delete(ownedDirectory); Files.move(retained, ownedDirectory);
            first.close();
        }
        emptyDirectory();
    }

    @Test void emptyBudgetLeaseCanCloseWhileDirectoryDeletionIsRetried() throws Exception {
        var shared = new DiffSortLimits(8192, 4096, directory.toString(), 1024);
        var first = new DiffRowSort(shared, 2, 1024, () -> {});
        var run = first.sort(source(sharedBudgetRows()), keys, new int[]{0});
        Path obstacle = Files.writeString(run.path().resolveSibling("unowned-obstacle"), "keep");
        assertThrows(IllegalStateException.class, first::close);
        assertFalse(Files.exists(run.path()));
        var changed = new DiffSortLimits(8192, 4096, directory.toString(), 2048);
        try (var next = new DiffRowSort(changed, 2, 1024, () -> {})) {
            assertEquals(3, next.sort(source(sharedBudgetRows()), keys, new int[]{0}).rows());
        }
        assertEquals("keep", Files.readString(obstacle));
        Files.delete(obstacle); first.close(); first.close();
        emptyDirectory();
    }

    @Test void duplicatesAcrossChunksAndNullKeysFailBeforeConsumerCanOpenOutput() throws Exception {
        var input = new ArrayList<Object[]>();
        for (int i = 0; i < 100; i++) input.add(new Object[]{"key-" + i});
        input.add(new Object[]{"key-0"});
        try (var sort = new DiffRowSort(limits(1024 * 1024), 1, 1024, () -> {})) {
            assertThrows(IllegalStateException.class, () -> sort.sort(source(input), keys, new int[]{0}));
        }
        try (var sort = new DiffRowSort(limits(1024), 1, 1024, () -> {})) {
            assertThrows(IllegalStateException.class, () -> sort.sort(source(List.<Object[]>of(new Object[]{null})), keys, new int[]{0}));
        }
        emptyDirectory();
    }

    @Test void diskLimitAndCancellationCleanEveryOwnedRun() throws Exception {
        var input = new ArrayList<Object[]>();
        for (int i = 0; i < 100; i++) input.add(new Object[]{"some-value-" + i});
        try (var sort = new DiffRowSort(limits(1024), 1, 1024, () -> {})) {
            assertThrows(IllegalStateException.class, () -> sort.sort(source(input), keys, new int[]{0}));
        }
        emptyDirectory();
        var checks = new AtomicInteger();
        try (var sort = new DiffRowSort(limits(1024 * 1024), 1, 1024, () -> {
            if (checks.incrementAndGet() > 20) throw new IllegalStateException("cancelled");
        })) {
            assertThrows(IllegalStateException.class, () -> sort.sort(source(input), keys, new int[]{0}));
        }
        emptyDirectory();
    }

    @Test void tamperedTruncatedAndAppendedFramesCannotBecomeRows() throws Exception {
        for (String mutation : List.of("tamper", "truncate", "append")) {
            try (var sort = new DiffRowSort(limits(1024 * 1024), 1, 1024, () -> {})) {
                var run = sort.sort(source(List.<Object[]>of(new Object[]{"secret"})), keys, new int[]{0});
                byte[] bytes = Files.readAllBytes(run.path());
                if (mutation.equals("tamper")) bytes[bytes.length - 1] ^= 1;
                else if (mutation.equals("truncate")) bytes = Arrays.copyOf(bytes, bytes.length - 1);
                else bytes = Arrays.copyOf(bytes, bytes.length + 1);
                Files.write(run.path(), bytes);
                try (var rows = sort.rows(run)) {
                    assertThrows(IllegalStateException.class, () -> { while (rows.next() != null) { } });
                }
            }
        }
        emptyDirectory();
    }

    @Test void rowCodecPreservesExactTypesPrecisionOffsetsAndDetachedArrays() {
        var timestamp = Timestamp.valueOf("2026-10-03 12:34:56.123456789");
        var time = Time.valueOf("12:34:56"); time.setTime(time.getTime() + 123);
        var array = new JdbcArrayValue("numeric", Types.NUMERIC, Arrays.asList(new BigDecimal("1.0000000001"), null));
        Object[] row = {null, "中文😀", (byte) 1, (short) 2, 3, 4L, new BigInteger("18446744073709551615"),
                new BigDecimal("12345678901234567890123456789.123456789"), 1.25f, 1.5d, true, new byte[]{0, -1},
                UUID.randomUUID(), timestamp, java.sql.Date.valueOf("2026-10-03"), time, LocalDate.now(),
                LocalTime.of(12, 34, 56, 123456789), LocalDateTime.parse("2026-10-03T12:34:56.123456789"),
                OffsetDateTime.parse("2026-10-03T12:34:56.123456789+08:00"), OffsetTime.parse("12:34:56.123456789+08:00"),
                Instant.parse("2026-10-03T12:34:56.123456789Z"), ZonedDateTime.parse("2026-10-03T12:34:56+08:00[Asia/Shanghai]"), array};
        var codec = new DiffRowCodec(row.length, 16384); var copy = codec.decode(codec.encode(row));
        for (int i = 0; i < row.length - 1; i++) {
            if (row[i] instanceof byte[] bytes) assertArrayEquals(bytes, (byte[]) copy[i]);
            else { assertEquals(row[i], copy[i]); if (row[i] != null) assertEquals(row[i].getClass(), copy[i].getClass()); }
        }
        var copiedArray = (JdbcArrayValue) copy[copy.length - 1];
        assertEquals(array.baseTypeName(), copiedArray.baseTypeName()); assertEquals(array.elements(), copiedArray.elements());
    }

    @Test void codecRejectsUnknownObjectsInvalidUnicodeNonFiniteValuesAndTrailingBytes() {
        var codec = new DiffRowCodec(1, 1024);
        for (Object value : List.of(new Object(), "\uD800", Double.NaN, Float.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException.class, () -> codec.encode(new Object[]{value}));
        byte[] bytes = codec.encode(new Object[]{1});
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new Object[]{"x".repeat(1024)}));
    }
}
