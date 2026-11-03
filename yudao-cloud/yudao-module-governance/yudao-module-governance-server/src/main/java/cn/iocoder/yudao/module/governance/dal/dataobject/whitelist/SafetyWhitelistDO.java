package cn.iocoder.yudao.module.governance.dal.dataobject.whitelist;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 脱敏白名单 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_whitelist")
@KeySequence("dh_safety_whitelist_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyWhitelistDO extends BaseDO {

    /** 生效主体：用户 */
    public static final String SUBJECT_TYPE_USER = "USER";

    /** 生效主体：角色 */
    public static final String SUBJECT_TYPE_ROLE = "ROLE";

    /** 生效主体：部门 */
    public static final String SUBJECT_TYPE_DEPT = "DEPT";

    /** 豁免场景：全部 */
    public static final String SCENE_ALL = "ALL";

    /** 豁免范围：全局 */
    public static final String OBJECT_TYPE_GLOBAL = "GLOBAL";

    /** 状态：启用 */
    public static final Short STATUS_ENABLE = 0;

    /** 状态：停用 */
    public static final Short STATUS_DISABLE = 1;

    /** 审批状态：草稿 */
    public static final String APPROVAL_STATUS_DRAFT = "DRAFT";

    /** 本地配置权限直接授权，与审批通过状态分离。 */
    public static final String APPROVAL_STATUS_AUTHORIZED = "AUTHORIZED";

    /** 审批状态：审批中 */
    public static final String APPROVAL_STATUS_PENDING = "PENDING";

    /** 审批状态：已通过 */
    public static final String APPROVAL_STATUS_APPROVED = "APPROVED";

    /** 审批状态：已驳回 */
    public static final String APPROVAL_STATUS_REJECTED = "REJECTED";

    /**
     * 白名单编号
     */
    @TableId
    private Long id;

    /**
     * 白名单名称
     */
    private String name;

    /**
     * 生效主体
     */
    private String subjectType;

    /**
     * 豁免场景
     */
    private String scene;

    /**
     * 豁免范围
     */
    private String objectType;

    /**
     * 豁免对象编号
     */
    private Long objectId;

    /**
     * 生效开始时间
     */
    private LocalDateTime effectiveTime;

    /**
     * 生效结束时间
     */
    private LocalDateTime expireTime;

    /**
     * 状态
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Short status;

    /**
     * 审批状态：DRAFT-草稿 PENDING-审批中 APPROVED-已通过 REJECTED-已驳回
     */
    private String approvalStatus;

    /**
     * 申请理由
     */
    private String applyReason;

    /**
     * 审批人（用户编号）
     */
    private String approver;

    /**
     * 描述
     */
    private String description;

    /**
     * 备注
     */
    private String remark;

    private Long revision;

    private String sourceRecordId;

    private String sourceCreatorName;

    private String sourceUpdaterName;

    private LocalDateTime sourceCreateTime;

    private LocalDateTime sourceUpdateTime;

    /** 本地授权依据，与远端审批历史分离。 */
    private String authorizationBasis;

    private String authorizationReason;


}
