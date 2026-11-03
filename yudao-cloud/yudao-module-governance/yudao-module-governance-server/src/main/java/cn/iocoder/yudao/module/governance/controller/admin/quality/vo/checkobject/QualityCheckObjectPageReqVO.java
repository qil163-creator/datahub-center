package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 检查对象分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityCheckObjectPageReqVO extends PageParam {

    @Schema(description = "检查对象名称（模糊）")
    private String objectName;

    @Schema(description = "表名称（模糊）")
    private String tableName;

    @Schema(description = "数据源连接ID")
    private Long datasourceConnId;

    @Schema(description = "数据连接类型")
    private String datasourceType;

}
