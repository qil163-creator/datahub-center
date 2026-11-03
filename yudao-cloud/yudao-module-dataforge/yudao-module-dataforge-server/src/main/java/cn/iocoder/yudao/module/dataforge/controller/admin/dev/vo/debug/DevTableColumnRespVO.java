package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.debug;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 表字段元数据 Response VO（编辑器智能补全用）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表字段元数据 Response VO")
@Data
public class DevTableColumnRespVO {

    @Schema(description = "字段名", example = "user_id")
    private String name;

    @Schema(description = "字段注释", example = "用户编号")
    private String comment;

    @Schema(description = "数据类型", example = "BIGINT")
    private String dataType;

}
