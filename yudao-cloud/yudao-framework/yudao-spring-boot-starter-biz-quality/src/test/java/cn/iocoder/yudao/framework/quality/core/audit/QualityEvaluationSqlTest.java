package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.quality.config.YudaoQualityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QualityEvaluationSqlTest {
    @Test void postgresGeneratorConnectionRequiresStandardStringModeOn() throws Exception {
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class);
        var setting = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.when(statement.executeQuery("SHOW standard_conforming_strings")).thenReturn(setting);
        org.mockito.Mockito.when(setting.next()).thenReturn(true, false);
        org.mockito.Mockito.when(setting.getString(1)).thenReturn("on");
        assertDoesNotThrow(() -> AuditSqlDialect.validateConnection(connection, ProductTypeEnum.POSTGRESQL));
        org.mockito.Mockito.when(setting.next()).thenReturn(true, false);
        org.mockito.Mockito.when(setting.getString(1)).thenReturn("off");
        assertThrows(java.sql.SQLException.class, () -> AuditSqlDialect.validateConnection(connection, ProductTypeEnum.POSTGRESQL));
        org.mockito.Mockito.verify(statement, org.mockito.Mockito.times(2)).setQueryTimeout(5);
    }
    @Test void autoConfigurationProvidesMutexAndNormalDataGenerator() {
        try (var application = new AnnotationConfigApplicationContext(YudaoQualityAutoConfiguration.class)) {
            var generator = application.getBean(AuditSqlRegistry.class).getGenerator("MUTUAL_EXCLUSION_VALIDATION");
            var context = context();
            context.setConfig(Map.of("condition", "IS_TRUE", "trueValues", List.of("yes", "O'Reilly")));
            assertTrue(generator.buildCountSql(context).contains("'O''Reilly'"));
            assertTrue(generator.buildNormalDataSql(context, 10).contains("CASE WHEN"));
            context.setConfig(Map.of("condition", "IS_TRUE", "trueValues", List.of("yes")));
            assertThrows(IllegalArgumentException.class, () -> generator.buildCountSql(context));
            context.setConfig(Map.of("condition", "IS_TRUE", "trueValues", List.of("yes", "a\\b")));
            assertThrows(IllegalArgumentException.class, () -> generator.buildCountSql(context));
        }
    }

    @Test void uniqueNormalRowsUseComplementOfTheSameDuplicateRelation() {
        var generator = new AuditSqlRegistry().getGenerator("COMPOSITE_UNIQUENESS_VALIDATION");
        var context = context();
        String errors = generator.buildErrorDataSql(context, 10);
        String normal = generator.buildNormalDataSql(context, 10);
        assertTrue(errors.contains("EXISTS"), errors);
        assertTrue(normal.contains("NOT EXISTS"), normal);
        assertEquals(errors.replace("EXISTS", "NOT EXISTS"), normal);
    }

    static AuditRuleContext context() {
        var context = new AuditRuleContext(DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        context.setTableName("\"sample\"");
        context.setColumns(List.of("\"a\"", "\"b\""));
        context.setColumn("\"a\"");
        context.setConfig(Map.of());
        return context;
    }
}
