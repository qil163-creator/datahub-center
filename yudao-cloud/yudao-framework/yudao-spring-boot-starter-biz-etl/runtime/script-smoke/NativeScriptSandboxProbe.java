package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.json.JSONUtil;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;

/** 验收只输出布尔与技术计数，原始令牌和隔离进程日志均不写出。 */
public final class NativeScriptSandboxProbe {
    private static void require(boolean condition){if(!condition)throw new IllegalStateException("脚本隔离验收失败");}
    public static void main(String[] arguments)throws Exception {
        var config=JSONUtil.parseObj(Files.readString(Path.of(arguments[0])));var policy=config.getJSONObject("scriptConfiguration").getJSONObject("worker-policy");var docker=policy.getJSONObject("docker");var recovery=docker.getJSONObject("recovery");
        var p=new EtlScriptWorkerPolicy(true,policy.getJSONArray("classpathEntries").toList(String.class).stream().map(Path::of).toList(),1048576,8388608,8388608,1000,1000,65536,30000,30000,256,EtlScriptWorkerPolicy.Mode.DOCKER,new EtlScriptWorkerPolicy.Docker(Path.of(docker.getStr("executable")),docker.getStr("endpoint"),docker.getStr("image"),List.of(),false,5000,60000,new EtlScriptWorkerPolicy.Recovery(Path.of(recovery.getStr("directory")),recovery.getStr("serviceId"),16,true)));
        var runner=new EtlScriptRunner(p);runner.initializeRecovery();runner.ensureAvailable();
        String source="public class CrossBatch implements tech.qiantong.qdata.common.core.base.EtlBase { public String execute(com.alibaba.fastjson2.JSONObject data,Object log)throws Exception {if(System.getenv(\"DATAHUB_ETL_SECRET_DIR\")!=null||System.getenv(\"DATAHUB_ETL_SECRET_REFS\")!=null)throw new IllegalStateException(); com.alibaba.fastjson2.JSONArray rows=data.getJSONArray(\"data\");java.math.BigDecimal total=java.math.BigDecimal.ZERO;for(Object item:rows)total=total.add((java.math.BigDecimal)((com.alibaba.fastjson2.JSONObject)item).get(\"amount\"));rows.remove(0);com.alibaba.fastjson2.JSONObject row=new com.alibaba.fastjson2.JSONObject();row.put(\"id\",9007199254740993L);row.put(\"amount\",total);rows.add(row);return \"1\";}}";
        Map<String,Object> isolation;
        try(var session=runner.open(EtlScriptRunner.Language.QDATA_JAVA,source,()->{},()->{throw new IllegalStateException("隔离worker退出未确认");})){
            var list=new ProcessBuilder(docker.getStr("executable"),"--host",docker.getStr("endpoint"),"ps","-q","--filter","label=cn.iocoder.yudao.etl.worker-service="+recovery.getStr("serviceId")).start();String id=new String(list.getInputStream().readAllBytes()).strip();require(list.waitFor()==0&&id.matches("[a-f0-9]{12,64}"));
            var inspect=new ProcessBuilder(docker.getStr("executable"),"--host",docker.getStr("endpoint"),"inspect",id).start();var details=JSONUtil.parseArray(new String(inspect.getInputStream().readAllBytes())).getJSONObject(0);require(inspect.waitFor()==0);var host=details.getJSONObject("HostConfig");
            require(host.getStr("NetworkMode").equals("none")&&host.getBool("ReadonlyRootfs")&&host.getJSONObject("LogConfig").getStr("Type").equals("none"));
            require(details.getJSONObject("Config").getJSONArray("Env").stream().noneMatch(value->value.toString().contains("DATAHUB_ETL_SECRET")));
            require(details.getJSONArray("Mounts").stream().map(JSONUtil::parseObj).allMatch(mount->!mount.getBool("RW")&&p.classpathEntries().stream().anyMatch(path->path.toString().equals(mount.getStr("Source")))));
            var logs=new ProcessBuilder(docker.getStr("executable"),"--host",docker.getStr("endpoint"),"logs",id).start();byte[] logged=logs.getInputStream().readAllBytes();logs.getErrorStream().readAllBytes();logs.waitFor();require(logged.length==0);
            isolation=Map.of("networkNone",true,"readOnly",true,"logDriverNone",true,"childLogsBytes",logged.length,"onlyApprovedReadOnlyJars",true,"noSecretEnvironment",true);
            var result=session.execute(List.of(new LinkedHashMap<>(Map.of("id",1L,"amount",new BigDecimal("123456789012345678.120001"))),new LinkedHashMap<>(Map.of("id",2L,"amount",new BigDecimal("0.000009")))));
            require(result.size()==2&&result.get(1).get("id").equals(9007199254740993L)&&result.get(1).get("amount").equals(new BigDecimal("123456789012345678.120010")));
        }
        var flow=JSONUtil.parseObj(Files.readString(Path.of(config.getStr("flowFile"))));
        String tokenSource=JSONUtil.parseObj(flow.getStr("flowJson")).getJSONArray("nodes").stream().map(JSONUtil::parseObj).filter(node->"token".equals(node.getStr("id"))).findFirst().orElseThrow().getJSONObject("properties").getJSONObject("nodeJson").getStr("code");
        tokenSource=tokenSource.replace("System.err.println(\"response=\"+token);", "System.err.println(\"response=\"+token);new java.io.FileOutputStream(java.io.FileDescriptor.err).write(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));");
        try(var session=runner.open(EtlScriptRunner.Language.QDATA_TOKEN,tokenSource,()->{},()->{throw new IllegalStateException("退出未确认");})){
            var result=session.execute(List.of());require(result.size()==1&&result.getFirst().get("__token") instanceof String);
            var logs=new ProcessBuilder(docker.getStr("executable"),"--host",docker.getStr("endpoint"),"logs",session.containerId()).start();byte[] captured=logs.getInputStream().readAllBytes();logs.getErrorStream().readAllBytes();logs.waitFor();require(captured.length==0);
        }
        System.out.println(JSONUtil.toJsonStr(Map.of("status","PASS","isolation",isolation,"tokenStdoutStderrAndRawFdDiscarded",true,"fullBatchDeleteAppendAndCrossRowDecimal",true,"runnerActiveWorkersAfterClose",runner.activeWorkers())));
    }
}
