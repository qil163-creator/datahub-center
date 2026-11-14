package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 逻辑模型版本快照 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LogicModelVersionDetailRespVO extends LogicModelVersionRespVO {

    @Schema(description = "发布时的模型表头快照")
    private Map<String, Object> modelSnapshot;

    @Schema(description = "发布时的字段快照")
    private List<ModelFieldItem> fields;

    @Schema(description = "发布时的表类型专属口径配置")
    private Object typeConfig;
}
