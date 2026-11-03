package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据推送配置 Response VO")
@Data
public class DataPushConfigRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "配置名称")
    private String name;

    @Schema(description = "通道类型：1-HTTP 2-数据库 3-消息队列")
    private Integer channelType;

    @Schema(description = "通道配置")
    private DataPushChannelConfigVO config;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "所属机构编号")
    private Long orgId;

    @Schema(description = "所属机构名称")
    private String orgName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "是否被已发布任务引用（锁定不可改）")
    private Boolean locked;

}
