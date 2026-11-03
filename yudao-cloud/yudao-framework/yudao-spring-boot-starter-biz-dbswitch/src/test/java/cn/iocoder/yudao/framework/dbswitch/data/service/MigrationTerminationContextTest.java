package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 新增结果字段不能破坏 Boot 4 装配或 Jackson 2/3 两个已存在的边界。 */
class MigrationTerminationContextTest {
    @Test
    void transferSummaryKeepsTheSameMeaningAcrossJacksonVersions() throws Exception {
        var table = new cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult();
        table.setTransferSummary(new cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary(
                cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary.Operation.FULL_SCAN_TABLE,
                9007199254740993L, 9007199254740993L, 3, 2, 1, false, true, false));
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(jackson3.readValue(jackson2.writeValueAsBytes(table), table.getClass()).getTransferSummary())
                .isEqualTo(table.getTransferSummary());
        assertThat(jackson2.readValue(jackson3.writeValueAsBytes(table), table.getClass()).getTransferSummary())
                .isEqualTo(table.getTransferSummary());
        assertThat(jackson2.readValue("{}", table.getClass()).getTransferSummary()).isNull();
        assertThat(jackson3.readValue("{}", table.getClass()).getTransferSummary()).isNull();
    }

    @Test
    void starterStillStartsWithoutOpeningTaskDatabases() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DbSwitchProperties.class);
                    assertThat(context).doesNotHaveBean(MigrationService.class);
                });
    }

    @Test
    void resultTerminationHasTheSameWireMeaningAcrossJacksonVersions() throws Exception {
        var result = new DbSwitchResult();
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(jackson2.readTree(jackson2.writeValueAsBytes(result)).get("termination").asText())
                .isEqualTo("UNCONFIRMED");
        assertThat(jackson3.readTree(jackson3.writeValueAsBytes(result)).get("termination").asString())
                .isEqualTo("UNCONFIRMED");
        result.setTermination(MigrationTermination.CONFIRMED);
        assertThat(jackson2.readTree(jackson2.writeValueAsBytes(result)).get("termination").asText())
                .isEqualTo("CONFIRMED");
        assertThat(jackson3.readTree(jackson3.writeValueAsBytes(result)).get("termination").asString())
                .isEqualTo("CONFIRMED");
    }
}
