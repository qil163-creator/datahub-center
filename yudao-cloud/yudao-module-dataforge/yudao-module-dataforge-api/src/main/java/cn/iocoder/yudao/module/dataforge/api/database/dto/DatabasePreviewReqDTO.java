package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "RPC 服务 - 数据表只读预览请求")
@Data
public class DatabasePreviewReqDTO {

    @NotNull(message = "数据源编号不能为空")
    private Long databaseId;

    private String schemaName;

    @NotBlank(message = "表名不能为空")
    private String tableName;

    @Min(value = 1, message = "预览行数不能小于 1")
    @Max(value = 100, message = "预览行数不能大于 100")
    private Integer limit = 20;

    /**
     * 过滤条件（全部 AND 连接）。字段名会校验必须属于该表，值以参数绑定方式传入，
     * 只允许白名单操作符，杜绝拼接任意 SQL。
     */
    @Valid
    @Size(max = 10, message = "过滤条件最多 10 条")
    private List<Filter> filters;

    /**
     * 预览列白名单。空或未传表示 SELECT *；非空时只投影这些列，未知列拒绝。
     */
    @Schema(description = "预览列白名单，空表示全部列")
    @Valid
    @Size(max = 200, message = "预览列最多 200 个")
    private List<@NotBlank @Size(max = 200) String> columns;

    @Schema(description = "预览过滤条件")
    @Data
    public static class Filter {

        /** 等于 */
        public static final String OP_EQ = "eq";

        /** 不等于 */
        public static final String OP_NE = "ne";

        /** 大于 */
        public static final String OP_GT = "gt";

        /** 大于等于 */
        public static final String OP_GE = "ge";

        /** 小于 */
        public static final String OP_LT = "lt";

        /** 小于等于 */
        public static final String OP_LE = "le";

        /** 模糊包含 */
        public static final String OP_LIKE = "like";

        /** 为空 */
        public static final String OP_NULL = "isNull";

        /** 不为空 */
        public static final String OP_NOT_NULL = "notNull";

        @NotBlank(message = "过滤字段不能为空")
        @Size(max = 200, message = "过滤字段名过长")
        private String column;

        @NotBlank(message = "操作符不能为空")
        private String operator;

        @Size(max = 500, message = "过滤值过长")
        private String value;

    }

}
