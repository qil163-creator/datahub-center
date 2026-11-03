package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 申请审批轨迹")
@Data
public class MarketCheckProcessRespVO {

    private Long id;

    private Long resourceApplyId;

    private String action;

    private Integer fromStatus;

    private Integer toStatus;

    private Long operatorUserId;

    private String operatorName;

    private String note;

    private LocalDateTime createTime;

}
