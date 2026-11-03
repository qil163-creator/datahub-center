package cn.iocoder.yudao.framework.etl.core.executor.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EtlManagedCryptoTest {
    @TempDir Path root;

    @Test void roundTripsReferenceAndLegacyAlgorithmsWithoutChangingLegacyProtocol() throws Exception {
        String key = "abc123DEF456gh78";
        Files.writeString(root.resolve("key1"), key + "\n");
        for (String algorithm : new String[]{"AES", "SM4", "AES128", "AES256", "DES", "3DES"}) {
            String encrypted = EtlManagedCrypto.transform(false, algorithm, "key1", "河道水情😀", root);
            assertNotEquals("河道水情😀", encrypted);
            assertEquals(EtlCryptoUtils.encrypt(algorithm, key, "河道水情😀"), encrypted);
            assertEquals("河道水情😀", EtlManagedCrypto.transform(true, algorithm, "key1", encrypted, root));
        }
    }

    @Test void rejectsTraversalSymlinksMissingAndOversizedKeysWithoutDisclosure() throws Exception {
        Files.writeString(root.resolve("large"), "x".repeat(4097));
        Files.createSymbolicLink(root.resolve("link"), root.resolve("large"));
        for (String reference : new String[]{"../large", "/large", "link", "missing", "large"}) {
            var failure = assertThrows(RuntimeException.class,
                    () -> EtlManagedCrypto.transform(false, "AES", reference, "value", root));
            assertFalse(failure.getMessage().contains(root.toString()));
            assertFalse(failure.getMessage().contains("x".repeat(32)));
        }
    }

    @Test void rejectsBadCiphertextAndInvalidReferenceKeys() throws Exception {
        Files.writeString(root.resolve("badKey"), "short-sensitive-value");
        var failure = assertThrows(RuntimeException.class,
                () -> EtlManagedCrypto.transform(false, "AES", "badKey", "value", root));
        assertFalse(failure.getMessage().contains("short-sensitive-value"));
        Files.writeString(root.resolve("key1"), "abc123DEF456gh78");
        assertThrows(RuntimeException.class, () -> EtlManagedCrypto.transform(true, "AES", "key1", "invalid-ciphertext", root));
    }
}
