package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 问题工单新增/修改 Request VO")
@Data
public class QualityWorkOrderSaveReqVO {

    private Long id;

    @Schema(description = "编辑时必填：打开表单时读取的lockVersion；新增不需要。不得用提交时现查版本覆盖", example = "0")
    @Min(value = 0, message = "工单版本不能为负数")
    @Max(value = 2147483646, message = "工单版本已达到可编辑上限")
    private Integer expectedVersion;

    // Jackson 的默认 Integer 反序列化会截断小数；只约束此请求字段，不改变全局 JSON 策略。

    /**
     * 接收 JSON 预期版本并按约定转换为工单版本值。
     */
    @JsonSetter("expectedVersion")
    public void setExpectedVersionFromJson(BigDecimal version) {
        expectedVersion = version == null ? null : version.intValueExact();
    }

    @NotBlank(message = "工单名称不能为空")
    @Size(max = 256, message = "工单名称长度不能超过 256")
    private String orderName;

    private Long taskId;

    private Long ownerUserId;

    private Long handlingDeptId;

    private Long confirmerUserId;

    private LocalDateTime deadline;

    @Size(max = 1024)
    private String description;

    private String problemType;

    private String severity;

    private String sourceType;

    private List<Long> issueIds;

}
