package cn.iocoder.yudao.framework.dbswitch.common.entity;

import lombok.Data;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.util.ArrayList;
import java.util.List;

/**
 * @ClassName MapperConfig
 * @Author zrx
 * @Date 2024/1/8 17:06
 */
@Data
public class MapperConfig {

    /** Final physical target name; independent of the legacy ALL/SINGLE regex selection. */
    private String targetTableName;

    /** Null inherits the source comment; an empty string explicitly clears it on creation. */
    private String targetTableComment;

    private cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions dorisOptions;

    private List<PatternMapper> regexTableMapper = new ArrayList<>();

    private List<PatternMapper> regexColumnMapper = new ArrayList<>();

    private List<String> sourcePrimaryKeys = new ArrayList<>();

    //时间序列字段，适用于时序数据库
    private String timestampColumn;

    private String increaseColumnName;

    private ColumnDescription increaseColumn;

    //增量字段的开始和结束值
    private String increaseStartVal;

    private String increaseEndVal;

    /**
     * 返回当前COPY。
     */
    public MapperConfig copy() {
        var result = new MapperConfig();
        result.targetTableName = targetTableName;
        result.targetTableComment = targetTableComment;
        result.dorisOptions = dorisOptions;
        result.regexTableMapper = copyPatterns(regexTableMapper);
        result.regexColumnMapper = copyPatterns(regexColumnMapper);
        result.sourcePrimaryKeys = sourcePrimaryKeys == null ? null : List.copyOf(sourcePrimaryKeys);
        result.timestampColumn = timestampColumn;
        result.increaseColumnName = increaseColumnName;
        result.increaseColumn = increaseColumn == null ? null : increaseColumn.copy();
        result.increaseStartVal = increaseStartVal;
        result.increaseEndVal = increaseEndVal;
        return result;
    }

    /**
     * 复制当前匹配模式配置。
     */
    public static List<PatternMapper> copyPatterns(List<PatternMapper> values) {
        return values == null ? null : values.stream()
                .map(value -> new PatternMapper(value.getFromPattern(), value.getToValue())).toList();
    }
}
