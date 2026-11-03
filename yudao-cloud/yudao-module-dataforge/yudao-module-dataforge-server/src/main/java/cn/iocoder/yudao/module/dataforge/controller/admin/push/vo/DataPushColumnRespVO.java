package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "管理后台 - 数据推送列信息")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataPushColumnRespVO {

    @Schema(description = "列名")
    private String name;

    @Schema(description = "注释")
    private String comment;

    @Schema(description = "数据类型")
    private String dataType;

    @Schema(description = "是否可作为增量字段（数值或时间类型）")
    private Boolean incrementSupported;

}
