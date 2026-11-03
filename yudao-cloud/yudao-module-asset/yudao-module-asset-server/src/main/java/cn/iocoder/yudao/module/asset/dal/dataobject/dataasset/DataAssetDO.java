package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("dh_asset_data_asset")
@KeySequence("dh_asset_data_asset_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataAssetDO extends BaseDO {

    public static final int TYPE_DATABASE_TABLE = 1;

    public static final int TYPE_API = 2;

    public static final int TYPE_FILE = 3;

    public static final int STATUS_UNPUBLISHED = 0;

    public static final int STATUS_PUBLISHED = 1;

    public static final int STATUS_OFFLINE = 2;

    public static final int SHARE_UNKNOWN = 0;

    public static final int SHARE_PENDING = 0;

    public static final int SHARE_UNCONDITIONAL = 1;

    public static final int SHARE_CONDITIONAL = 2;

    public static final int SHARE_DENIED = 3;

    @TableId
    private Long id;

    private String assetCode;

    private String catalogItemUrn;

    private String assetName;

    private Integer assetType;

    private Long categoryId;

    private Long metaTableId;

    private String resourceUrn;

    private Long publishedSnapshotId;

    private String publishedVersion;

    private Long datasourceId;

    private String sourceSystemName;

    private String dbName;

    private String schemaName;

    private String tableName;

    private String tableType;

    private String layer;

    private Long subjectDomainId;

    private String subjectDomainName;

    private Long rowCount;

    private Integer fieldCount;

    private Long storageSize;

    private BigDecimal healthScore;

    private String description;

    private String registrationConfigJson;

    private Integer status;

    private LocalDateTime publishTime;

    private String publisher;

    private Long techLeaderUserId;

    private String techLeaderPhone;

    private Long businessLeaderUserId;

    private String businessLeaderPhone;

    private Long ownerDeptId;

    private Long viewCount;

    private Integer shareAttribute;

    private String shareCondition;

    private String updateCycle;

    private String businessPurpose;

    private String contactName;

    private String contactPhone;

    private String remark;

    /** 与 shareAttribute 同步；0027 列 share_attr，0026 列 share_attribute */
    private Integer shareAttr;

    private String shareDenyReason;

    private Long providerDeptId;

    private Long sourceDeptId;

    private String dutyBasis;

    private String updateRule;

    private Long catalogRevision;

    private Long publishedCatalogVersionId;

    private Integer techAvailable;

    private String externalCode;

    private Long stopSupplyUserId;

    private Long stopSupplyDeptId;

    private Boolean retirementRequested;

    private String stopSupplyReason;

    private LocalDateTime stopSupplyTime;

    /**
     * 解析数据资产的共享属性。
     */
    public int resolveShareAttr() {
        Integer value = shareAttr != null ? shareAttr : shareAttribute;
        return value == null ? SHARE_UNKNOWN : value;
    }

    /**
     * 解析数据资产的共享属性。
     */
    public static int resolveShareAttr(DataAssetDO asset) {
        return asset == null ? SHARE_UNKNOWN : asset.resolveShareAttr();
    }

}
