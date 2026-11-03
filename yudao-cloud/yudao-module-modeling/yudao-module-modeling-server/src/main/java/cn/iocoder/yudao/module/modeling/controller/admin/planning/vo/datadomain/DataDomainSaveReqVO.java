package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import lombok.Data;
import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 数据板块保存 Request VO")
@Data
public class DataDomainSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "数据板块名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "水质监测数据域")
    @NotBlank(message = "数据板块名称不能为空")
    @Size(max = 32, message = "板块名称不能超过 32 个字符")
    private String name;

    @Schema(description = "英文缩写", requiredMode = Schema.RequiredMode.REQUIRED, example = "WQM")
    @NotBlank(message = "英文缩写不能为空")
    @Size(max = 64, message = "英文编码不能超过 64 个字符")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "英文编码只允许字母、数字和下划线")
    private String code;

    @Schema(description = "板块图标")
    @Size(max = 1024)
    private String icon;

    @Schema(description = "业务负责人编号，最多三人")
    @Size(max = 3, message = "业务负责人最多选择三人")
    private List<@NotNull @Positive Long> businessOwnerIds = new ArrayList<>();

    @Schema(description = "数据负责人编号，最多三人")
    @Size(max = 3, message = "数据负责人最多选择三人")
    private List<@NotNull @Positive Long> dataOwnerIds = new ArrayList<>();

    @Schema(description = "逻辑表命名规则")
    @NotNull
    @Size(min = 4, max = 6, message = "请完整配置逻辑表命名规则")
    private List<@NotNull @Valid DataDomainNamingRuleVO> namingRules = DataDomainNamingRuleVO.defaults();

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

    @Schema(description = "描述", example = "水源地、河湖断面水质监测数据")
    @Size(max = 256, message = "描述不能超过 256 个字符")
    private String description;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

}
