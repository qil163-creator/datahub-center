package cn.iocoder.yudao.module.dataforge.dal.dataobject.push;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据推送增量水位 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_push_increase_log")
@KeySequence("dh_dataforge_push_increase_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataPushIncreaseLogDO extends BaseDO {

    @TableId
    private Long id;

    private Long pushTaskId;

    private String increaseColumn;

    private String startVal;

    private String endVal;

}
