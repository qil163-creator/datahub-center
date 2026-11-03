package cn.iocoder.yudao.module.asset.dal.dataobject.market;

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

import java.time.LocalDate;

@TableName("dh_market_work_calendar")
@KeySequence("dh_market_work_calendar_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketWorkCalendarDO extends BaseDO {

    @TableId
    private Long id;

    private LocalDate calendarDay;

    private Integer dayKind;

    private String remark;
}
