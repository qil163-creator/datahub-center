package cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - API 取数结果 Response VO")
@Data
public class DataApiFetchRespVO {

    @Schema(description = "推断的返回字段（按首行 key，类型默认字符串）")
    private List<ResponseColumnItem> columns;

    @Schema(description = "行数据")
    private List<Map<String, Object>> rows;

}
