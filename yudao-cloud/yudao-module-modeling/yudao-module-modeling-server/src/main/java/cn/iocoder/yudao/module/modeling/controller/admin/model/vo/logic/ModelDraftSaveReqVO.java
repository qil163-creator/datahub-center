package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 表单与 ER 编辑器共用的完整草稿命令。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "统一模型完整草稿保存")
public class ModelDraftSaveReqVO extends LogicModelCreateReqVO {

    @NotNull(message = "模型编号不能为空")
    private Long modelId;

}
