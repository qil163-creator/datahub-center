package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler.Authentication;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationDataxBindingsPropertiesTest {
    @Test void fileBindingsAreDeniedUntilTaskAndConnectorAreExplicitlyAuthorized() {
        var properties = properties();
        assertThrows(IllegalArgumentException.class, () -> properties.require(9L, "read-csv", null, "fixture"));
        assertThrows(IllegalArgumentException.class, () -> properties.require(7L, "write-csv", null, "fixture"));
        assertThrows(IllegalArgumentException.class, () -> properties.require(7L, "read-csv", null, "../fixture"));
        var binding = properties.require(7L, "read-csv", null, "fixture");
        assertEquals("/safe/input", binding.rootPath());
        assertEquals(Authentication.NONE, binding.authentication());
    }

    @Test void networkBindingsRequireExactDatabaseIdAndUniqueAuthorizedMatch() {
        var properties = properties(); var binding = properties.getBindings().get("fixture");
        binding.setDatabaseId(9007199254740993L); binding.setConnectorCodes(Set.of("read-hdfs"));
        assertThrows(IllegalArgumentException.class, () -> properties.require(7L, "read-hdfs", 1L, null));
        assertThrows(IllegalArgumentException.class, () -> properties.require(9L, "read-hdfs", 9007199254740993L, null));
        assertThrows(IllegalArgumentException.class, () -> properties.require(7L, "read-hdfs", null, "fixture"));
        assertEquals(Authentication.NONE, properties.require(7L, "read-hdfs", 9007199254740993L, null).authentication());
        properties.setBindings(Map.of("first", binding, "second", binding));
        assertThrows(IllegalArgumentException.class, () -> properties.require(7L, "read-hdfs", 9007199254740993L, null));
    }

    @Test void authenticationDefaultsNeverTreatMissingSecretsAsAnonymous() {
        var binding = new IntegrationDataxBindingsProperties.ManagedBinding();
        assertEquals(Authentication.REQUIRED, binding.getAuthentication());
        assertTrue(binding.getAllowedTaskIds().isEmpty());
        assertTrue(new IntegrationDataxBindingsProperties().getBindings().isEmpty());
    }

    private static IntegrationDataxBindingsProperties properties() {
        var binding = new IntegrationDataxBindingsProperties.ManagedBinding();
        binding.setAllowedTaskIds(Set.of(7L)); binding.setConnectorCodes(Set.of("read-csv"));
        binding.setAuthentication(Authentication.NONE); binding.setRootPath("/safe/input");
        binding.setInstalledPlugins(Set.of("txtfilereader"));
        var properties = new IntegrationDataxBindingsProperties(); properties.setBindings(Map.of("fixture", binding)); return properties;
    }
}
