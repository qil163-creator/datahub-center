package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 数据需求")
@Data
public class MarketDemandRespVO {

    private Long id;

    private String demandNo;

    private String title;

    private Integer demandType;

    private String description;

    private String purpose;

    private String expectedFields;

    private String expectedUpdateFreq;

    private Integer expectedDelivery;

    private LocalDateTime expectFinishTime;

    private Integer urgency;

    private Long applyDeptId;

    private String applyDeptName;

    private Long applyUserId;

    private String applyUserName;

    private String phone;

    private String email;

    private Integer status;

    private LocalDateTime publishTime;

    private String closeReason;

    private Integer acceptanceStatus;

    private Long acceptedApplyId;

    private LocalDateTime acceptedTime;

    private String remark;

    private LocalDateTime createTime;

    private Integer matchCount;

    private Integer replyCount;

    private List<MarketDemandMatchRespVO> matches;

    private List<MarketDemandReplyRespVO> replies;

}
