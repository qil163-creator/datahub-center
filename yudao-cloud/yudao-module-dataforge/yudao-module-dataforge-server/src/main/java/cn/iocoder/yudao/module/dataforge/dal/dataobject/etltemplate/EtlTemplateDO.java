package cn.iocoder.yudao.module.dataforge.dal.dataobject.etltemplate;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("dh_dataforge_etl_template")
@KeySequence("dh_dataforge_etl_template_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class EtlTemplateDO extends BaseDO {

    @TableId
    private Long id;

    private String name;

    private String description;

    private Long ownerUserId;

    private Integer nodeCount;

    private String requestId;

    private String contentHash;

    /** 结构化 JSON 以 text 保存，Hash 按不可变原文校验。 */
    private String configJson;
}
