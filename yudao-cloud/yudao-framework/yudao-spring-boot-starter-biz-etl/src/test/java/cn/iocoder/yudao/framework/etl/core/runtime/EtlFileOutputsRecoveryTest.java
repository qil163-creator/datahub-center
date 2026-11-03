package cn.iocoder.yudao.framework.etl.core.runtime;

import cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlFileOutputsRecoveryTest {
    @TempDir Path root;

    @Test void intentPrecedesTemporaryAndPublicationDecisionPrecedesVisibleTarget() throws Exception {
        var port = new RecordingPort(); var output = new EtlFileOutputs(policy(), port);
        Path part = output.stage("n", "etl://exports", "out.csv"); Files.writeString(part, "a\n1\n");
        output.ready("n"); assertFalse(Files.exists(port.binding.target())); output.publish(); output.discard(true);
        assertEquals(List.of("intent", "created", "ready", "approve", "published", "PUBLISHED"), port.events);
        assertEquals("a\n1\n", Files.readString(port.binding.target())); assertFalse(Files.exists(part));
    }

    @Test void failedIntentCreatesNoTemporaryFile() throws Exception {
        var port = new RecordingPort() { @Override public EtlFileStore.Binding reserve(String n, String d, String f) throws IOException {
            super.reserve(n, d, f); throw new IOException("ledger unavailable");
        }};
        var outputs = new EtlFileOutputs(policy(), port);
        assertThrows(IOException.class, () -> outputs.stage("n", "etl://exports", "out.csv"));
        assertFalse(Files.exists(port.binding.temporary())); assertFalse(Files.exists(port.binding.lock()));
    }

    @Test void publishAcknowledgementLossReconcilesWithoutRewritingOrDuplicateTarget() throws Exception {
        var port = new RecordingPort() { @Override public void published(EtlFileStore.Prepared p) { throw new IllegalStateException("lost receipt"); }};
        var outputs = new EtlFileOutputs(policy(), port);
        var part = outputs.stage("n", "etl://exports", "out.csv"); Files.writeString(part, "payload"); outputs.ready("n");
        assertThrows(IllegalStateException.class, outputs::publish); outputs.discard(true);
        var before = EtlFileStore.fingerprint(port.binding, port.binding.target());
        assertEquals("PUBLISHED", EtlFileStore.reconcile(port.binding, port.prepared.fingerprint().fileKey(), port.prepared.fingerprint(), true).state());
        assertEquals("PUBLISHED", EtlFileStore.reconcile(port.binding, port.prepared.fingerprint().fileKey(), port.prepared.fingerprint(), true).state());
        assertEquals(before, EtlFileStore.fingerprint(port.binding, port.binding.target())); assertTrue(Files.exists(port.binding.anchor()));
    }

    @Test void approvedSealedTemporaryPublishesAfterCrash() throws Exception {
        var binding = binding(); var prepared = prepare(binding, "sealed");
        assertEquals("PUBLISHED", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), true).state());
        assertEquals("sealed", Files.readString(binding.target())); assertFalse(Files.exists(binding.temporary()));
    }

    @Test void incompleteGraphNeverPublishesAndOnlyItsKnownTemporaryIsDeleted() throws Exception {
        var binding = binding(); var prepared = prepare(binding, "unapproved"); Path other = root.resolve("unrelated.part"); Files.writeString(other, "keep");
        assertEquals("DISCARDED", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), false).state());
        assertFalse(Files.exists(binding.target())); assertFalse(Files.exists(binding.temporary())); assertFalse(Files.exists(binding.anchor())); assertEquals("keep", Files.readString(other));
    }

    @Test void liveFileLockIndependentlyProtectsWriterEvenAfterPlatformLeaseExpires() throws Exception {
        var binding = binding(); var prepared = prepare(binding, "active");
        try (var ignored = EtlFileStore.acquire(binding)) {
            assertThrows(IOException.class, () -> EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), false));
            assertEquals("active", Files.readString(binding.temporary()));
        }
        assertEquals("DISCARDED", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), false).state());
    }

    @Test void externallyChangedTemporaryAndUnrelatedTargetAreNeverDeleted() throws Exception {
        var binding = binding(); var prepared = prepare(binding, "original"); Files.writeString(binding.temporary(), "tampered");
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), true).state());
        Files.writeString(binding.target(), "someone else's output");
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), true).state());
        assertEquals("someone else's output", Files.readString(binding.target())); assertEquals("tampered", Files.readString(binding.temporary()));
    }

    @Test void identicalBytesWithDifferentFilesystemIdentityCannotImpersonateReceipt() throws Exception {
        var binding = binding(); var prepared = prepare(binding, "same"); Files.writeString(binding.target(), "same");
        assertEquals(Files.readString(binding.temporary()), Files.readString(binding.target()));
        assertFalse(Files.isSameFile(binding.target(), binding.anchor()));
        assertThrows(IOException.class, () -> EtlFileStore.fingerprint(binding, binding.target()));
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), true).state());
        assertTrue(Files.exists(binding.target())); assertTrue(Files.exists(binding.temporary()));
    }

    @Test void missingIdentityOrMissingApprovedPayloadRequiresManualDecision() throws Exception {
        var binding = binding(); prepare(binding, "unknown");
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, null, null, false).state()); assertTrue(Files.exists(binding.temporary()));
        Files.delete(binding.temporary());
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, null, null, true).state());
    }

    @Test void unknownTerminationDoesNotDeleteTemporary() throws Exception {
        var outputs = new EtlFileOutputs(policy()); var part = outputs.stage("n", root.toString(), "legacy.csv");
        Files.writeString(part, "writer still running"); outputs.discard(false);
        assertTrue(Files.exists(part)); assertEquals("UNKNOWN", outputs.states().get("n"));
    }

    @Test void missingOrReplacedAnchorIsNotAcceptedForKnownTemporary() throws Exception {
        var binding = binding(); var prepared = prepare(binding, "original"); Files.delete(binding.anchor());
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), false).state());
        Files.writeString(binding.anchor(), "original");
        assertEquals("MANUAL", EtlFileStore.reconcile(binding, prepared.fingerprint().fileKey(), prepared.fingerprint(), false).state());
        assertTrue(Files.exists(binding.temporary())); assertTrue(Files.exists(binding.anchor()));
    }

    @Test void pathNamespaceAndFileNamesRejectTraversalAndPortableAliases() throws Exception {
        for (String name : List.of("../x", "a/b", "a\\b", "CON", "out.", "out ", ".etl-internal", ".ETL-internal", "nul.txt", "x:y"))
            assertThrows(IllegalArgumentException.class, () -> EtlFileStore.binding(root, List.of("safe"), UUID.randomUUID().toString(), name), name);
        assertThrows(IllegalArgumentException.class, () -> EtlFileStore.binding(root, List.of(".."), UUID.randomUUID().toString(), "x.csv"));
        var first = binding(); var again = EtlFileStore.binding(root, List.of("run"), first.operationKey(), "out.csv"); assertEquals(first, again);
    }

    @Test void approvedPublishFailureRetainsSealedPayloadForReconciliation() throws Exception {
        var port = new RecordingPort() { @Override public void approvePublish(List<EtlFileStore.Prepared> outputs) {
            super.approvePublish(outputs);
            try { Files.writeString(binding.target(), "racing external target"); } catch (IOException error) { throw new IllegalStateException(error); }
        }};
        var outputs = new EtlFileOutputs(policy(), port); var part = outputs.stage("n", "etl://exports", "out.csv");
        Files.writeString(part, "sealed"); outputs.ready("n"); assertThrows(IOException.class, outputs::publish); outputs.discard(true);
        assertEquals("UNKNOWN", outputs.states().get("n")); assertEquals("sealed", Files.readString(part));
        assertEquals("racing external target", Files.readString(port.binding.target()));
    }

    @Test void uncertainDecisionAcknowledgementRetainsBytesAndCannotMasqueradeAsDiscarded() throws Exception {
        var port = new RecordingPort() { @Override public void approvePublish(List<EtlFileStore.Prepared> outputs) {
            super.approvePublish(outputs); throw new IllegalStateException("decision committed but response lost");
        }};
        var outputs = new EtlFileOutputs(policy(), port); var part = outputs.stage("n", "etl://exports", "out.csv");
        Files.writeString(part, "sealed"); outputs.ready("n"); assertThrows(IllegalStateException.class, outputs::publish); outputs.discard(true);
        assertEquals("UNKNOWN", outputs.states().get("n")); assertTrue(Files.exists(part)); assertTrue(Files.exists(port.binding.anchor()));
        assertEquals("PUBLISHED", EtlFileStore.reconcile(port.binding, port.prepared.fingerprint().fileKey(), port.prepared.fingerprint(), true).state());
    }

    @Test void recoveredIntentCannotStartWritingAfterItsFenceWasReleased() throws Exception {
        var port = new RecordingPort() { @Override public void beforeCreate(EtlFileStore.Binding binding) {
            assertThrows(IOException.class, () -> EtlFileStore.acquire(binding));
            assertFalse(Files.exists(binding.temporary())); throw new IllegalStateException("intent already reconciled");
        }};
        var outputs = new EtlFileOutputs(policy(), port);
        assertThrows(IllegalStateException.class, () -> outputs.stage("n", "etl://exports", "out.csv")); outputs.discard(true);
        assertFalse(Files.exists(port.binding.temporary())); assertFalse(Files.exists(port.binding.anchor()));
    }

    private EtlFileOutputPolicy policy() { return new EtlFileOutputPolicy(List.of(root), false); }
    private EtlFileStore.Binding binding() throws IOException { return EtlFileStore.binding(root, List.of("run"), UUID.randomUUID().toString(), "out.csv"); }
    private EtlFileStore.Prepared prepare(EtlFileStore.Binding binding, String value) throws IOException {
        EtlFileStore.create(binding); Files.writeString(binding.temporary(), value); return new EtlFileStore.Prepared(binding, EtlFileStore.seal(binding));
    }
    private class RecordingPort implements EtlFileOutputPort {
        final List<String> events = new ArrayList<>(); EtlFileStore.Binding binding; EtlFileStore.Prepared prepared;
        public EtlFileStore.Binding reserve(String n, String d, String f) throws IOException {
            binding = EtlFileStore.binding(root, List.of("run"), UUID.randomUUID().toString(), f);
            assertFalse(Files.exists(binding.temporary())); events.add("intent"); return binding;
        }
        public void created(EtlFileStore.Binding binding, String key) { assertTrue(Files.exists(binding.temporary())); events.add("created"); }
        public void ready(EtlFileStore.Prepared output) { prepared = output; events.add("ready"); }
        public void approvePublish(List<EtlFileStore.Prepared> outputs) { assertFalse(Files.exists(binding.target())); events.add("approve"); }
        public void published(EtlFileStore.Prepared output) { assertTrue(Files.exists(binding.target())); events.add("published"); }
        public void discarded(EtlFileStore.Binding binding, String state) { events.add(state); }
    }
}
