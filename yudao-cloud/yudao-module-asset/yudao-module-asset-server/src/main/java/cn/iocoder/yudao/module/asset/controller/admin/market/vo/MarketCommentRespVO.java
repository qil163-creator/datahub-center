package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 资源评价")
@Data
public class MarketCommentRespVO {

    private Long id;

    private Long assetId;

    private Long applyId;

    private String commentStr;

    private Integer level;

    private String replyStr;

    private Long replyUserId;

    private String replyUserName;

    private LocalDateTime replyTime;

    private String creator;

    private String creatorName;

    private Integer commentKind;

    private String problemType;

    private String impactScope;

    private LocalDateTime foundTime;

    private Long workOrderId;

    private String workOrderStatus;

    private LocalDateTime createTime;

}
