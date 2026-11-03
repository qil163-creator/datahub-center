package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

@TableName(value = "dh_governance_meta_file", autoResultMap = true)
@KeySequence("dh_governance_meta_file_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFileDO extends BaseDO {

    /** 可信离线导入的展示快照，不接受浏览器维护，不参与授权或连接执行。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler.class,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String displaySnapshot;

    @TableId
    private Long id;

    private Long datasourceId;

    private String datasourceName;

    private Long sourceSystemId;

    private String sourceSystemName;

    private Long taskId;

    private Long instanceId;

    private String path;

    private String parentPath;

    private String name;

    private String fileType;

    private Long size;

    private java.time.LocalDateTime sourceModifiedTime;

    private java.time.LocalDateTime firstSeenTime;

    private java.time.LocalDateTime lastSeenTime;

    private Integer objectStatus;

    private String businessName;

    private String description;

    private Long ownerUserId;

    private Long techLeaderUserId;

    private String techLeaderPhone;

    private Long businessLeaderUserId;

    private String businessLeaderPhone;

    private Integer bizRevision;
}
