package cn.iocoder.yudao.module.supply.framework.rpc.config;

import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;


@Configuration(value = "supplyRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {DatabaseApi.class, NotifyMessageSendApi.class, SafetySecurityApi.class,
        AdminUserApi.class, cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi.class})
public class RpcConfiguration {

}
