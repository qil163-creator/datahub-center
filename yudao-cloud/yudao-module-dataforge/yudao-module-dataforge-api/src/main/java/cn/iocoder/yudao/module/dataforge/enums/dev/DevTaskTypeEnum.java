package cn.iocoder.yudao.module.dataforge.enums.dev;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/** 开发任务类型。保留既有传输值，同时明确引擎与程序形态。 */
@Getter
@RequiredArgsConstructor
public enum DevTaskTypeEnum {
    SQL(null, null),
    PROCEDURE(null, null),
    SHELL(null, null),
    FLINK_SQL("FLINK", "SQL"),
    FLINK_JAR("FLINK", "JAR"),
    PYFLINK("FLINK", "PYTHON"),
    SPARK_SQL("SPARK", "SQL"),
    SPARK_JAR("SPARK", "JAR"),
    PYSPARK("SPARK", "PYTHON");

    private final String engine;

    private final String programType;

    /**
     * 判断是否满足引擎任务条件。
     *
     * @return 判断结果
     */
    public boolean isEngineTask() {
        return engine != null;
    }

    /**
     * 判断是否满足历史SQL条件。
     *
     * @return 判断结果
     */
    public boolean isLegacySql() {
        return this == FLINK_SQL || this == SPARK_SQL;
    }

    /**
     * 从输入构造类型。
     *
     * @param type 类型标识
     * @return 开发任务类型
     */
    public static DevTaskTypeEnum fromType(String type) {
        return Arrays.stream(values()).filter(value -> value.name().equals(type)).findFirst().orElse(null);
    }
}
