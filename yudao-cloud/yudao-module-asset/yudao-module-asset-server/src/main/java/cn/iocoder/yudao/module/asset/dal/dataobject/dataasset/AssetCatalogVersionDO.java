package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("dh_asset_catalog_version")
@KeySequence("dh_asset_catalog_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetCatalogVersionDO extends BaseDO {

    public static final int STATUS_DRAFT = 0;

    public static final int STATUS_SUBMITTED = 1;

    public static final int STATUS_RETURNED = 2;

    public static final int STATUS_APPROVED = 3;

    public static final int STATUS_PUBLISHED = 4;

    @TableId
    private Long id;

    private Long assetId;

    private Integer versionNo;

    private String contentJson;

    private String contentHash;

    private Long techSnapshotId;

    private Integer status;

    private Long submitUserId;

    private LocalDateTime submitTime;

    private Long checkUserId;

    private LocalDateTime checkTime;

    private String checkNote;

    private LocalDateTime publishTime;

}
