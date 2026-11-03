package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 不可变发布与恢复记录；历史快照从不被当前草稿覆盖。 */
@TableName("dh_dataforge_etl_version")
@KeySequence("dh_dataforge_etl_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class EtlVersionDO extends BaseDO {

    @TableId
    private Long id;

    private Long pipelineId;

    private Integer revision;

    private Long releaseGeneration;

    private String eventType;

    private Long sourceVersionId;

    private Long actorId;

    private String reason;

    private String contentHash;

    private String configJson;
}
