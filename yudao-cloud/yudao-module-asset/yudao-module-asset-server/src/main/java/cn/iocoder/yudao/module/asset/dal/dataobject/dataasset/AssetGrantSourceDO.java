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

@TableName("dh_asset_grant_source")
@KeySequence("dh_asset_grant_source_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetGrantSourceDO extends BaseDO {

    public static final int TYPE_APPLY = 1;

    public static final int TYPE_MANUAL = 2;

    public static final int TYPE_HISTORY = 3;

    @TableId
    private Long id;

    private Long applyId;

    private Integer sourceType;

    private Long appId;

    private Long apiId;

    private Long assetId;

    private String scopeJson;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer revoked;

    private Long revision;

    private String sourceKey;

    private String sourceStatus;

}
