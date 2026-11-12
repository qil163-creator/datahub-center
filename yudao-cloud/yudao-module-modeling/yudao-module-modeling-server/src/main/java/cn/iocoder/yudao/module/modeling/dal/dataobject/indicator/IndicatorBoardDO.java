package cn.iocoder.yudao.module.modeling.dal.dataobject.indicator;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 指标看板：保存一组指标查询（组件），展示时按查看者身份实时查询并动态脱敏，不保存查询结果。
 */
@TableName("dh_modeling_indicator_board")
@KeySequence("dh_modeling_indicator_board_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class IndicatorBoardDO extends BaseDO {

    @TableId
    private Long id;
    /** 名称 */
    private String name;
    /** 说明 */
    private String description;
    /** 创建人（只有创建人可修改、删除） */
    private Long ownerUserId;
    /** 组件 JSON：[{key, title, chartType, query}] */
    private String widgetsJson;
    /** 乐观锁修订号 */
    private Integer revision;
}
