import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Standalone release smoke, compiled into a NEW temporary classes directory.
 * Compile and run with a classpath containing only that directory and the exported nine JARs.
 * Arguments: absolute-manifest absolute-docker-executable endpoint pinned-image absolute-local-root daemon-root
 * [--allow-unbounded-swap-for-local-test]. The optional last flag is exclusively for local smoke environments;
 * strict swap admission is the default. Never use this tool against a production/shared business database.
 * No source, rows, raw CLI errors, environment or endpoint are printed on failure. */
public final class VerifyScriptWorkerBundle {
    private static final List<String> ARTIFACTS = List.of("yudao-spring-boot-starter-biz-etl", "yudao-dbswitch-common",
            "hutool-all", "nashorn-core", "asm", "asm-commons", "asm-tree", "asm-analysis", "asm-util");
    private static final long MAX_JAR = 64L * 1024 * 1024, MAX_TOTAL = 256L * 1024 * 1024;
    private static volatile String phase = "arguments";

    public static void main(String[] args) {
        try {
            require(args.length == 6 || args.length == 7 && args[6].equals("--allow-unbounded-swap-for-local-test"));
            boolean strictSwap = args.length == 6;
            phase = "manifest";
            List<Path> entries = bundle(Path.of(args[0]));
            phase = "client-classpath"; verifyClientClasspath(entries);
            phase = "trusted-configuration";
            var docker = new EtlScriptWorkerPolicy.Docker(Path.of(args[1]), args[2], args[3],
                    List.of(new EtlScriptWorkerPolicy.HostPathMapping(Path.of(args[4]), args[5])), strictSwap, 5000);
            var defaults = EtlScriptWorkerPolicy.defaults(true);
            var policy = new EtlScriptWorkerPolicy(true, entries, defaults.maxSourceBytes(), defaults.maxBatchBytes(),
                    defaults.maxResultBytes(), defaults.maxBatchRows(), defaults.maxResultRows(), defaults.maxLogBytes(),
                    defaults.startupTimeoutMillis(), defaults.batchTimeoutMillis(), defaults.heapMegabytes(),
                    EtlScriptWorkerPolicy.Mode.DOCKER, docker);
            var runner = new EtlScriptRunner(policy);
            phase = "admission"; runner.ensureAvailable();
            System.out.println("BUNDLE_VALIDATED artifacts=9 strictSwap=" + strictSwap);
            phase = "java-compile"; require(runner.checkJava(javaSource()).isEmpty()); require(runner.activeWorkers() == 0);
            phase = "java-execute"; String javaId;
            var row = new LinkedHashMap<String, Object>();
            row.put("id", 9_007_199_254_740_993L);
            row.put("decimal", new BigDecimal("123456789012345678901.12345678901234567890"));
            try (var worker = runner.open(EtlScriptRunner.Language.JAVA, javaSource(), () -> { }, VerifyScriptWorkerBundle::unconfirmed)) {
                javaId = worker.containerId(); require(javaId != null && javaId.matches("[a-f0-9]{64}"));
                var first = worker.execute(List.of(row)).getFirst();
                require(first.get("id") instanceof Long && row.get("id").equals(first.get("id")));
                require(first.get("decimal") instanceof BigDecimal && row.get("decimal").equals(first.get("decimal")));
                require(Integer.valueOf(1).equals(first.get("batch")));
                require(Integer.valueOf(2).equals(worker.execute(List.of(row)).getFirst().get("batch")));
            }
            phase = "java-cleanup"; require(runner.activeWorkers() == 0); assertContainerAbsent(docker, javaId);
            phase = "js-execute"; String jsId;
            String js = "var batch=0; function execute(rows) { rows[0].batch=++batch; rows[0].value+=2; return rows; }";
            try (var worker = runner.open(EtlScriptRunner.Language.JS, js, () -> { }, VerifyScriptWorkerBundle::unconfirmed)) {
                jsId = worker.containerId(); require(jsId != null && jsId.matches("[a-f0-9]{64}"));
                var first = worker.execute(List.of(Map.of("value", 40))).getFirst();
                require(first.get("value") instanceof Number n && n.intValue() == 42);
                require(first.get("batch") instanceof Number n && n.intValue() == 1);
                require(worker.execute(List.of(Map.of("value", 0))).getFirst().get("batch") instanceof Number n && n.intValue() == 2);
            }
            phase = "js-cleanup"; require(runner.activeWorkers() == 0); assertContainerAbsent(docker, jsId);
            System.out.println("BUNDLE_WORKER_VERIFIED compile=true javaTyped=true javaBatches=2 jsBatches=2 containersRemoved=true activeWorkers=0");
            if (!strictSwap) System.out.println("LOCAL_TEST_ONLY swapLimitWasExplicitlyNotRequired=true");
        } catch (Throwable failure) {
            // Do not print failure messages/causes: script, CLI and malformed manifest errors may contain sensitive content.
            System.err.println("BUNDLE_VERIFY_FAILED phase=" + phase); System.exit(1);
        }
    }

    private static List<Path> bundle(Path manifest) throws Exception {
        require(manifest.isAbsolute() && Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS));
        byte[] bytes;
        try (var input = Files.newInputStream(manifest)) { bytes = input.readNBytes(65537); }
        require(bytes.length <= 65536);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        var json = JSONUtil.parseObj(text);
        require(Integer.valueOf(1).equals(json.getInt("formatVersion")));
        require(json.get("sourceSha256") instanceof String hash && hash.matches("[a-f0-9]{64}"));
        var artifacts = json.getJSONArray("artifacts"); var paths = json.getJSONArray("classpathEntries");
        require(artifacts != null && paths != null && artifacts.size() == 9 && paths.size() == 9);
        Path directory = manifest.toRealPath().getParent();
        var byName = new LinkedHashMap<String, Path>();
        for (Object value : paths) {
            require(value instanceof String); Path path = Path.of((String) value);
            require(path.isAbsolute() && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
            Path canonical = path.toRealPath();
            require(canonical.getParent().equals(directory));
            require(byName.put(canonical.getFileName().toString(), canonical) == null);
        }
        var seenArtifacts = new HashSet<String>(); var seenFiles = new HashSet<String>(); long total = 0;
        for (int i = 0; i < artifacts.size(); i++) {
            var item = artifacts.getJSONObject(i);
            require(item != null && item.get("file") instanceof String && item.get("sha256") instanceof String);
            String file = (String) item.get("file"), hash = (String) item.get("sha256");
            require(hash.matches("[a-f0-9]{64}") && seenFiles.add(file));
            Path path = byName.get(file); require(path != null);
            var matched = ARTIFACTS.stream().filter(artifact -> file.matches(java.util.regex.Pattern.quote(artifact)
                    + "-[0-9][A-Za-z0-9._+-]*\\.jar")).toList();
            require(matched.size() == 1 && seenArtifacts.add(matched.getFirst()));
            Object declared = item.get("bytes"); require(declared instanceof Number && declared.toString().matches("[1-9][0-9]{0,8}"));
            long size = Long.parseLong(declared.toString());
            require(size <= MAX_JAR && Files.size(path) == size); total += size; require(total <= MAX_TOTAL);
            var digest = MessageDigest.getInstance("SHA-256"); long observed = 0;
            try (var input = Files.newInputStream(path)) {
                byte[] chunk = new byte[8192]; int length;
                while ((length = input.read(chunk)) != -1) {
                    observed += length; require(observed <= MAX_JAR); digest.update(chunk, 0, length);
                }
            }
            require(observed == size && HexFormat.of().formatHex(digest.digest()).equals(hash) && Files.size(path) == size);
        }
        require(seenArtifacts.size() == ARTIFACTS.size());
        return List.copyOf(byName.values());
    }

    private static void verifyClientClasspath(List<Path> entries) throws Exception {
        Path harness = Path.of(VerifyScriptWorkerBundle.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(Files.isDirectory(harness));
        var expected = new HashSet<>(entries); require(expected.add(harness)); var actual = new HashSet<Path>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            require(actual.add(Path.of(entry).toRealPath()));
        require(actual.equals(expected));
        // The runtime must load the runner from the verified physical release JAR, never target/classes or a nested BOOT URL.
        Path runner = Path.of(EtlScriptRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(entries.contains(runner) && runner.getFileName().toString().startsWith("yudao-spring-boot-starter-biz-etl-"));
    }

    private static String javaSource() {
        return "import java.util.*; import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript; "
                + "public class Example implements EtlJavaScript { private int batch; "
                + "public List<Map<String,Object>> execute(List<Map<String,Object>> rows) { rows.getFirst().put(\"batch\",++batch); return rows; } }";
    }
    private static void unconfirmed() { throw new IllegalStateException("Container termination unconfirmed"); }
    private static void require(boolean valid) { if (!valid) throw new IllegalStateException("Verification failed"); }

    private static void assertContainerAbsent(EtlScriptWorkerPolicy.Docker docker, String id) throws Exception {
        var builder = new ProcessBuilder(docker.executable().toString(), "--host", docker.endpoint(), "ps", "--all", "--no-trunc",
                "--filter", "id=" + id, "--format", "{{.ID}}");
        builder.environment().clear(); String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) builder.environment().put("SystemRoot", systemRoot);
        Process process = builder.start();
        var readers = Executors.newFixedThreadPool(2, task -> { var t = new Thread(task, "etl-bundle-inspect"); t.setDaemon(true); return t; });
        try {
            process.getOutputStream().close();
            var out = readers.submit(() -> boundedControl(process.getInputStream()));
            var error = readers.submit(() -> boundedControl(process.getErrorStream()));
            require(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0);
            require(new String(out.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8).isBlank()); error.get(1, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
            readers.shutdownNow(); require(process.waitFor(1, TimeUnit.SECONDS)); require(readers.awaitTermination(1, TimeUnit.SECONDS));
        }
    }
    private static byte[] boundedControl(InputStream input) throws IOException {
        try (input) { byte[] bytes = input.readNBytes(4097); require(bytes.length <= 4096); return bytes; }
    }
}
