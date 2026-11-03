package cn.iocoder.yudao.framework.etl.core.runtime;

import cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedExcelWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EtlExcelTemporaryResourceTest {
    @TempDir Path root;

    @Test void realStreamingWorkbookUsesTwoRegisteredObjectsAndCleansBeforeReady() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 1024 * 1024);
        outputs.stage("n", root.toString(), "out.xlsx");
        try {
            try (var workbook = new EtlManagedExcelWorkbook(outputs, "n")) {
                var sheet = workbook.createSheet("records");
                for (int i = 0; i < 1500; i++) sheet.createRow(i).createCell(0).setCellValue("中文-" + i);
                Path sheetFile = EtlFileStore.temporaryPath(port.binding, "excel-sheet-1");
                assertTrue(Files.size(sheetFile) > 0); assertEquals(port.binding.target().getParent(), sheetFile.getParent());
                assertEquals("CREATED", port.resources.get("excel-sheet-1").state());
                try (var stream = outputs.open("n")) { workbook.write(stream); }
                assertEquals(Set.of("excel-sheet-1", "excel-template-1"), port.resources.keySet());
                assertThrows(IllegalStateException.class, () -> outputs.ready("n"));
            }
            assertTrue(port.resources.values().stream().allMatch(r -> r.state().equals("CLEANED")));
            for (var name : port.resources.keySet()) { assertFalse(Files.exists(EtlFileStore.temporaryPath(port.binding, name))); assertFalse(Files.exists(EtlFileStore.temporaryAnchor(port.binding, name))); }
            outputs.ready("n"); outputs.publish();
            try (var in = Files.newInputStream(port.binding.target()); var workbook = new XSSFWorkbook(in)) {
                assertEquals(1500, workbook.getSheetAt(0).getPhysicalNumberOfRows());
                assertEquals("中文-1499", workbook.getSheetAt(0).getRow(1499).getCell(0).getStringCellValue());
            }
        } finally { outputs.discard(true); }
    }

    @Test void actualEncodedBytesAreSharedAcrossScratchSlotsButOutputHasSeparateBudget() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 6); outputs.stage("n", root.toString(), "out.xlsx");
        try {
            try (var first = outputs.temporary("n", "excel-sheet-1")) {
                try (var stream = first.open()) { stream.write("中".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            }
            try (var second = outputs.temporary("n", "excel-template-1")) {
                try (var stream = second.open()) { stream.write(new byte[3]); assertThrows(IOException.class, () -> stream.write(1)); }
            }
            assertThrows(IllegalStateException.class, () -> outputs.ready("n"));
        } finally { outputs.discard(true); }
        assertEquals("DISCARDED", outputs.states().get("n"));
    }

    @Test void successfulScratchBudgetDoesNotConsumeFinalOutputAllowance() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 6); outputs.stage("n", root.toString(), "out.xlsx");
        try {
            try (var resource = outputs.temporary("n", "excel-sheet-1")) { try (var stream = resource.open()) { stream.write(new byte[6]); } }
            try (var stream = outputs.open("n")) { stream.write(new byte[6]); }
            outputs.ready("n"); outputs.publish(); assertEquals(6, Files.size(port.binding.target()));
        } finally { outputs.discard(true); }
    }

    @Test void realWorksheetOverflowCannotBecomeReadyAndClosesBothPayloadLinks() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 128); outputs.stage("n", root.toString(), "out.xlsx");
        try {
            assertThrows(Exception.class, () -> {
                try (var workbook = new EtlManagedExcelWorkbook(outputs, "n")) {
                    var sheet = workbook.createSheet("records");
                    for (int i = 0; i < 1500; i++) sheet.createRow(i).createCell(0).setCellValue("中文".repeat(50));
                }
            });
            assertThrows(IllegalStateException.class, () -> outputs.ready("n"));
            assertFalse(Files.exists(EtlFileStore.temporaryPath(port.binding, "excel-sheet-1")));
            assertFalse(Files.exists(EtlFileStore.temporaryAnchor(port.binding, "excel-sheet-1")));
        } finally { outputs.discard(true); }
    }

    @Test void failedIntentCreatesNoTemporaryObjectAndReceiptFailureCannotBecomeReady() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 100); outputs.stage("n", root.toString(), "out.xlsx");
        port.failIntent = true;
        assertThrows(IllegalStateException.class, () -> outputs.temporary("n", "excel-sheet-1"));
        assertFalse(Files.exists(EtlFileStore.temporaryPath(port.binding, "excel-sheet-1")));
        assertThrows(IllegalStateException.class, () -> outputs.ready("n")); outputs.discard(true);
    }

    @Test void realTemplateZipIsBoundedBeforeAnyFinalOutputCanBePublished() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 1024); outputs.stage("n", root.toString(), "out.xlsx");
        try {
            assertThrows(Exception.class, () -> {
                try (var workbook = new EtlManagedExcelWorkbook(outputs, "n")) {
                    workbook.createSheet("records").createRow(0).createCell(0).setCellValue("value");
                    try (var stream = outputs.open("n")) { workbook.write(stream); }
                }
            });
            assertTrue(port.resources.containsKey("excel-template-1"));
            assertTrue(port.resources.values().stream().allMatch(r -> r.state().equals("CLEANED")));
            assertThrows(IllegalStateException.class, () -> outputs.ready("n")); assertFalse(Files.exists(port.binding.target()));
        } finally { outputs.discard(true); }
    }

    @Test void lostCleanupAcknowledgementBlocksReadyThenReconcilesAbsentPair() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 100); outputs.stage("n", root.toString(), "out.xlsx");
        var resource = outputs.temporary("n", "excel-sheet-1"); try (var stream = resource.open()) { stream.write(1); }
        port.failClean = true; assertThrows(IOException.class, resource::close);
        assertFalse(Files.exists(EtlFileStore.temporaryPath(port.binding, "excel-sheet-1")));
        assertFalse(Files.exists(EtlFileStore.temporaryAnchor(port.binding, "excel-sheet-1")));
        assertThrows(IllegalStateException.class, () -> outputs.ready("n")); outputs.discard(true);
        assertEquals("CLEANUP_PENDING", outputs.states().get("n"));
        port.failClean = false;
        assertEquals("DISCARDED", reconcile(port).state()); assertEquals("CLEANED", port.resources.get("excel-sheet-1").state());
    }

    @Test void replacedObjectIsNotDeletedByPoiCloseDisposeOrRecovery() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 1024 * 1024); outputs.stage("n", root.toString(), "out.xlsx");
        var workbook = new EtlManagedExcelWorkbook(outputs, "n"); workbook.createSheet("records").createRow(0).createCell(0).setCellValue("original");
        try (var stream = outputs.open("n")) { workbook.write(stream); }
        Path sheet = EtlFileStore.temporaryPath(port.binding, "excel-sheet-1"); Files.delete(sheet); Files.writeString(sheet, "replacement");
        assertThrows(IOException.class, workbook::close); assertFalse(workbook.dispose());
        assertEquals("replacement", Files.readString(sheet)); outputs.discard(true);
        assertEquals("MANUAL", reconcile(port).state()); assertEquals("replacement", Files.readString(sheet));
        assertTrue(Files.exists(port.binding.temporary())); assertTrue(Files.exists(EtlFileStore.temporaryAnchor(port.binding, "excel-sheet-1")));
    }

    @Test void missingAnchorAndUnknownCreationReceiptAreNeverBlindlyCleaned() throws Exception {
        var binding = binding(); String key = EtlFileStore.create(binding);
        String tempKey = EtlFileStore.createTemporary(binding, "excel-sheet-1"); Files.delete(EtlFileStore.temporaryAnchor(binding, "excel-sheet-1"));
        var resource = new EtlFileStore.TemporaryResource("excel-sheet-1", "CREATED", tempKey);
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, key, null, false, List.of(resource), ignored -> fail()).state());
        assertTrue(Files.exists(EtlFileStore.temporaryPath(binding, "excel-sheet-1")));
        var other = EtlFileStore.createTemporary(binding, "excel-template-1"); assertNotNull(other);
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, key, null, false,
                List.of(new EtlFileStore.TemporaryResource("excel-template-1", "INTENT", null)), ignored -> fail()).state());
        assertTrue(Files.exists(EtlFileStore.temporaryPath(binding, "excel-template-1")));
    }

    @Test void activeOsLockAndUnknownTerminationKeepKnownScratchBytes() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 100); outputs.stage("n", root.toString(), "out.xlsx");
        var resource = outputs.temporary("n", "excel-sheet-1"); try (var stream = resource.open()) { stream.write(1); }
        assertThrows(IOException.class, () -> reconcile(port));
        outputs.discard(false); assertEquals("UNKNOWN", outputs.states().get("n"));
        assertThrows(IOException.class, () -> reconcile(port)); assertTrue(Files.exists(resource.registeredPath()));
        // Only this test worker is now explicitly confirmed stopped; do not leak its process fence into TempDir cleanup.
        outputs.discard(true);
    }

    @Test void resourceNamesAndMaximumManifestSizeCannotEscapeNamespace() throws Exception {
        var port = new Ledger(); var outputs = outputs(port, 100); outputs.stage("n", root.toString(), "out.xlsx");
        try {
            assertThrows(IllegalArgumentException.class, () -> outputs.temporary("n", "../foreign"));
            for (int i = 1; i <= 4; i++) { try (var ignored = outputs.temporary("n", "slot-" + i)) { } }
            assertThrows(IllegalStateException.class, () -> outputs.temporary("n", "slot-5"));
            assertThrows(IllegalStateException.class, () -> outputs.temporary("n", "slot-1"));
        } finally { outputs.discard(true); }
    }

    @Test void abruptlyHaltedWriterReleasesOsFenceAndOnlyKnownRegisteredResourcesAreRecovered() throws Exception {
        var binding = binding(); Path manifest = root.resolve("child-receipt.txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(java, "-cp", classpath, CrashWriter.class.getName(), binding.target().getParent().toString(),
                binding.operationKey(), binding.storageIdentity(), manifest.toString()).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "fixture child did not exit"); assertEquals(0, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        var receipts = Files.readAllLines(manifest); assertEquals(2, receipts.size());
        Path foreign = binding.target().getParent().resolve("poi-sxssf-unregistered.xml"); Files.writeString(foreign, "do not discover or delete");
        var resource = new EtlFileStore.TemporaryResource("excel-sheet-1", "CREATED", receipts.get(1));
        var cleaned = new ArrayList<EtlFileStore.TemporaryResource>();
        assertEquals("DISCARDED", EtlFileStore.reconcile(binding, receipts.getFirst(), null, false, List.of(resource), cleaned::add).state());
        assertEquals("CLEANED", cleaned.getFirst().state()); assertFalse(Files.exists(EtlFileStore.temporaryPath(binding, resource.name())));
        assertFalse(Files.exists(EtlFileStore.temporaryAnchor(binding, resource.name()))); assertEquals("do not discover or delete", Files.readString(foreign));
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            Path dir = Path.of(args[0]); String op = args[1];
            var binding = new EtlFileStore.Binding(op, args[2], dir.resolve("out.xlsx"), dir.resolve(".etl-" + op + ".part"), dir.resolve(".etl-" + op + ".lock"));
            EtlFileStore.acquire(binding); String main = EtlFileStore.create(binding);
            String scratch = EtlFileStore.createTemporary(binding, "excel-sheet-1"); Files.writeString(EtlFileStore.temporaryPath(binding, "excel-sheet-1"), "orphan XML bytes");
            Files.write(Path.of(args[3]), List.of(main, scratch)); Runtime.getRuntime().halt(0);
        }
    }

    private EtlFileOutputs outputs(Ledger port, long bytes) { return new EtlFileOutputs(new EtlFileOutputPolicy(List.of(root), false), port, bytes); }
    private EtlFileStore.Binding binding() throws Exception { return EtlFileStore.binding(root, List.of("tenant-7", "owner-99"), UUID.randomUUID().toString(), "out.xlsx"); }
    private EtlFileStore.Reconciled reconcile(Ledger port) throws IOException {
        return EtlFileStore.reconcile(port.binding, port.key, null, false, List.copyOf(port.resources.values()), r -> port.temporaryCleaned(port.binding, r.name(), r.fileKey()));
    }
    private final class Ledger implements EtlFileOutputPort {
        EtlFileStore.Binding binding; String key; boolean failIntent, failClean;
        final Map<String, EtlFileStore.TemporaryResource> resources = new LinkedHashMap<>();
        @Override public EtlFileStore.Binding reserve(String node, String directory, String name) throws IOException {
            binding = EtlFileStore.binding(root, List.of("tenant-7", "owner-99"), UUID.randomUUID().toString(), name); return binding;
        }
        @Override public void created(EtlFileStore.Binding binding, String key) { this.key = key; }
        @Override public void temporaryIntent(EtlFileStore.Binding binding, String name) {
            if (failIntent) throw new IllegalStateException("intent unavailable");
            resources.put(name, new EtlFileStore.TemporaryResource(name, "INTENT", null));
        }
        @Override public void temporaryCreated(EtlFileStore.Binding binding, String name, String key) { resources.put(name, new EtlFileStore.TemporaryResource(name, "CREATED", key)); }
        @Override public void temporaryCleaned(EtlFileStore.Binding binding, String name, String key) {
            if (failClean) throw new IllegalStateException("cleanup acknowledgement unavailable");
            resources.put(name, new EtlFileStore.TemporaryResource(name, "CLEANED", key));
        }
        @Override public void ready(EtlFileStore.Prepared prepared) { assertTrue(resources.values().stream().allMatch(r -> r.state().equals("CLEANED"))); }
        @Override public void approvePublish(List<EtlFileStore.Prepared> outputs) { }
        @Override public void published(EtlFileStore.Prepared output) { }
        @Override public void discarded(EtlFileStore.Binding binding, String state) { }
    }
}
