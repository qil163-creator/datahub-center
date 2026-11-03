package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据同步表级结果分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataAccessTaskDetailPageReqVO extends PageParam {

    @Schema(description = "数据同步任务id", example = "1024")
    private Long dataAccessId;

    @Schema(description = "执行记录id", example = "1")
    private Long taskId;

    @Schema(description = "是否成功：0-否 1-是", example = "1")
    private Integer ifSuccess;

    @Schema(description = "源端表名", example = "t_user")
    private String sourceTableName;

    @Schema(description = "目的端表名", example = "ods_t_user")
    private String targetTableName;

}
