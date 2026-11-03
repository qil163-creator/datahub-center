package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 逻辑模型 ER 画布布局保存 Request VO")
@Data
public class LogicErLayoutSaveReqVO {

    @Schema(description = "模型坐标列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "坐标列表不能为空")
    @Valid
    private List<Position> positions;

    @Schema(description = "模型坐标项")
    @Data
    public static class Position {

        @Schema(description = "逻辑模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @NotNull(message = "模型编号不能为空")
        private Long modelId;

        private Long expectedLayoutRevision;

        @Schema(description = "画布横坐标", requiredMode = Schema.RequiredMode.REQUIRED, example = "120")
        @NotNull(message = "横坐标不能为空")
        private Integer posX;

        @Schema(description = "画布纵坐标", requiredMode = Schema.RequiredMode.REQUIRED, example = "80")
        @NotNull(message = "纵坐标不能为空")
        private Integer posY;

    }

}
