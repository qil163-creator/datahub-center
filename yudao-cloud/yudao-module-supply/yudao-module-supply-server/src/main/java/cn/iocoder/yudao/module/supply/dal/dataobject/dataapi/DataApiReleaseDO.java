package cn.iocoder.yudao.module.supply.dal.dataobject.dataapi;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.supply.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName(value = "dh_supply_api_release", autoResultMap = true)
@KeySequence("dh_supply_api_release_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataApiReleaseDO extends BaseDO {

    @TableId
    private Long id;

    private Long apiId;

    private Integer revision;

    private String apiVersion;

    @TableField(value = "config_json", typeHandler = JsonbStringTypeHandler.class)
    private String configJson;

    private String configHash;

    private String contractHash;

    private String testEvidenceHash;

    private String publisher;

    private LocalDateTime publishTime;

}
