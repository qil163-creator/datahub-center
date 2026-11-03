package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 在线填报-提交 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报提交 Request VO")
@Data
public class FillSubmitReqVO {

    @Schema(description = "客户端已同步到的服务端版本（提交冻结的 CAS 基准）", requiredMode = Schema.RequiredMode.REQUIRED, example = "135")
    @NotNull(message = "expectedVersion 不能为空")
    private Long expectedVersion;

    @Schema(description = "报送类型：1-正常 2-零上报（本期无数据，行数必须为 0）", example = "1")
    private Integer submissionType;

    @Schema(description = "零上报说明（submissionType=2 时建议填写）", example = "本期无新增项目")
    private String emptyReason;

}
