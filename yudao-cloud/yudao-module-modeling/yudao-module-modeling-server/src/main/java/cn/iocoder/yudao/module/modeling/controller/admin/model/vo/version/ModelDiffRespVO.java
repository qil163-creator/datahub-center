package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 模型结构比对 Response VO")
@Data
public class ModelDiffRespVO {

    /** 字段变化类型：新增。 */
    public static final String ADDED = "ADDED";
    /** 字段变化类型：删除。 */
    public static final String REMOVED = "REMOVED";
    /** 字段变化类型：修改。 */
    public static final String MODIFIED = "MODIFIED";
    /** 字段变化类型：未变化。 */
    public static final String UNCHANGED = "UNCHANGED";

    @Schema(description = "比对基准的名称，如 V2", example = "V2")
    private String fromLabel;

    @Schema(description = "比对目标的名称，如 V3 或 当前设计", example = "V3")
    private String toLabel;

    @Schema(description = "表头属性变化")
    private List<AttributeChange> headerChanges = new ArrayList<>();

    @Schema(description = "字段变化，按目标字段顺序排列，删除的字段排在最后")
    private List<FieldChange> fields = new ArrayList<>();

    @Schema(description = "变化汇总")
    private Summary summary = new Summary();

    @Schema(description = "属性变化")
    @Data
    public static class AttributeChange {

        @Schema(description = "属性键", example = "dataType")
        private String key;

        @Schema(description = "属性名称", example = "数据类型")
        private String label;

        @Schema(description = "变化前的值", example = "VARCHAR")
        private String before;

        @Schema(description = "变化后的值", example = "TEXT")
        private String after;
    }

    @Schema(description = "字段变化")
    @Data
    public static class FieldChange {

        @Schema(description = "变化类型：ADDED、REMOVED、MODIFIED、UNCHANGED", example = "MODIFIED")
        private String changeType;

        @Schema(description = "字段稳定标识", example = "3b8f…")
        private String fieldUid;

        @Schema(description = "字段英文名，删除时取变化前的值", example = "order_no")
        private String fieldName;

        @Schema(description = "字段中文名，删除时取变化前的值", example = "订单号")
        private String cnName;

        @Schema(description = "变化前字段，新增时为空")
        private ModelFieldItem before;

        @Schema(description = "变化后字段，删除时为空")
        private ModelFieldItem after;

        @Schema(description = "修改的属性")
        private List<AttributeChange> changes = new ArrayList<>();
    }

    @Schema(description = "变化汇总")
    @Data
    public static class Summary {

        @Schema(description = "新增字段数", example = "1")
        private int added;

        @Schema(description = "删除字段数", example = "0")
        private int removed;

        @Schema(description = "修改字段数", example = "2")
        private int modified;

        @Schema(description = "未变化字段数", example = "15")
        private int unchanged;

        @Schema(description = "表头属性变化数", example = "1")
        private int headerChanged;
    }
}
