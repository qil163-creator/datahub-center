package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.Size;

@Data
@EqualsAndHashCode(callSuper = true)
public class MetaFilePageReqVO extends PageParam {

    @Size(max = 255)
    private String name;

    @Size(max = 32)
    private String fileType;

    private Long datasourceId;

    private Long sourceSystemId;

    private Boolean sourceSystemMissing;

    @Size(max = 4096)
    private String path;

    private Integer objectStatus;
}
