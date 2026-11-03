package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QualityReadEvidenceTest {
    private final LocalDateTime at = LocalDateTime.of(2026, 9, 23, 10, 0);

    @Test void evidenceJsonRoundTripPreservesSubMillisecondReadTimes() {
        var start = at.withNano(123456789);
        String json = JsonUtils.toJsonString(QualityReadEvidence.capture(connection(), object("THIRD_PARTY"),
                "SELECT 1", true, start, start.plusNanos(1)));
        var restored = JsonUtils.parseObject(json, QualityReadEvidence.class);
        assertThat(restored.startedTime()).isEqualTo(start);
        assertThat(restored.finishedTime()).isEqualTo(start.plusNanos(1));
        assertThat(json).contains("2026-09-23T10:00:00.123456789");
    }

    @Test void captureDoesNotPersistCredentialsOrRawSql() {
        var connection = connection();
        connection.setJdbcUrl("synthetic-sensitive-url"); connection.setUsername("synthetic-sensitive-user");
        connection.setPassword("synthetic-sensitive-password");
        String json = JsonUtils.toJsonString(QualityReadEvidence.capture(connection, object("THIRD_PARTY"),
                "SELECT private_field FROM private_table", true, at, at.plusSeconds(1)));
        assertThat(json).doesNotContain("synthetic-sensitive", "private_field", "private_table");
        assertThat(JsonUtils.parseObject(json, QualityReadEvidence.class).getReadMode()).isEqualTo("SOURCE_DIRECT");
    }

    @Test void declaredThirdPartyCannotOverridePlatformOrUnknownUsage() {
        var connection = connection();
        connection.setUsageType(2);
        assertThat(QualityReadEvidence.capture(connection, object("THIRD_PARTY"), "SELECT 1", true, at, at)
                .getReadMode()).isEqualTo("UNPROVEN_REPLICA");
        connection.setUsageType(null);
        assertThat(QualityReadEvidence.capture(connection, object("THIRD_PARTY"), "SELECT 1", true, at, at)
                .getReadMode()).isEqualTo("UNPROVEN_REPLICA");
    }

    @Test void replicaAndCustomSqlDoNotClaimFullSourceCoverage() {
        var read = QualityReadEvidence.capture(connection(), object("ODS"), "SELECT 1", false, at, at);
        assertThat(read.getReadMode()).isEqualTo("UNPROVEN_REPLICA");
        assertThat(read.getCoverage()).isEqualTo("CUSTOM_UNPROVEN");
    }

    @Test void resolverPreservesProviderRevisionWithoutTimestampPrecisionLoss() {
        var api = mock(DatabaseApi.class);
        var resolver = new MasterDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        var dto = new DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("POSTGRESQL"); dto.setUsageType(1);
        dto.setConfigurationRevision("2026-09-23T10:00:00.123456");
        when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        var connection = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThat(connection.getConfigurationRevision()).isEqualTo("2026-09-23T10:00:00.123456");
        assertThat(connection.getUsageType()).isEqualTo(1);
        dto.setConfigurationRevision(null);
        assertThat(resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS).getConfigurationRevision()).isNull();
    }

    private MasterDatabaseResolver.ConnectionInfo connection() {
        var connection = new MasterDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(4L); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema("public"); connection.setUsageType(1); connection.setConfigurationRevision("revision");
        return connection;
    }

    private QualityCheckObjectDO object(String type) {
        return QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType(type).tableName("orders").build();
    }
}
