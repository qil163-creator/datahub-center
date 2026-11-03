package cn.iocoder.yudao.framework.etl.core.script;

import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;
import com.sun.net.httpserver.*;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 可信 launcher 到隔离 Docker worker 的专用 mTLS 边界；默认没有绑定且不接受请求内源码。 */
public final class EtlNativeScriptBroker implements AutoCloseable {
    public record Identity(String task, String workerGroup, String engine, String certificateSha256) {
        public Identity {
            if (task == null || !task.matches("[A-Za-z0-9_.-]{1,128}") || workerGroup == null || !workerGroup.matches("[A-Za-z0-9_.-]{1,100}")
                    || !Set.of("SPARK", "FLINK").contains(engine) || certificateSha256 == null || !certificateSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("脚本资源执行身份无效");
        }
    }
    public record ResourcePolicy(Set<Identity> identities) { public ResourcePolicy { identities = Set.copyOf(identities); } }
    public record PreparedVersion(String bindingId, String epoch) { }
    public record Grant(String task, String workerGroup, String engine, String clientCertificateSha256,
                        String sourceSha256, String source, EtlScriptRunner.Language language,
                        List<EtlFieldContract> inputFields, List<EtlFieldContract> outputFields) {
        public Grant {
            if (task == null || !task.matches("[A-Za-z0-9_.-]{1,128}") || workerGroup == null || !workerGroup.matches("[A-Za-z0-9_.-]{1,100}")
                    || !Set.of("SPARK", "FLINK").contains(engine) || clientCertificateSha256 == null || !clientCertificateSha256.matches("[a-f0-9]{64}")
                    || sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}") || source == null || source.length() > 1024 * 1024 || source.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024 || !sourceSha256.equals(digest(source.getBytes(StandardCharsets.UTF_8)))
                    || !Set.of(EtlScriptRunner.Language.JAVA, EtlScriptRunner.Language.QDATA_JAVA, EtlScriptRunner.Language.QDATA_TOKEN).contains(language)) throw new IllegalArgumentException("脚本受管绑定或源码快照无效");
            inputFields = List.copyOf(inputFields); outputFields = List.copyOf(outputFields);
            if (language != EtlScriptRunner.Language.QDATA_TOKEN && (inputFields.isEmpty() || outputFields.isEmpty()) || inputFields.size() > 256 || outputFields.size() > 256
                    || inputFields.stream().map(EtlFieldContract::name).distinct().count() != inputFields.size()
                    || outputFields.stream().map(EtlFieldContract::name).distinct().count() != outputFields.size()) throw new IllegalArgumentException("脚本需要固定且唯一的输入输出字段合同");
        }
        @Override public String toString() { return "Grant[task=" + task + ", engine=" + engine + ", sourceSha256=" + sourceSha256 + "]"; }
    }

    private final EtlScriptRunner runner;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Map<String, Version> versions = new HashMap<>();
    private final Map<String, Map<String, HttpResource>> httpResources = new HashMap<>();
    private final Map<String, Map<String, WorkerHttpResource>> workerHttpResources = new HashMap<>();
    private final Map<String, String> latestPublications = new HashMap<>();
    private final Map<String, ResourcePolicy> resources;
    private volatile Set<String> activeVersions;
    private long versionBytes;
    private final String epoch = UUID.randomUUID().toString();
    private final long lifetimeNanos, cacheBudget;
    private final AtomicLong cachedResultsAndReservations = new AtomicLong();
    private final int maximumSessions;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, String> runs = new HashMap<>();
    private final HttpsServer server;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService reaper;
    private final ScheduledExecutorService deadlines;

    public EtlNativeScriptBroker(InetSocketAddress address, SSLContext tls, EtlScriptRunner runner,
                                Map<String, Grant> grants, int maximumSessions, long lifetimeMillis, long cacheBudget) throws IOException {
        this(address, tls, runner, grants, Map.of(), maximumSessions, lifetimeMillis, cacheBudget);
    }
    public EtlNativeScriptBroker(InetSocketAddress address, SSLContext tls, EtlScriptRunner runner,
                                Map<String, Grant> grants, Map<String, ResourcePolicy> resources, int maximumSessions, long lifetimeMillis, long cacheBudget) throws IOException {
        this.runner = Objects.requireNonNull(runner); this.grants.putAll(grants); this.activeVersions = Set.copyOf(grants.keySet()); this.resources = Map.copyOf(resources);
        var certificateOwners = new HashMap<String, String>();
        for (var resource : resources.values()) for (var identity : resource.identities()) {
            String owner = identity.task() + ":" + identity.workerGroup() + ":" + identity.engine();
            String previous = certificateOwners.putIfAbsent(identity.certificateSha256(), owner);
            if (previous != null && !previous.equals(owner)) throw new IllegalArgumentException("不同任务、工作组或引擎不得共用脚本执行证书");
        }
        var policy = runner.policy();
        if (!policy.enabled() || policy.mode() != EtlScriptWorkerPolicy.Mode.DOCKER || policy.docker().recovery() == null
                || !policy.docker().recovery().recoverOnStartup() || maximumSessions < 1 || maximumSessions > 64
                || lifetimeMillis < 1000 || lifetimeMillis > 300_000 || cacheBudget < policy.maxResultBytes() + 512L || cacheBudget > 128L * 1024 * 1024)
            throw new IllegalArgumentException("脚本 broker 需要启用持久恢复的 Docker 隔离及有界资源策略");
        for (String id : grants.keySet()) if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("脚本受管绑定编号无效");
        this.maximumSessions = maximumSessions; this.lifetimeNanos = TimeUnit.MILLISECONDS.toNanos(lifetimeMillis); this.cacheBudget = cacheBudget;
        server = HttpsServer.create(address, 16);
        server.setHttpsConfigurator(new HttpsConfigurator(Objects.requireNonNull(tls)) {
            @Override public void configure(HttpsParameters parameters) {
                var configured = getSSLContext().getDefaultSSLParameters(); configured.setNeedClientAuth(true);
                configured.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"}); parameters.setSSLParameters(configured);
            }
        });
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> { var thread = new Thread(runnable, "etl-script-broker"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor); server.createContext("/v1/script", this::handle);
        deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> { var thread = new Thread(runnable, "etl-script-broker-deadline"); thread.setDaemon(true); return thread; });
        reaper = Executors.newSingleThreadScheduledExecutor(runnable -> { var thread = new Thread(runnable, "etl-script-broker-expiry"); thread.setDaemon(true); return thread; });
    }

    public String epoch() { return epoch; }
    public int port() { return server.getAddress().getPort(); }
    public void start() { runner.initializeRecovery(); runner.ensureAvailable(); server.start(); reaper.scheduleAtFixedRate(this::expire, 1, 1, TimeUnit.SECONDS); }

    /** 仅同进程受信发布控制面调用；HTTP 运行协议没有登记入口，准备版本也不能被 worker 执行。 */
    public synchronized PreparedVersion describeVersion(String resource, String publication, Grant grant) {
        return version(resource, publication, grant, false);
    }
    public synchronized PreparedVersion prepareVersion(String resource, String publication, Grant grant) {
        return version(resource, publication, grant, true);
    }
    private PreparedVersion version(String resource, String publication, Grant grant, boolean prepare) {
        ResourcePolicy policy = resources.get(resource);
        if (policy == null || publication == null || !publication.matches("[A-Za-z0-9_.-]{1,128}")
                || !policy.identities.contains(new Identity(grant.task, grant.workerGroup, grant.engine, grant.clientCertificateSha256))) throw denied();
        try {
            byte[] descriptor = EtlScriptProtocol.encode(2 * 1024 * 1024, output -> {
                for (String value : List.of(resource, publication, grant.task, grant.workerGroup, grant.engine, grant.clientCertificateSha256, grant.sourceSha256, grant.language.name())) EtlScriptProtocol.writeText(output, value, 256);
                EtlScriptProtocol.writeRows(output, grant.inputFields.stream().map(EtlFieldContract::properties).toList(), 256, 2 * 1024 * 1024);
                EtlScriptProtocol.writeRows(output, grant.outputFields.stream().map(EtlFieldContract::properties).toList(), 256, 2 * 1024 * 1024);
                EtlScriptProtocol.writeText(output, grant.source, runner.policy().maxSourceBytes());
            });
            String id = "script_" + digest(descriptor).substring(0, 48);
            Version old = versions.get(id);
            if (old == null && prepare) {
                if (versions.size() >= 4096 || versionBytes + descriptor.length > 32L * 1024 * 1024) throw denied();
                String scope = grant.task + ":" + grant.workerGroup + ":" + grant.engine;
                grants.put(id, grant); versions.put(id, new Version(scope, publication, descriptor.length, System.nanoTime() + TimeUnit.MINUTES.toNanos(10))); versionBytes += descriptor.length;
            }
            return new PreparedVersion(id, epoch);
        } catch (IOException failure) { throw denied(); }
    }

    /** 成功发布且事务已经提交后，一次激活该控制面快照的全部节点。 */
    public synchronized void activateVersions(List<PreparedVersion> prepared) {
        var publications = new HashMap<String, String>();
        for (var item : prepared) {
            Version version = versions.get(item.bindingId);
            if (!epoch.equals(item.epoch) || version == null || System.nanoTime() > version.deadline && !activeVersions.contains(item.bindingId)) throw denied();
            String old = publications.putIfAbsent(version.scope, version.publication);
            if (old != null && !old.equals(version.publication)) throw denied();
        }
        var next = new HashSet<>(activeVersions); prepared.forEach(item -> next.add(item.bindingId));
        latestPublications.putAll(publications); activeVersions = Set.copyOf(next);
    }

    public synchronized void discardPrepared(List<PreparedVersion> prepared) {
        for (var item : prepared) if (epoch.equals(item.epoch) && !activeVersions.contains(item.bindingId)) removeVersion(item.bindingId);
    }

    public synchronized boolean active(PreparedVersion version) { return epoch.equals(version.epoch) && activeVersions.contains(version.bindingId); }

    /** 绑定同一待发布版本的已授权 HTTP 执行器；节点和运行客户端均不能提交白名单或执行器。 */
    public synchronized void prepareHttpResource(PreparedVersion version, String resourceRef, String policyHash,
                                               EtlScriptAuthenticatedHttp.ResourceExecutor executor) {
        if (!epoch.equals(version.epoch) || !versions.containsKey(version.bindingId) || resourceRef == null || !resourceRef.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
                || policyHash == null || !policyHash.matches("[a-f0-9]{64}") || executor == null) throw denied();
        var resources = httpResources.computeIfAbsent(version.bindingId, ignored -> new HashMap<>());
        var old = resources.get(resourceRef);
        if (old != null) { if (!old.policyHash.equals(policyHash)) throw denied(); return; }
        if (activeVersions.contains(version.bindingId) || resources.size() >= 64) throw denied();
        resources.put(resourceRef, new HttpResource(policyHash, executor));
    }

    /** 隔离 worker 没有网络，仅可按受管资源精确匹配后通过此执行器请求。 */
    public synchronized void prepareWorkerHttpResource(PreparedVersion version, String resourceRef, String policyHash,
            Set<String> endpoints, Set<String> methods, int maximumBytes, int timeoutMillis, EtlScriptWorkerHttp.Executor executor) {
        if (!epoch.equals(version.epoch) || !versions.containsKey(version.bindingId) || resourceRef == null || !resourceRef.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
                || policyHash == null || !policyHash.matches("[a-f0-9]{64}") || endpoints == null || endpoints.isEmpty() || endpoints.size() > 32 || methods == null || methods.isEmpty() || !Set.of("GET","POST").containsAll(methods)
                || maximumBytes < 1 || maximumBytes > EtlScriptWorkerHttp.MAX_BYTES || timeoutMillis < 100 || timeoutMillis > 30000 || executor == null) throw denied();
        for (String endpoint : endpoints) new EtlScriptWorkerHttp.Request(endpoint,"GET",Map.of(),Map.of(),new byte[0],maximumBytes,timeoutMillis);
        var entries = workerHttpResources.computeIfAbsent(version.bindingId, ignored -> new HashMap<>()); var old = entries.get(resourceRef);
        if (old != null) { if (!old.policyHash.equals(policyHash)) throw denied(); return; }
        if (activeVersions.contains(version.bindingId) || entries.size() >= 32) throw denied();
        entries.put(resourceRef,new WorkerHttpResource(policyHash,Set.copyOf(endpoints),Set.copyOf(methods),maximumBytes,timeoutMillis,executor));
    }
    private EtlScriptWorkerHttp.Executor workerHttp(Session session) {
        final List<WorkerHttpResource> resources;
        synchronized (this) { resources = List.copyOf(workerHttpResources.getOrDefault(session.binding,Map.of()).values()); }
        if (resources.isEmpty()) return null;
        return request -> {
            var matches = resources.stream().filter(resource -> resource.endpoints.contains(request.endpoint()) && resource.methods.contains(request.method())).toList();
            if (matches.size() != 1) throw denied(); var resource = matches.getFirst();
            var limited = new EtlScriptWorkerHttp.Request(request.endpoint(),request.method(),request.query(),request.headers(),request.body(),Math.min(request.maximumBytes(),resource.maximumBytes),Math.min(request.timeoutMillis(),resource.timeoutMillis));
            var result = resource.executor.exchange(limited); if (result.body().length > limited.maximumBytes()) throw denied(); return result;
        };
    }

    private void handle(HttpExchange exchange) {
        var deadline = deadlines.schedule(exchange::close, 45, TimeUnit.SECONDS);
        byte[] response; int status = 200;
        try {
            if (!(exchange instanceof HttpsExchange secure) || !exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().toString().equals("/v1/script")
                    || !"application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) throw denied();
            String fingerprint = EtlScriptTls.fingerprint((X509Certificate) secure.getSSLSession().getPeerCertificates()[0]);
            byte[] bytes = exchange.getRequestBody().readNBytes(EtlScriptBrokerProtocol.MAX_BYTES + 1);
            if (bytes.length > EtlScriptBrokerProtocol.MAX_BYTES) throw denied();
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != EtlScriptBrokerProtocol.VERSION) throw denied();
                int operation = input.readUnsignedByte();
                if (operation == EtlScriptBrokerProtocol.HTTP) {
                    var claim = EtlScriptBrokerProtocol.Claim.read(input); requireGrant(claim, fingerprint);
                    var request = EtlScriptAuthenticatedHttp.read(input);
                    int count = input.readInt(); if (count < 0 || count > 64) throw denied();
                    var headers = new LinkedHashMap<String, String>(request.headers()); var tokens = new ArrayList<String>();
                    for (int index = 0; index < count; index++) {
                        String header = EtlScriptAuthenticatedHttp.text(input), reference = EtlScriptAuthenticatedHttp.text(input), prefix = EtlScriptAuthenticatedHttp.text(input);
                        if (!header.matches("[A-Za-z][A-Za-z0-9-]{0,99}") || headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(header)) || !prefix.isEmpty() && !prefix.matches("[A-Za-z][A-Za-z0-9_-]{0,31} ")) throw denied();
                        String token = authentication(reference, claim, fingerprint); tokens.add(token); headers.put(header, prefix + token);
                    }
                    EtlScriptProtocol.requireEnd(input);
                    var authenticated = new EtlScriptAuthenticatedHttp.Request(request.resourceRef(), request.endpoint(), request.method(), request.query(), headers, request.body(), request.maximumBytes(), request.timeoutMillis());
                    HttpResource resource;
                    synchronized (this) { resource = httpResources.getOrDefault(claim.binding(), Map.of()).get(request.resourceRef()); }
                    if (resource == null) throw denied();
                    var result = resource.executor.exchange(authenticated);
                    byte[] resultBody = result.body();
                    if (resultBody.length > request.maximumBytes() || new HashSet<>(tokens).stream().anyMatch(token -> contains(resultBody, token.getBytes(StandardCharsets.UTF_8)))) throw denied();
                    response = EtlScriptBrokerProtocol.okay(output -> EtlScriptAuthenticatedHttp.write(output, result));
                } else if (operation == EtlScriptBrokerProtocol.OPEN) {
                    var claim = EtlScriptBrokerProtocol.Claim.read(input); EtlScriptProtocol.requireEnd(input);
                    Session session = open(claim, fingerprint);
                    response = EtlScriptBrokerProtocol.okay(output -> EtlScriptProtocol.writeText(output, session.id, 36));
                } else {
                    String id = EtlScriptProtocol.readText(input, 36); Session session = find(id, fingerprint);
                    if (operation == EtlScriptBrokerProtocol.BATCH) {
                        long sequence = input.readLong(); var rows = EtlScriptProtocol.readRows(input, runner.policy().maxBatchRows(), runner.policy().maxBatchBytes()); EtlScriptProtocol.requireEnd(input);
                        response = batch(session, sequence, digest(bytes), rows);
                    } else if (operation == EtlScriptBrokerProtocol.ACK) {
                        long sequence = input.readLong(); EtlScriptProtocol.requireEnd(input);
                        synchronized (session) {
                            if (sequence < session.acknowledged || sequence > session.sequence) throw denied();
                            session.acknowledged = sequence;
                            for (var entry : session.cache.entrySet()) if (entry.getKey() < sequence && session.cache.remove(entry.getKey(), entry.getValue())) cachedResultsAndReservations.addAndGet(-entry.getValue().response.length);
                        }
                        response = EtlScriptBrokerProtocol.okay(output -> { });
                    } else if (operation == EtlScriptBrokerProtocol.CLOSE || operation == EtlScriptBrokerProtocol.CANCEL) {
                        EtlScriptProtocol.requireEnd(input);
                        if (operation == EtlScriptBrokerProtocol.CANCEL) {
                            session.cancelled.set(true); if (session.worker != null) session.worker.close(); session.token = null; session.authContext = "";
                            session.closed = true;
                        } else synchronized (session) { session.closed = true; }
                        if (session.unconfirmed.get()) throw denied();
                        response = EtlScriptBrokerProtocol.okay(output -> { });
                    } else throw denied();
                }
            }
        } catch (Throwable failure) { response = EtlScriptBrokerProtocol.failed(); status = 403; }
        try { exchange.getResponseHeaders().set("Content-Type", "application/octet-stream"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response); }
        catch (IOException ignored) { }
        finally { deadline.cancel(false); exchange.close(); }
    }

    private synchronized Session open(EtlScriptBrokerProtocol.Claim claim, String fingerprint) {
        Grant grant = requireGrant(claim, fingerprint);
        String run = claim.binding() + ":" + claim.run() + ":" + claim.partition();
        Session session;
        synchronized (this) {
            String old = runs.get(run);
            if (old != null) return find(old, fingerprint);
            Version version = versions.get(claim.binding());
            if (version != null && !version.publication.equals(latestPublications.get(version.scope))) throw denied();
            if (sessions.size() >= maximumSessions || runs.size() >= 4096) throw denied();
            session = new Session(grant, claim.binding(), fingerprint, claim.run(), System.nanoTime() + lifetimeNanos);
            sessions.put(session.id, session); runs.put(run, session.id);
        }
        return session;
    }
    private synchronized Grant requireGrant(EtlScriptBrokerProtocol.Claim claim, String fingerprint) {
        Grant grant = grants.get(claim.binding());
        if (grant == null || !activeVersions.contains(claim.binding()) || !epoch.equals(claim.epoch()) || !grant.clientCertificateSha256.equals(fingerprint) || !grant.task.equals(claim.task())
                || !grant.workerGroup.equals(claim.workerGroup()) || !grant.engine.equals(claim.engine()) || !grant.sourceSha256.equals(claim.sourceHash())) throw denied();
        return grant;
    }
    private synchronized String authentication(String reference, EtlScriptBrokerProtocol.Claim claim, String fingerprint) {
        for (Session session : sessions.values()) if (reference.equals(session.authContext) && session.token != null && !session.cancelled.get() && System.nanoTime() <= session.deadline
                && session.fingerprint.equals(fingerprint) && session.run.equals(claim.run()) && session.grant.task.equals(claim.task()) && session.grant.workerGroup.equals(claim.workerGroup()) && session.grant.engine.equals(claim.engine())) {
            session.deadline = System.nanoTime() + lifetimeNanos; return session.token;
        }
        throw denied();
    }
    private synchronized Session find(String id, String fingerprint) {
        Session session = sessions.get(id);
        if (session == null || !session.fingerprint.equals(fingerprint) || System.nanoTime() > session.deadline || session.unconfirmed.get()) throw denied();
        if (!session.closed) session.deadline = System.nanoTime() + lifetimeNanos;
        return session;
    }
    private byte[] batch(Session session, long sequence, String requestHash, List<Map<String, Object>> rows) throws IOException {
        var httpExecutor = workerHttp(session);
        synchronized (session) {
            if (session.cancelled.get()) throw denied();
            var previous = session.cache.get(sequence);
            if (previous != null) { if (!previous.requestHash.equals(requestHash)) throw denied(); return previous.response; }
            if (sequence != session.sequence || session.closed || session.inFlight || session.cancelled.get() || session.sequence == Long.MAX_VALUE
                    || session.cache.size() >= 128) throw denied();
            validate(session.grant.inputFields, rows);
            long reserved = runner.policy().maxResultBytes() + 512L;
            while (true) { long used = cachedResultsAndReservations.get(); if (used + reserved > cacheBudget) throw denied(); if (cachedResultsAndReservations.compareAndSet(used, used + reserved)) break; }
            boolean stored = false;
            session.inFlight = true;
            try {
                session.worker = runner.open(session.grant.language, session.grant.source, () -> { if (session.cancelled.get() || System.nanoTime() > session.deadline) throw denied(); }, () -> session.unconfirmed.set(true), httpExecutor);
                var result = session.worker.execute(rows);
                session.worker.close(); session.worker = null;
                if (session.cancelled.get() || session.unconfirmed.get()) throw denied();
                if (session.grant.language == EtlScriptRunner.Language.QDATA_TOKEN) {
                    if (result.size() != 1 || !(result.getFirst().get("__token") instanceof String token)) throw denied();
                    session.token = token; if (session.authContext.isEmpty()) session.authContext = UUID.randomUUID().toString(); result = rows;
                }
                validate(session.grant.outputFields, result);
                var actual = result;
                byte[] response = EtlScriptBrokerProtocol.okay(output -> { output.writeLong(sequence); EtlScriptProtocol.writeText(output, session.authContext, 36); EtlScriptProtocol.writeRows(output, actual, runner.policy().maxResultRows(), runner.policy().maxResultBytes()); });
                session.cache.put(sequence, new Cached(requestHash, response)); cachedResultsAndReservations.addAndGet(response.length - reserved); stored = true; session.sequence++; session.inFlight = false; return response;
            } catch (Throwable failure) { session.cancelled.set(true); session.closed = true; if (session.worker != null) session.worker.close(); throw denied(); }
            finally { if (!stored) cachedResultsAndReservations.addAndGet(-reserved); }
        }
    }
    private static void validate(List<EtlFieldContract> fields, List<Map<String, Object>> rows) {
        var names = new LinkedHashSet<>(fields.stream().map(EtlFieldContract::name).toList());
        for (var row : rows) {
            if (!row.keySet().equals(names)) throw denied();
            for (var field : fields) { field.validate(row); Object value = row.get(field.name()); if (value instanceof Double number && !Double.isFinite(number) || value instanceof Float single && !Float.isFinite(single)) throw denied(); }
        }
    }
    private synchronized void expire() {
        for (var iterator = sessions.values().iterator(); iterator.hasNext();) {
            Session session = iterator.next();
            if (System.nanoTime() <= session.deadline) continue;
            session.cancelled.set(true);
            try { if (session.worker != null) session.worker.close(); session.closed = true; }
            catch (RuntimeException ignored) { session.unconfirmed.set(true); }
            if (!session.unconfirmed.get()) {
                for (var entry : session.cache.entrySet()) if (session.cache.remove(entry.getKey(), entry.getValue())) cachedResultsAndReservations.addAndGet(-entry.getValue().response.length);
                session.token = null; iterator.remove();
            }
        }
        for (String id : new ArrayList<>(versions.keySet())) {
            Version version = versions.get(id); Grant grant = grants.get(id);
            if (System.nanoTime() > version.deadline && (!activeVersions.contains(id) || !version.publication.equals(latestPublications.get(version.scope)))
                    && sessions.values().stream().noneMatch(session -> session.grant == grant)) removeVersion(id);
        }
        // 有界的运行实例墓碑保留至本 epoch 结束，不能让过期或未知副作用的会话重新执行。
    }
    private void removeVersion(String id) {
        Version removed = versions.remove(id); if (removed == null) return;
        grants.remove(id); httpResources.remove(id); workerHttpResources.remove(id); versionBytes -= removed.bytes;
        var next = new HashSet<>(activeVersions); next.remove(id); activeVersions = Set.copyOf(next);
    }
    @Override public synchronized void close() {
        server.stop(0); reaper.shutdownNow(); deadlines.shutdownNow(); executor.shutdownNow();
        for (Session session : sessions.values()) { session.cancelled.set(true); try { if (session.worker != null) session.worker.close(); } catch (RuntimeException ignored) { session.unconfirmed.set(true); } }
    }
    private static String digest(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw denied(); } }
    private static IllegalStateException denied() { return new IllegalStateException("脚本运行请求未获授权、已失效或执行失败"); }
    private record WorkerHttpResource(String policyHash, Set<String> endpoints, Set<String> methods, int maximumBytes, int timeoutMillis, EtlScriptWorkerHttp.Executor executor) { }
    private record Cached(String requestHash, byte[] response) { }
    private record Version(String scope, String publication, int bytes, long deadline) { }
    private record HttpResource(String policyHash, EtlScriptAuthenticatedHttp.ResourceExecutor executor) { }
    private static boolean contains(byte[] bytes, byte[] token) {
        if (token.length == 0) return false;
        int[] fallback = new int[token.length];
        for (int index = 1, matched = 0; index < token.length; index++) { while (matched > 0 && token[index] != token[matched]) matched = fallback[matched - 1]; if (token[index] == token[matched]) matched++; fallback[index] = matched; }
        for (int index = 0, matched = 0; index < bytes.length; index++) { while (matched > 0 && bytes[index] != token[matched]) matched = fallback[matched - 1]; if (bytes[index] == token[matched]) matched++; if (matched == token.length) return true; }
        return false;
    }
    private static final class Session {
        private final String id = UUID.randomUUID().toString(), fingerprint, run, binding; private final Grant grant; private volatile long deadline;
        private final AtomicBoolean cancelled = new AtomicBoolean(), unconfirmed = new AtomicBoolean();
        private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
        private volatile EtlScriptRunner.Session worker; private long sequence, acknowledged; private volatile boolean closed, inFlight;
        private volatile String token, authContext = "";
        private Session(Grant grant, String binding, String fingerprint, String run, long deadline) { this.grant = grant; this.binding = binding; this.fingerprint = fingerprint; this.run = run; this.deadline = deadline; }
    }
}
