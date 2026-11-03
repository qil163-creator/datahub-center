package cn.iocoder.yudao.module.dataforge.dal.dataobject.job;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
/** 一次作业运行内的冻结节点及经过双向核对的真实实例身份。 */
@Data @EqualsAndHashCode(callSuper=true)
@TableName("dh_dataforge_job_node_run") @KeySequence("dh_dataforge_job_node_run_seq")
public class JobNodeRunDO extends BaseDO {
    @TableId private Long id;
    private Long jobRecordId; private String nodeId; private String kind; private Long taskId; private String taskName;
    private Long taskVersion; private Long platformRecordId; private Long dsProjectCode;
    private Long dsDefinitionCode; private Long parentTaskCode; private Integer parentTaskInstanceId;
    private Integer dsInstanceId; private String status;
}
