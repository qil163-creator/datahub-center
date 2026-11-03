package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 主数据模型 DO
 *
 * 参考 srt-cloud 的 data_governance_master_model：
 * 发布时按字段定义在绑定的数据源中创建物理表（srt 建在"项目库"，本项目改为显式绑定数据源实例）
 *
 * @author Li
 */
@TableName("dh_governance_master_model")
@KeySequence("dh_governance_master_model_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterModelDO extends BaseDO {

    public static final int STATUS_UNRELEASED = 0;

    public static final int STATUS_RELEASED = 1;

    public static final int STATUS_OFFLINE = 2;

    public static final String PUBLISH_READY = "READY";

    public static final String PUBLISH_APPLYING = "APPLYING";

    public static final String PUBLISH_UNKNOWN = "UNKNOWN";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 历史目录编号，仅兼容旧入口
     */
    private Long catalogId;

    /** 所属主数据域。 */
    private Long domainId;

    /** 已发布结构版本。 */
    private Integer version;

    /** 数据变更是否需要审批。 */
    private Boolean approvalEnabled;

    /** 可选 BPM 流程定义标识；为空时使用领域人工审批。 */
    private String approvalProcessKey;

    /** 外部建表结果，UNKNOWN 必须核查，不能自动重试。 */
    private String publishState;

    private String publishMessage;

    /**
     * 物理表所在数据源编号（dataforge 数据源实例）
     */
    private Long databaseId;

    /**
     * 物理表名
     */
    private String tableName;

    /**
     * 中文名称（物理表注释）
     */
    private String tableCn;

    /**
     * 描述
     */
    private String description;

    /**
     * 状态：0-草稿 1-已发布 2-已下线
     */
    private Integer status;

    /**
     * 发布时间
     */
    private LocalDateTime releaseTime;

    /**
     * 发布人编号
     */
    private Long releaseUserId;

    /**
     * 历史机构编号，不作为主数据域授权依据
     */
    private Long orgId;

}
