package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class MetaFileUpdateBizReqVO {

    @NotNull
    private Long id;

    @NotNull
    @Min(0)
    private Integer bizRevision;

    @Size(max = 200)
    private String businessName;

    @Size(max = 256)
    private String description;

    private Long ownerUserId;

    private Long techLeaderUserId;

    @Size(max = 32)
    private String techLeaderPhone;

    private Long businessLeaderUserId;

    @Size(max = 32)
    private String businessLeaderPhone;
}
