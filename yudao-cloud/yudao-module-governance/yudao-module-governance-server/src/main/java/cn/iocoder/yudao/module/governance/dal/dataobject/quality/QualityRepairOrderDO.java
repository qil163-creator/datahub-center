package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-修复工单 DO
 *
 * 6种修复类型自动生成 SQL + 审批/执行/验证状态流转
 *
 * @author Li
 */
@TableName("dh_governance_quality_repair_order")
@KeySequence("dh_governance_quality_repair_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityRepairOrderDO extends BaseDO {

    /** 修复类型 - 填充默认值 */
    public static final String TYPE_FILL_DEFAULT = "FILL_DEFAULT";

    /** 修复类型 - 删除异常记录 */
    public static final String TYPE_DELETE_RECORD = "DELETE_RECORD";

    /** 修复类型 - 格式修正 */
    public static final String TYPE_FORMAT_FIX = "FORMAT_FIX";

    /** 修复类型 - 去重清洗 */
    public static final String TYPE_DEDUP = "DEDUP";

    /** 修复类型 - 标准化映射 */
    public static final String TYPE_STANDARD_MAP = "STANDARD_MAP";

    /** 修复类型 - 自定义SQL */
    public static final String TYPE_CUSTOM_SQL = "CUSTOM_SQL";

    /** 审批状态 - 待审批 */
    public static final String APPROVE_PENDING = "PENDING";

    /** 审批状态 - 已通过 */
    public static final String APPROVE_PASSED = "PASSED";

    /** 审批状态 - 已拒绝 */
    public static final String APPROVE_REJECTED = "REJECTED";

    /** 执行状态 - 待执行 */
    public static final String EXEC_PENDING = "PENDING";

    /** 执行状态 - 执行中 */
    public static final String EXEC_RUNNING = "RUNNING";

    /** 执行状态 - 执行成功 */
    public static final String EXEC_SUCCESS = "SUCCESS";

    /** 执行状态 - 已证明回滚或未提交的失败 */
    public static final String EXEC_FAILED_CONFIRMED = "FAILED_CONFIRMED";

    /** 兼容历史失败码，不得当作已证明回滚 */
    public static final String EXEC_FAILED = "FAILED";

    /** 执行状态 - 远端结果未知，禁止自动重放 */
    public static final String EXEC_UNKNOWN = "UNKNOWN";

    /** 验证状态 - 未验证 */
    public static final String VERIFY_UNVERIFIED = "UNVERIFIED";

    /** 验证状态 - 验证通过 */
    public static final String VERIFY_PASSED = "PASSED";

    /** 验证状态 - 验证未通过 */
    public static final String VERIFY_FAILED = "FAILED";

    @TableId
    private Long id;

    /** 工单标题 */
    private String orderTitle;

    /** 修复类型 */
    private String repairType;

    /** 检查对象ID */
    private Long objectId;

    /** 检测表 */
    private String tableName;

    /** 检测字段 */
    private String checkField;

    /** 目标字段 */
    private String targetField;

    /** 修复配置JSON */
    private String repairConfig;

    /** 修复SQL */
    private String repairSql;

    /** 审批状态：PENDING/PASSED/REJECTED */
    private String approveStatus;

    /** 执行状态：PENDING/SUCCESS/FAILED */
    private String execStatus;

    /** 验证状态：UNVERIFIED/PASSED/FAILED */
    private String verifyStatus;

    /** 修复说明 */
    private String repairDesc;

    /** 执行结果 */
    private String execResult;

    /** 执行时间 */
    private LocalDateTime execTime;

    /** 审批绑定的不可变 SQL 快照 */
    private String approvedSql;

    /** 审批内容摘要 */
    private String approvedContentHash;

    /** 审批快照 JSON */
    private String approvedSnapshot;

    /** 服务端确认的审批人 */
    private Long approveUserId;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 内容版本，修改执行内容后递增 */
    private Integer contentVersion;

    /** 预期影响行数上限 */
    private Integer maxAffectedRows;

    /** 执行操作编号 */
    private String operationId;

    /** 当前执行租约令牌 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String executionToken;

    /** 当前执行租约截止时间 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;

}
