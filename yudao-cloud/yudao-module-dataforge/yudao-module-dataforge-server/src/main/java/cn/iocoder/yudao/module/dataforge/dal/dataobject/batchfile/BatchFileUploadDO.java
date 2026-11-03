package cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 批量文件入库的素材持久状态。 */
@TableName("dh_batch_file_upload")
@KeySequence("dh_batch_file_upload_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchFileUploadDO extends BaseDO {

    @TableId
    private Long id;

    private Long ownerId;

    private String operationKey;

    private Long storageFileId;

    private String name;

    private String fileType;

    private String source;

    private Long connectionId;

    private String path;

    private Long size;

    private String sha256;

    private String state;

    private Integer rowCount;

    private String fieldsJson;

    private Long taskId;

    private java.time.LocalDateTime expiresAt;
}
