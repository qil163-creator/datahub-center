package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("dh_asset_market_listing")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketListingDO extends BaseDO {

    @TableId(type = IdType.INPUT)
    private Long assetId;

    private Long marketCategoryId;

    private String displayName;

    private String description;

    private String frequency;

    /** 升序逗号分隔的展示声明，实际授权和交付能力仍由原服务验证。 */
    private String deliveryTypes;

    /** 来源系统的集市记录编号，仅用于展示溯源，不参与本地授权判断。 */
    private String sourceRecordId;

    private String fileName;

    private String fileType;

    private String serviceIdentifier;

    private Integer primaryDeliveryType;

    /** 来源系统的展示分数，不代表本平台重新计算的数据质量分数。 */
    private String displayScore;

    private Long revision;
}
