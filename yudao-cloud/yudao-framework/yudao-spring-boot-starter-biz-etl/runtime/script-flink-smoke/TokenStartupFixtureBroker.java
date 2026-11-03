package cn.iocoder.yudao.framework.etl.script.fixture;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.script.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 独立mTLS身份的Token启动脚本，随后仅运行真正的JDBC有限源和表输出。 */
public final class TokenStartupFixtureBroker {
    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]); var config = JSONUtil.parseObj(Files.readString(directory.resolve("private.json")));
        var classpath = config.getJSONArray("workerClasspath").stream().map(Object::toString).map(Path::of).toList();
        var docker = new EtlScriptWorkerPolicy.Docker(Path.of(config.getStr("docker")), config.getStr("dockerEndpoint"), config.getStr("image"), List.of(), false, 5000, 120000,
                new EtlScriptWorkerPolicy.Recovery(directory.resolve("ledger"), "flink-token-fixture", 16, true, 10000));
        var runner = new EtlScriptRunner(new EtlScriptWorkerPolicy(true, classpath, 1048576, 8388608, 8388608, 1000, 1000, 65536, 30000, 30000, 192, EtlScriptWorkerPolicy.Mode.DOCKER, docker));
        var identity = new EtlNativeScriptBroker.Identity("flink-token-fixture", "isolated-components", "FLINK", config.getStr("clientFingerprint"));
        try (var broker = new EtlNativeScriptBroker(new InetSocketAddress("0.0.0.0", config.getInt("port")), EtlScriptTls.load("fixture.server.key", "fixture.server.cert", "fixture.ca"), runner, Map.of(),
                Map.of("token", new EtlNativeScriptBroker.ResourcePolicy(Set.of(identity))), 16, 300000, 64L * 1024 * 1024)) {
            String sentinel = config.getStr("tokenSentinel");
            if (sentinel == null || !sentinel.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Token夹具标记无效");
            String source = "public class StartupToken implements tech.qiantong.qdata.common.core.base.EtlBase {public String execute(com.alibaba.fastjson2.JSONObject data,Object logParams) {if(!data.getJSONArray(\"data\").isEmpty())throw new IllegalStateException(\"initializer must have zero data rows\");writeLog(logParams,\"response=" + sentinel + "\");return \"" + sentinel + "\";}}";
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            var grant = new EtlNativeScriptBroker.Grant(identity.task(), identity.workerGroup(), identity.engine(), identity.certificateSha256(), hash, source, EtlScriptRunner.Language.QDATA_TOKEN, List.of(), List.of());
            var version = broker.prepareVersion("token", "token-startup-v1", grant); broker.activateVersions(List.of(version));
            var jdbc = config.getJSONObject("tokenJdbc"); String url = jdbc.getStr("url");
            if (url == null || !url.matches("jdbc:postgresql://token-postgres:5432/token_qa")) throw new IllegalArgumentException("Token夹具只允许专用网络中的PG");
            String options = "'connector'='jdbc','url'='" + url + "','username'='qa_token','password'='','driver'='org.postgresql.Driver'";
            String shape = " (`id` BIGINT, `label` STRING, `amount` DECIMAL(38,8)) ";
            var initialization = List.of("CREATE TEMPORARY TABLE fixture_input" + shape + "WITH (" + options + ",'table-name'='qa_token.source')",
                    "CREATE TEMPORARY TABLE fixture_output" + shape + "WITH (" + options + ",'table-name'='qa_token.target','sink.buffer-flush.max-rows'='1','sink.max-retries'='0')");
            for (String name : List.of("token", "token-unauthorized")) {
                boolean unauthorized = name.equals("token-unauthorized");
                var binding = new EtlScriptBrokerClient.Binding("https://host.docker.internal:" + config.getInt("port") + "/v1/script", version.epoch(), version.bindingId(), hash,
                        identity.task(), identity.workerGroup(), identity.engine(), unauthorized ? "fixture.other.key" : "fixture.client.key", unauthorized ? "fixture.other.cert" : "fixture.client.cert", "fixture.ca");
                var program = new EtlNativeScriptProgram("FLINK", jdbc.getBool("streaming", false), 1, initialization,
                        List.of(new EtlNativeScriptProgram.Initializer("token", "login", binding)), List.of(), List.of("INSERT INTO fixture_output SELECT id,label,amount FROM fixture_input"));
                Files.write(directory.resolve(name + ".args"), program.argumentChunks());
            }
            broker.start(); Files.writeString(directory.resolve("ready"), "ready"); Runtime.getRuntime().addShutdownHook(new Thread(broker::close));
            while (!Files.exists(directory.resolve("stop"))) {
                var sessionsField = EtlNativeScriptBroker.class.getDeclaredField("sessions"); sessionsField.setAccessible(true); var sessions = (Map<?, ?>) sessionsField.get(broker);
                var values = new ArrayList<Map<String,Object>>();
                synchronized (broker) {
                    for (Object session : sessions.values()) {
                        var item = new LinkedHashMap<String,Object>();
                        for (String key : List.of("sequence", "closed")) { var field = session.getClass().getDeclaredField(key); field.setAccessible(true); item.put(key, field.get(session)); }
                        var context = session.getClass().getDeclaredField("authContext"); context.setAccessible(true);
                        item.put("hasOpaqueContext", Objects.toString(context.get(session), "").matches("[a-f0-9-]{36}")); item.put("case", "token"); values.add(item);
                    }
                }
                Files.writeString(directory.resolve("status.json"), JSONUtil.toJsonStr(Map.of("activeWorkers", runner.activeWorkers(), "sessions", values))); Thread.sleep(100);
            }
        }
    }
}
