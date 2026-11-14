package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-中间单位默认下发名单 DO（周期任务每期生成时自动下发到下一级）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_dispatch_template")
@KeySequence("dh_dataforge_fill_dispatch_template_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillDispatchTemplateDO extends BaseDO {

    /** 编号 */
    @TableId
    private Long id;

    /** 采集计划编号 */
    private Long planId;

    /** 下发单位（上级报送组织）编号 */
    private Long parentOrgId;

    /** 下级报送组织编号 */
    private Long reportingOrgId;

    /** 下级组织名称快照 */
    private String orgName;

    /** 下级组织编码快照 */
    private String orgCode;

    /** 下级组织路径快照 */
    private String orgPath;

    /** 默认填报人 JSON 数组：[{"userId","userName","deptId"}]（编号为字符串） */
    private String assigneeUserIds;

    /** 截止时间比下发单位提前的小时数 */
    private Integer deadlineOffsetHours;

    /** 显示顺序 */
    private Integer sort;

}
