package cn.iocoder.yudao.module.governance.dal.dataobject.lineage;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 数据血缘-上报来源：每个 (sourceType, sourceId) 一行，记录最新接受的修订、解析器版本和内容摘要。
 */
@TableName(value = "dh_governance_lineage_source", autoResultMap = true)
@KeySequence("dh_governance_lineage_source_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LineageSourceDO extends BaseDO {

    /** 同一事务内刚创建、尚未写入时的修订号占位值 */
    public static final long UNSET_REVISION = -1L;

    @TableId
    private Long id;

    /** 来源类型，取值见 LineageSourceTypeEnum */
    private String sourceType;

    /** 来源对象编号 */
    private Long sourceId;

    /** 来源名称快照 */
    private String sourceName;

    /** 已接受的来源修订号 */
    private Long sourceRevision;

    /** 已接受的解析器版本 */
    private Integer analyzerVersion;

    /** 已接受内容的 SHA-256 */
    private String contentHash;

    /** 解析状态，取值见 LineageReportStatusEnum */
    private String status;

    /** 解析问题 JSON 数组 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String issues;

    /** 当前表级边数量 */
    private Integer tableEdgeCount;

    /** 当前字段级边数量 */
    private Integer columnEdgeCount;

    /** 最近一次接受上报的时间 */
    private LocalDateTime reportedTime;

}
