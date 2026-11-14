package cn.iocoder.yudao.module.dataforge.framework.rpc.config;

import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.infra.api.file.FileApi;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import cn.iocoder.yudao.module.system.api.logger.OperateLogApi;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "dataforgeRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {DeptApi.class, AdminUserApi.class, DictDataApi.class, FileApi.class, cn.iocoder.yudao.module.infra.api.file.ManagedFileApi.class,
        NotifyMessageSendApi.class, cn.iocoder.yudao.module.system.api.sms.SmsSendApi.class,
        cn.iocoder.yudao.module.system.api.mail.MailSendApi.class,
        OperateLogApi.class, BpmProcessInstanceApi.class,
        MetadataApi.class, PermissionApi.class, QualityTaskApi.class,
        cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi.class,
        cn.iocoder.yudao.module.governance.api.lineage.LineageApi.class,
        cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi.class})
public class RpcConfiguration {

}
