package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagRespVO;

import java.util.List;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表元数据 Response VO（列表与详情共用，详情含库/来源冗余信息）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表元数据 Response VO")
@Data
public class MetaTableRespVO {

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String displaySnapshot;

    @Schema(description = "连接地址，只读展示")
    private String host;

    private String port;

    private String username;

    private String creator;

    private String creatorName;

    private String creatorPhone;

    private String updater;

    private String updaterName;

    private String updaterPhone;

    private LocalDateTime updateTime;

    @Schema(description = "发布与定版状态修订号")
    private Integer stateRevision;

    private Boolean finalized;

    private String finalizedVersion;

    private Long finalizedSnapshotId;

    private LocalDateTime finalizedTime;

    private String finalizedBy;

    private Boolean readOnly;

    private String datasourceName;

    private String schemaName;

    private String techLeaderName;

    private String businessLeaderName;

    private String ownerDeptName;

    private List<MetaTagRespVO> tags;


    @Schema(description = "平台业务属性修订号")
    private Integer bizRevision;

    @Schema(description = "表元数据编号", example = "1")
    private Long id;

    @Schema(description = "资源 URN", example = "urn:datahub:prod:table:1:100")
    private String resourceUrn;

    @Schema(description = "来源：1-采集 2-手工 3-导入", example = "1")
    private Integer origin;

    @Schema(description = "库元数据编号", example = "1")
    private Long dbId;

    @Schema(description = "库名（冗余展示）", example = "dwd")
    private String dbName;

    @Schema(description = "数据源编号", example = "1")
    private Long datasourceId;

    @Schema(description = "数据库类型（冗余展示）", example = "DORIS")
    private String dbType;

    @Schema(description = "来源系统名称（冗余展示）", example = "山洪灾害监测预警系统")
    private String sourceSystemName;

    @Schema(description = "表名", example = "dwd_dim_station")
    private String tableName;

    @Schema(description = "类型：TABLE/VIEW", example = "TABLE")
    private String tableType;

    @Schema(description = "源库表注释", example = "站点基础信息")
    private String sourceComment;

    @Schema(description = "平台注释（平台维护）", example = "站点基础信息（含经纬度整合字段）")
    private String customComment;

    @Schema(description = "业务名称（平台维护）", example = "站点基础信息表")
    private String businessName;

    @Schema(description = "所属分层", example = "DWD")
    private String layer;

    @Schema(description = "字段数量", example = "13")
    private Integer columnCount;

    @Schema(description = "主键字段列表 JSON", example = "[\"id\"]")
    private String primaryKeys;

    @Schema(description = "索引信息 JSON")
    private String indexes;

    @Schema(description = "分区信息 JSON")
    private String partitionInfo;

    @Schema(description = "存储引擎", example = "OLAP")
    private String engine;

    @Schema(description = "结构语义版本", example = "v1.0.0")
    private String structVersion;

    @Schema(description = "行数（估算）", example = "10000")
    private Long rowCount;

    @Schema(description = "存储大小（字节，估算）", example = "1048576")
    private Long storageSize;

    @Schema(description = "数据最近更新时间")
    private LocalDateTime dataUpdateTime;

    @Schema(description = "源表创建时间")
    private LocalDateTime tbCreateTime;

    @Schema(description = "结构变更累计次数", example = "3")
    private Integer structChangeCount;

    @Schema(description = "最近结构变更时间")
    private LocalDateTime lastStructChangeTime;

    @Schema(description = "是否主表：0-否 1-是", example = "1")
    private Integer masterFlag;

    @Schema(description = "是否临时表：0-否 1-是")
    private Integer temporaryFlag;

    @Schema(description = "平台维护的存储类型，独立于采集存储引擎")
    private String storageType;

    @Schema(description = "是否敏感表：0-否 1-是", example = "0")
    private Integer sensitiveFlag;

    @Schema(description = "业务定义")
    private String businessDefinition;

    @Schema(description = "主题域编号", example = "1")
    private Long subjectDomainId;

    @Schema(description = "主题域名称", example = "水利监测")
    private String subjectDomainName;

    @Schema(description = "目录挂接：0-未挂接 1-已挂接", example = "0")
    private Integer catalogLinked;

    @Schema(description = "技术负责人用户编号", example = "1")
    private Long techLeaderUserId;

    @Schema(description = "技术负责人电话", example = "13800000000")
    private String techLeaderPhone;

    @Schema(description = "业务负责人用户编号", example = "2")
    private Long businessLeaderUserId;

    @Schema(description = "业务负责人电话", example = "13800000001")
    private String businessLeaderPhone;

    @Schema(description = "责任部门编号", example = "100")
    private Long ownerDeptId;

    @Schema(description = "对象状态：1-正常 2-待确认删除 3-已删除", example = "1")
    private Integer objectStatus;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "0")
    private Integer publishStatus;

    @Schema(description = "定版版本号", example = "v1.0.0")
    private String publishedVersion;

    @Schema(description = "定版快照编号", example = "1")
    private Long publishedSnapshotId;

    @Schema(description = "发布时间")
    private LocalDateTime publishTime;

    @Schema(description = "健康度评分（P2）", example = "80.5")
    private BigDecimal healthScore;

    @Schema(description = "首次发现时间")
    private LocalDateTime firstSeenTime;

    @Schema(description = "最近确认时间")
    private LocalDateTime lastSeenTime;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
