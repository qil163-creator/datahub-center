package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据推送配置分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataPushConfigPageReqVO extends PageParam {

    @Schema(description = "配置名称")
    private String name;

    @Schema(description = "通道类型：1-HTTP 2-数据库 3-消息队列")
    private Integer channelType;

    @Schema(description = "所属机构编号")
    private Long orgId;

}
