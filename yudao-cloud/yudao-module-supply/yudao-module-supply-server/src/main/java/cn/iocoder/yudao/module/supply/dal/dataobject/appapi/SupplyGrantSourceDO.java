package cn.iocoder.yudao.module.supply.dal.dataobject.appapi;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.supply.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 来源账本包含撤销墓碑；不得通过通用删除接口回收来源身份。 */
@TableName(value = "dh_supply_grant_source", autoResultMap = true)
@KeySequence("dh_supply_grant_source_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplyGrantSourceDO extends BaseDO {

    @TableId
    private Long id;

    private String sourceSystem;

    private String sourceKey;

    private Long appId;

    private Long apiId;

    private Long sourceRevision;

    private String sourceStatus;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String scopeJson;

    private String payloadHash;

    private String compatibilityKey;
}
