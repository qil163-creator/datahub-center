package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetRegistrationCandidateRespVO extends MetadataTableRespDTO {

    private Integer assetType;

    private String name;

    private String fileName;

    private String fileType;

    private String path;

    private String method;

    private String description;

    private Boolean registered;
}
