package cn.iocoder.yudao.module.governance.dal.dataobject.subject;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 脱敏白名单生效主体 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_whitelist_subject")
@KeySequence("dh_safety_whitelist_subject_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyWhitelistSubjectDO extends BaseDO {

    /**
     * 主体编号
     */
    @TableId
    private Long id;

    /**
     * 所属白名单编号
     */
    private Long whitelistId;

    /**
     * 主体编号
     */
    private Long subjectId;


}
