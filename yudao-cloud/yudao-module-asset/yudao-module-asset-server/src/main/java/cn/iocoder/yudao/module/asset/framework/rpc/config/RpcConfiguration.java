package cn.iocoder.yudao.module.asset.framework.rpc.config;

import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.QualityWorkOrderApi;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.SensitiveClassificationReferenceApi;
import cn.iocoder.yudao.module.governance.api.standard.StandardElementApi;
import cn.iocoder.yudao.module.governance.api.standard.StandardAssociationApi;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermApi;
import cn.iocoder.yudao.module.modeling.api.registration.RegistrationContextApi;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import cn.iocoder.yudao.module.supply.api.app.SupplyQuotaApi;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * 资产模块依赖的远程服务：元数据/标准数据元（治理）、只读预览（数据源）、
 * 应用/API/来源授权/消费额度（数据服务）、用户与部门（系统）。
 */
@Configuration(value = "assetRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {MetadataApi.class, StandardElementApi.class, StandardAssociationApi.class, StandardTermApi.class,
        DatabaseApi.class, cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi.class, RegistrationContextApi.class,
        SupplyAppApi.class, SupplyDataApiApi.class,
        SupplyGrantSourceApi.class, SupplyQuotaApi.class,
        AdminUserApi.class, DeptApi.class,
        SafetySecurityApi.class, SensitiveClassificationReferenceApi.class,
        cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi.class,
        QualityTaskApi.class, QualityWorkOrderApi.class,
        PermissionApi.class, NotifyMessageSendApi.class})
public class RpcConfiguration {

}
