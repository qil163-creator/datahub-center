package cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "管理后台 - API 请求参数项")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestParamItem {

    @Schema(description = "参数名", example = "token")
    private String name;

    @Schema(description = "参数值", example = "xxx")
    private String value;

    @Schema(description = "说明")
    private String description;

}
