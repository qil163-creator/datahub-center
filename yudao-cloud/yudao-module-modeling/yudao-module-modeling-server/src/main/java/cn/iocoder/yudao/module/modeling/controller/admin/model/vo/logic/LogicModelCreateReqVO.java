package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 逻辑模型创建/修改 Request VO")
@Data
public class LogicModelCreateReqVO {

    @Schema(description = "主键（修改基础信息时必填）", example = "1")
    private Long id;

    @Schema(description = "保存时读取的模型修订号")
    private Long expectedRevision;

    @Size(max = 500)
    private String grain;

    private cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO typeConfig;

    @Schema(description = "所属数据域归属编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long dataDomainId;

    @Schema(description = "模型名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "aaa")
    @NotEmpty(message = "模型名称不能为空")
    @Size(max = 100, message = "模型名称不能超过 100 个字符")
    private String name;

    @Schema(description = "模型编码", example = "aaa")
    @Size(max = 64, message = "模型编码不能超过 64 个字符")
    private String code;

    @Schema(description = "创建方式：FORWARD-正向建模 REVERSE-逆向建模", example = "FORWARD")
    private String createMode;

    @Schema(description = "逆向建模：来源数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "逆向建模：来源表名", example = "ods_teacher")
    private String tableName;

    @Schema(description = "字段定义（正向建模可先不填）")
    private List<ModelFieldItem> fields;

    @Schema(description = "建模风格：RELATIONAL/DIMENSIONAL/WIDE/TIME_SERIES/SPATIAL/EVENT_STREAM",
            example = "RELATIONAL")
    private String modelStyle;

    @Schema(description = "所属数仓分层编号（草稿可暂缺，发布时必填）", example = "6")
    private Long layerId;

    @Schema(description = "所属主题域编号（选填）", example = "1")
    private Long subjectDomainId;

    @Schema(description = "表类型：SOURCE/DETAIL/DIMENSION/SUMMARY/APPLICATION，留空取分层默认值",
            example = "DETAIL")
    private String tableType;

    @Schema(description = "业务分类编号")
    private Long bizCategoryId;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;

    @Schema(description = "完整表名大小写：1 大写，2 小写", example = "1")
    private Integer tableCase;

    @Schema(description = "标准类型")
    @Size(max = 32, message = "标准类型不能超过 32 个字符")
    private String documentType;

    @Schema(description = "标准登记编号")
    private Long documentId;

    @Schema(description = "责任人用户编号")
    private Long contactUserId;

    @Schema(description = "责任人联系电话")
    @Size(max = 32, message = "联系电话不能超过 32 个字符")
    private String contactNumber;

    @Schema(description = "是否启用：0 禁用，1 启用", example = "0")
    private Integer enabled;

    @Schema(description = "完整逻辑表名")
    @Size(max = 128)
    private String tableNameNotation;

    @Schema(description = "来源系统编号")
    private Long sourceSystemId;

    @Schema(description = "来源数据连接编号")
    private Long datasourceId;

    @Size(max = 32)
    private String dbType;

    @Size(max = 128)
    private String dbName;

    @Size(max = 128)
    private String schemaName;

    @Schema(description = "关联业务对象或业务活动编号")
    private Long entityId;

    @Size(max = 8)
    private String objType;

    @Size(max = 8)
    private String businessType;

    @Schema(description = "统计时效：1 天，2 小时，3 分钟")
    @Size(max = 8)
    private String dataTimeliness;

    @Schema(description = "统计粒度显示名称，逗号分隔")
    @Size(max = 500)
    private String statisticsStr;

    @Schema(description = "统计粒度对应的已启用普通业务对象编号")
    @Size(max = 32)
    private List<Long> grainEntityIds;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

}
