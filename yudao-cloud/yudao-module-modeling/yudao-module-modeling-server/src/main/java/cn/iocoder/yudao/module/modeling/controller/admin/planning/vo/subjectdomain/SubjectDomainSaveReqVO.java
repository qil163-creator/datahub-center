package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import lombok.Data;

@Schema(description = "管理后台 - 主题域保存 Request VO")
@Data
public class SubjectDomainSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "上级主题域不能为空")
    private Long parentId;

    @Schema(description = "所属数据板块编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请选择数据板块")
    @Positive
    private Long dataDomainId;

    @Schema(description = "英文缩写", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "英文缩写不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{1,15}$", message = "英文缩写只允许 1 至 15 位字母、数字和下划线")
    private String abbreviation;

    @Schema(description = "主题域名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "水文气象域")
    @NotBlank(message = "主题域名称不能为空")
    @Size(max = 64, message = "主题域名称不能超过 64 个字符")
    @Pattern(regexp = "^[\\u4e00-\\u9fffA-Za-z0-9_-]+$", message = "主题域名称只允许中文、字母、数字、下划线和短横线")
    private String name;

    @Schema(description = "主题域编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "HYD_MET")
    @NotBlank(message = "英文缩写不能为空")
    @Size(max = 64, message = "英文编码不能超过 64 个字符")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "英文编码只允许字母、数字和下划线")
    private String code;

    @Schema(description = "负责人用户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "负责人不能为空")
    @Positive(message = "负责人编号必须大于零")
    private Long leaderUserId;

    @Schema(description = "负责人电话（随负责人自动带出）", example = "00000000000")
    @Size(max = 20, message = "负责人电话不能超过 20 个字符")
    private String leaderPhone;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

    @Schema(description = "描述")
    @Size(max = 256, message = "描述不能超过 256 个字符")
    private String description;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

}
