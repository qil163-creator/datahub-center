package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.etl.core.file.TabularFileReader;
import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.fileresource.FileResourceConnectionResolver;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.dataforge.service.batchfile.BatchFileRules.*;

/** 平台研发范围内配置可见；未绑定素材始终按上传者隔离。 */
@Service
public class BatchFileService {

    @Resource
    private BatchFileTaskMapper tasks;

    @Resource
    private BatchFileUploadMapper uploads;

    @Resource
    private BatchFileRunMapper runs;

    @Resource
    private DatabaseMapper databases;

    @Resource
    private AccessDatabaseResolver resolver;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private AdminUserApi users;

    @Resource
    private ManagedFileApi storage;

    @Resource
    private FileResourceConnectionResolver remoteFiles;

    @Resource
    private TransactionTemplate transaction;

    private final java.util.concurrent.atomic.AtomicLong cleanupCursor = new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.atomic.AtomicLong expiredCleanupCursor = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 获得目标数据源、负责人及文件限额。
     *
     * @return 选项
     */
    public Options options() {
        List<DatabaseOption> dbs = new ArrayList<>();
        for (DatabaseDO db : databases.selectList(new LambdaQueryWrapperX<DatabaseDO>().eq(DatabaseDO::getStatus, 0))) {
            try {
                if (!"MYSQL".equalsIgnoreCase(resolver.resolveProductType(db).name())) {
                    continue;
                }
                DatabaseOption option = new DatabaseOption();
                option.setId(db.getId());
                option.setName(db.getName());
                dbs.add(option);
            } catch (RuntimeException ignored) { /* 不支持的产品不出现在目标列表。 */ }
        }
        return new Options(dbs, BeanUtils.toBean(users.getUserListByStatus(0).getCheckedData(), UserOption.class),
                new Limits(MAX_FILES, MAX_BYTES, MAX_TOTAL_BYTES, MAX_ROWS, MAX_COLUMNS), TYPES);
    }

    /**
     * 获得平台批量文件任务分页。
     *
     * @param request 请求参数
     * @return 平台批量文件任务分页结果
     */
    public PageResult<Task> page(Page request) {
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<BatchFileTaskDO, ?> sort = switch (Objects.toString(
                request.getSortField(), "id")) {
            case "id" -> BatchFileTaskDO::getId;
            case "fileCount" -> BatchFileTaskDO::getFileCount;
            case "targetCount" -> BatchFileTaskDO::getTargetCount;
            case "updateTime" -> BatchFileTaskDO::getUpdateTime;
            default -> throw exception(BATCH_FILE_CONFIG_INVALID, "排序字段不支持");
        };
        var query = new LambdaQueryWrapperX<BatchFileTaskDO>()
                .likeIfPresent(BatchFileTaskDO::getTaskName, request.getTaskName())
                .eqIfPresent(BatchFileTaskDO::getLastStatus, request.getLastStatus())
                .orderBy(true, "asc".equals(request.getSortOrder()), sort);
        if (!"id".equals(Objects.toString(request.getSortField(), "id"))) {
            query.orderByDesc(BatchFileTaskDO::getId);
        }
        PageResult<BatchFileTaskDO> result = tasks.selectPage(request, query);
        return new PageResult<>(result.getList().stream().map(this::view).toList(), result.getTotal());
    }

    /**
     * 获得入库实例统计。
     *
     * @return 汇总
     */
    public Summary summary() {
        Summary value = new Summary();
        value.setTaskCount(tasks.selectCount());
        value.setRunCount(runs.selectCount());
        value.setSuccessCount(
                runs.selectCount(new LambdaQueryWrapperX<BatchFileRunDO>().eq(BatchFileRunDO::getStatus, "SUCCESS")));
        value.setFailedCount(runs.selectCount(new LambdaQueryWrapperX<BatchFileRunDO>().in(BatchFileRunDO::getStatus,
                List.of("FAILED", "PARTIAL_SUCCESS", "UNKNOWN"))));
        value.setStatisticsTime(LocalDateTime.now());
        return value;
    }

    /**
     * 获得任务详情。
     *
     * @param id 记录编号
     * @return 任务
     */
    public Task get(Long id) {
        return view(requireTask(id));
    }

    /**
     * 校验任务。
     *
     * @param id 记录编号
     * @return 批次文件任务
     */
    BatchFileTaskDO requireTask(Long id) {
        BatchFileTaskDO task = tasks.selectById(id);
        if (task == null) {
            throw exception(BATCH_FILE_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 获取批次文件的视图。
     *
     * @param entity 实体
     * @return 任务
     */
    Task view(BatchFileTaskDO entity) {
        Task value = BeanUtils.toBean(entity, Task.class);
        Task config = read(entity.getConfigJson(), Task.class);
        value.setFiles(config.getFiles());
        value.setTargets(config.getTargets());
        if (entity.getLastRunId() != null) {
            BatchFileRunDO run = runs.selectById(entity.getLastRunId());
            if (run != null) {
                value.setLastRunResolvedTime(run.getResolvedTime());
                for (RunItem item : read(run.getItemsJson(), RunItem[].class)) {
                    value.getFiles().stream().filter(f -> f.getId().equals(item.getFileId())).findFirst()
                            .ifPresent(f -> {
                                String status = item.getStatus();
                                if ("UNKNOWN".equals(run.getStatus())) {
                                    if ("RUNNING".equals(status)) {
                                        status = "UNKNOWN";
                                    } else if ("PENDING".equals(status)) {
                                        status = "FAILED";
                                    }
                                }
                                f.setLastStatus(status);
                                f.setWrittenRows(item.getWrittenRows());
                            });
                }
            }
        }
        return value;
    }

    /**
     * 上传批次文件。
     *
     * @param file 文件
     * @param actor 操作人编号
     * @return 文件
     */
    public File upload(MultipartFile file, Long actor) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "单文件大小必须为 1 字节至 20 MB");
        }
        try {
            return store(file.getOriginalFilename(), file.getBytes(), "LOCAL", null, null, actor);
        } catch (java.io.IOException ex) {
            throw exception(BATCH_FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 获取批次文件的系统文件。
     *
     * @param request 请求参数
     * @param actor 操作人编号
     * @return 文件
     */
    public File systemFile(SystemFile request, Long actor) {
        access.requireUserPermission(actor, "dataforge:data-file:query");
        access.requireUserPermission(actor, "dataforge:data-file:download");
        try (var client = remoteFiles.open(request.getConnectionId()); var out = new ByteArrayOutputStream()) {
            var info = client.stat(request.getPath());
            if (info.directory() || info.size() <= 0 || info.size() > MAX_BYTES) {
                throw exception(BATCH_FILE_CONFIG_INVALID, "仅支持不超过 20 MB 的文件");
            }
            extension(info.name());
            client.download(info.path(), out, MAX_BYTES);
            return store(info.name(), out.toByteArray(), "SYSTEM", request.getConnectionId(), info.path(), actor);
        } catch (Exception ex) {
            throw exception(BATCH_FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 获取批次文件的存储。
     *
     * @param name 名称
     * @param bytes 字节
     * @param source 来源数据
     * @param connection 数据库连接
     * @param path 路径
     * @param actor 操作人编号
     * @return 文件
     */
    private File store(String name, byte[] bytes, String source, Long connection, String path, Long actor) {
        if (actor == null) {
            throw exception(BATCH_FILE_UPLOAD_INVALID);
        }
        String type = extension(name);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "单文件大小不能超过 20 MB");
        }
        final TabularFileReader.Table table;
        try {
            table = TabularFileReader.read(bytes, type, parserLimits());
        } catch (Exception ex) {
            throw exception(BATCH_FILE_UPLOAD_FAILED);
        }
        List<Field> fields = headers(table.headers());
        BatchFileUploadDO upload = new BatchFileUploadDO();
        upload.setOwnerId(actor);
        upload.setOperationKey(UUID.randomUUID().toString());
        upload.setName(name);
        upload.setFileType(type);
        upload.setSource(source);
        upload.setConnectionId(connection);
        upload.setPath(path);
        upload.setSize((long) bytes.length);
        upload.setSha256(sha(bytes));
        upload.setRowCount(table.rows().size());
        upload.setFieldsJson(json(fields));
        upload.setState("PENDING");
        upload.setExpiresAt(LocalDateTime.now().plusHours(24));
        audit(upload, actor.toString());
        uploads.insert(upload); // 先落意图，跨服务超时由清理作业按操作键对账。
        ManagedFileCreateReqDTO request = new ManagedFileCreateReqDTO();
        request.setOperationKey(upload.getOperationKey());
        request.setName(name);
        request.setContent(bytes);
        try {
            ManagedFileRespDTO receipt = storage.createManaged(request).getCheckedData();
            if (receipt == null || !"READY".equals(receipt.getState()) || !Objects.equals(receipt.getSize(),
                    upload.getSize())
                    || !Objects.equals(receipt.getSha256(), upload.getSha256())) {
                throw exception(BATCH_FILE_STORAGE_UNAVAILABLE);
            }
            if (uploads.ready(upload.getId(), receipt.getId(), actor.toString()) != 1) {
                clean(upload.getId()); // 迟到回执遇到清理意图时立即尝试对账，失败继续由作业重试。
                throw exception(BATCH_FILE_STORAGE_UNAVAILABLE);
            }
            upload.setStorageFileId(receipt.getId());
            upload.setState("READY");
            return fileView(upload);
        } catch (RuntimeException ex) {
            throw exception(BATCH_FILE_STORAGE_UNAVAILABLE);
        }
    }

    /**
     * 获取批次文件的解析器上限。
     *
     * @return 上限
     */
    static TabularFileReader.Limits parserLimits() {
        return new TabularFileReader.Limits(MAX_BYTES, MAX_ROWS, MAX_COLUMNS, 1024 * 1024);
    }

    /**
     * 计算输入内容的 SHA 摘要。
     *
     * @param bytes 字节
     * @return 计算输入内容的 SHA 摘要文本
     */
    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 获取批次文件的文件视图。
     *
     * @param upload 上传
     * @return 文件
     */
    File fileView(BatchFileUploadDO upload) {
        File file = BeanUtils.toBean(upload, File.class);
        file.setType(upload.getFileType());
        file.setFields(new ArrayList<>(Arrays.asList(read(upload.getFieldsJson(), Field[].class))));
        return file;
    }

    /**
     * 保存批次文件。
     *
     * @param request 请求参数
     * @param actor 操作人编号
     * @param create 创建
     * @return 生成或保存的记录编号
     */
    public Long save(Save request, Long actor, boolean create) {
        var responsible = users.validateUser(request.getResponsibleUserId());
        if (responsible == null || responsible.getMobile() == null || responsible.getMobile().isBlank()) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "负责人尚未维护联系电话，请先完善用户信息");
        }
        access.requireDatabase(request.getTargetDatabaseId());
        var db = resolver.resolve(request.getTargetDatabaseId(), BATCH_FILE_TARGET_INVALID);
        if (!"MYSQL".equalsIgnoreCase(db.getProductType().name())) {
            throw exception(BATCH_FILE_TARGET_INVALID);
        }
        try {
            return transaction.execute(tx -> {
                BatchFileTaskDO task;
                if (create) {
                    task = new BatchFileTaskDO();
                    task.setOnline(false);
                    task.setRevision(1L);
                    task.setLastStatus("NOT_EXECUTED");
                    task.setSuccessFiles(0);
                    task.setFailedFiles(0);
                } else {
                    task = tasks.lock(request.getId());
                    editable(task, request.getRevision());
                    task.setRevision(task.getRevision() + 1);
                }
                Map<Long, BatchFileUploadDO> selected = new LinkedHashMap<>();
                request.getFiles().stream().map(FileSelection::getId).sorted().distinct()
                        .forEach(id -> selected.put(id, uploads.lock(id)));
                validate(request, selected, actor, create ? null : task.getId());
                Task config = new Task();
                config.setFiles(request.getFiles().stream().map(selection -> {
                    File file = fileView(selected.get(selection.getId()));
                    file.setFields(selection.getFields());
                    return file;
                }).toList());
                config.setTargets(request.getTargets());
                for (Target target : config.getTargets()) {
                    for (int i = 0; i < target.getFileIds().size(); i++) {
                        Long fileId = target.getFileIds().get(i);
                        int order = i + 1;
                        config.getFiles().stream().filter(f -> f.getId().equals(fileId)).findFirst().ifPresent(f -> {
                            f.setTargetTable(target.getName());
                            f.setWriteOrder(order);
                        });
                    }
                }
                task.setTaskName(request.getTaskName().strip());
                task.setResponsibleUserId(responsible.getId());
                task.setResponsibleName(responsible.getNickname());
                task.setResponsiblePhone(responsible.getMobile());
                task.setDescription(request.getDescription());
                task.setRemark(request.getRemark());
                task.setTargetDatabaseId(db.getDatabaseId());
                task.setTargetDatabaseName(db.getName());
                task.setConfigJson(json(config));
                task.setFileCount(config.getFiles().size());
                task.setTargetCount(config.getTargets().size());
                audit(task, actor.toString());
                if (create) {
                    tasks.insert(task);
                } else {
                    tasks.updateById(task);
                }
                // 素材绑定与任务配置在同一事务提交；历史执行快照仍保有原素材引用。
                for (BatchFileUploadDO upload : selected.values()) {
                    upload.setTaskId(task.getId());
                    audit(upload, actor.toString());
                    uploads.updateById(upload);
                }
                return task.getId();
            });
        } catch (DuplicateKeyException ex) {
            throw exception(BATCH_FILE_CONFLICT);
        }
    }

    /**
     * 校验批次文件任务存在、修订号一致且未上线或运行，拒绝修改不可编辑任务。
     *
     * @param task 任务记录
     * @param revision 修订号
     */
    void editable(BatchFileTaskDO task, Long revision) {
        if (task == null) {
            throw exception(BATCH_FILE_NOT_EXISTS);
        }
        if (!Objects.equals(task.getRevision(), revision)) {
            throw exception(BATCH_FILE_CONFLICT);
        }
        if (Boolean.TRUE.equals(task.getOnline()) || runs.countActive(task.getId()) > 0) {
            throw exception(BATCH_FILE_STATE_INVALID);
        }
    }

    /**
     * 读取批次文件状态。
     *
     * @param request 请求参数
     * @param actor 操作人编号
     */
    public void status(Status request, Long actor) {
        if (request.getOnline()) {
            BatchFileTaskDO before = requireTask(request.getId());
            access.requireDatabase(before.getTargetDatabaseId());
            if (!"MYSQL".equalsIgnoreCase(
                    resolver.resolve(before.getTargetDatabaseId(), BATCH_FILE_TARGET_INVALID).getProductType()
                            .name())) {
                throw exception(BATCH_FILE_TARGET_INVALID);
            }
        }
        transaction.executeWithoutResult(tx -> {
            BatchFileTaskDO task = tasks.lock(request.getId());
            if (task == null) {
                throw exception(BATCH_FILE_NOT_EXISTS);
            }
            if (!Objects.equals(task.getRevision(), request.getRevision())) {
                throw exception(BATCH_FILE_CONFLICT);
            }
            if (runs.countActive(task.getId()) > 0) {
                throw exception(BATCH_FILE_STATE_INVALID);
            }
            task.setOnline(request.getOnline());
            task.setRevision(task.getRevision() + 1);
            audit(task, actor.toString());
            tasks.updateById(task);
        });
    }

    /**
     * 删除批次文件。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @param actor 操作人编号
     */
    public void delete(Long id, Long revision, Long actor) {
        transaction.executeWithoutResult(tx -> {
            BatchFileTaskDO task = tasks.lock(id);
            editable(task, revision);
            audit(task, actor.toString());
            tasks.updateById(task);
            tasks.deleteById(id);
        });
    }

    /**
     * 移除上传。
     *
     * @param id 记录编号
     * @param actor 操作人编号
     */
    public void removeUpload(Long id, Long actor) {
        transaction.executeWithoutResult(tx -> {
            BatchFileUploadDO upload = uploads.lock(id);
            if (upload == null || upload.getTaskId() != null || !Objects.equals(upload.getOwnerId(), actor)) {
                throw exception(BATCH_FILE_UPLOAD_INVALID);
            }
            upload.setState("CLEANUP_PENDING");
            upload.setExpiresAt(LocalDateTime.now());
            audit(upload, actor.toString());
            uploads.updateById(upload);
        });
        clean(id);
    }

    /** 持久意图对账：超时上传按操作键发现对象，再删除；失败留待下一轮重试。 */
    void clean(Long id) {
        BatchFileUploadDO upload = uploads.selectById(id);
        if (upload == null || upload.getTaskId() != null || !"CLEANUP_PENDING".equals(upload.getState())) {
            return;
        }
        try {
            ManagedFileRespDTO receipt = storage.getManaged(upload.getOperationKey()).getCheckedData();
            if (receipt == null) {
                return; // 尚无回执不等于迟到创建永远不会发生，保留意图继续对账。
            }
            if (!Boolean.TRUE.equals(
                    storage.deleteManaged(receipt.getId(), upload.getOperationKey()).getCheckedData())) {
                return;
            }
            if (uploads.completeCleanup(id) == 1) {
                upload.setState("CLEANED");
            }
        } catch (RuntimeException ignored) { /* 意图未清除，下一轮作业继续对账。 */ }
    }

    /**
     * 清理过期。
     */
    public void cleanupExpired() {
        List<BatchFileUploadDO> expired = uploads.selectExpired(expiredCleanupCursor.get());
        if (expired.isEmpty()) {
            expiredCleanupCursor.set(0);
        }
        for (BatchFileUploadDO candidate : expired) {
            expiredCleanupCursor.set(candidate.getId());
            Boolean claimed = transaction.execute(tx -> {
                BatchFileUploadDO current = uploads.lock(candidate.getId());
                if (current == null || current.getTaskId() != null || current.getExpiresAt()
                        .isAfter(LocalDateTime.now())) {
                    return false;
                }
                current.setState("CLEANUP_PENDING");
                audit(current, "batch-file-cleanup");
                uploads.updateById(current);
                return true;
            });
            if (Boolean.TRUE.equals(claimed)) {
                clean(candidate.getId());
            }
        }
        // 已移出配置的素材保留 30 天，供历史实例核查；未解决实例仍阻止回收。
        List<BatchFileUploadDO> bound = uploads.selectList(new LambdaQueryWrapperX<BatchFileUploadDO>()
                .isNotNull(BatchFileUploadDO::getTaskId).eq(BatchFileUploadDO::getState, "READY")
                .gt(BatchFileUploadDO::getId, cleanupCursor.get()).orderByAsc(BatchFileUploadDO::getId)
                .last("LIMIT 50"));
        if (bound.isEmpty()) {
            cleanupCursor.set(0);
        }
        for (BatchFileUploadDO candidate : bound) {
            cleanupCursor.set(candidate.getId());
            Boolean claimed = transaction.execute(tx -> {
                BatchFileTaskDO task = tasks.lock(candidate.getTaskId());
                BatchFileUploadDO current = uploads.lock(candidate.getId());
                if (current == null || !"READY".equals(current.getState()) || current.getUpdateTime() == null
                        || current.getUpdateTime().isAfter(LocalDateTime.now().minusDays(30))) {
                    return false;
                }
                if (task != null && read(task.getConfigJson(), Task.class).getFiles().stream()
                        .anyMatch(f -> f.getId().equals(current.getId()))) {
                    return false;
                }
                if (runs.countRetainedFile(current.getId()) > 0) {
                    return false;
                }
                uploads.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BatchFileUploadDO>()
                                .eq(BatchFileUploadDO::getId, current.getId()).set(BatchFileUploadDO::getTaskId, null)
                                .set(BatchFileUploadDO::getState, "CLEANUP_PENDING")
                                .set(BatchFileUploadDO::getExpiresAt, LocalDateTime.now())
                                .set(BatchFileUploadDO::getUpdater, "batch-file-cleanup")
                                .set(BatchFileUploadDO::getUpdateTime, LocalDateTime.now()));
                return true;
            });
            if (Boolean.TRUE.equals(claimed)) {
                clean(candidate.getId());
            }
        }
    }

    /**
     * 获取批次文件的内容。
     *
     * @param file 文件
     * @return 转换后的字节序列
     */
    byte[] content(File file) {
        BatchFileUploadDO upload = uploads.selectById(file.getId());
        if (upload == null || !"READY".equals(upload.getState()) || !Objects.equals(upload.getSha256(),
                file.getSha256())) {
            throw exception(BATCH_FILE_UPLOAD_INVALID);
        }
        byte[] data = storage.readManaged(upload.getStorageFileId(), upload.getOperationKey()).getCheckedData();
        if (data == null || data.length > MAX_BYTES || !sha(data).equals(file.getSha256())) {
            throw exception(BATCH_FILE_UPLOAD_INVALID);
        }
        return data;
    }
}

