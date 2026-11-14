package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-组织填报实例（工作项）DO
 *
 * 周期任务 × 报送组织的最小原子单元：权限、多人协作、提交、审批和合并都以它为边界；
 * 状态迁移一律通过本行的数据库 CAS 完成（status + serverVersion）。
 * 按业务记录执行后台任务，不依赖租户上下文。
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_work_item")
@KeySequence("dh_dataforge_fill_work_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillWorkItemDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 周期任务编号
     */
    private Long taskId;

    /**
     * 采集计划编号（冗余便查）
     */
    private Long planId;

    /**
     * 冻结的模板版本编号
     */
    private Long tableVersionId;

    /**
     * 报送组织编号
     */
    private Long reportingOrgId;

    /**
     * 组织名称快照
     */
    private String orgName;

    /**
     * 组织编码快照
     */
    private String orgCode;

    /**
     * 组织路径快照
     */
    private String orgPath;

    /**
     * 主状态：0-待填报 1-填报中 2-提交校验中 3-待启动审批 4-审批中
     * 5-整改中 6-待合并 7-合并中 8-合并失败 9-已合并 10-已取消，见 FillWorkItemStatusEnum
     */
    private Integer status;

    /**
     * 逾期标识（附加标志，不替代主状态）：0-未逾期 1-已逾期
     */
    private Integer overdue;

    /**
     * 报送类型：1-正常 2-零上报，见 FillSubmissionTypeEnum
     */
    private Integer submissionType;

    /**
     * 服务端单调版本：草稿操作与提交冻结竞争同一版本做 CAS
     */
    private Long serverVersion;

    /**
     * 提交次数
     */
    private Integer attemptCount;

    /**
     * 当前提交快照编号
     */
    private Long currentSubmissionId;

    /**
     * 草稿行数（冗余统计）
     */
    private Integer draftRowCount;

    /**
     * 配置快照哈希（模板版本+人员+周期配置）
     */
    private String configHash;

    /**
     * 截止时间（默认继承任务，可被单独延期覆盖）
     */
    private LocalDateTime deadlineTime;

    /**
     * 最后修改人编号
     */
    private Long lastModifyUserId;

    /**
     * 最后修改时间
     */
    private LocalDateTime lastModifyTime;

    /**
     * 上级组织实例编号；空表示由发起者直接下发的顶层实例
     */
    private Long parentWorkItemId;

    /**
     * 层级：顶层为 0，每下发一级加 1
     */
    private Integer level;

}
