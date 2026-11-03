package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 元数据外部登记的幂等收据；与元数据变更在同一事务提交。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_meta_ingest_event")
@KeySequence("dh_meta_ingest_event_seq")
public class MetaIngestEventDO extends BaseDO {

    @TableId
    private Long id;

    private String source;

    private String eventId;

    private String targetKey;

    private Long revision;

    private String payloadHash;

    private Long tableId;

    private String status;

    private LocalDateTime occurredAt;
}
