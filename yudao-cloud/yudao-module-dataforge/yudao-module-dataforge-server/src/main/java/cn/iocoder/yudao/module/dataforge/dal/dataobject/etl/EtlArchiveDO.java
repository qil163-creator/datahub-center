package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dh_dataforge_etl_archive")
@KeySequence("dh_dataforge_etl_archive_seq")
public class EtlArchiveDO extends BaseDO {

    @TableId
    private Long id;

    private Long recordId;

    private Long actorId;

    private String reason;

    private LocalDateTime archivedAt;

    private LocalDateTime evidenceAccessUntil;

    private LocalDateTime restoredAt;

    private Long restoredBy;
}
