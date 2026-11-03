package cn.iocoder.yudao.module.asset.controller.admin.assettag.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssetTagLinkedRespVO {

    private Long relationId;

    private Integer relationRevision;

    private Long assetId;

    private String sourceRecordId;

    private String assetName;

    private String tableName;

    private String description;

    private Integer assetType;

    private String categoryName;

    private String subjectDomainName;

    private String creator;

    /** 来源资产创建昵称，仅用于展示，不作为本地操作人或授权依据。 */
    private String sourceCreatorName;

    private String creatorName;

    /** 来源资产创建时间，仅用于展示和排序，本地审计时间保持不变。 */
    private LocalDateTime sourceCreateTime;

    private LocalDateTime createTime;
}
