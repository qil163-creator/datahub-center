package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "政务数据校核工单")
@Data
public class AssetReviewTicketRespVO {

    private Long id;

    private Long assetId;

    private Long catalogVersionId;

    private Integer issueType;

    private String evidence;

    private Long demandDeptId;

    private Long handleDeptId;

    private Integer status;

    private LocalDateTime dueTime;

    private String conclusion;

    private LocalDateTime createTime;

}
