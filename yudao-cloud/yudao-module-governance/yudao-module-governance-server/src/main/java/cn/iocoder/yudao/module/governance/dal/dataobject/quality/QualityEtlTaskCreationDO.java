package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityEtlTaskCreationDO extends BaseDO {

    private Long id;

    private String requestId;

    private String requestHash;

    private Long actorId;

    private Long taskId;

    private Long checkObjectId;

    private String taskName;

    private Integer taskStatus;

    private String definitionHash;

    private String targetJson;
}
