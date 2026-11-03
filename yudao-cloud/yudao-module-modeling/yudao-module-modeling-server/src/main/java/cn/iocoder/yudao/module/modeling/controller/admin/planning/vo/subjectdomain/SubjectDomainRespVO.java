package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 主题域 Response VO")
@Data
public class SubjectDomainRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级节点", example = "0")
    private Long parentId;

    private Long dataDomainId;

    private String abbreviation;

    @Schema(description = "主题域名称", example = "水文气象域")
    private String name;

    @Schema(description = "英文缩写", example = "HYD_MET")
    private String code;

    @Schema(description = "负责人用户编号", example = "1")
    private Long leaderUserId;

    @Schema(description = "负责人电话", example = "00000000000")
    private String leaderPhone;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者编号")
    private String creator;

    @Schema(description = "负责人姓名")
    private String leaderName;

    @Schema(description = "创建人姓名")
    private String creatorName;

    @Schema(description = "创建人联系方式")
    private String creatorPhone;

    @Schema(description = "业务实体数量（对象及活动），无概念模型查询权限时为空")
    private Long businessObjectCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新者编号")
    private String updater;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
