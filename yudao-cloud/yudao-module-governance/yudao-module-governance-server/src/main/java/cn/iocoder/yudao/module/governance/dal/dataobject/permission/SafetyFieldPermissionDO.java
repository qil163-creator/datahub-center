package cn.iocoder.yudao.module.governance.dal.dataobject.permission;

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

/**
 * 数据字段访问权限 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_field_permission")
@KeySequence("dh_governance_safety_field_permission_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyFieldPermissionDO extends BaseDO {

    public static final int TYPE_DENY = 0;

    public static final int TYPE_PLAIN = 1;

    public static final int TYPE_MASK = 2;

    @TableId
    private Long id;

    /** 数据源编号 */
    private Long databaseId;

    /** 表名 */
    private String tableName;

    /** 精确物理模式；历史NULL表示未核验，不能当作跨模式通配符。 */
    private String schemaName;

    private String bindingNote;

    private Long bindingActorId;

    private java.time.LocalDateTime bindingTime;

    /** 字段名 */
    private String fieldName;

    /** 角色编号 */
    private Long roleId;

    /** 数据分级编号 */
    private Long securityLevelId;

    /** 字段权限：0-禁止访问 1-明文访问 2-脱敏访问 */
    private Integer permissionType;

    /** 脱敏规则编号 */
    private Long maskingRuleId;

    /** 状态：0-开启 1-关闭 */
    private Integer status;

    /** 备注 */
    private String remark;

}
