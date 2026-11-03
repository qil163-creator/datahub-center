package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterCodeSegmentVO")
@Data
public class MasterCodeSegmentVO {

    @NotNull
    @Pattern(regexp = "CONSTANT|DATE|SEQUENCE|ATTRIBUTE")
    private String segmentType;

    @NotNull
    @Size(max = 100)
    private String segmentValue;

    @Size(max = 10)
    private String sepChar;
}
