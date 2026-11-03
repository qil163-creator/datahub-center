package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 有界隔离证据。原始数据只在经过数据源权限复核的详情接口返回。 */
@TableName("dh_dataforge_etl_quality_issue")
@KeySequence("dh_dataforge_etl_quality_issue_seq")
@Data
public class EtlQualityIssueDO extends BaseDO {

    @TableId
    private Long id;

    private Long batchId;

    private Long rowNumber;

    private String qualityStatus;

    private String rowJson;

    private String violationsJson;

    /** 内部原来源证明；不进入列表或详情 VO。 */
    private String provenanceJson;
}
