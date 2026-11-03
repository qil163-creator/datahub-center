package cn.iocoder.yudao.framework.etl.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EtlFileStoreDownloadTest {
    @TempDir Path root;

    @Test void streamsExactPublishedBytesWithBoundedChunksAndOneUseWhileHoldingWriterFence() throws Exception {
        byte[] expected = new byte[192 * 1024 + 31];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31);
        var published = published(expected); var output = new ByteArrayOutputStream();
        try (var download = EtlFileStore.openPublished(published.binding(), published.fingerprint())) {
            assertEquals(expected.length, download.bytes());
            assertEquals(published.fingerprint().sha256(), download.sha256());
            assertThrows(IOException.class, () -> EtlFileStore.acquire(published.binding()));
            download.transferTo(new OutputStream() {
                @Override public void write(int value) { fail("The implementation must stream buffered chunks"); }
                @Override public void write(byte[] bytes, int offset, int length) {
                    assertTrue(length <= 64 * 1024); output.write(bytes, offset, length);
                }
            });
            assertArrayEquals(expected, output.toByteArray());
            assertThrows(IOException.class, () -> download.transferTo(output));
        }
        try (var ignored = EtlFileStore.acquire(published.binding())) { assertTrue(Files.exists(published.binding().target())); }
    }

    @Test void hashesTheEntirePayloadBeforeAdmittingATamperedTail() throws Exception {
        byte[] expected = new byte[192 * 1024]; var published = published(expected);
        try (var channel = java.nio.channels.FileChannel.open(published.binding().target(), StandardOpenOption.WRITE)) {
            channel.position(expected.length - 1); channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> EtlFileStore.openPublished(published.binding(), published.fingerprint()));
        assertTrue(Files.exists(published.binding().target()));
        try (var ignored = EtlFileStore.acquire(published.binding())) { /* Failed preflight releases the operation fence. */ }
    }

    @Test void identicalReplacementAndMissingAnchorCannotImpersonateTheSealedObject() throws Exception {
        var published = published(new byte[]{1, 2, 3});
        Files.delete(published.binding().target()); Files.write(published.binding().target(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> EtlFileStore.openPublished(published.binding(), published.fingerprint()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(published.binding().target()));
        Files.delete(published.binding().target()); Files.createLink(published.binding().target(), published.binding().anchor());
        Files.delete(published.binding().anchor());
        assertThrows(IOException.class, () -> EtlFileStore.openPublished(published.binding(), published.fingerprint()));
    }

    @Test void missingTargetOrLockIsDeniedWithoutCreatingAReplacement() throws Exception {
        var published = published(new byte[]{1}); Files.delete(published.binding().target());
        assertThrows(IOException.class, () -> EtlFileStore.openPublished(published.binding(), published.fingerprint()));
        assertFalse(Files.exists(published.binding().target()));
        Files.createLink(published.binding().target(), published.binding().anchor()); Files.delete(published.binding().lock());
        assertThrows(IOException.class, () -> EtlFileStore.openPublished(published.binding(), published.fingerprint()));
        assertFalse(Files.exists(published.binding().lock()));
    }

    @Test void existingBindingDoesNotCreateMissingStorageEvidenceOrNamespaces() throws Exception {
        String operation = UUID.randomUUID().toString();
        assertThrows(IOException.class, () -> EtlFileStore.existingBinding(root, List.of("missing"), operation, "out.txt"));
        assertFalse(Files.exists(root.resolve(".etl-storage-id"))); assertFalse(Files.exists(root.resolve("missing")));
        var binding = EtlFileStore.binding(root, List.of("known"), operation, "out.txt");
        assertEquals(binding, EtlFileStore.existingBinding(root, List.of("known"), operation, "out.txt"));
        assertThrows(IOException.class, () -> EtlFileStore.existingBinding(root, List.of("other"), operation, "out.txt"));
        assertFalse(Files.exists(root.resolve("other")));
    }

    @Test void changedSizeIsDeniedBeforeAnyTransportWriteAndCloseIsIdempotent() throws Exception {
        var published = published(new byte[]{1});
        try (var download = EtlFileStore.openPublished(published.binding(), published.fingerprint())) {
            Files.write(published.binding().target(), new byte[]{2}, StandardOpenOption.APPEND);
            var output = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> download.transferTo(output)); assertEquals(0, output.size());
            download.close(); download.close();
        }
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(published.binding().target()));
    }

    @Test void oversizedPublishedFileIsRejectedBeforeHashingOrAllocatingIt() throws Exception {
        var published = published(new byte[]{1});
        try (var channel = java.nio.channels.FileChannel.open(published.binding().target(), StandardOpenOption.WRITE)) {
            channel.position(EtlFileStore.MAX_BYTES); channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> EtlFileStore.openPublished(published.binding(), published.fingerprint()));
        assertEquals(EtlFileStore.MAX_BYTES + 1, Files.size(published.binding().target()));
    }

    @Test void transportFailureClosesOnlyTheReadHandleAndNeverDeletesThePublishedFile() throws Exception {
        var published = published(new byte[]{1, 2});
        assertThrows(IOException.class, () -> {
            try (var download = EtlFileStore.openPublished(published.binding(), published.fingerprint())) {
                download.transferTo(new OutputStream() {
                    @Override public void write(int value) throws IOException { throw new IOException("client disconnected"); }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw new IOException("client disconnected"); }
                });
            }
        });
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(published.binding().target()));
        try (var ignored = EtlFileStore.acquire(published.binding())) { /* The next request can acquire after a failed transfer. */ }
    }

    private EtlFileStore.Prepared published(byte[] bytes) throws Exception {
        var binding = EtlFileStore.binding(root, List.of("run"), UUID.randomUUID().toString(), "result-" + UUID.randomUUID() + ".bin");
        try (var ignored = EtlFileStore.acquire(binding)) {
            EtlFileStore.create(binding); Files.write(binding.temporary(), bytes);
            var output = new EtlFileStore.Prepared(binding, EtlFileStore.seal(binding)); EtlFileStore.publish(output); return output;
        }
    }
}
