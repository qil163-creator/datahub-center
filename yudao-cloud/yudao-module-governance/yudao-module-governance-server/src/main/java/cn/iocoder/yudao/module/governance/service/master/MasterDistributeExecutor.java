package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.BytesUnitUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.governance.enums.master.MasterDistributeTypeEnum;
import cn.iocoder.yudao.module.governance.enums.master.MasterRunStatusEnum;
import cn.iocoder.yudao.module.governance.enums.master.MasterRunTypeEnum;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MODEL_DATABASE_NOT_EXISTS;

/**
 * 主数据派发执行器
 *
 * 参考 srt-cloud 的 MasterDistributeFactory + DistributeDb/Api/MqImpl，并修正其缺陷：
 * HTTP 禁止重投请求体/重定向，2xx 仅表示传输确认；Kafka/RabbitMQ 确认不代表消费方业务生效。
 * 数据量统计使用真实序列化字节数、行循环响应中断
 *
 * @author Li
 */
@Slf4j
@Component
public class MasterDistributeExecutor {

    public static final String MQ_TYPE_KAFKA = "kafka";

    public static final String MQ_TYPE_RABBIT = "rabbitmq";

    private static final int DEFAULT_FETCH_SIZE = 1000;

    private static final int API_DEFAULT_FETCH_SIZE = 100;

    private static final int HTTP_TIMEOUT_MS = 30_000;

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private MasterDistributeMapper distributeMapper;

    @Resource
    private MasterDistributeRunCoordinator coordinator;

    @Resource
    private MasterModelMapper modelMapper;

    @Resource
    private MasterColumnMapper columnMapper;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private MasterDistributeApiPolicy apiPolicy;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeCheckpointMapper checkpoints;

    private ExecutorService executor;

    private ScheduledExecutorService heartbeatExecutor;

    /**
     * 本机仅保留工作者句柄；数据库状态/令牌才是跨节点事实。
     */
    private final ConcurrentMap<String, LocalRun> runningTasks = new ConcurrentHashMap<>();

    private static final class LocalRun {

        final MasterDistributeLogDO run;

        volatile Thread thread;

        /**
         * 使用给定运行初始化主数据分发。
         */
        LocalRun(MasterDistributeLogDO run) {
            this.run = run;
        }

        /**
         * 中断当前分发运行持有的执行线程或任务。
         */
        void interrupt() {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    /**
     * 初始化主数据分发使用的运行资源。
     */
    @PostConstruct
    public void init() {
        AtomicInteger counter = new AtomicInteger();
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "master-distribute-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "master-distribute-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        // 每个节点只维护本机句柄的租约；失联恢复由持久Job执行。
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 释放主数据分发持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 提交执行（异步）
     *
     * @return false 表示该任务正在执行中，本次跳过
     */
    public boolean submit(Long distributeId, MasterRunTypeEnum runType) {
        var accepted = coordinator.accept(distributeId, runType);
        if (accepted == null) {
            return false;
        }
        dispatch(accepted);
        return true;
    }

    /** 已受理记录入队失败仍保留；所有入口只派发同一持久运行。 */
    public void dispatch(MasterDistributeLogDO accepted) {
        if (accepted == null) {
            return;
        }
        var claimed = coordinator.claim(accepted);
        if (claimed == null) {
            return;
        }
        var local = new LocalRun(claimed);
        runningTasks.put(claimed.getLeaseToken(), local);
        try {
            executor.execute(() -> {
                local.thread = Thread.currentThread();
                try {
                    execute(claimed);
                } finally {
                    runningTasks.remove(claimed.getLeaseToken(), local);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            runningTasks.remove(claimed.getLeaseToken(), local);
            coordinator.rejected(claimed);
        }
    }

    /**
     * 续约当前分发运行的执行租约。
     */
    private void heartbeat() {
        for (var local : runningTasks.values()) {
            try {
                if (!coordinator.heartbeat(local.run)) {
                    local.interrupt();
                }
            } catch (Exception ex) {
                local.interrupt();
                log.warn("派发续租失败 runId={} type={}", local.run.getId(), ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * 执行主数据分发处理流程，并维护相应运行结果。
     */
    void execute(MasterDistributeLogDO logDO) {
        Long distributeId = logDO.getDistributeId();
        boolean sending = false;
        int result = MasterRunStatusEnum.FAILED.getStatus();
        String error = null;
        try {
            MasterDistributeDO distribute = distributeMapper.selectById(distributeId);
            if (distribute == null) {
                throw new IllegalStateException("派发任务已不存在");
            }
            MasterModelDO model = modelMapper.selectById(distribute.getMasterModelId());
            if (model == null || model.getStatus() == null
                    || model.getStatus() != MasterModelDO.STATUS_RELEASED) {
                throw new IllegalStateException("主数据模型不存在或未发布");
            }
            MasterDistributeConfigVO config = JsonUtils.parseObject(
                    distribute.getDistributeJson(), MasterDistributeConfigVO.class);
            if (config == null) {
                throw new IllegalStateException("派发配置为空");
            }
            MasterDistributeTypeEnum type = MasterDistributeTypeEnum.of(distribute.getDistributeType());
            if (type == null) {
                throw new IllegalStateException("派发类型不合法");
            }
            // 纯配置预检不触及外部系统；非法地址/请求头不能先占用发送后 UNKNOWN。
            if (type == MasterDistributeTypeEnum.API) {
                MasterDistributeApiPolicy.restoreSecrets(distribute, config.getApi());
            }
            ApiRequestPlan apiPlan = type == MasterDistributeTypeEnum.API ? prepareApi(config.getApi()) : null;
            MasterDistributeApiPolicy.Plan mappingPlan = type == MasterDistributeTypeEnum.API
                    ? apiPolicy.plan(model.getId(), config.getApi()) : null;
            if (type == MasterDistributeTypeEnum.MQ) {
                validateMq(config.getMq());
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (type != MasterDistributeTypeEnum.API) {
                if (!coordinator.begin(logDO)) {
                    return;
                }
                sending = true;
                logDO.setRunStatus(MasterRunStatusEnum.RUNNING.getStatus());
            }
            switch (type) {
                case DB -> executeDb(model, config.getDb(), logDO);
                case API -> executeApi(model, apiPlan, mappingPlan, logDO);
                case MQ -> executeMq(model, config.getMq(), logDO);
            }
            sending = sending || Integer.valueOf(2).equals(logDO.getRunStatus());
            result = logDO.getRunStatus().equals(MasterRunStatusEnum.UNKNOWN.getStatus()) ? 5 : 3;
            if (result == 5) {
                error = "派发未完整确认，可能部分生效，禁止直接重跑";
            }
        } catch (Throwable ex) {
            sending = sending || Integer.valueOf(2).equals(logDO.getRunStatus());
            log.warn("派发异常 taskId={} runId={} type={}", distributeId, logDO.getId(), ex.getClass().getSimpleName());
            result = sending ? 5 : 4;
            error = sending ? "发送后结果未确认，禁止直接重跑，请核查外部结果" : "发送前准备失败，未执行发送";
        } finally {
            // JDBC 清理/事务可能消费中断；终态仍须尝试落库，并将停止信号还给调用者。
            boolean interrupted = Thread.interrupted();
            try {
                coordinator.finish(logDO, sending, result, error);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ==================== DB 派发（dbswitch 直写） ====================

    /**
     * 执行数据库。
     */
    private void executeDb(MasterModelDO model, MasterDistributeConfigVO.DbConfig dbConfig,
            MasterDistributeLogDO logDO) throws Exception {
        if (dbConfig == null || dbConfig.getDatabaseId() == null) {
            throw new IllegalStateException("未配置目标数据源");
        }
        MasterDatabaseResolver.ConnectionInfo sourceConn = resolveModelConnection(model);
        MasterDatabaseResolver.ConnectionInfo targetConn = databaseResolver.resolve(
                dbConfig.getDatabaseId(), MASTER_MODEL_DATABASE_NOT_EXISTS);

        MigrationRequest properties = new MigrationRequest();
        SourceDataSourceProperties source = new SourceDataSourceProperties();
        source.setSourceType(SourceType.TABLE.getCode());
        source.setSourceProductType(sourceConn.getProductType());
        source.setUrl(sourceConn.getJdbcUrl());
        source.setDriverClassName(sourceConn.getProductType().getDriveClassName());
        source.setUsername(sourceConn.getUsername());
        source.setPassword(sourceConn.getPassword());
        source.setSourceSchema(sourceConn.getSchema());
        source.setFetchSize(dbConfig.getFetchSize() != null && dbConfig.getFetchSize() > 0
                ? dbConfig.getFetchSize() : DEFAULT_FETCH_SIZE);
        source.setIncludeOrExclude(1);
        source.setSourceIncludes(model.getTableName());
        // 目标表名不同于源表时，用精确正则映射改名
        if (StrUtil.isNotBlank(dbConfig.getTargetTableName())
                && !dbConfig.getTargetTableName().equals(model.getTableName())) {
            List<PatternMapper> mappers = new ArrayList<>();
            mappers.add(new PatternMapper("^" + java.util.regex.Pattern.quote(model.getTableName()) + "$",
                    dbConfig.getTargetTableName()));
            source.setRegexTableMapper(mappers);
        }
        properties.getSource().add(source);

        TargetDataSourceProperties target = properties.getTarget();
        target.setTargetProductType(targetConn.getProductType());
        target.setUrl(targetConn.getJdbcUrl());
        target.setDriverClassName(targetConn.getProductType().getDriveClassName());
        target.setUsername(targetConn.getUsername());
        target.setPassword(targetConn.getPassword());
        target.setTargetSchema(targetConn.getSchema());
        target.setTargetDrop(Boolean.TRUE.equals(dbConfig.getTargetDrop()));
        target.setSyncExist(dbConfig.getSyncExist() == null || dbConfig.getSyncExist());
        target.setOnlyCreate(Boolean.TRUE.equals(dbConfig.getOnlyCreate()));
        target.setChangeDataSync(Boolean.TRUE.equals(dbConfig.getChangeDataSync()));

        // 不将目标库原异常持久化到业务日志，避免泄漏连接或数据内容。
        MigrationService migrationService = new MigrationService(properties, tableResult -> {
        }, null, dbSwitchRuntime);
        DbSwitchResult result = migrationService.run();
        // 保留未知语义交由外层记录核查原因，不能仅凭表级成功标志宣布派发成功。
        if (result.getTermination() != MigrationTermination.CONFIRMED) {
            throw new UnconfirmedWriteException();
        }
        boolean allSuccess = result.getIfAllSuccess().get();
        logDO.setRunStatus(allSuccess
                ? MasterRunStatusEnum.SUCCESS.getStatus() : MasterRunStatusEnum.UNKNOWN.getStatus());
        logDO.setDataCount(result.getTotalRowCount().get());
        logDO.setByteCount(BytesUnitUtils.bytesSizeToHuman(result.getTotalBytes().get()));
        if (!allSuccess) {
            logDO.setErrorInfo("派发未全部成功，需核查目标数据和已生效部分");
        }
    }

    // ==================== API 派发（分批 POST JSON 数组） ====================

    /**
     * 执行API。
     */
    private void executeApi(MasterModelDO model, ApiRequestPlan apiPlan,
            MasterDistributeApiPolicy.Plan mappingPlan, MasterDistributeLogDO logDO) throws Exception {
        AtomicLong dataCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        try {
            readAndBatch(model, apiPlan.fetchSize(), batch -> {
                var payloads = new ArrayList<Map<String, Object>>();
                var receipts = new ArrayList<cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeCheckpointMapper.Checkpoint>();
                for (var row : batch) {
                    if (!mappingPlan.includes(row)) {
                        continue;
                    }
                    var payload = mappingPlan.map(row);
                    payloads.add(payload);
                    if (!mappingPlan.primaryKeys().isEmpty()) {
                        receipts.add(
                                new cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeCheckpointMapper.Checkpoint(
                                        mappingPlan.key(row), MasterDistributeApiPolicy.hash(payload)));
                    }
                }
                if ("INCREMENTAL".equals(mappingPlan.config().getPushMode()) && !receipts.isEmpty()) {
                    var previous = new java.util.HashMap<String, String>();
                    checkpoints.find(logDO.getDistributeId(), receipts.stream().map(r -> r.rowKeyHash()).toList())
                            .forEach(r -> previous.put(r.rowKeyHash(), r.payloadHash()));
                    for (int i = receipts.size() - 1; i >= 0; i--) {
                        if (receipts.get(i).payloadHash().equals(previous.get(receipts.get(i).rowKeyHash()))) {
                            receipts.remove(i);
                            payloads.remove(i);
                        }
                    }
                }
                if (payloads.isEmpty()) {
                    return;
                }
                if (!Integer.valueOf(2).equals(logDO.getRunStatus())) {
                    if (!coordinator.begin(logDO)) {
                        throw new IllegalStateException("运行已停止，未发送");
                    }
                    logDO.setRunStatus(2);
                }
                byte[] body = JsonUtils.toJsonString(payloads).getBytes(StandardCharsets.UTF_8);
                logDO.setTotalCount(dataCount.get() + payloads.size());
                logDO.setUnknownCount((long) payloads.size());
                if (!coordinator.progress(logDO, List.of())) {
                    throw new IllegalStateException("运行租约已失效，未发送");
                }
                sendApiBatch(client, apiPlan.request(body));
                // 仅累计获得传输确认的批次；后批未知时保留已确认前缀，不声称整任务原子。
                logDO.setDataCount(dataCount.addAndGet(payloads.size()));
                logDO.setSuccessCount(dataCount.get());
                logDO.setUnknownCount(0L);
                logDO.setByteCount(BytesUnitUtils.bytesSizeToHuman(byteCount.addAndGet(body.length)));
                if (!coordinator.progress(logDO, receipts)) {
                    throw new IllegalStateException("回执未能持久确认");
                }
            });
            // 空结果同样完成持久运行，但不会发出空 HTTP 请求。
            if (!Integer.valueOf(2).equals(logDO.getRunStatus())) {
                if (!coordinator.begin(logDO)) {
                    throw new IllegalStateException("运行已停止");
                }
                logDO.setRunStatus(2);
            }
        } finally {
            // close() 会等待响应流结束；超时/中断后不做无界等待，也不复用异常会话。
            boolean interrupted = Thread.interrupted();
            try {
                client.shutdownNow();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 主数据 API 配置的不可变发送计划；只组装领域请求，传输交给 JDK 公共 API。 */
    record ApiRequestPlan(URI uri, Map<String, String> headers, int fetchSize, String method, int timeoutSeconds) {

        /**
         * 创建主数据分发处理请求并保存待处理状态。
         */
        HttpRequest request(byte[] body) {
            var consumed = new AtomicBoolean();
            // 即使部署方开启 JDK 全方法重试，也不能第二次提供同一业务请求体。
            var once = HttpRequest.BodyPublishers.ofInputStream(() -> {
                if (!consumed.compareAndSet(false, true)) {
                    return null; // JDK 契约：无法重开流即发送失败。
                }
                return new ByteArrayInputStream(body);
            });
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.fromPublisher(once, body.length));
            headers.forEach(builder::header);
            return builder.build();
        }
    }

    /**
     * 准备主数据分发的API。
     */
    static ApiRequestPlan prepareApi(MasterDistributeConfigVO.ApiConfig apiConfig) {
        if (apiConfig == null || StrUtil.isBlank(apiConfig.getUrl())) {
            throw new IllegalArgumentException("未配置推送地址");
        }
        MasterDistributeApiPolicy.validateOptions(apiConfig);
        String url = apiConfig.getUrl().trim();
        URI uri = URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("推送地址必须是无内嵌身份和片段的有效 HTTP/HTTPS 地址");
        }
        int size = apiConfig.getFetchSize() == null ? API_DEFAULT_FETCH_SIZE : apiConfig.getFetchSize();
        if (size <= 0 || size > 10_000) {
            throw new IllegalArgumentException("API 批次大小必须在 1 到 10000 之间");
        }
        StringBuilder query = new StringBuilder();
        if (apiConfig.getParams() != null) {
            for (MasterDistributeConfigVO.KeyValue param : apiConfig.getParams()) {
                if (param == null) {
                    throw new IllegalArgumentException("查询参数无效");
                }
                if (StrUtil.isBlank(param.getKey()) || param.getValue() == null) {
                    continue;
                }
                if (!query.isEmpty()) {
                    query.append('&');
                }
                query.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
            }
        }
        if (!query.isEmpty()) {
            String separator = !url.contains("?") ? "?" : url.endsWith("?") || url.endsWith("&") ? "" : "&";
            uri = URI.create(url + separator + query);
        }
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        Set<String> reserved = Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade",
                "expect", "te", "trailer", "content-encoding");
        if (apiConfig.getHeaders() != null) {
            for (var header : apiConfig.getHeaders()) {
                if (header == null) {
                    throw new IllegalArgumentException("请求头无效");
                }
                if (StrUtil.isBlank(header.getKey())) {
                    continue;
                }
                String name = header.getKey();
                String value = StrUtil.nullToEmpty(header.getValue());
                if (reserved.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("禁止覆盖传输控制请求头");
                }
                if ("content-type".equalsIgnoreCase(name)) {
                    if (!"application/json".equalsIgnoreCase(value.trim())
                            && !"application/json;charset=utf-8".equalsIgnoreCase(value.replace(" ", ""))) {
                        throw new IllegalArgumentException("主数据 API 仅发送 UTF-8 JSON");
                    }
                    continue;
                }
                // 使用客户端相同的解析器校验 CRLF/非法头，禁止保存时与执行时各写一套规则。
                HttpRequest.newBuilder(uri).header(name, value);
                headers.put(name, value);
            }
        }
        if ("TOKEN".equals(apiConfig.getAuthType())) {
            String token = apiConfig.getToken().strip();
            String value = token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token;
            HttpRequest.newBuilder(uri).header("Authorization", value);
            headers.put("Authorization", value);
        }
        return new ApiRequestPlan(uri, Map.copyOf(headers), size, apiConfig.getMethod(), apiConfig.getTimeoutSeconds());
    }

    /**
     * 发送API批次。
     */
    static void sendApiBatch(HttpClient client, HttpRequest request) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("主数据 HTTP 已中断，未发送下一批");
        }
        java.util.concurrent.CompletableFuture<HttpResponse<Void>> future = null;
        try {
            future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            var response = future.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("主数据 HTTP 传输未确认，状态=" + response.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("主数据 HTTP 已中断，外部结果待核查");
        } catch (Exception ex) {
            // 不附带异常 cause、URL、请求头或响应正文；原运行状态保存 UNKNOWN。
            throw new IllegalStateException("主数据 HTTP 传输未确认，外部结果待核查");
        } finally {
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    // ==================== MQ 派发（Kafka / RabbitMQ） ====================

    /** 保存及执行旧配置共用的纯预检；不建立连接、不进行 DNS 查询。 */
    static void validateMq(MasterDistributeConfigVO.MqConfig mq) {
        if (mq == null || (mq.getFetchSize() != null && (mq.getFetchSize() < 1 || mq.getFetchSize() > 10_000))) {
            throw new IllegalArgumentException("消息队列配置或批次大小不合法");
        }
        if (MQ_TYPE_KAFKA.equals(mq.getMqType())) {
            var kafka = mq.getKafka();
            if (kafka == null || StrUtil.isBlank(kafka.getBootstrapServers()) || kafka.getTopic() == null
                    || !kafka.getTopic().matches("[a-zA-Z0-9._-]{1,249}")
                    || ".".equals(kafka.getTopic()) || "..".equals(kafka.getTopic())) {
                throw new IllegalArgumentException("Kafka 配置不合法");
            }
            for (String address : kafka.getBootstrapServers().split(",", -1)) {
                try {
                    URI endpoint = URI.create("kafka://" + address.trim());
                    if (endpoint.getHost() == null || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                            || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                            || endpoint.getRawFragment() != null || !endpoint.getRawPath().isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Kafka 服务地址不合法");
                }
            }
        } else if (MQ_TYPE_RABBIT.equals(mq.getMqType())) {
            var rabbit = mq.getRabbit();
            if (rabbit == null || StrUtil.isBlank(rabbit.getHost()) || rabbit.getPort() == null
                    || rabbit.getPort() < 1 || rabbit.getPort() > 65535
                    || !validMqShortString(rabbit.getExchange()) || !validMqShortString(rabbit.getRoutingKey())) {
                throw new IllegalArgumentException("RabbitMQ 配置不合法");
            }
        } else {
            throw new IllegalArgumentException("队列类型仅支持 kafka / rabbitmq");
        }
    }

    /**
     * 判断主数据分发的MQ短整数字符串是否有效。
     */
    private static boolean validMqShortString(String value) {
        return StrUtil.isNotBlank(value) && value.indexOf('\0') < 0
                && value.getBytes(StandardCharsets.UTF_8).length <= 255;
    }

    /**
     * 执行MQ。
     */
    private void executeMq(MasterModelDO model, MasterDistributeConfigVO.MqConfig mqConfig,
            MasterDistributeLogDO logDO) throws Exception {
        int fetchSize = mqConfig.getFetchSize() == null ? API_DEFAULT_FETCH_SIZE : mqConfig.getFetchSize();
        AtomicLong dataCount = new AtomicLong();
        AtomicLong byteCount = new AtomicLong();
        try {
            if (MQ_TYPE_KAFKA.equals(mqConfig.getMqType())) {
                MasterDistributeConfigVO.KafkaConfig kafka = mqConfig.getKafka();
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                // 本领域没有可配置的弱确认兼容值；SDK 只在同一幂等生产者会话内重试。
                props.put(ProducerConfig.ACKS_CONFIG, "all");
                props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
                props.put(ProducerConfig.RETRIES_CONFIG, String.valueOf(Integer.MAX_VALUE));
                props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
                props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
                props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
                props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(HTTP_TIMEOUT_MS));
                props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
                KafkaProducer<String, String> producer = new KafkaProducer<>(props);
                try {
                    readAndBatch(model, fetchSize, batch -> {
                        String message = JsonUtils.toJsonString(batch);
                        requireMqNotInterrupted();
                        var receipt = producer.send(new ProducerRecord<>(kafka.getTopic(), message))
                                .get(HTTP_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS);
                        if (receipt == null || !receipt.hasOffset() || !kafka.getTopic().equals(receipt.topic())) {
                            throw new IllegalStateException("Kafka 未返回有效投递回执");
                        }
                        requireMqNotInterrupted();
                        logDO.setDataCount(dataCount.addAndGet(batch.size()));
                        logDO.setByteCount(BytesUnitUtils.bytesSizeToHuman(
                                byteCount.addAndGet(message.getBytes(StandardCharsets.UTF_8).length)));
                    });
                } finally {
                    boolean interrupted = Thread.interrupted();
                    try {
                        // 禁止无界 close/flush 在失败后继续排空发送；已确认的前缀不需要再 flush。
                        producer.close(Duration.ZERO);
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } else if (MQ_TYPE_RABBIT.equals(mqConfig.getMqType())) {
                MasterDistributeConfigVO.RabbitConfig rabbit = mqConfig.getRabbit();
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost(rabbit.getHost());
                factory.setPort(rabbit.getPort());
                if (StrUtil.isNotBlank(rabbit.getUsername())) {
                    factory.setUsername(rabbit.getUsername());
                }
                if (rabbit.getPassword() != null) {
                    factory.setPassword(rabbit.getPassword());
                }
                factory.setVirtualHost(StrUtil.blankToDefault(rabbit.getVirtualHost(), "/"));
                factory.setConnectionTimeout(HTTP_TIMEOUT_MS);
                factory.setHandshakeTimeout(HTTP_TIMEOUT_MS);
                factory.setChannelRpcTimeout(HTTP_TIMEOUT_MS);
                factory.setAutomaticRecoveryEnabled(false);
                factory.setTopologyRecoveryEnabled(false);
                Connection connection = factory.newConnection();
                try {
                    Channel channel = connection.createChannel();
                    if (channel == null) {
                        throw new IllegalStateException("RabbitMQ 通道不可用");
                    }
                    AtomicBoolean returned = new AtomicBoolean();
                    // return 在 confirm 前由客户端分发；只保留标志，不保留正文/远端错误。
                    channel.addReturnListener((code, text, exchange, key, properties, body) -> returned.set(true));
                    channel.confirmSelect();
                    AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                            .deliveryMode(2).contentType("application/json").contentEncoding("UTF-8").build();
                    readAndBatch(model, fetchSize, batch -> {
                        String message = JsonUtils.toJsonString(batch);
                        byte[] body = message.getBytes(StandardCharsets.UTF_8);
                        requireMqNotInterrupted();
                        channel.basicPublish(rabbit.getExchange(), rabbit.getRoutingKey(), true, properties, body);
                        channel.waitForConfirmsOrDie(HTTP_TIMEOUT_MS);
                        if (returned.get()) {
                            throw new IllegalStateException("RabbitMQ 消息未路由到队列");
                        }
                        requireMqNotInterrupted();
                        logDO.setDataCount(dataCount.addAndGet(batch.size()));
                        logDO.setByteCount(BytesUnitUtils.bytesSizeToHuman(byteCount.addAndGet(body.length)));
                    });
                } finally {
                    boolean interrupted = Thread.interrupted();
                    try {
                        // 每次运行独占连接，任何结果均终止连接/通道，不自动恢复或复用异常会话。
                        connection.abort(1_000);
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        } catch (InterruptedException | org.apache.kafka.common.errors.InterruptException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MQ 派发被中断，结果需核查");
        } catch (Exception ex) {
            // 不把 broker 错误、地址、账号和消息正文带入外部异常。
            throw new IllegalStateException("MQ 未获得完整投递确认，禁止直接重跑");
        }
    }

    /**
     * 校验主数据分发的MQ非已中断，不满足约束时拒绝当前操作。
     */
    private static void requireMqNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("MQ 派发被中断");
        }
    }

    // ==================== 公共源端读取 ====================

    @FunctionalInterface
    private interface BatchConsumer {

        /**
         * 受理主数据分发请求并保存可恢复的执行记录。
         */
        void accept(List<Map<String, Object>> batch) throws Exception;
    }

    /**
     * 全量读取主数据物理表，按批回调（API / MQ 派发共用）
     */
    private void readAndBatch(MasterModelDO model, int fetchSize, BatchConsumer consumer) throws Exception {
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(model.getId());
        if (columns.isEmpty()) {
            throw new IllegalStateException("主数据模型未定义字段");
        }
        List<String> columnNames = convertList(columns, MasterColumnDO::getName);
        MasterDatabaseResolver.ConnectionInfo conn = resolveModelConnection(model);
        HikariDataSource dataSource = databaseResolver.createDataSource(conn);
        boolean interruptedDuringRead = false;
        try {
            IDatabaseOperator operator = dbSwitchRuntime.reader(
                    dataSource, conn.getProductType());
            operator.setFetchSize(fetchSize);
            try (StatementResultSet srs = operator.queryTableData(
                    conn.getSchema(), model.getTableName(), columnNames);
                 ResultSet rs = srs.getResultset()) {
                try {
                    List<Map<String, Object>> batch = new ArrayList<>(fetchSize);
                    while (rs.next()) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IllegalStateException("任务已被中断");
                        }
                        batch.add(buildRowMap(columnNames, rs));
                        if (batch.size() >= fetchSize) {
                            consumer.accept(MasterDataServiceImpl.toWireRows(batch));
                            batch = new ArrayList<>(fetchSize);
                        }
                    }
                    if (!batch.isEmpty()) {
                        consumer.accept(MasterDataServiceImpl.toWireRows(batch));
                    }
                } finally {
                    // 在 ResultSet/Statement 关闭和连接归还之前保存信号，避免 JDBC 吞掉它。
                    interruptedDuringRead = Thread.interrupted();
                }
            }
        } finally {
            // HTTP 取消保留中断标志；清理本任务拥有的连接池时暂清标志，避免关闭流程直接中止。
            boolean interrupted = Thread.interrupted() || interruptedDuringRead;
            try {
                dataSource.close();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 行转 Map：数字/布尔保留原生类型，二进制转十六进制字符串，其余转字符串
     */
    private Map<String, Object> buildRowMap(List<String> columns, ResultSet rs) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : columns) {
            Object value = rs.getObject(column);
            if (value == null || value instanceof Number || value instanceof Boolean) {
                row.put(column, value);
            } else if (value instanceof byte[] bytes) {
                row.put(column, HexUtil.encodeHexStr(bytes));
            } else if (value instanceof Blob blob) {
                row.put(column, HexUtil.encodeHexStr(blob.getBytes(1, (int) blob.length())));
            } else if (value instanceof Clob clob) {
                row.put(column, clob.getSubString(1, (int) clob.length()));
            } else {
                row.put(column, value.toString());
            }
        }
        return row;
    }

    /**
     * 解析主数据分发的模型连接。
     */
    private MasterDatabaseResolver.ConnectionInfo resolveModelConnection(MasterModelDO model) {
        return databaseResolver.resolve(model.getDatabaseId(), MASTER_MODEL_DATABASE_NOT_EXISTS);
    }

}
