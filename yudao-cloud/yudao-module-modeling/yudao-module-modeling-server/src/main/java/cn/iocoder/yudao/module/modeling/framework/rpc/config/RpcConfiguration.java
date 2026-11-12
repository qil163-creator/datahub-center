package cn.iocoder.yudao.module.modeling.framework.rpc.config;

import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.standard.StandardElementApi;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "modelingRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {AdminUserApi.class, DictDataApi.class, DatabaseApi.class, StandardElementApi.class,
        MetadataApi.class, cn.iocoder.yudao.module.governance.api.standard.StandardReferenceApi.class,
        cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi.class,
        cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi.class})
public class RpcConfiguration {

}
