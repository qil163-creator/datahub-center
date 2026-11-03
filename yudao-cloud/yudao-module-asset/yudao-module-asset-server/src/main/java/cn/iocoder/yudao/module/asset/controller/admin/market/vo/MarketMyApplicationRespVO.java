package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 本人申请记录")
@Data
public class MarketMyApplicationRespVO {
    private Long id;
    private String applyNo;
    private String title;
    private Long assetId;
    private String assetName;
    private Integer assetType;
    private Integer status;
    private String purpose;
    private LocalDateTime applyStartTime;
    private LocalDateTime applyEndTime;
    private String checkNote;
    private LocalDateTime checkTime;
    private LocalDateTime createTime;
}
