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
 * 数据表访问权限 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_table_permission")
@KeySequence("dh_governance_safety_table_permission_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyTablePermissionDO extends BaseDO {

    public static final int TYPE_DENY = 0;

    public static final int TYPE_READ = 1;

    public static final int TYPE_READ_WRITE = 2;

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

    /** 角色编号 */
    private Long roleId;

    /** 数据分级编号 */
    private Long securityLevelId;

    /** 表权限：0-禁止访问 1-只读 2-读写 */
    private Integer permissionType;

    /** 行级过滤条件（预留） */
    private String rowFilterCondition;

    /** 独立写授权 JSON；NULL 代表未授权，不能从 permissionType=2 推断写权限。 */
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String writePolicy;

    /** 状态：0-开启 1-关闭 */
    private Integer status;

    /** 备注 */
    private String remark;

}
