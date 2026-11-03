package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 不需要连接数据库的配置门禁；不支持的写模式必须在任务开始前拒绝。 */
class MigrationTargetSqlAuthorizationConfigTest {

    @Test void ordinaryPostgresCreateNewIsAdmitted() {
        assertDoesNotThrow(() -> MigrationService.validateAuthorizedConfiguration(request()));
    }

    @ParameterizedTest @ValueSource(strings = {"unspecified", "STRICT_COPY", "APPEND", "REPLACE", "unknown"})
    void otherWriteModesAreRejected(String mode) {
        var request = request();
        request.getTarget().setWriteMode(mode.equals("unspecified") ? null : mode);
        assertThrows(IllegalArgumentException.class, () -> MigrationService.validateAuthorizedConfiguration(request));
    }

    @ParameterizedTest @ValueSource(strings = {"sourceProduct", "targetProduct", "sqlSource", "sourceBefore", "sourceAfter",
            "targetBefore", "targetAfter", "drop", "truncate", "incremental", "copy", "onlyCreate", "index", "default",
            "autoIncrement", "identity", "exclusiveWrite", "exclusiveDdl", "customDdl", "timestamp"})
    void unsupportedPathsAreRejectedBeforeAnyDatabaseAccess(String option) {
        var request = request();
        var source = request.getSource().getFirst();
        var target = request.getTarget();
        switch (option) {
            case "sourceProduct" -> source.setSourceProductType(ProductTypeEnum.MYSQL);
            case "targetProduct" -> target.setTargetProductType(ProductTypeEnum.MYSQL);
            case "sqlSource" -> source.setSourceSql("SELECT * FROM source_rows");
            case "sourceBefore" -> source.setBeforeSqlScripts("DELETE FROM source_rows");
            case "sourceAfter" -> source.setAfterSqlScripts("DELETE FROM source_rows");
            case "targetBefore" -> target.setBeforeSqlScripts("DELETE FROM target_rows");
            case "targetAfter" -> target.setAfterSqlScripts("DELETE FROM target_rows");
            case "drop" -> target.setTargetDrop(true);
            case "truncate" -> target.setTargetTruncateData(true);
            case "incremental" -> target.setChangeDataSync(true);
            case "copy" -> target.setWriterEngineInsert(false);
            case "onlyCreate" -> target.setOnlyCreate(true);
            case "index" -> target.setIndexCreate(true);
            case "default" -> target.setSyncDefaultVal(true);
            case "autoIncrement" -> target.setCreateTableAutoIncrement(true);
            case "identity" -> target.setPreserveIdentityValues(true);
            case "exclusiveWrite" -> target.setExclusiveTargetOwnership(true);
            case "exclusiveDdl" -> target.setExclusiveTargetDdlOwnership(true);
            case "customDdl" -> target.setCustomDdlMap(Map.of("target_rows", "CREATE TABLE target_rows(id integer)"));
            case "timestamp" -> source.setTimestampColumn("time");
            default -> fail("Unknown fixture");
        }
        assertThrows(IllegalArgumentException.class, () -> MigrationService.validateAuthorizedConfiguration(request));
    }

    private MigrationRequest request() {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        request.getSource().add(source);
        request.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        request.getTarget().setWriteMode("CREATE_NEW");
        return request;
    }
}
