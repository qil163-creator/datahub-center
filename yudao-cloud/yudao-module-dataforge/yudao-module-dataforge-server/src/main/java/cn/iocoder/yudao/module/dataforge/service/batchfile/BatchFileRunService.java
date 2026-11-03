package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.dataforge.service.batchfile.BatchFileRules.*;

/** 持久请求幂等、单任务占用及租约围栏；租约失效只标未知，不自动重放。 */
@Service
public class BatchFileRunService {

    @Resource
    private BatchFileTaskMapper tasks;

    @Resource
    private BatchFileRunMapper runs;

    @Resource
    private BatchFileService service;

    @Resource
    private BatchFileTargetAdapter target;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private TransactionTemplate transaction;

    private final Set<Long> local = ConcurrentHashMap.newKeySet();

    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), r -> {
        Thread t = new Thread(r, "batch-file-worker");
        t.setDaemon(true);
        return t;
    },
            new ThreadPoolExecutor.AbortPolicy());

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "batch-file-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * 执行批次文件运行处理。
     *
     * @param request 请求参数
     * @param actor 操作人编号
     * @return 回执
     */
    public Receipt execute(Execute request, Long actor) {
        access.requireUserPermission(actor, "dataforge:batch-file:execute");
        try {
            if (!UUID.fromString(request.getRequestId()).toString().equalsIgnoreCase(request.getRequestId())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "请求编号必须为 UUID");
        }
        String requestId = request.getRequestId().toLowerCase(Locale.ROOT);
        Receipt receipt;
        try {
            receipt = transaction.execute(tx -> {
                BatchFileTaskDO task = tasks.lock(request.getId());
                if (task == null) {
                    throw exception(BATCH_FILE_NOT_EXISTS);
                }
                BatchFileRunDO previous = byRequest(requestId);
                if (previous != null) {
                    return replay(previous, task.getId(), actor);
                }
                if (!Boolean.TRUE.equals(task.getOnline()) || runs.countActive(task.getId()) > 0) {
                    throw exception(BATCH_FILE_STATE_INVALID);
                }
                Task snapshot = service.view(task);
                BatchFileRunDO run = new BatchFileRunDO();
                run.setTaskId(task.getId());
                run.setRequestId(requestId);
                run.setExecutionUserId(actor);
                run.setConfigJson(json(snapshot));
                run.setStatus("QUEUED");
                run.setFileCount(snapshot.getFiles().size());
                run.setSuccessCount(0);
                run.setFailedCount(0);
                run.setWrittenRows(0L);
                run.setItemsJson(json(initialItems(snapshot)));
                audit(run, actor.toString());
                runs.insert(run);
                task.setLastRunId(run.getId());
                task.setLastStatus("QUEUED");
                task.setLastRunTime(LocalDateTime.now());
                task.setSuccessFiles(0);
                task.setFailedFiles(0);
                audit(task, actor.toString());
                tasks.updateById(task);
                return new Receipt(run.getId(), run.getStatus(), false);
            });
        } catch (DuplicateKeyException ex) {
            BatchFileRunDO previous = byRequest(requestId);
            if (previous == null) {
                throw exception(BATCH_FILE_STATE_INVALID);
            }
            receipt = replay(previous, request.getId(), actor);
        }
        submit(receipt.id());
        return receipt;
    }

    /**
     * 获取批次文件运行的按请求。
     *
     * @param key 标识键
     * @return 批次文件运行
     */
    private BatchFileRunDO byRequest(String key) {
        return runs.selectOne(BatchFileRunDO::getRequestId, key);
    }

    /**
     * 重放批次文件运行。
     *
     * @param run 运行
     * @param taskId 任务编号
     * @param actor 操作人编号
     * @return 回执
     */
    private Receipt replay(BatchFileRunDO run, Long taskId, Long actor) {
        if (!Objects.equals(run.getTaskId(), taskId) || !Objects.equals(run.getExecutionUserId(), actor)) {
            throw exception(BATCH_FILE_CONFLICT);
        }
        return new Receipt(run.getId(), run.getStatus(), true);
    }

    /**
     * 获取批次文件运行的初始项。
     *
     * @param task 任务记录
     * @return 批次文件运行的初始项结果集合
     */
    static List<RunItem> initialItems(Task task) {
        Map<Long, File> files = new HashMap<>();
        task.getFiles().forEach(f -> files.put(f.getId(), f));
        List<RunItem> items = new ArrayList<>();
        for (Target group : task.getTargets()) {
            for (int i = 0; i < group.getFileIds().size(); i++) {
                File file = files.get(group.getFileIds().get(i));
                RunItem item = new RunItem();
                item.setFileId(file.getId());
                item.setFileName(file.getName());
                item.setTargetTable(group.getName());
                item.setWriteOrder(i + 1);
                item.setStatus("PENDING");
                items.add(item);
            }
        }
        return items;
    }

    /**
     * 提交批次文件运行。
     *
     * @param id 记录编号
     */
    void submit(Long id) {
        if (!local.add(id)) {
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    work(id);
                } finally {
                    local.remove(id);
                }
            });
        } catch (RejectedExecutionException full) {
            local.remove(id); /* 已持久排队，由 XXL 作业再投递。 */
        }
    }

    /**
     * 处理批次文件运行。
     *
     * @param id 记录编号
     */
    private void work(Long id) {
        String token = UUID.randomUUID().toString();
        if (runs.claim(id, token) != 1) {
            return;
        }
        AtomicBoolean live = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (runs.heartbeat(id, token) != 1) {
                    live.set(false);
                }
            } catch (RuntimeException lost) {
                live.set(false);
            }
        }, 20, 20, TimeUnit.SECONDS);
        try {
            BatchFileRunDO entity = runs.selectById(id);
            Task snapshot = read(entity.getConfigJson(), Task.class);
            List<RunItem> items = new ArrayList<>(Arrays.asList(read(entity.getItemsJson(), RunItem[].class)));
            Map<Long, File> files = new HashMap<>();
            snapshot.getFiles().forEach(f -> files.put(f.getId(), f));
            Set<String> uncertainTargets = new HashSet<>();
            Runnable boundary = () -> {
                if (!live.get() || Thread.currentThread().isInterrupted() || runs.heartbeat(id, token) != 1) {
                    throw new IllegalStateException("执行租约已失效");
                }
            };
            updateTaskStatus(entity.getTaskId(), id, "RUNNING", 0, 0);
            for (RunItem item : items) {
                boundary.run();
                if (uncertainTargets.contains(item.getTargetTable())) {
                    item.setStatus("FAILED");
                    item.setErrorMessage("同一目标表此前写入结果未知，后续文件未执行");
                } else {
                    item.setStatus("RUNNING");
                    progress(entity, token, items);
                    try {
                        access.requireUserPermission(entity.getExecutionUserId(), "dataforge:batch-file:execute");
                        File file = files.get(item.getFileId());
                        if ("SYSTEM".equals(file.getSource())) {
                            access.requireUserPermission(entity.getExecutionUserId(), "dataforge:data-file:query");
                            access.requireUserPermission(entity.getExecutionUserId(), "dataforge:data-file:download");
                        }
                        var outcome = target.append(snapshot, file, service.content(file), entity.getExecutionUserId(),
                                boundary);
                        item.setStatus(outcome.status());
                        item.setWrittenRows(outcome.writtenRows());
                        item.setErrorMessage(outcome.errorMessage());
                        if ("UNKNOWN".equals(outcome.status())) {
                            uncertainTargets.add(item.getTargetTable());
                        }
                    } catch (RuntimeException failure) {
                        item.setStatus("FAILED");
                        item.setErrorMessage("文件内容或执行权限校验失败，未写入数据");
                    }
                }
                progress(entity, token, items);
            }
            String status = finalStatus(items);
            int success = count(items, "SUCCESS"), failed = items.size() - success;
            long rows = rows(items);
            if (runs.finish(id, token, status, json(items), success, failed, rows,
                    "UNKNOWN".equals(status) ? "存在未确认数据库操作，请人工核查" : null) == 1) {
                updateTaskStatus(entity.getTaskId(), id, status, success, failed);
            }
        } catch (RuntimeException failure) {
            // 无法证明最终围栏写入时保留 RUNNING，由数据库时间判断租约过期并转 UNKNOWN。
        } finally {
            live.set(false);
            heartbeat.cancel(false);
        }
    }

    /**
     * 更新进度批次文件运行。
     *
     * @param run 运行
     * @param token 当前执行或租约令牌
     * @param items 项
     */
    private void progress(BatchFileRunDO run, String token, List<RunItem> items) {
        if (runs.progress(run.getId(), token, json(items), count(items, "SUCCESS"),
                count(items, "FAILED") + count(items, "UNKNOWN"), rows(items)) != 1) {
            throw new IllegalStateException("运行围栏失效");
        }
    }

    /**
     * 获取批次文件运行的最终状态。
     *
     * @param items 项
     * @return 批次文件运行的最终状态文本
     */
    static String finalStatus(List<RunItem> items) {
        if (items.stream().anyMatch(item -> "UNKNOWN".equals(item.getStatus()))) {
            return "UNKNOWN";
        }
        int success = count(items, "SUCCESS");
        return success == items.size() ? "SUCCESS" : success > 0 || rows(items) > 0 ? "PARTIAL_SUCCESS" : "FAILED";
    }

    /**
     * 统计批次文件运行。
     *
     * @param items 项
     * @param status 目标状态
     * @return 符合条件或受影响的记录数量
     */
    private static int count(List<RunItem> items, String status) {
        return (int) items.stream().filter(i -> status.equals(i.getStatus())).count();
    }

    /**
     * 提取批次文件运行行数据。
     *
     * @param items 项
     * @return 行数值
     */
    private static long rows(List<RunItem> items) {
        return items.stream().mapToLong(i -> i.getWrittenRows() == null ? 0 : i.getWrittenRows()).sum();
    }

    /**
     * 更新任务状态。
     *
     * @param taskId 任务编号
     * @param runId 运行编号
     * @param status 目标状态
     * @param success 成功
     * @param failed 失败
     */
    private void updateTaskStatus(Long taskId, Long runId, String status, int success, int failed) {
        transaction.executeWithoutResult(tx -> {
            BatchFileTaskDO task = tasks.lock(taskId);
            if (task != null && Objects.equals(task.getLastRunId(), runId)) {
                task.setLastStatus(status);
                task.setSuccessFiles(success);
                task.setFailedFiles(failed);
                audit(task, "batch-file-worker");
                tasks.updateById(task);
            }
        });
    }

    /**
     * 分页读取批次文件运行。
     *
     * @param request 请求参数
     * @return 分页读取批次文件运行结果
     */
    public PageResult<Run> page(RunPage request) {
        service.requireTask(request.getTaskId());
        var page = runs.selectPage(request, new LambdaQueryWrapperX<BatchFileRunDO>()
                .eq(BatchFileRunDO::getTaskId, request.getTaskId()).orderByDesc(BatchFileRunDO::getId));
        return new PageResult<>(page.getList().stream().map(this::view).toList(), page.getTotal());
    }

    /**
     * 读取运行。
     *
     * @param id 记录编号
     * @return 运行
     */
    public Run get(Long id) {
        BatchFileRunDO run = runs.selectById(id);
        if (run == null) {
            throw exception(BATCH_FILE_RUN_NOT_EXISTS);
        }
        return view(run);
    }

    /**
     * 获取批次文件运行的视图。
     *
     * @param run 运行
     * @return 运行
     */
    private Run view(BatchFileRunDO run) {
        Run value = BeanUtils.toBean(run, Run.class);
        List<RunItem> items = new ArrayList<>(Arrays.asList(read(run.getItemsJson(), RunItem[].class)));
        if ("UNKNOWN".equals(run.getStatus())) {
            for (RunItem item : items) {
                if ("RUNNING".equals(item.getStatus())) {
                    item.setStatus("UNKNOWN");
                    item.setErrorMessage("工作者中断，目标写入结果待核查");
                } else if ("PENDING".equals(item.getStatus())) {
                    item.setStatus("FAILED");
                    item.setErrorMessage("工作者中断，未执行");
                }
            }
        }
        value.setItems(items);
        return value;
    }

    /**
     * 解析批次文件运行配置或身份。
     *
     * @param request 请求参数
     * @param actor 操作人编号
     */
    public void resolve(Resolve request, Long actor) {
        access.requireUserPermission(actor, "dataforge:batch-file:execute");
        if (!Boolean.TRUE.equals(request.getConfirmedStopped()) || !Boolean.TRUE.equals(request.getOutputReviewed())
                || local.contains(request.getId())) {
            throw exception(BATCH_FILE_REVIEW_REQUIRED);
        }
        BatchFileRunDO before = runs.selectById(request.getId());
        if (before == null) {
            throw exception(BATCH_FILE_RUN_NOT_EXISTS);
        }
        transaction.executeWithoutResult(tx -> {
            BatchFileTaskDO task = tasks.lock(before.getTaskId());
            BatchFileRunDO run = runs.lock(request.getId());
            if (run == null || !"UNKNOWN".equals(run.getStatus()) || run.getResolvedTime() != null
                    || (run.getLeaseUntil() != null && run.getLeaseUntil().isAfter(LocalDateTime.now()))) {
                throw exception(BATCH_FILE_REVIEW_REQUIRED);
            }
            run.setResolvedBy(actor);
            run.setResolvedTime(LocalDateTime.now());
            run.setResolutionReason(request.getReason().strip());
            audit(run, actor.toString());
            runs.updateById(run);
            if (task != null) {
                task.setOnline(false);
                task.setRevision(task.getRevision() + 1);
                audit(task, actor.toString());
                tasks.updateById(task);
            }
        });
    }

    /**
     * 根据持久意图和当前外部状态核对执行结果。
     */
    public void reconcile() {
        runs.recoverExpired();
        for (BatchFileRunDO run : runs.selectUnreflectedUnknown()) {
            updateTaskStatus(run.getTaskId(), run.getId(), "UNKNOWN", run.getSuccessCount(), run.getFailedCount());
        }
        for (BatchFileRunDO run : runs.selectList(new LambdaQueryWrapperX<BatchFileRunDO>()
                .eq(BatchFileRunDO::getStatus, "QUEUED").orderByAsc(BatchFileRunDO::getId).last("LIMIT 20"))) {
            submit(run.getId());
        }
        service.cleanupExpired();
    }

    /**
     * 关闭当前实例持有的连接、会话或执行资源。
     */
    @PreDestroy
    public void close() {
        workers.shutdownNow();
        heartbeats.shutdownNow();
    }
}

