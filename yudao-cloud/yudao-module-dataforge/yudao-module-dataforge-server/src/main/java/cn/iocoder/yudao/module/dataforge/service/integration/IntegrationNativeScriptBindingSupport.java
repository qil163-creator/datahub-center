package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeScriptCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedHttp;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.script.*;
import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** 领域薄适配：任务资源与秘密引用授权、发布事务衔接；用户代码只交给组件隔离 worker。 */
@Component
public class IntegrationNativeScriptBindingSupport {
    public enum Purpose { CHECK, RUN, PUBLICATION }
    public record Prepared(EtlNativeScriptCompiler.Plan plan,List<EtlNativeScriptBroker.PreparedVersion> versions) { }
    @Resource private IntegrationNativeScriptBindingsProperties properties;
    @Resource private IntegrationRuntimeProperties runtime;
    @Resource private IntegrationDataxBindingsProperties httpBindings;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<EtlNativeScriptBroker> brokers;

    public Prepared compile(IntegrationTaskDO task,List<EtlNodeDef> nodes,List<EtlEdgeDef> edges,
            EtlNativeSqlCompiler.Tables tables,String initializationSql,List<EtlNodeDef> originalNodes,Purpose purpose) {
        if(!properties.isEnabled()) throw invalid("原生隔离脚本执行尚未由管理员启用");
        for (var node : originalNodes) if ("custom-token".equals(node.getToolCode()) && edges.stream().noneMatch(edge -> node.getNo().equals(edge.getTargetNo()))) {
            Set<String> targets = "FLINK".equals(task.getEngine()) ? Set.of("read-db", "read-kafka", "read-tdengine") : Set.of("rest-api");
            for (var edge : edges) if (node.getNo().equals(edge.getSourceNo()) && originalNodes.stream().noneMatch(target -> target.getNo().equals(edge.getTargetNo()) && targets.contains(target.getToolCode())))
                throw invalid("初始化认证脚本必须直接控制当前引擎已开放的数据源启动");
        }
        var broker=brokers.getIfAvailable(); if(broker==null) throw invalid("受管脚本 broker 不可用");
        String publication=publication(task,originalNodes,edges); var versions=new ArrayList<EtlNativeScriptBroker.PreparedVersion>();
        try {
            var config=JSONUtil.parseObj(task.getEngineConfigJson()); int parallelism=config.getInt("parallelism",1);
            var plan=EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.valueOf(task.getEngine()),"REALTIME".equals(task.getMode()),parallelism,nodes,edges,tables,initializationSql,definition->{
                var resource=properties.getResources().get(definition.resource());
                if(resource==null||definition.maximumRows()>resource.getMaximumRows()||definition.flushMillis()>resource.getMaximumFlushMillis()) throw invalid("当前任务无权使用该脚本资源或批次预算超限");
                var identities=resource.getIdentities().stream().filter(identity->Objects.equals(identity.getTaskId(),task.getId())&&Objects.equals(identity.getWorkerGroup(),task.getWorkerGroup())&&Objects.equals(identity.getEngine(),task.getEngine())).toList();
                if(identities.size()!=1) throw invalid("脚本资源必须唯一绑定当前任务、工作组和引擎证书");
                var identity=identities.getFirst();
                for(String ref:List.of(identity.getPrivateKeyRef(),identity.getCertificateRef(),identity.getAuthorityRef())) requireSecret(task,ref);
                var grant=new EtlNativeScriptBroker.Grant(String.valueOf(task.getId()),task.getWorkerGroup(),task.getEngine(),identity.getCertificateSha256(),EtlNativeScriptCompiler.sha(definition.source()),definition.source(),definition.language(),EtlNativeScriptCompiler.contracts(definition.inputFields()),EtlNativeScriptCompiler.contracts(definition.outputFields()));
                var version=purpose==Purpose.PUBLICATION?broker.prepareVersion(definition.resource(),publication,grant):broker.describeVersion(definition.resource(),publication,grant);
                if(purpose==Purpose.RUN&&!broker.active(version)) throw invalid("脚本发布版本已失效，请正常保存发布后重试；broker重启不会重放旧源码");
                if(purpose==Purpose.PUBLICATION) { versions.add(version); prepareHttp(task,originalNodes,resource,broker,version); if (resource.getWorkerHttpLanguages().contains(definition.language())) prepareWorkerHttp(task,resource,broker,version); }
                return new EtlScriptBrokerClient.Binding(properties.getEndpoint(),version.epoch(),version.bindingId(),grant.sourceSha256(),grant.task(),grant.workerGroup(),grant.engine(),identity.getPrivateKeyRef(),identity.getCertificateRef(),identity.getAuthorityRef());
            });
            return new Prepared(plan,List.copyOf(versions));
        } catch(RuntimeException failure){if(purpose==Purpose.PUBLICATION)broker.discardPrepared(versions);throw failure;}
    }

    /** 调用方须在成功保存的 afterCommit 激活；回滚路径只删除未激活准备版本。 */
    public void complete(Prepared prepared){brokers.getObject().activateVersions(prepared.versions());}
    public void abort(Prepared prepared){brokers.getObject().discardPrepared(prepared.versions());}
    public NativeTaskParameters.Resource launcher(String engine){
        var launcher=properties.getLaunchers().get(engine);if(launcher==null)throw invalid("当前引擎尚未绑定受管脚本 launcher 资源");
        return new NativeTaskParameters.Resource(launcher.getResourceName(),launcher.getWorkerRelativePath());
    }
    private void requireSecret(IntegrationTaskDO task,String ref){
        var binding=runtime.getManagedSecretBindings().get(ref);
        if(binding==null||!binding.getAllowedTaskIds().contains(task.getId())||!binding.getWorkerGroups().contains(task.getWorkerGroup())||!binding.getEngines().contains(task.getEngine()))throw invalid("脚本证书引用未获当前任务、工作组及引擎授权");
    }
    private void prepareHttp(IntegrationTaskDO task,List<EtlNodeDef> nodes,IntegrationNativeScriptBindingsProperties.ResourceBinding scriptResource,EtlNativeScriptBroker broker,EtlNativeScriptBroker.PreparedVersion version){
        for(var node:nodes)if("rest-api".equals(node.getToolCode())){
            var config=JSONUtil.parseObj(node.getNodeJson());var refs=config.getJSONObject("headerContextRefs");if(refs==null||refs.isEmpty())continue;
            String resource=config.getStr("resourceBindingId","");if(!scriptResource.getHttpResourceRefs().contains(resource))throw invalid("脚本资源未获授权向该接口提供认证上下文");
            var binding=httpBindings.require(task.getId(),"rest-api",null,resource);var api=config.getJSONObject("apiConfig");
            String endpoint=api.getStr("requestUrl"),method=api.getStr("requestMethod");var addresses=Set.copyOf(binding.addresses());
            if(!addresses.contains(endpoint)||!Set.of("GET","POST").contains(method))throw invalid("脚本接口地址或方法未获真实资源绑定授权");
            int budget=config.getInt("maxResponseBytes",1048576);if(budget<1||budget>16777216)throw invalid("脚本接口响应预算无效");
            String hash=EtlNativeScriptCompiler.sha(JSONUtil.toJsonStr(Map.of("endpoint",endpoint,"method",method,"addresses",new TreeSet<>(addresses),"budget",budget)));
            broker.prepareHttpResource(version,resource,hash,request->{
                if(!endpoint.equals(request.endpoint())||!method.equals(request.method())||request.maximumBytes()>budget||request.timeoutMillis()>30000)throw invalid("脚本接口请求不符合已发布资源策略");
                var response=EtlManagedHttp.exchange(endpoint,addresses,method,request.query(),request.headers(),request.body(),request.maximumBytes());
                return new EtlScriptAuthenticatedHttp.Response(200,response.contentType(),response.bytes());
            });
        }
    }
    private void prepareWorkerHttp(IntegrationTaskDO task,IntegrationNativeScriptBindingsProperties.ResourceBinding scripts,EtlNativeScriptBroker broker,EtlNativeScriptBroker.PreparedVersion version){
        scripts.getWorkerHttpResources().forEach((id,policy)->{
            var binding=httpBindings.require(task.getId(),"rest-api",null,id);var endpoints=Set.copyOf(binding.addresses());
            policy.getHeaderSecretRefs().values().forEach(ref->requireSecret(task,ref));
            String hash=EtlNativeScriptCompiler.sha(JSONUtil.toJsonStr(canonical(Map.of("endpoints",new TreeSet<>(endpoints),"methods",new TreeSet<>(policy.getMethods()),"bytes",policy.getMaximumBytes(),"timeout",policy.getTimeoutMillis(),"secrets",policy.getHeaderSecretRefs()))));
            broker.prepareWorkerHttpResource(version,id,hash,endpoints,policy.getMethods(),policy.getMaximumBytes(),policy.getTimeoutMillis(),request->{
                var headers=new LinkedHashMap<>(request.headers());
                policy.getHeaderSecretRefs().forEach((header,ref)->{if(headers.keySet().stream().anyMatch(name->name.equalsIgnoreCase(header)))throw invalid("隔离脚本请求头与管理员秘密请求头冲突");headers.put(header,cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets.readFromEnvironment(ref));});
                var response=EtlManagedHttp.exchange(request.endpoint(),endpoints,request.method(),request.query(),headers,request.body(),request.maximumBytes(),request.timeoutMillis());
                return new EtlScriptWorkerHttp.Response(200,response.contentType(),response.bytes());
            });
        });
    }
    private static String publication(IntegrationTaskDO task,List<EtlNodeDef> nodes,List<EtlEdgeDef> edges){
        var canonicalNodes=nodes.stream().map(node->Map.of("id",node.getNo(),"code",node.getToolCode(),"config",canonical(JSONUtil.parseObj(node.getNodeJson())))).toList();
        var canonicalEdges=edges.stream().map(edge->Map.of("source",edge.getSourceNo(),"output",edge.resolveOutputPort(),"target",edge.getTargetNo(),"input",Objects.toString(edge.getInputPort(),""),"type",edge.isControl()?"CONTROL":"DATA")).toList();
        return EtlNativeScriptCompiler.sha(JSONUtil.toJsonStr(Map.of("nodes",canonicalNodes,"edges",canonicalEdges,"task",task.getId(),"engine",task.getEngine(),"worker",task.getWorkerGroup(),"mode",task.getMode(),"engineConfig",canonical(JSONUtil.parseObj(task.getEngineConfigJson())))));
    }
    private static Object canonical(Object value){if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((key,item)->sorted.put(String.valueOf(key),canonical(item)));return sorted;}if(value instanceof Iterable<?> values){var result=new ArrayList<Object>();values.forEach(item->result.add(canonical(item)));return result;}return value;}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
