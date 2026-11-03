package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.EtlPreviewProperties;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 有数量上限且归创建者所有的临时会话；每个实例仅持有自身启动 UUID 与资源。 */
@Service
public class EtlPreviewSessionService {

    private final EtlDesignService design;

    private final ExecutionAccessService access;

    private final EtlPreviewProperties config;

    private final String instanceId = UUID.randomUUID().toString();

    private final Map<String, Session> sessions = new HashMap<>();

    private final ExecutorService workers;

    private final ScheduledExecutorService maintenance;

    private Clock clock = Clock.systemUTC();

    private boolean closed;

    /**
     * 创建ETL 预览会话实例。
     *
     * @param design 设计
     * @param access 访问
     * @param config 处理配置
     */
    public EtlPreviewSessionService(EtlDesignService design, ExecutionAccessService access,
            EtlPreviewProperties config) {
        this.design = design;
        this.access = access;
        this.config = config;
        workers = Executors.newFixedThreadPool(config.getConcurrency(), task -> daemon(task, "etl-preview-session"));
        maintenance = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "etl-preview-session-cleanup"));
    }

    /**
     * 启动ETL 预览会话。
     */
    @PostConstruct
    void start() {
        maintenance.scheduleWithFixedDelay(this::maintenanceTick, 1, 1, TimeUnit.SECONDS);
    }

    public record Capabilities(String instanceId, String mode, int retentionSeconds, int executionSeconds,
                               int concurrency, long issuedAt) {

    }

    /**
     * 获取ETL 预览会话的能力。
     *
     * @return 能力
     */
    public Capabilities capabilities() {
        owner(true);
        return new Capabilities(instanceId, "INSTANCE_LOCAL", config.getRetentionSeconds(),
                config.getExecutionSeconds(), config.getConcurrency(), now());
    }

    /**
     * 创建ETL 预览会话。
     *
     * @param request 请求参数
     * @return ETL 预览会话响应数据
     */
    public EtlPreviewSessionRespVO create(EtlPreviewSessionReqVO request) {
        Owner owner = owner(true);
        requireInstance(request.getInstanceId());
        String json = JsonUtils.toJsonString(request);
        if (json.length() > 1024 * 1024) {
            throw exception(ETL_PREVIEW_CAPACITY);
        }
        String hash = DigestUtil.sha256Hex(json);
        String key = key(owner, request.getRequestId());
        Session session;
        synchronized (sessions) {
            maintenanceLocked();
            session = sessions.get(key);
            if (session != null) {
                if (session.hash != null && !session.hash.equals(hash)) {
                    throw exception(ETL_PREVIEW_REQUEST_CONFLICT);
                }
                return response(session, false);
            }
            if (request.getIssuedAt() == null || request.getIssuedAt() > now() + 5_000L || now() - request.getIssuedAt() > 60_000L) {
                throw exception(ETL_PREVIEW_REQUEST_CONFLICT);
            }
            if (closed || !retentionCapacity(owner)
                    || sessions.values().stream().filter(s -> !exited(s)).count() >= config.getConcurrency()
                    || sessions.values().stream().anyMatch(s -> s.owner.equals(owner) && !exited(s))) {
                throw exception(ETL_PREVIEW_CAPACITY);
            }
            session = new Session(owner, request.getRequestId(), hash,
                    JsonUtils.parseObject(json, EtlPreviewSessionReqVO.class), now());
            sessions.put(key, session);
            Session submitted = session;
            try {
                workers.execute(() -> execute(submitted));
            } catch (RejectedExecutionException failure) {
                sessions.remove(key);
                throw exception(ETL_PREVIEW_CAPACITY);
            }
            return response(session, false);
        }
    }

    /**
     * 读取ETL 预览会话响应数据。
     *
     * @param key 标识键
     * @return ETL 预览会话响应数据
     */
    public EtlPreviewSessionRespVO get(EtlPreviewSessionKeyReqVO key) {
        Owner owner = owner(true);
        Session session = find(owner, key);
        EtlPreviewEngine.Result result;
        synchronized (sessions) {
            maintenanceLocked();
            result = session.result;
            if (result != null && result.success()) {
                if (session.verifying) {
                    throw exception(ETL_PREVIEW_CAPACITY);
                }
                session.verifying = true;
            }
        }
        if (result != null && result.success()) {
            try {
                session.guard.verify();
            } catch (RuntimeException denied) {
                synchronized (sessions) {
                    session.result = null;
                    session.guard.clear();
                    session.restricted = true;
                }
                throw exception(ETL_PREVIEW_RESULT_RESTRICTED);
            } finally {
                synchronized (sessions) {
                    session.verifying = false;
                }
            }
        }
        synchronized (sessions) {
            return response(session, true);
        }
    }

    /**
     * 取消ETL 预览会话。
     *
     * @param key 标识键
     * @return ETL 预览会话响应数据
     */
    public EtlPreviewSessionRespVO cancel(EtlPreviewSessionKeyReqVO key) {
        Owner owner = owner(false);
        requireInstance(key.getInstanceId());
        synchronized (sessions) {
            maintenanceLocked();
            Session session = sessions.get(key(owner, key.getRequestId()));
            if (session == null) {
                // 取消可能早于创建请求到达；保留有数量上限的墓碑记录，防止迟到的创建请求启动工作。
                if (closed || !retentionCapacity(owner)) {
                    throw exception(ETL_PREVIEW_CAPACITY);
                }
                session = new Session(owner, key.getRequestId(), null, null, now());
                session.workerExited = true;
                sessions.put(key(owner, key.getRequestId()), session);
            }
            requestStop(session, false);
            return response(session, false);
        }
    }

    /**
     * 查找会话。
     *
     * @param owner 所有者
     * @param key 标识键
     * @return 会话
     */
    private Session find(Owner owner, EtlPreviewSessionKeyReqVO key) {
        requireInstance(key.getInstanceId());
        synchronized (sessions) {
            maintenanceLocked();
            Session session = sessions.get(key(owner, key.getRequestId()));
            if (session == null) {
                throw exception(ETL_PREVIEW_NOT_FOUND);
            }
            return session;
        }
    }

    /**
     * 执行ETL 预览会话处理。
     *
     * @param session 会话
     */
    private void execute(Session session) {
        session.worker = Thread.currentThread();
        try {
            {
                access.requireUserPermission(session.owner.actor, "dataforge:etl:query");
                access.requireUserPermission(session.owner.actor, "dataforge:etl:run");
                if (session.control.stopRequested()) {
                    return;
                }
                var result = design.preview(session.request, session.owner.actor, session.control, session.guard);
                synchronized (sessions) {
                    if (!session.cancelled && !session.expired) {
                        session.result = result;
                    }
                }
            }
            ;
        } catch (RuntimeException failure) {
            synchronized (sessions) {
                session.failed = true;
                session.result = null;
                session.guard.clear();
            }
        } finally {
            synchronized (sessions) {
                session.request = null;
                session.worker = null;
                session.workerExited = true;
                if (session.result == null || !session.result.success()) {
                    session.guard.clear();
                }
            }
            Thread.interrupted();
        }
    }

    /** 本地有数量上限的维护，不执行租户 SQL、RPC 或集群协调。 */
    void maintenanceTick() {
        synchronized (sessions) {
            maintenanceLocked();
        }
    }

    /**
     * 在会话维护锁内处理执行超时、取消及结果过期，待资源退出后移除过期会话。
     */
    private void maintenanceLocked() {
        long now = now();
        for (var session : sessions.values()) {
            if (!executionExited(session) && now - session.createdAt >= config.getExecutionSeconds() * 1000L) {
                requestStop(session, true);
            }
            if (!exited(session) && session.control.stopRequested() && !session.cancelled) {
                requestStop(session, false);
            }
            if (now >= session.expiresAt) {
                session.result = null;
                session.guard.clear();
                if (!exited(session)) {
                    requestStop(session, true);
                }
            }
            session.control.resourcesExited();
        }
        sessions.values().removeIf(s -> now >= s.expiresAt + 60_000L && exited(s));
    }

    /**
     * 请求停止。
     *
     * @param session 会话
     * @param expired 过期
     */
    private void requestStop(Session session, boolean expired) {
        session.cancelled = true;
        session.expired |= expired;
        if (session.stopAt == 0) {
            session.stopAt = now();
        }
        session.result = null;
        session.guard.clear();
        session.control.requestStop();
        // 同时中断准备与 RPC 工作线程；停止请求不等于退出确认。
        Thread worker = session.worker;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * 判断执行已退出条件是否成立。
     *
     * @param session 会话
     * @return 处理是否成功
     */
    private boolean executionExited(Session session) {
        return session.workerExited && session.control.resourcesExited();
    }

    /**
     * 判断已退出条件是否成立。
     *
     * @param session 会话
     * @return 处理是否成功
     */
    private boolean exited(Session session) {
        return executionExited(session) && !session.verifying;
    }

    /**
     * 判断保留容量条件是否成立。
     *
     * @param owner 所有者
     * @return 处理是否成功
     */
    private boolean retentionCapacity(Owner owner) {
        return sessions.size() < 64
                && sessions.values().stream().filter(s -> s.owner.equals(owner)).count() < 8
                && sessions.values().stream().count() < 32
                && sessions.values().stream().filter(this::retainsSamples).count() < config.getMaxSessions()
                && sessions.values().stream().filter(s -> s.owner.equals(owner) && retainsSamples(s)).count() < 2
                && sessions.values().stream().filter(s -> retainsSamples(s)).count() < 4;
    }

    /**
     * 判断保留样本条件是否成立。
     *
     * @param session 会话
     * @return 处理是否成功
     */
    private boolean retainsSamples(Session session) {
        return session.result != null || !exited(session);
    }

    /**
     * 组装ETL 预览会话响应数据。
     *
     * @param s 文本值
     * @param includeResult 包含结果
     * @return ETL 预览会话响应数据
     */
    private EtlPreviewSessionRespVO response(Session s, boolean includeResult) {
        boolean exited = exited(s);
        boolean elapsed = s.stopAt != 0 && now() - s.stopAt >= config.getCleanupGraceSeconds() * 1000L;
        String cleanup = exited ? "CONFIRMED" : elapsed ? "UNCONFIRMED" : "RUNNING";
        String status = s.restricted ? "RESULT_RESTRICTED" : s.cancelled
                ? exited ? (s.expired ? "EXPIRED" : "CANCELLED") : elapsed ? "TERMINATION_UNCONFIRMED" : "CANCEL_REQUESTED"
                : !exited ? "RUNNING" : s.failed || s.result == null || !s.result.success() ? "FAILED" : "SUCCEEDED";
        if (now() >= s.expiresAt && exited) {
            status = "EXPIRED";
        }
        String message = switch (status) {
            case "TERMINATION_UNCONFIRMED" -> "已请求停止，但执行线程或数据库资源尚未确认退出，仍占用并发名额。";
            case "CANCEL_REQUESTED" -> "已请求停止，正在等待执行线程和数据库资源退出。";
            case "FAILED" -> "预览未通过；请检查权限、配置或预算。";
            case "EXPIRED" -> "短时会话已过期，样本已清除。";
            case "RESULT_RESTRICTED" -> "当前权限或脱敏结果无法复核，缓存结果已清除，请重新预览。";
            default -> null;
        };
        return new EtlPreviewSessionRespVO(instanceId, s.id, "INSTANCE_LOCAL", status, cleanup, s.workerExited,
                s.expiresAt, message,
                includeResult && exited && !s.cancelled && !s.restricted && now() < s.expiresAt ? s.result : null);
    }

    /**
     * 获取ETL 预览会话的所有者。
     *
     * @param run 运行
     * @return 所有者
     */
    private Owner owner(boolean run) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        access.requireUserPermission(actor, "dataforge:etl:query");
        if (run) {
            access.requireUserPermission(actor, "dataforge:etl:run");
        }
        return new Owner(actor);
    }

    /**
     * 校验实例。
     *
     * @param instance 实例
     */
    private void requireInstance(String instance) {
        if (!instanceId.equals(instance)) {
            throw exception(ETL_PREVIEW_NOT_FOUND);
        }
    }

    /**
     * 构造ETL 预览会话标识键。
     *
     * @param owner 所有者
     * @param id 记录编号
     * @return 构造ETL 预览会话标识键文本
     */
    private static String key(Owner owner, String id) {
        return owner.actor + ":" + id;
    }

    /**
     * 读取当前组件时钟时间。
     *
     * @return 当前时间数值
     */
    private long now() {
        return clock.millis();
    }

    /**
     * 创建用于当前后台处理的守护线程。
     *
     * @param task 任务记录
     * @param name 名称
     * @return 线程
     */
    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 关闭当前实例持有的连接、会话或执行资源。
     */
    @PreDestroy
    public void close() {
        synchronized (sessions) {
            closed = true;
            sessions.values().forEach(s -> requestStop(s, true));
        }
        maintenance.shutdownNow();
        workers.shutdownNow();
    }

    private record Owner(Long actor) {

    }

    private final class Session {

        final Owner owner;

        final String id, hash;

        final long createdAt, expiresAt;

        final EtlExecutionControl control = new EtlExecutionControl();

        final EtlPreviewResultGuard guard = new EtlPreviewResultGuard();

        EtlPreviewReqVO request;

        EtlPreviewEngine.Result result;

        volatile Thread worker;

        volatile boolean workerExited;

        boolean cancelled, expired, failed, restricted, verifying;

        long stopAt;

        /**
         * 创建会话实例。
         *
         * @param owner 所有者
         * @param id 记录编号
         * @param hash 摘要
         * @param request 请求参数
         * @param createdAt 已创建时间
         */
        Session(Owner owner, String id, String hash, EtlPreviewReqVO request, long createdAt) {
            this.owner = owner;
            this.id = id;
            this.hash = hash;
            this.request = request;
            this.createdAt = createdAt;
            this.expiresAt = createdAt + config.getRetentionSeconds() * 1000L;
        }
    }
}
