package cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

@TableName(value = "dh_governance_standard_code_value_mapping", autoResultMap = true)
@KeySequence("dh_governance_standard_code_value_mapping_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardCodeMappingDO extends BaseDO {

    @TableId
    private Long id;

    private Integer codeSetId;

    private String originalValue;

    private Integer codeItemId;

    private Long revision;
}
