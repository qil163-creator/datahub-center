package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.List;

/** 当前设计与其乐观锁标记，禁止将两个不同读取时点的值组合保存。 */
@Data
@Schema(description = "管理后台 - 模型当前设计")
public class ModelDesignRespVO {
    private Long modelId;
    private Long revision;
    private String status;
    private List<ModelFieldItem> fields;
    private ModelTypeConfigVO typeConfig;
}
