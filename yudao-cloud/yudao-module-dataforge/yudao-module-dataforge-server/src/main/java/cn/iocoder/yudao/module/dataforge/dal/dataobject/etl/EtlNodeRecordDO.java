package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 可视化 ETL 节点执行记录 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_etl_node_record")
@KeySequence("dh_dataforge_etl_node_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EtlNodeRecordDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 流程执行记录编号
     */
    private Long recordId;

    /**
     * 流程编号
     */
    private Long pipelineId;

    /**
     * 画布节点编号
     */
    private String nodeNo;

    /**
     * 节点名称
     */
    private String nodeName;

    /**
     * 组件代码
     */
    private String toolCode;

    /**
     * 运行状态：1-等待 2-运行中 3-成功 4-失败
     */
    private Integer runStatus;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 节点日志
     */
    private String log;

    /**
     * 读取行数
     */
    private Long readCount;

    /**
     * 输出行数
     */
    private Long writeCount;

    /** 端口产生行数 JSON；NULL 表示旧记录或统计未完成。 */
    private String portCountsJson;

    /** 文件节点在图结束后的发布或清理状态。 */
    private String fileOutputState;

}
