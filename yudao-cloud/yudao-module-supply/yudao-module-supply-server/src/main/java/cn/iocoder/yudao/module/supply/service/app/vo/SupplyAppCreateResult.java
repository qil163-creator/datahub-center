package cn.iocoder.yudao.module.supply.service.app.vo;

import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 应用创建结果：secret 只在创建响应中返回一次，落库值始终加密保存。
 */
@Data
@AllArgsConstructor
public class SupplyAppCreateResult {

    private SupplyAppDO app;

    private String plainSecret;

}
