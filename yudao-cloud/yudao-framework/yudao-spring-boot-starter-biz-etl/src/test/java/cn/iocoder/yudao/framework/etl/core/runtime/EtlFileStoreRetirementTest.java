package cn.iocoder.yudao.framework.etl.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.framework.etl.core.runtime.EtlFileStore.RetirementPhase.*;

class EtlFileStoreRetirementTest {
    @TempDir Path root;

    @Test void removesOnlySealedManagedPairAfterCommittedIntentAndNeverRecreatesOnRepeat() throws Exception {
        var output = published(new byte[]{1, 2, 3}); var phases = new ArrayList<EtlFileStore.RetirementPhase>();
        EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, phase -> {
            if (phase == INTENT) { assertTrue(Files.exists(output.binding().target())); assertTrue(Files.exists(output.binding().anchor())); }
            phases.add(phase);
        });
        assertEquals(List.of(INTENT, TARGET_REMOVED, COMPLETE), phases);
        assertFalse(Files.exists(output.binding().target())); assertFalse(Files.exists(output.binding().anchor()));
        assertTrue(Files.exists(output.binding().lock())); assertTrue(Files.exists(root.resolve(".etl-storage-id")));
        EtlFileStore.retirePublished(output.binding(), output.fingerprint(), COMPLETE, phase -> assertEquals(COMPLETE, phase));
        assertFalse(Files.exists(output.binding().target()));
    }

    @Test void aMissingPublicationWithoutDurableIntentCannotAuthorizeAnchorRemovalOrCompletion() throws Exception {
        var output = published(new byte[]{1}); Files.delete(output.binding().target());
        assertThrows(EtlFileStore.UnprovenIdentityException.class,
                () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> fail()));
        assertTrue(Files.exists(output.binding().anchor())); Files.delete(output.binding().anchor());
        assertThrows(EtlFileStore.UnprovenIdentityException.class,
                () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> fail()));
    }

    @Test void activeDownloadOrWriterFencePreventsEveryCheckpointAndUnlink() throws Exception {
        var output = published(new byte[]{1});
        try (var download = EtlFileStore.openPublished(output.binding(), output.fingerprint())) {
            assertThrows(IOException.class, () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> fail()));
            download.verifyUnchanged(); assertTrue(Files.exists(output.binding().target())); assertTrue(Files.exists(output.binding().anchor()));
        }
    }

    @Test void missingOperationLockCannotBeRecreatedToAuthorizeCleanup() throws Exception {
        var output = published(new byte[]{1}); Files.delete(output.binding().lock());
        assertThrows(IOException.class, () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> fail()));
        assertFalse(Files.exists(output.binding().lock())); assertTrue(Files.exists(output.binding().target())); assertTrue(Files.exists(output.binding().anchor()));
    }

    @Test void identicalReplacementAndTailTamperingDoNotAuthorizeDeletion() throws Exception {
        var replaced = published(new byte[]{1, 2}); Files.delete(replaced.binding().target()); Files.write(replaced.binding().target(), new byte[]{1, 2});
        assertThrows(IOException.class, () -> EtlFileStore.retirePublished(replaced.binding(), replaced.fingerprint(), NONE, ignored -> fail()));
        assertTrue(Files.exists(replaced.binding().target())); assertTrue(Files.exists(replaced.binding().anchor()));
        var tail = published(new byte[192 * 1024]);
        try (var channel = java.nio.channels.FileChannel.open(tail.binding().target(), StandardOpenOption.WRITE)) {
            channel.position(192 * 1024 - 1); channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> EtlFileStore.retirePublished(tail.binding(), tail.fingerprint(), NONE, ignored -> fail()));
        assertTrue(Files.exists(tail.binding().target())); assertTrue(Files.exists(tail.binding().anchor()));
    }

    @Test void intentTransactionFailureLeavesBothNamesAndReleasesTheFence() throws Exception {
        var output = published(new byte[]{1});
        assertThrows(IllegalStateException.class, () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE,
                phase -> { assertEquals(INTENT, phase); throw new IllegalStateException("receipt unavailable"); }));
        assertTrue(Files.exists(output.binding().target())); assertTrue(Files.exists(output.binding().anchor()));
        EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> { });
        assertFalse(Files.exists(output.binding().target()));
    }

    @Test void lostPostUnlinkReceiptRetriesFromIntentWithoutDeletingAnUnknownName() throws Exception {
        var output = published(new byte[]{1});
        assertThrows(IllegalStateException.class, () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE,
                phase -> { if (phase == TARGET_REMOVED) throw new IllegalStateException("ack lost"); }));
        assertFalse(Files.exists(output.binding().target())); assertFalse(Files.exists(output.binding().anchor()));
        EtlFileStore.retirePublished(output.binding(), output.fingerprint(), INTENT, phase -> assertEquals(COMPLETE, phase));
    }

    @Test void loneOriginalAnchorResumesOnlyWhenNativeIdentityIsAvailable() throws Exception {
        var output = published(new byte[]{1}); Files.delete(output.binding().target());
        Object nativeKey = Files.readAttributes(output.binding().anchor(), BasicFileAttributes.class).fileKey();
        if (nativeKey == null) {
            assertThrows(EtlFileStore.UnprovenIdentityException.class,
                    () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), INTENT, ignored -> fail()));
            assertTrue(Files.exists(output.binding().anchor()));
        } else {
            EtlFileStore.retirePublished(output.binding(), output.fingerprint(), INTENT, ignored -> { });
            assertFalse(Files.exists(output.binding().anchor()));
        }
    }

    @Test void replacementLoneAnchorCannotImpersonateOriginalBytes() throws Exception {
        var output = published(new byte[]{1}); Files.delete(output.binding().target());
        // Keep the old inode alive to prevent a filesystem from legitimately reusing its native key.
        Path witness = output.binding().anchor().resolveSibling("test-kept-original"); Files.createLink(witness, output.binding().anchor());
        Files.delete(output.binding().anchor()); Files.write(output.binding().anchor(), new byte[]{1});
        assertThrows(EtlFileStore.UnprovenIdentityException.class,
                () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), INTENT, ignored -> fail()));
        assertTrue(Files.exists(output.binding().anchor())); assertTrue(Files.exists(witness));
    }

    @Test void extraHardlinksAreRejectedWhereTheProviderExposesCountsAndNeverTraversedOtherwise() throws Exception {
        var output = published(new byte[]{1}); Path external = root.resolve("externally-kept-link"); Files.createLink(external, output.binding().target());
        if (Files.getFileStore(output.binding().target()).supportsFileAttributeView("unix")) {
            assertThrows(EtlFileStore.UnprovenIdentityException.class,
                    () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> fail()));
            assertTrue(Files.exists(output.binding().target())); assertTrue(Files.exists(output.binding().anchor()));
        } else {
            EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> { });
            assertFalse(Files.exists(output.binding().target()));
        }
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(external));
    }

    @Test void stagedNamesAndContradictoryPhasesAreRejectedWithoutDeletingAnything() throws Exception {
        var output = published(new byte[]{1}); Files.write(output.binding().temporary(), new byte[]{9});
        assertThrows(EtlFileStore.UnprovenIdentityException.class,
                () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, ignored -> fail()));
        Files.delete(output.binding().temporary());
        assertThrows(EtlFileStore.UnprovenIdentityException.class,
                () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), TARGET_REMOVED, ignored -> fail()));
        assertTrue(Files.exists(output.binding().target())); assertTrue(Files.exists(output.binding().anchor()));
    }

    @Test void replacementDuringIntentCallbackIsDeniedBeforeTheFirstUnlink() throws Exception {
        var output = published(new byte[]{1});
        assertThrows(IOException.class, () -> EtlFileStore.retirePublished(output.binding(), output.fingerprint(), NONE, phase -> {
            try { Files.delete(output.binding().target()); Files.write(output.binding().target(), new byte[]{1}); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }));
        assertTrue(Files.exists(output.binding().target())); assertTrue(Files.exists(output.binding().anchor()));
    }

    private EtlFileStore.Prepared published(byte[] bytes) throws Exception {
        var binding = EtlFileStore.binding(root, List.of("owned-run"), UUID.randomUUID().toString(), "result-" + UUID.randomUUID() + ".bin");
        try (var ignored = EtlFileStore.acquire(binding)) {
            EtlFileStore.create(binding); Files.write(binding.temporary(), bytes);
            var result = new EtlFileStore.Prepared(binding, EtlFileStore.seal(binding)); EtlFileStore.publish(result); return result;
        }
    }
}
