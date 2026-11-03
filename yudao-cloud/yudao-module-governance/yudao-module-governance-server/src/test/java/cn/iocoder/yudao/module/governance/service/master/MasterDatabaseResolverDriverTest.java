package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.service.master.ConfiguredDriverTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class MasterDatabaseResolverDriverTest {
    @TempDir Path directory;

    @Test void differentNodePathsDoNotChangeEffectiveConnectionRevision() throws Exception {
        Path first = copyDriver(directory, "node-a.jar");
        Path second = Files.copy(first, directory.resolve("node-b.jar"));
        var a = resolver(first, "provider-1").resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        var b = resolver(second, "provider-1").resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThat(a.getConfigurationRevision()).startsWith("jdbc-v1:").isEqualTo(b.getConfigurationRevision());
        assertThat(JsonUtils.toJsonString(a)).doesNotContain(directory.toString(), "driverBinding", "driverClassName", "fingerprint");
        assertThat(a.toString()).doesNotContain(directory.toString(), "driverBinding");
    }

    @Test void providerAndDriverContentBothParticipateInRevision() throws Exception {
        Path first = copyDriver(directory, "driver.jar");
        String initial = resolver(first, "provider-1").resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS).getConfigurationRevision();
        assertThat(resolver(first, "provider-2").resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS).getConfigurationRevision())
                .isNotEqualTo(initial);
        Files.write(first, new byte[]{0}, StandardOpenOption.APPEND);
        assertThat(resolver(first, "provider-1").resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS).getConfigurationRevision())
                .isNotEqualTo(initial);
    }

    @Test void missingProviderRevisionIsNeverTurnedIntoProof() throws Exception {
        Path jar = copyDriver(directory, "driver.jar");
        for (String revision : new String[]{null, "", " "}) {
            assertThat(resolver(jar, revision).resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS).getConfigurationRevision())
                    .isEqualTo(revision);
        }
    }

    @Test void changedJarAfterResolveFailsBeforeConnectionPoolCreation() throws Exception {
        Path jar = copyDriver(directory, "driver.jar");
        var resolver = resolver(jar, "provider-1");
        var info = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        Files.write(jar, new byte[]{0}, StandardOpenOption.APPEND);
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.createDataSource(info)).withMessageContaining("SHA-256 mismatch");
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS));
    }

    @Test void manualOrCrossResolverConnectionCannotBypassTrustedBinding() throws Exception {
        Path jar = copyDriver(directory, "driver.jar");
        var resolver = resolver(jar, "provider-1");
        var manual = new MasterDatabaseResolver.ConnectionInfo(); manual.setProductType(ProductTypeEnum.POSTGRESQL);
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.createDataSource(manual));
        var foreign = resolver(jar, "provider-1").resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.createDataSource(foreign));
        var changedProduct = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS); changedProduct.setProductType(ProductTypeEnum.MYSQL);
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.createDataSource(changedProduct));
    }
}
