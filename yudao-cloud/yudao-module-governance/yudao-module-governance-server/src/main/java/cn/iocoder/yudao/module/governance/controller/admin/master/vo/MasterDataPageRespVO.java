package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 主数据记录分页 Response VO")
@Data
public class MasterDataPageRespVO {

    @Schema(description = "列名列表（按物理表实际返回）")
    private List<String> columns;

    @Schema(description = "行数据")
    private List<Map<String, Object>> list;

    @Schema(description = "总条数", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Long total;

}
