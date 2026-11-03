package cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 逻辑表 Response VO")
@Data
public class LogicTableRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "76")
    private Long id;

    @Schema(description = "设计修订号，删除时作为乐观锁", example = "0")
    private Long revision;

    @Schema(description = "中文名称", example = "实时告警信息表")
    private String name;

    @Schema(description = "英文名", example = "SL_WARN_R_TMP")
    private String code;

    @Schema(description = "完整表名", example = "ODS_SL_WARN_R_TMP")
    private String tableNameNotation;

    @Schema(description = "表类型", example = "SOURCE")
    private String tableType;

    @Schema(description = "是否启用：0 禁用，1 启用", example = "1")
    private Integer enabled;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "字段数", example = "31")
    private Long columnCount;

    @Schema(description = "来源系统编号", example = "4")
    private Long sourceSystemId;

    @Schema(description = "来源系统名称", example = "水利数据仓库-DWS汇总层")
    private String sourceSystemName;

    @Schema(description = "来源数据库类型", example = "DM8")
    private String dbType;

    @Schema(description = "来源数据库名", example = "DATA")
    private String dbName;

    @Schema(description = "数据板块编号", example = "29")
    private Long dataDomainId;

    @Schema(description = "数据板块名称", example = "水利监测与防汛调度")
    private String dataDomainName;

    @Schema(description = "数据板块图标")
    private String dataDomainIcon;

    @Schema(description = "主题域编号", example = "117")
    private Long subjectDomainId;

    @Schema(description = "主题域名称", example = "防汛预警")
    private String subjectDomainName;

    @Schema(description = "关联业务实体编号", example = "69")
    private Long entityId;

    @Schema(description = "关联业务实体名称", example = "防汛责任区")
    private String entityName;

    @Schema(description = "维度类型", example = "1")
    private String objType;

    @Schema(description = "业务类型", example = "1")
    private String businessType;

    @Schema(description = "统计时效", example = "1")
    private String dataTimeliness;

    @Schema(description = "统计粒度（逗号分隔）", example = "会员,客户")
    private String statisticsStr;

    @Schema(description = "责任人用户编号", example = "1")
    private Long contactUserId;

    @Schema(description = "联系电话", example = "15888888888")
    private String contactNumber;

    @Schema(description = "责任人姓名", example = "吴同")
    private String ownerName;

    @Schema(description = "责任人电话", example = "15365128327")
    private String ownerPhone;

    @Schema(description = "创建人编号", example = "1")
    private String creator;

    @Schema(description = "创建人姓名", example = "超级管理员")
    private String creatorName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
