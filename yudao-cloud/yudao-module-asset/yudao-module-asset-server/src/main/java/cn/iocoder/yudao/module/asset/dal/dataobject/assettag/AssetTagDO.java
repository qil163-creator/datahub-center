package cn.iocoder.yudao.module.asset.dal.dataobject.assettag;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("dh_asset_tag")
@KeySequence("dh_asset_tag_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetTagDO extends BaseDO {

    public static final int STATUS_ENABLED = 0;

    public static final int STATUS_DISABLED = 1;

    @TableId
    private Long id;

    private String name;

    /**
     * 标签分组名称（兼容旧数据，新数据以 categoryId 为准）
     */
    private String category;

    /**
     * 标签类目编号
     */
    private Long categoryId;

    private String color;

    /**
     * 近义词，多个以逗号分隔
     */
    private String nearSynonyms;

    /**
     * 同义词，多个以逗号分隔
     */
    private String synonyms;

    private String description;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

    private String remark;

    private Integer revision;

    private String sourceRecordId;

    private String sourceCreatorName;

    private String sourceUpdaterName;

    private Integer sourceSortOrder;

    private java.time.LocalDateTime sourceCreateTime;

    private java.time.LocalDateTime sourceUpdateTime;

}
