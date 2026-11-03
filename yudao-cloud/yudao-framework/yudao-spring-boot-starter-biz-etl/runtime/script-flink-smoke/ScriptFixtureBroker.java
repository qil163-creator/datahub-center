package cn.iocoder.yudao.framework.etl.script.fixture;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.script.*;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 独立受信测试控制面；不接受远程源码登记，也不复用业务任务执行身份。 */
public final class ScriptFixtureBroker {
    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]); var config = JSONUtil.parseObj(Files.readString(directory.resolve("private.json")));
        var classpath = config.getJSONArray("workerClasspath").stream().map(Object::toString).map(Path::of).toList();
        var docker = new EtlScriptWorkerPolicy.Docker(Path.of(config.getStr("docker")), config.getStr("dockerEndpoint"), config.getStr("image"), List.of(), false, 5000, 120000,
                new EtlScriptWorkerPolicy.Recovery(directory.resolve("ledger"), "flink-script-fixture", 32, true, 10000));
        var policy = new EtlScriptWorkerPolicy(true, classpath, 1048576, 8388608, 8388608, 1000, 1000, 65536, 30000, 30000, 192, EtlScriptWorkerPolicy.Mode.DOCKER, docker);
        var runner = new EtlScriptRunner(policy);
        var identity = new EtlNativeScriptBroker.Identity("flink-script-fixture", "isolated-components", "FLINK", config.getStr("clientFingerprint"));
        var resources = new HashMap<String, EtlNativeScriptBroker.ResourcePolicy>();
        for (String name : List.of("bounded", "overflow", "count", "timer", "resume", "replay", "cache", "badtype", "cancel", "unauthorized")) resources.put(name, new EtlNativeScriptBroker.ResourcePolicy(Set.of(identity)));
        try (var broker = new EtlNativeScriptBroker(new InetSocketAddress("0.0.0.0", config.getInt("port")), EtlScriptTls.load("fixture.server.key", "fixture.server.cert", "fixture.ca"), runner, Map.of(), resources, 32, 300000, 128L * 1024 * 1024)) {
            var input = List.of(new EtlNativeScriptProgram.Field("id", "BIGINT", false), new EtlNativeScriptProgram.Field("label", "STRING", true),
                    new EtlNativeScriptProgram.Field("amount", "DECIMAL(30,4)", true), new EtlNativeScriptProgram.Field("day", "DATE", true), new EtlNativeScriptProgram.Field("moment", "TIMESTAMP(9)", true));
            var fields = List.of(new EtlFieldContract("id", "Long", false, null, null, null), new EtlFieldContract("label", "String", true, null, null, null),
                    new EtlFieldContract("amount", "Decimal", true, 30, 4, null), new EtlFieldContract("day", "Date", true, null, null, null), new EtlFieldContract("moment", "Date", true, null, null, null));
            var names = new HashMap<String,String>();
            for (String name : resources.keySet()) {
                String source = "import java.util.*;import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript;public class Batch implements EtlJavaScript {public List<Map<String,Object>> execute(List<Map<String,Object>> rows)throws Exception {"
                        + (name.equals("cancel") ? "Thread.sleep(25000);" : "")
                        + "var out=new ArrayList<Map<String,Object>>();for(var row:rows){var copy=new LinkedHashMap<String,Object>(row);copy.put(\"label\",\"batch=\"+rows.size()+\"|\"+row.get(\"label\"));"
                        + (name.equals("badtype") ? "copy.put(\"id\",1.5D);" : "") + "out.add(copy);}return out;}}";
                if (name.equals("bounded")) source = "public class Batch implements tech.qiantong.qdata.common.core.base.EtlBase {public String execute(com.alibaba.fastjson2.JSONObject data,Object logParams) {var rows=data.getJSONArray(\"data\");for(Object value:rows){var row=(com.alibaba.fastjson2.JSONObject)value;row.put(\"label\",\"batch=\"+rows.size()+\"|\"+row.get(\"label\"));}return \"1\";}}";
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var grant = new EtlNativeScriptBroker.Grant(identity.task(), identity.workerGroup(), identity.engine(), identity.certificateSha256(), hash, source, name.equals("bounded") ? EtlScriptRunner.Language.QDATA_JAVA : EtlScriptRunner.Language.JAVA, fields, fields);
                var version = broker.prepareVersion(name, "fixture-v1", grant); broker.activateVersions(List.of(version)); names.put(version.bindingId(), name);
                var binding = new EtlScriptBrokerClient.Binding("https://host.docker.internal:" + config.getInt("port") + "/v1/script", version.epoch(), version.bindingId(), hash,
                        identity.task(), identity.workerGroup(), "FLINK", name.equals("unauthorized") ? "fixture.other.key" : "fixture.client.key", name.equals("unauthorized") ? "fixture.other.cert" : "fixture.client.cert", "fixture.ca");
                boolean bounded = name.equals("bounded") || name.equals("overflow") || name.equals("badtype") || name.equals("unauthorized");
                int maximum = name.equals("overflow") || name.equals("cancel") ? 1 : name.equals("timer") ? 100 : 2;
                long flush = bounded ? 0 : name.equals("resume") ? 5000 : name.equals("timer") ? 500 : 30000;
                var stage = new EtlNativeScriptProgram.Stage(name, "SELECT * FROM fixture_input", "dh_native_script_" + hash.substring(0, 24), input, input, binding, bounded, maximum, flush);
                String remote = config.getStr("remote");
                String initialization = "CREATE TEMPORARY TABLE fixture_output (`id` BIGINT, `label` STRING, `amount` DECIMAL(30,4), `day` DATE, `moment` TIMESTAMP(9)) WITH ('connector'='filesystem','path'='" + remote + "/" + name + "-sql-output','format'='json','json.timestamp-format.standard'='ISO-8601')";
                String query = "SELECT CAST(`id` AS BIGINT) AS `id`,CAST(`label` AS STRING) AS `label`,CAST(`amount` AS DECIMAL(30,4)) AS `amount`,CAST(`day` AS DATE) AS `day`,CAST(`moment` AS TIMESTAMP(9)) AS `moment` FROM (VALUES (9007199254740993,'河道 O''Reilly😀',CAST('12345678901234567890123456.7800' AS DECIMAL(30,4)),'2026-11-02','2026-11-02 12:34:56.123456789'),(9007199254740994,CAST(NULL AS STRING),CAST(NULL AS DECIMAL(30,4)),CAST(NULL AS STRING),CAST(NULL AS STRING))) AS v(`id`,`label`,`amount`,`day`,`moment`)";
                var sqlStage = new EtlNativeScriptProgram.Stage(name, query, stage.view(), input, input, binding, bounded, maximum, flush);
                var program = new EtlNativeScriptProgram("FLINK", !bounded, 1, List.of(initialization), List.of(sqlStage), List.of("INSERT INTO fixture_output SELECT * FROM " + stage.view()));
                Files.write(directory.resolve(name + ".args"), program.argumentChunks());
            }
            broker.start(); Files.writeString(directory.resolve("ready"), "ready");
            Runtime.getRuntime().addShutdownHook(new Thread(broker::close));
            while (!Files.exists(directory.resolve("stop"))) {
                var summary = new TreeMap<String,Object>(); summary.put("activeWorkers", runner.activeWorkers());
                var member = EtlNativeScriptBroker.class.getDeclaredField("sessions"); member.setAccessible(true);
                var sessions = (Map<?,?>) member.get(broker); var values = new ArrayList<Map<String,Object>>();
                synchronized (broker) {
                    for (Object session : sessions.values()) {
                        var item = new LinkedHashMap<String,Object>();
                        for (String key : List.of("id", "binding", "sequence", "acknowledged", "closed", "inFlight")) {
                            var field = session.getClass().getDeclaredField(key); field.setAccessible(true); item.put(key, field.get(session));
                        }
                        item.put("case", names.get(item.get("binding"))); values.add(item);
                    }
                }
                summary.put("sessions", values); Files.writeString(directory.resolve("status.json"), JSONUtil.toJsonStr(summary)); Thread.sleep(100);
            }
        }
    }
}
