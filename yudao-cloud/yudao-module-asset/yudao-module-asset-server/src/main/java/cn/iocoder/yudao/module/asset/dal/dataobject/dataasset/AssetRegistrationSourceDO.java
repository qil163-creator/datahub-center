package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("dh_asset_registration_source")
public class AssetRegistrationSourceDO extends BaseDO {

    @TableId
    private Long assetId;

    private Integer sourceKind;

    private Long sourceId;

    private String sourceVersion;

    private String sourceName;

    private String fileType;

    private String sourcePath;

    private Long storageSize;
}
