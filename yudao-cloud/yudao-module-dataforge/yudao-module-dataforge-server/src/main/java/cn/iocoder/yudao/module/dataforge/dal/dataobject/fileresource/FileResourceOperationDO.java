package cn.iocoder.yudao.module.dataforge.dal.dataobject.fileresource;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 远程变更意图及结果：不复制源端文件元数据，也不保存连接凭据。 */
@TableName("dh_dataforge_file_operation")
@KeySequence("dh_dataforge_file_operation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class FileResourceOperationDO extends BaseDO {

    @TableId
    private Long id;

    private Long connectionId;

    private String action;

    private String path;

    private String targetPath;

    private String status;

    private String resultMessage;
}
