package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 发布模型目录节点")
public class ModelMaterializedCatNodeRespVO {

    private String key;

    private String name;

    @Schema(description = "GROUP/LAYER_CATEGORY/LAYER/SOURCE_SYSTEM/DATA_DOMAIN/SUBJECT_DOMAIN")
    private String type;

    private Long layerId;

    private List<Long> layerIds;

    private Long sourceSystemId;

    private Long dataDomainId;

    private Long subjectDomainId;

    private String tableType;

    private List<ModelMaterializedCatNodeRespVO> children;
}
