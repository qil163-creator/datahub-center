package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseMonitorVO.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMonitorWireContractTest {
    private static final long LARGE_ID = 9007199254740993L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 13, 11, 12, 13, 456_000_000);

    @Test
    void ruleAndAlarmResponsesKeepLargeIdsAndPreciseThresholdsLossless() {
        withBootMapper(mapper -> {
            var rule = new Rule();
            rule.setId(LARGE_ID);
            rule.setDatabaseId(42L);
            rule.setThreshold("999999999999.123456");
            rule.setCreateTime(TIME);
            rule.setUpdateTime(TIME.plusSeconds(1));
            var ruleJson = mapper.readTree(mapper.writeValueAsBytes(CommonResult.success(rule))).get("data");
            assertString(ruleJson, "id", "9007199254740993");
            assertNumber(ruleJson, "databaseId", 42L);
            assertString(ruleJson, "threshold", "999999999999.123456");
            assertTimestamp(ruleJson, "createTime", TIME);
            assertTimestamp(ruleJson, "updateTime", TIME.plusSeconds(1));

            var alarm = new Alarm();
            alarm.setId(LARGE_ID + 2);
            alarm.setDatabaseId(42L);
            alarm.setRuleId(LARGE_ID);
            alarm.setThreshold("999999999999.123456");
            alarm.setCurrentValue("999999999999.123457");
            alarm.setFirstTime(TIME);
            alarm.setLastTime(TIME.plusMinutes(1));
            alarm.setHandledTime(TIME.plusMinutes(2));
            var alarmJson = mapper.readTree(mapper.writeValueAsBytes(CommonResult.success(alarm))).get("data");
            assertString(alarmJson, "id", "9007199254740995");
            assertString(alarmJson, "ruleId", "9007199254740993");
            assertNumber(alarmJson, "databaseId", 42L);
            assertString(alarmJson, "threshold", "999999999999.123456");
            assertString(alarmJson, "currentValue", "999999999999.123457");
            assertTimestamp(alarmJson, "firstTime", TIME);
            assertTimestamp(alarmJson, "lastTime", TIME.plusMinutes(1));
            assertTimestamp(alarmJson, "handledTime", TIME.plusMinutes(2));
        });
    }

    @Test
    void logAndMonitorMeasuresDistinguishExactStringsApproximateNumbersAndUnknowns() {
        withBootMapper(mapper -> {
            var log = new ReadWriteLog();
            log.setId("ACCESS:" + LARGE_ID);
            log.setRecordId(LARGE_ID);
            log.setOutputRows("9007199254740993");
            log.setInputRows("0");
            log.setCreateTime(TIME);
            log.setStartTime(TIME.plusSeconds(1));
            log.setEndTime(TIME.plusSeconds(2));
            log.setDurationMs(1000L);
            var page = mapper.readTree(mapper.writeValueAsBytes(CommonResult.success(
                    new PageResult<>(List.of(log), 1L)))).get("data");
            assertNumber(page, "total", 1L);
            var row = page.get("list").get(0);
            assertString(row, "id", "ACCESS:9007199254740993");
            assertString(row, "recordId", "9007199254740993");
            assertString(row, "outputRows", "9007199254740993");
            assertString(row, "inputRows", "0");
            assertNumber(row, "durationMs", 1000L);
            assertTimestamp(row, "createTime", TIME);
            assertTimestamp(row, "startTime", TIME.plusSeconds(1));
            assertTimestamp(row, "endTime", TIME.plusSeconds(2));

            var overview = new Overview();
            overview.setTodayOutputRows("9007199254740993");
            overview.setTodayInputRows("7");
            overview.setTotalTransferRows("9007199254741000");
            overview.setCapacityUsed("9007199254740995");
            overview.setPendingAlarmCount(2L);
            overview.setResponseTimeMs(125L);
            overview.setLastCollectedTime(TIME);
            overview.setReadSpeed(12.5);
            var value = mapper.readTree(mapper.writeValueAsBytes(overview));
            assertString(value, "todayOutputRows", "9007199254740993");
            assertString(value, "todayInputRows", "7");
            assertString(value, "totalTransferRows", "9007199254741000");
            assertString(value, "capacityUsed", "9007199254740995");
            assertString(value, "throughputUnit", "rows/s");
            assertNumber(value, "pendingAlarmCount", 2L);
            assertNumber(value, "responseTimeMs", 125L);
            assertTrue(value.get("readSpeed").isNumber());
            assertEquals(12.5, value.get("readSpeed").doubleValue());
            assertTrue(value.get("writeSpeed").isNull());
            assertTrue(value.get("capacityTotal").isNull());
            assertTimestamp(value, "lastCollectedTime", TIME);

            var point = new TrendPoint();
            point.setDate("2026-10-13");
            point.setOutputRows("9007199254740993");
            point.setInputRows("0");
            var trend = new Trend();
            trend.setDays(7);
            trend.setPoints(List.of(point));
            var pointJson = mapper.readTree(mapper.writeValueAsBytes(trend)).get("points").get(0);
            assertString(pointJson, "outputRows", "9007199254740993");
            assertString(pointJson, "inputRows", "0");
            assertTrue(pointJson.get("responseTimeMs").isNull());
            assertTrue(pointJson.get("onlineRate").isNull());
        });
    }

    @Test
    void requestsAcceptBothLongRepresentationsWithoutChangingDecimalTextOrTime() {
        withBootMapper(mapper -> {
            var save = mapper.readValue("""
                    {"id":"9007199254740993","databaseId":42,"threshold":"999999999999.123456"}
                    """, RuleSave.class);
            assertEquals(LARGE_ID, save.getId());
            assertEquals(42L, save.getDatabaseId());
            assertEquals("999999999999.123456", save.getThreshold());
            var handle = mapper.readValue("""
                    {"databaseId":"9007199254740993","id":42,"action":"HANDLE","remark":"核实恢复"}
                    """, AlarmHandle.class);
            assertEquals(LARGE_ID, handle.getDatabaseId());
            assertEquals(42L, handle.getId());

            var rule = new Rule();
            rule.setId(LARGE_ID);
            rule.setCreateTime(TIME);
            var restored = mapper.readValue(mapper.writeValueAsBytes(rule), Rule.class);
            assertEquals(LARGE_ID, restored.getId());
            assertEquals(TIME, restored.getCreateTime());
        });
    }

    private static void assertString(JsonNode node, String field, String expected) {
        assertTrue(node.get(field).isString(), field);
        assertEquals(expected, node.get(field).asString(), field);
    }

    private static void assertNumber(JsonNode node, String field, long expected) {
        assertTrue(node.get(field).isNumber(), field);
        assertEquals(expected, node.get(field).longValue(), field);
    }

    private static void assertTimestamp(JsonNode node, String field, LocalDateTime expected) {
        assertNumber(node, field, expected.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    private static void withBootMapper(Consumer<ObjectMapper> assertion) {
        var previous = JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, YudaoJacksonAutoConfiguration.class)).run(context -> {
                assertNull(context.getStartupFailure());
                assertion.accept(context.getBean(ObjectMapper.class));
            });
        } finally {
            JsonUtils.init(previous);
        }
    }
}
