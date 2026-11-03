package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeScriptCompiler;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedHttp;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.io.*;

/** 使用真实任务证书、Docker worker 和认证接口验证运行门禁；不输出令牌或私钥。 */
public final class NativeScriptBrokerProbe {
    private static void require(boolean value){if(!value)throw new IllegalStateException("broker实际验收失败");}
    private static void denied(Runnable action){try{action.run();throw new AssertionError("预期拒绝");}catch(IllegalStateException expected){}}
    public static void main(String[] arguments)throws Exception {
        var config=JSONUtil.parseObj(Files.readString(Path.of(arguments[0])));var policy=config.getJSONObject("scriptConfiguration").getJSONObject("worker-policy");var docker=policy.getJSONObject("docker");var recovery=docker.getJSONObject("recovery");
        var p=new EtlScriptWorkerPolicy(true,policy.getJSONArray("classpathEntries").toList(String.class).stream().map(Path::of).toList(),1048576,8388608,8388608,1000,1000,65536,30000,30000,256,EtlScriptWorkerPolicy.Mode.DOCKER,new EtlScriptWorkerPolicy.Docker(Path.of(docker.getStr("executable")),docker.getStr("endpoint"),docker.getStr("image"),List.of(),false,5000,60000,new EtlScriptWorkerPolicy.Recovery(Path.of(recovery.getStr("directory")),recovery.getStr("serviceId"),16,true)));
        var flow=JSONUtil.parseObj(Files.readString(Path.of(config.getStr("flowFile"))));var graph=JSONUtil.parseObj(flow.getStr("flowJson"));String source=graph.getJSONArray("nodes").stream().map(JSONUtil::parseObj).filter(node->"token".equals(node.getStr("id"))).findFirst().orElseThrow().getJSONObject("properties").getJSONObject("nodeJson").getStr("code");
        String cert=config.getJSONObject("fingerprints").getStr("task1004");var identity=new EtlNativeScriptBroker.Identity("1004","datahub-development","SPARK",cert);var grant=new EtlNativeScriptBroker.Grant("1004","datahub-development","SPARK",cert,EtlNativeScriptCompiler.sha(source),source,EtlScriptRunner.Language.QDATA_TOKEN,List.of(),List.of());
        var runner=new EtlScriptRunner(p);String run=UUID.randomUUID().toString();
        try(var broker=new EtlNativeScriptBroker(new InetSocketAddress("127.0.0.1",18449),EtlScriptTls.load("script.server.key","script.server.cert","script.ca"),runner,Map.of(),Map.of("script1004",new EtlNativeScriptBroker.ResourcePolicy(Set.of(identity))),16,300000,33554432)){
            broker.start();var described=broker.describeVersion("script1004","flow1",grant);var binding=binding(broker,described,grant,"script1004.key","script1004.cert",broker.epoch(),"1004");
            denied(()->new EtlScriptBrokerClient(binding,run,"token",null));
            var version=broker.prepareVersion("script1004","flow1",grant);denied(()->new EtlScriptBrokerClient(binding,run,"token",null));
            String endpoint="http://127.0.0.1:19087/token-rows";
            broker.prepareHttpResource(version,"componentTokenHttp","a".repeat(64),request->{require(request.endpoint().equals(endpoint)&&request.method().equals("GET")&&request.maximumBytes()<=8388608);var result=EtlManagedHttp.exchange(endpoint,Set.of(endpoint),request.method(),request.query(),request.headers(),request.body(),request.maximumBytes());return new EtlScriptAuthenticatedHttp.Response(200,result.contentType(),result.bytes());});
            broker.activateVersions(List.of(version));String context;String session;
            try(var client=new EtlScriptBrokerClient(binding,run,"token",null)){
                var response=client.execute(0,List.of());require(response.rows().isEmpty()&&response.authenticationContext().matches("[a-f0-9-]{36}"));context=response.authenticationContext();session=client.sessionReference();
                require(client.execute(0,List.of()).authenticationContext().equals(context));
                denied(()->client.execute(0,List.of(Map.of())));
            }
            String descriptor=EtlScriptAuthenticatedHttp.encode(new EtlScriptAuthenticatedHttp.Context(binding,run,Map.of("login",context)));
            var result=EtlScriptAuthenticatedHttp.exchange(descriptor,new EtlScriptAuthenticatedHttp.Request("componentTokenHttp",endpoint,"GET",Map.of(),Map.of(),new byte[0],8388608,30000),Map.of("Authorization",new EtlScriptAuthenticatedHttp.HeaderReference("login","Bearer ")));
            require(result.status()==200&&JSONUtil.parseObj(new String(result.body(),java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("data").getJSONArray("rows").size()==2);
            denied(()->new EtlScriptBrokerClient(binding(broker,version,grant,"otherTask.key","otherTask.cert",broker.epoch(),"1004"),"other-run","token",null));
            denied(()->new EtlScriptBrokerClient(binding(broker,version,grant,"script1004.key","script1004.cert",UUID.randomUUID().toString(),"1004"),"other-run","token",null));
            denied(()->new EtlScriptBrokerClient(binding(broker,version,grant,"script1004.key","script1004.cert",broker.epoch(),"1003"),"other-run","token",null));
            denied(()->new EtlScriptBrokerClient(new EtlScriptBrokerClient.Binding(binding.endpoint(),binding.epoch(),binding.bindingId(),binding.sourceHash(),binding.task(),"other-group",binding.engine(),binding.privateKeyRef(),binding.certificateRef(),binding.authorityRef()),"other-run","token",null));
            denied(()->new EtlScriptBrokerClient(new EtlScriptBrokerClient.Binding(binding.endpoint(),binding.epoch(),binding.bindingId(),binding.sourceHash(),binding.task(),binding.workerGroup(),"FLINK",binding.privateKeyRef(),binding.certificateRef(),binding.authorityRef()),"other-run","token",null));
            denied(()->EtlScriptAuthenticatedHttp.exchange(EtlScriptAuthenticatedHttp.encode(new EtlScriptAuthenticatedHttp.Context(binding,"wrong-run",Map.of("login",context))),new EtlScriptAuthenticatedHttp.Request("componentTokenHttp",endpoint,"GET",Map.of(),Map.of(),new byte[0],8388608,30000),Map.of("Authorization",new EtlScriptAuthenticatedHttp.HeaderReference("login","Bearer "))));
            var next=broker.prepareVersion("script1004","flow2",grant);broker.activateVersions(List.of(next));denied(()->new EtlScriptBrokerClient(binding,"new-run","token",null));
            try(var resumed=new EtlScriptBrokerClient(binding,run,"token",session)){require(resumed.execute(0,List.of()).authenticationContext().equals(context));}
            var report=new LinkedHashMap<String,Object>();for(String key:List.of("realMutualTls","tokenToAuthenticatedApi","exactCachedReplay","mismatchedReplayDenied","inactiveAndReadOnlyDenied","otherTaskCertificateDenied","wrongTaskAndRunDenied","wrongGroupAndEngineDenied","staleEpochDenied","oldVersionNewRunDeniedExistingRunRetained"))report.put(key,true);report.put("status","PASS");report.put("apiRows",2);System.out.println(JSONUtil.toJsonStr(report));
        }
    }
    private static EtlScriptBrokerClient.Binding binding(EtlNativeScriptBroker broker,EtlNativeScriptBroker.PreparedVersion version,EtlNativeScriptBroker.Grant grant,String key,String cert,String epoch,String task){return new EtlScriptBrokerClient.Binding("https://localhost:18449/v1/script",epoch,version.bindingId(),grant.sourceSha256(),task,"datahub-development","SPARK",key,cert,"script.ca");}
}
