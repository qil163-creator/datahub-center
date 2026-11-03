package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Schema(description = "管理后台 - API 服务测试 Request VO（向导第三步，配置可未保存）")
@Data
public class DataApiTestReqVO {

    @Schema(description = "API 配置", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "API 配置不能为空")
    @Valid
    private DataApiSaveReqVO api;

    @Schema(description = "请求参数值")
    private Map<String, Object> params;

    @Schema(description = "页码（分页格式时）", example = "1")
    private Integer pageNo;

    @Schema(description = "每页条数（分页格式时）", example = "10")
    private Integer pageSize;

}
