package cn.iocoder.yudao.framework.dbswitch.dbchange;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DiffSortAutoConfigurationTest {
    @TempDir Path directory;
    @Test void deploymentLimitsReachTheActualRuntimeAndJsonContract() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.migration.diff-sort-memory-bytes=8192",
                        "yudao.dbswitch.migration.diff-sort-max-spill-bytes=65536",
                        "yudao.dbswitch.migration.diff-sort-max-shared-spill-bytes=131072",
                        "yudao.dbswitch.migration.diff-sort-directory=" + directory)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var options = context.getBean(DbSwitchRuntime.class).options();
                    assertEquals(new DiffSortLimits(8192, 65536, directory.toString(), 131072), options.diffSort());
                    var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(options);
                    assertEquals(8192, json.path("diffSort").path("memoryBytes").asLong());
                    assertEquals(65536, json.path("diffSort").path("maxSpillBytes").asLong());
                    assertEquals(131072, json.path("diffSort").path("maxSharedSpillBytes").asLong());
                });
    }
    @Test void invalidSortBudgetFailsStartup() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.migration.diff-sort-memory-bytes=0")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
