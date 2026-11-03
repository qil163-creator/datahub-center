package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 主数据模型统计 Response VO")
@Data
public class MasterModelStatisticsRespVO {

    private Long total;

    private Long released;

    private Long draft;

    private Long offline;
}
