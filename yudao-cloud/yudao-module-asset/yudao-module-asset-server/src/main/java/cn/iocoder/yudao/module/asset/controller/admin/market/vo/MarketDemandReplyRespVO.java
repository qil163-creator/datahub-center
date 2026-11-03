package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 需求响应")
@Data
public class MarketDemandReplyRespVO {

    private Long id;

    private Long demandId;

    private Integer replyType;

    private String content;

    private Long replyDeptId;

    private String replyDeptName;

    private Long replyUserId;

    private String replyUserName;

    private LocalDateTime createTime;

}
