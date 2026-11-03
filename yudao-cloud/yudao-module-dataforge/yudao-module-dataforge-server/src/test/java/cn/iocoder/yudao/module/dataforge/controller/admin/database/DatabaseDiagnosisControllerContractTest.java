package cn.iocoder.yudao.module.dataforge.controller.admin.database;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通过真实安全代理和应用 JSON 配置验证诊断边界，不连接业务数据库或外部服务。 */
class DatabaseDiagnosisControllerContractTest {

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void queryPermissionCannotAuthorizeWriteChecks() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("diagnosis-contract", "unused", List.of()));
        try (var context = new AnnotationConfigApplicationContext()) {
            var service = mock(DatabaseService.class);
            context.getBeanFactory().registerSingleton("databaseService", service);
            context.register(SecurityConfiguration.class);
            context.refresh();
            var security = context.getBean(SecurityFrameworkService.class);
            var controller = context.getBean(DatabaseController.class);
            var request = new DatabaseDiagnosisReqVO();
            request.setDatabaseId(9007199254740993L);
            when(service.diagnoseDatabase(request)).thenReturn(new DatabaseDiagnosisRespVO());

            assertThrows(AccessDeniedException.class, () -> controller.diagnoseDatabase(request));
            verifyNoInteractions(service);

            when(security.hasPermission("dataforge:database:query")).thenReturn(true);
            assertNotNull(controller.diagnoseDatabase(request).getCheckedData());
            verify(service).diagnoseDatabase(request);
            clearInvocations(service);

            request.setIncludeWriteChecks(true);
            assertThrows(AccessDeniedException.class, () -> controller.diagnoseDatabase(request));
            verifyNoInteractions(service);
            when(security.hasPermission("dataforge:database:update")).thenReturn(true);
            assertNotNull(controller.diagnoseDatabase(request).getCheckedData());
            verify(service).diagnoseDatabase(request);

            clearInvocations(service);
            when(security.hasPermission("dataforge:database:query")).thenReturn(false);
            assertThrows(AccessDeniedException.class, () -> controller.diagnoseDatabase(request));
            verifyNoInteractions(service);
        }
    }

    @Test
    void requestRequiresExactlyOneTargetAndValidatesNestedConnection() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new DatabaseDiagnosisReqVO();
            assertFalse(request.isIncludeWriteChecks());
            assertFalse(validator.validate(request).isEmpty());
            request.setDatabaseId(9007199254740993L);
            assertTrue(validator.validate(request).isEmpty());
            request.setDatabaseId(-1L);
            assertFalse(validator.validate(request).isEmpty());

            var connection = new DatabaseSaveReqVO();
            connection.setDatabaseType(1);
            connection.setDatabaseIp("127.0.0.1");
            connection.setDatabasePort("55473");
            request.setDatabaseId(null);
            request.setConnection(connection);
            assertTrue(validator.validate(request).isEmpty());
            request.setDatabaseId(1L);
            assertFalse(validator.validate(request).isEmpty());
            request.setDatabaseId(null);
            connection.setDatabaseType(null);
            assertFalse(validator.validate(request).isEmpty());
        }
    }

    @Test
    void bootJsonPreservesLargeIdsTimestampsAndGroupedResults() {
        var previous = JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, YudaoJacksonAutoConfiguration.class)).run(context -> {
                assertNull(context.getStartupFailure());
                var mapper = context.getBean(ObjectMapper.class);
                var request = mapper.readValue("{\"databaseId\":\"9007199254740993\"}", DatabaseDiagnosisReqVO.class);
                assertEquals(9007199254740993L, request.getDatabaseId());
                assertFalse(request.isIncludeWriteChecks());
                assertEquals("9007199254740993", mapper.readTree(mapper.writeValueAsBytes(request))
                        .get("databaseId").asString());

                var response = mapper.readValue("""
                        {"diagnosisId":"contract-fixture","overallStatus":"PASS","connectionSuccessful":true,
                         "durationMs":12,"normalCount":1,"warningCount":0,"errorCount":0,"skippedCount":0,"totalCount":1,
                         "groups":[{"groupCode":"BASIC","groupName":"基础连接","description":"真实连接检查",
                           "status":"PASS","normalCount":1,"warningCount":0,"errorCount":0,"skippedCount":0,"totalCount":1,
                           "items":[{"checkCode":"AUTH","checkName":"账号认证","checkContent":"建立连接",
                             "status":"PASS","message":"连接正常","impact":"","durationMs":12}]}]}
                        """, DatabaseDiagnosisRespVO.class);
                response.setCheckedAt(LocalDateTime.of(2026, 10, 15, 1, 0));
                var json = mapper.readTree(mapper.writeValueAsBytes(response));
                assertTrue(json.get("checkedAt").isNumber());
                assertTrue(json.get("checkedAt").asLong() > 1_000_000_000_000L);
                assertTrue(json.get("durationMs").isNumber());
                assertTrue(json.get("connectionSuccessful").asBoolean());
                assertEquals("PASS", json.get("groups").get(0).get("items").get(0).get("status").asString());
                assertEquals("连接正常", json.get("groups").get(0).get("items").get(0).get("message").asString());
                assertNull(json.get("password"));
                assertNull(json.get("jdbcUrl"));
                assertNull(json.get("secretKey"));
                var skipped = new DatabaseDiagnosisRespVO.Item();
                skipped.setStatus("SKIPPED");
                skipped.setReasonCode("NOT_AUTHORIZED");
                var skippedJson = mapper.readTree(mapper.writeValueAsBytes(skipped));
                assertEquals("SKIPPED", skippedJson.get("status").asString());
                assertEquals("NOT_AUTHORIZED", skippedJson.get("reasonCode").asString());
            });
        } finally {
            JsonUtils.init(previous);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfiguration {
        @Bean("ss")
        SecurityFrameworkService security() {
            return mock(SecurityFrameworkService.class);
        }

        @Bean
        DatabaseController controller() {
            return new DatabaseController();
        }
    }
}
