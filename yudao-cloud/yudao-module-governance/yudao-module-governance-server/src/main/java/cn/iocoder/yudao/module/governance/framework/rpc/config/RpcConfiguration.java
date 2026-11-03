package cn.iocoder.yudao.module.governance.framework.rpc.config;

import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.migration.SyncBatchEvidenceApi;
import cn.iocoder.yudao.module.infra.api.file.FileApi;
import cn.iocoder.yudao.module.modeling.api.model.ModelFieldApi;
import cn.iocoder.yudao.module.modeling.api.model.ModelRuleContextApi;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.governance.framework.rpc.MasterDistributeAlertNotifyClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "standardRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi.class, FileApi.class, DatabaseApi.class, AdminUserApi.class, DeptApi.class,
        NotifyMessageSendApi.class, BpmProcessInstanceApi.class, ModelFieldApi.class, ModelRuleContextApi.class, PermissionApi.class,
        SyncBatchEvidenceApi.class, MasterDistributeAlertNotifyClient.class,
        cn.iocoder.yudao.module.asset.api.sensitive.AssetSensitivePolicyApi.class,
        cn.iocoder.yudao.module.system.api.user.AdminUserOptionApi.class})
public class RpcConfiguration {

}
