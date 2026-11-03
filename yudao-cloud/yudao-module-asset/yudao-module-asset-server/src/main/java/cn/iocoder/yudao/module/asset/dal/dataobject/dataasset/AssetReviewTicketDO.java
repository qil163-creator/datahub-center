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

@TableName("dh_asset_review_ticket")
@KeySequence("dh_asset_review_ticket_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetReviewTicketDO extends BaseDO {

    public static final int TYPE_ERROR = 1;

    public static final int TYPE_MISSING = 2;

    public static final int TYPE_UNCLEAR = 3;

    public static final int TYPE_STALE = 4;

    public static final int TYPE_UNAVAILABLE = 5;

    public static final int STATUS_SUBMITTED = 0;

    public static final int STATUS_CLAIMED = 1;

    public static final int STATUS_FIXING = 2;

    public static final int STATUS_FEEDBACK = 3;

    public static final int STATUS_REVIEW = 4;

    public static final int STATUS_DONE = 5;

    public static final int STATUS_RETURNED = 6;

    @TableId
    private Long id;

    private Long assetId;

    private Long catalogVersionId;

    private Integer issueType;

    private String evidence;

    private Long demandDeptId;

    private Long handleDeptId;

    private Integer status;

    private LocalDateTime dueTime;

    private String conclusion;

}
