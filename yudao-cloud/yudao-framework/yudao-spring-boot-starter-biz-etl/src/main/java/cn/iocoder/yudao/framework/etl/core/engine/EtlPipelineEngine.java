package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可视化 ETL 流程执行引擎
 *
 * 参考 srt-cloud 的 EtlEngine：所有节点各占一个线程并发启动，
 * 数据边通过有界内存通道流水线传递，控制边等待上游完成后再执行。
 * execute 为阻塞调用（由业务侧线程池承载），中断调用线程即请求停止
 *
 * @author Li
 */
@Slf4j
public class EtlPipelineEngine implements AutoCloseable {

    private final EtlExecutorRegistry executorRegistry;

    private final cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime databaseRuntime;

    private final boolean ownsRuntime;

    private final cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy fileOutputPolicy;

    private final cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner scriptRunner;

    /**
     * 使用给定参数初始化 {@code EtlPipelineEngine}。
     */
    public EtlPipelineEngine(EtlExecutorRegistry registry) {
        this(registry, cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone(), true,
                cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy.disabled());
    }

    /**
     * 使用给定参数初始化 {@code EtlPipelineEngine}。
     */
    public EtlPipelineEngine(EtlExecutorRegistry registry,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime) {
        this(registry, runtime, false, cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy.disabled());
    }

    /**
     * 使用给定参数初始化 {@code EtlPipelineEngine}。
     */
    public EtlPipelineEngine(EtlExecutorRegistry registry,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy policy) {
        this(registry, runtime, false, policy);
    }

    /**
     * 使用给定参数初始化 {@code EtlPipelineEngine}。
     */
    public EtlPipelineEngine(EtlExecutorRegistry registry,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy policy,
            cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner scriptRunner) {
        this(registry, runtime, false, policy, scriptRunner);
    }

    /**
     * 使用给定参数初始化 {@code EtlPipelineEngine}。
     */
    private EtlPipelineEngine(EtlExecutorRegistry registry,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime,
            boolean ownsRuntime, cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy policy) {
        this(registry, runtime, ownsRuntime, policy, new cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner(
                cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.disabled()));
    }

    /**
     * 校验构造参数并初始化 {@code EtlPipelineEngine}。
     */
    private EtlPipelineEngine(EtlExecutorRegistry registry,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime,
            boolean ownsRuntime, cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy policy,
            cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner scriptRunner) {
        executorRegistry = java.util.Objects.requireNonNull(registry);
        databaseRuntime = java.util.Objects.requireNonNull(runtime);
        this.ownsRuntime = ownsRuntime;
        this.fileOutputPolicy = java.util.Objects.requireNonNull(policy);
        this.scriptRunner = java.util.Objects.requireNonNull(scriptRunner);
    }

    /**
     * 关闭 {@code EtlPipelineEngine} 当前持有的资源。
     */
    @Override
    public void close() {
        if (ownsRuntime) {
            databaseRuntime.close();
        }
    }

    /** 未确认退出的执行占住名额，避免遗留线程后继续接收任务耗尽进程。 */
    private static final java.util.concurrent.Semaphore RUN_SLOTS = new java.util.concurrent.Semaphore(4);

    /**
     * 阻塞执行一个流程 DAG
     */
    public EtlRunResult execute(EtlDag dag, EtlRunOptions options) {
        if (dag.getNodes().isEmpty() || dag.getNodes().size() > options.getMaxNodes()
                || options.getMaxNodes() > 64 || options.getTimeoutSeconds() <= 0
                || options.getMaxRows() <= 0 || options.getMaxBytes() <= 0 || options.getMaxRowBytes() <= 0) {
            throw new IllegalArgumentException("流程节点数或资源预算无效，轻量 ETL 最多允许 64 个节点");
        }
        long sampleMillis = options.getRuntimeSampleMillis();
        if (sampleMillis != 0 && (sampleMillis < 500 || sampleMillis > 60_000)) {
            throw new IllegalArgumentException("运行观测采样间隔须为 0 或 500—60000 毫秒");
        }
        if (dag.getNodes().stream()
                .anyMatch(node -> "java-script".equals(node.getToolCode()) || "js-script".equals(node.getToolCode()))) {
            scriptRunner.ensureAvailable();
        }
        EtlRunListener listener = options.getListener() != null ? options.getListener() : new EtlRunListener() {
        };
        EtlVariableContext variables = new EtlVariableContext(options.getParams());
        EtlRunContext context = new EtlRunContext(dag, options.getConnectionResolver(), listener, variables,
                databaseRuntime);
        context.setScriptRunner(scriptRunner);
        context.setApiPort(options.getApiPort());
        context.setRowPolicyPort(options.getRowPolicyPort());
        context.setReadPolicyPort(options.getReadPolicyPort());
        context.setStagedOutputPort(options.getStagedOutputPort());
        context.setRowObserver(options.getRowObserver());
        context.setFileOutputs(new cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputs(
                options.getFileOutputPolicy() == null ? fileOutputPolicy : options.getFileOutputPolicy(),
                options.getFileOutputPort()));
        context.setLimits(options);

        EtlRunResult result = new EtlRunResult();
        listener.onPipelineLog(String.format("流程开始执行，共 %s 个节点", dag.getNodes().size()));

        // 先创建全部执行器（配置错误在启动前暴露）
        List<AbstractEtlNodeExecutor> executors = new ArrayList<>();
        for (EtlNodeDef node : dag.getNodes()) {
            executors.add(executorRegistry.create(node, context));
        }

        if (!RUN_SLOTS.tryAcquire()) {
            throw new IllegalStateException("ETL 执行资源已满，包含可能仍未退出的节点");
        }
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(dag.getNodes().size(), runnable -> {
            Thread thread = new Thread(runnable, "etl-node-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        var control = options.getExecutionControl();
        if (control != null) {
            control.register(() -> {
                context.requestStop();
                pool.shutdownNow();
            }, pool::isTerminated);
        }
        long startedNanos = System.nanoTime();
        EtlRuntimeSampler sampler = sampleMillis > 0 && options.getListener() != null
                ? new EtlRuntimeSampler(context, executors, startedNanos) : null;
        long nextSample = startedNanos + TimeUnit.MILLISECONDS.toNanos(sampleMillis);
        boolean sampleFailureLogged = false;
        try {
            executors.forEach(executor -> {
                Runnable task = executor::run;
                pool.submit(options.getTaskWrapper() == null ? task : options.getTaskWrapper().apply(task));
            });
            CompletableFuture<Void> completion = CompletableFuture.allOf(
                    context.getNodeFutures().values().toArray(new CompletableFuture[0]));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.getTimeoutSeconds());
            while (!completion.isDone()) {
                if (context.failureMessage() != null) {
                    result.setFirstError(context.failureMessage());
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    context.fail("流程执行超过截止时间");
                    result.setFirstError(context.failureMessage());
                    break;
                }
                if (control != null && control.stopRequested()
                        || options.getStopRequested() != null && options.getStopRequested().getAsBoolean()) {
                    context.requestStop();
                    result.setStopped(true);
                    break;
                }
                if (sampler != null && System.nanoTime() >= nextSample) {
                    sampleFailureLogged = emitSample(listener, sampler, false, sampleFailureLogged);
                    nextSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sampleMillis);
                }
                try {
                    completion.get(250, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // 周期检查持久停止和流程截止时间。
                }
            }
        } catch (InterruptedException ex) {
            // 调用线程被中断 → 请求停止，等待节点线程按停止标记收尾后再恢复中断位
            context.requestStop();
            result.setStopped(true);
            listener.onPipelineLog("收到停止指令，正在终止各节点...");
            awaitQuietly(context);
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("[execute][ETL 流程执行异常]", ex);
            result.setFirstError(StrUtil.maxLength(ex.getMessage(), 500));
        } finally {
            context.requestStop();
            if (control != null && (result.isStopped() || result.getFirstError() != null || context.failureMessage() != null)) {
                control.requestStop();
            }
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    result.setTerminationUnconfirmed(true);
                    result.setFirstError("节点未确认退出，外部副作用待核查");
                }
            } catch (InterruptedException interrupted) {
                result.setTerminationUnconfirmed(true);
                Thread.currentThread().interrupt();
            }
            if (context.isTerminationUnconfirmed()) {
                result.setTerminationUnconfirmed(true);
                result.setFirstError("节点外部进程或读取线程未确认退出，需核查副作用");
            }
            if (control != null && !control.resourcesExited()) {
                result.setTerminationUnconfirmed(true);
            }
            if (!result.isTerminationUnconfirmed()) {
                RUN_SLOTS.release();
            } else if (control != null && !context.isTerminationUnconfirmed()) {
                control.whenResourcesExited(RUN_SLOTS::release);
            }
        }
        if (sampler != null) {
            emitSample(listener, sampler, true, sampleFailureLogged);
        }

        // 汇总节点结果
        if (result.getFirstError() == null && context.failureMessage() != null) {
            result.setFirstError(context.failureMessage());
        }
        int failed = 0;
        for (EtlNodeDef node : dag.getNodes()) {
            Boolean success = context.getNodeFuture(node.getNo()).getNow(false);
            if (!Boolean.TRUE.equals(success)) {
                failed++;
                if (result.getFirstError() == null) {
                    result.setFirstError(String.format("节点【%s】执行失败", node.getName()));
                }
            }
        }
        result.setFailedNodeCount(failed);
        result.setDatabaseProgress(java.util.Map.copyOf(context.getDatabaseProgress()));
        result.setDatabaseCommits(java.util.Map.copyOf(context.getDatabaseCommits()));
        result.setNodePortCounts(java.util.Map.copyOf(context.getNodePortCounts()));
        result.setTransferredRows(context.getTransferredRows().get());
        result.setTransferredBytes(context.getTransferredBytes().get());
        result.setPeakBufferedBytes(context.getPeakBufferedBytes().get());
        result.setSuccess(failed == 0 && !result.isStopped() && !result.isTerminationUnconfirmed()
                && result.getFirstError() == null && context.failureMessage() == null);
        try {
            if (result.isSuccess() && options.getStopRequested() != null && options.getStopRequested().getAsBoolean()) {
                result.setStopped(true);
                result.setSuccess(false);
            }
            if (result.isSuccess()) {
                context.getFileOutputs().publish();
            }
        } catch (Exception ex) {
            result.setSuccess(false);
            result.setFirstError("文件发布失败：" + StrUtil.maxLength(ex.getMessage(), 300));
        } finally {
            context.getFileOutputs().discard(!result.isTerminationUnconfirmed());
            result.setFileOutputStates(context.getFileOutputs().states());
            if (result.getFileOutputStates().containsValue("CLEANUP_PENDING")
                    || result.getFileOutputStates().containsValue("PUBLISHED_CLEANUP_PENDING")) {
                result.setSuccess(false);
                if (result.getFirstError() == null) {
                    result.setFirstError("临时文件清理失败，需人工核查");
                }
            }
        }
        listener.onPipelineLog(result.isStopped() ? "流程已停止"
                : String.format("流程执行%s，失败节点数：%s", result.isSuccess() ? "成功" : "失败", failed));
        return result;
    }

    /** 观测回调失败不改变执行结果，同一次运行只记录一次日志；返回是否已记录。 */
    private static boolean emitSample(EtlRunListener listener, EtlRuntimeSampler sampler, boolean finished,
            boolean failureLogged) {
        try {
            listener.onRuntimeSample(sampler.sample(finished));
            return failureLogged;
        } catch (RuntimeException ex) {
            if (!failureLogged) {
                log.warn("[execute][ETL 运行观测回调失败] {}", ex.getClass().getSimpleName());
            }
            return true;
        }
    }

    /**
     * 停止后给节点线程最多 30 秒的收尾时间（不再响应中断）
     */
    private void awaitQuietly(EtlRunContext context) {
        long deadline = System.currentTimeMillis() + 30_000L;
        for (CompletableFuture<Boolean> future : context.getNodeFutures().values()) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return;
            }
            try {
                future.get(remain, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // 停止收尾阶段的异常不影响结果
            }
        }
    }

}
