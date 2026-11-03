package cn.iocoder.yudao.framework.etl.core.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ETL 字段元信息（字段契约推导结果）
 *
 * @author Li
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EtlFieldMeta {

    private String name;

    private String dataType;

    /** Null 表示数据源或转换尚未证明此属性。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean nullable;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer precision;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer scale;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String timezone;

    /** 仅表示字符长度，不将数值精度或 JDBC 展示宽度当作长度。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long length;

    /** 可追溯的字段来源展示名；不包含连接字符串或凭据。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sourceName;

    /** 保留既有六参数字段契约构造器。 */
    public EtlFieldMeta(String name, String dataType, Boolean nullable, Integer precision, Integer scale, String timezone) {
        this(name, dataType, nullable, precision, scale, timezone, null, null);
    }

    /**
     * 使用给定参数初始化 {@code EtlFieldMeta}。
     */
    public EtlFieldMeta(String name, String dataType) {
        this(name, dataType, null, null, null, null);
    }

    /**
     * 返回当前COPY。
     */
    public EtlFieldMeta copy() {
        return new EtlFieldMeta(name, dataType, nullable, precision, scale, timezone, length, sourceName);
    }

    /**
     * 根据当前输入构建并返回 {@code EtlFieldMeta}。
     */
    public EtlFieldMeta renamed(String newName) {
        return new EtlFieldMeta(newName, dataType, nullable, precision, scale, timezone, length, sourceName);
    }

}
