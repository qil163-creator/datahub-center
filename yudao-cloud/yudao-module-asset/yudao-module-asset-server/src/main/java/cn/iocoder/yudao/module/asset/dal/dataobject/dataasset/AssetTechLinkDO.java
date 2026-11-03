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

@TableName("dh_asset_tech_link")
@KeySequence("dh_asset_tech_link_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetTechLinkDO extends BaseDO {

    @TableId
    private Long id;

    private Long assetId;

    private String providerModule;

    private String resourceUrn;

    private Long datasourceId;

    private String schemaName;

    private String objectName;

    private Long metaTableId;

    private Long snapshotId;

    private Integer primaryFlag;

}
