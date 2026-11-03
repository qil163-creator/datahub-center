package cn.iocoder.yudao.module.supply.dal.dataobject.dataapi;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 台账永久占用业务键；未知结果禁止自动再次执行。按应用与接口的业务键隔离。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_supply_api_operation")
@KeySequence("dh_supply_api_operation_seq")
public class DataApiOperationDO extends BaseDO {

    @TableId
    private Long id;

    private Long appId;

    private Long apiId;

    private Long releaseId;

    private String operationKey;

    private String requestHash;

    private String state;

    private String resultJson;

    private LocalDateTime leaseUntil;

    private String traceId;

    private Long resolvedBy;

    private String resolution;

    private String evidence;

    private LocalDateTime resolvedAt;
}
