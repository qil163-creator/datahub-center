package cn.iocoder.yudao.framework.dbswitch.config;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DriverProfilesTest {
    @Test
    void namedProfilesAreImmutableAndSelectionNeverFallsBackAcrossProducts() throws Exception {
        var configuration = profile(false);
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(), Map.of("pg-pinned", configuration))) {
            var pinned = drivers.find(ProductTypeEnum.POSTGRESQL, "pg-pinned").orElseThrow();
            configuration.setProduct(ProductTypeEnum.MYSQL);
            configuration.setPath("caller-mutated");
            assertEquals("pg-pinned", pinned.profileId());
            assertEquals(ProductTypeEnum.POSTGRESQL, pinned.product());
            assertTrue(drivers.find(ProductTypeEnum.POSTGRESQL).isEmpty());
            assertEquals(1, drivers.profiles(ProductTypeEnum.POSTGRESQL).size());
            assertThrows(IllegalArgumentException.class, () -> drivers.find(ProductTypeEnum.MYSQL, "pg-pinned"));
            assertThrows(IllegalArgumentException.class, () -> drivers.find(ProductTypeEnum.POSTGRESQL, "missing"));
            assertThrows(UnsupportedOperationException.class, () -> drivers.profiles(ProductTypeEnum.POSTGRESQL).clear());
        }
    }

    @Test
    void defaultsMustBeUnambiguousAndProfileIdsCannotBePaths() throws Exception {
        var profile = profile(true);
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(), Map.of("pg-default", profile))) {
            assertEquals("pg-default", drivers.find(ProductTypeEnum.POSTGRESQL).orElseThrow().profileId());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredJdbcDrivers(Map.of(), Map.of("pg-one", profile, "pg-two", profile)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredJdbcDrivers(Map.of(), Map.of("../untrusted.jar", profile)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, profile), Map.of("default-postgresql", profile)));
    }

    private static DbSwitchProperties.DriverProfile profile(boolean defaultForProduct) throws Exception {
        Path artifact = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var profile = new DbSwitchProperties.DriverProfile();
        profile.setProduct(ProductTypeEnum.POSTGRESQL);
        profile.setPath(artifact.toAbsolutePath().toString());
        profile.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        profile.setDefaultForProduct(defaultForProduct);
        return profile;
    }
}
