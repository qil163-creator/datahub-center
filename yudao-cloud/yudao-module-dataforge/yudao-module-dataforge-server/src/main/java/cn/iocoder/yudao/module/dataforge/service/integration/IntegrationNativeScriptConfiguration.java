package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.script.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.InetSocketAddress;
import java.util.*;

/** broker 是平台内受信发布控制面；HTTP 仅提供 mTLS 运行协议，没有远程源码登记入口。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="dataforge.integration.native-scripts",name="enabled",havingValue="true")
public class IntegrationNativeScriptConfiguration {
    @Bean(initMethod="start",destroyMethod="close")
    public EtlNativeScriptBroker integrationNativeScriptBroker(IntegrationNativeScriptBindingsProperties properties) throws java.io.IOException {
        var policies=new HashMap<String,EtlNativeScriptBroker.ResourcePolicy>();
        properties.getResources().forEach((id,resource)->{
            var identities=new HashSet<EtlNativeScriptBroker.Identity>();
            resource.getIdentities().forEach(identity->identities.add(new EtlNativeScriptBroker.Identity(String.valueOf(identity.getTaskId()),identity.getWorkerGroup(),identity.getEngine(),identity.getCertificateSha256())));
            policies.put(id,new EtlNativeScriptBroker.ResourcePolicy(identities));
        });
        return new EtlNativeScriptBroker(new InetSocketAddress(properties.getListenHost(),properties.getListenPort()),
                EtlScriptTls.load(properties.getServerPrivateKeyRef(),properties.getServerCertificateRef(),properties.getAuthorityRef()),
                new EtlScriptRunner(properties.getWorkerPolicy().toPolicy()),Map.of(),policies,properties.getMaximumSessions(),properties.getSessionLifetimeMillis(),properties.getCacheBudgetBytes());
    }
}
