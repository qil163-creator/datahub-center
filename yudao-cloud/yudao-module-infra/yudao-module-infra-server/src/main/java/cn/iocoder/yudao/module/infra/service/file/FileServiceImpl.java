package cn.iocoder.yudao.module.infra.service.file;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.http.HttpUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FileCreateReqVO;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FilePresignedUrlRespVO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import cn.iocoder.yudao.module.infra.dal.mysql.file.FileMapper;
import cn.iocoder.yudao.module.infra.framework.file.core.client.FileClient;
import cn.iocoder.yudao.module.infra.framework.file.core.utils.FilePathUtils;
import cn.iocoder.yudao.module.infra.framework.file.core.utils.FileTypeUtils;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.ManagedFileDO;
import cn.iocoder.yudao.module.infra.dal.mysql.file.ManagedFileMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

import java.util.List;
import java.util.Set;

import static cn.hutool.core.date.DatePattern.PURE_DATE_PATTERN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.infra.enums.ErrorCodeConstants.*;

/**
 * 文件 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Slf4j
public class FileServiceImpl implements FileService {

    /**
     * 允许上传的文件类型，可按业务需要调整
     */
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            // 图片
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp",
            "image/vnd.microsoft.icon", "image/tiff", "image/avif", "image/heic", "image/heif", "image/svg+xml",
            // 文档
            "application/pdf", "text/plain", "text/csv", "text/markdown", "text/html", "application/xhtml+xml",
            "application/xml", "application/json", "application/epub+zip", "message/rfc822", "application/vnd.ms-outlook",
            "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // 压缩包
            "application/zip", "application/x-rar-compressed", "application/x-7z-compressed", "application/gzip",
            // DataHub 已有 JDBC 驱动上传需要 JAR；仍按内容检测 MIME，不采信客户端声明。
            "application/java-archive",
            // 音视频
            "audio/mpeg", "audio/vnd.wave", "audio/vorbis", "audio/ogg", "audio/opus", "audio/mp4", "audio/webm",
            "audio/x-aac", "audio/x-flac", "audio/amr", "audio/x-ms-wma",
            "video/mp4", "video/quicktime", "video/x-msvideo", "video/webm", "video/x-ms-wmv", "video/x-flv",
            "application/x-matroska", "video/x-matroska"
    );

    /**
     * 上传文件的前缀，是否包含日期（yyyyMMdd）
     *
     * 目的：按照日期，进行分目录
     */
    static boolean PATH_PREFIX_DATE_ENABLE = true;
    /**
     * 上传文件的后缀，是否启用
     *
     * 算法：当前时间戳（毫秒）+ 5 位随机数；目的是保证文件的唯一性，避免覆盖
     * 定制：可按需调整成 UUID、或者其他方式
     */
    static boolean PATH_SUFFIX_TIMESTAMP_ENABLE = false;
    /**
     * 后缀是否作为上级目录
     *
     * true：{@code yyyyMMdd/<后缀>/原文件名.ext}；保留原文件名
     * false：{@code yyyyMMdd/原文件名_<后缀>.ext}；后缀拼到文件名
     */
    static boolean PATH_SUFFIX_AS_DIRECTORY = true;

    @Resource
    private FileConfigService fileConfigService;

    @Resource
    private FileMapper fileMapper;

    @Resource private ManagedFileMapper managedFileMapper;
    @Resource private PlatformTransactionManager transactionManager;
    private static final long MANAGED_MAX_BYTES = 20L * 1024 * 1024;

    @Override
    public ManagedFileRespDTO createManaged(String owner, ManagedFileCreateReqDTO request) throws Exception {
        validateManagedIdentity(owner, request.getOperationKey());
        byte[] content = request.getContent();
        if (content == null || content.length == 0) throw exception(FILE_IS_EMPTY);
        if (content.length > MANAGED_MAX_BYTES) throw exception(MANAGED_FILE_LIMIT_EXCEEDED);
        String suppliedName = request.getName();
        if (suppliedName == null || suppliedName.isBlank() || suppliedName.length() > 255) throw exception(FILE_PATH_INVALID);
        CheckedFile checked = checkFileContent(content, suppliedName);
        String name = checked.name();
        String type = checked.type();
        if (name.length() > 255) throw exception(FILE_PATH_INVALID);
        String hash = DigestUtil.sha256Hex(content);
        ManagedFileDO operation = managedFileMapper.find(owner, request.getOperationKey());
        if (operation == null) {
            FileClient client = fileConfigService.getMasterFileClient();
            Assert.notNull(client, "客户端(master) 不能为空");
            operation = new ManagedFileDO().setOwnerService(owner).setOperationKey(request.getOperationKey())
                    .setName(name).setSha256(hash).setSize((long) content.length).setConfigId(client.getId())
                    .setPath("managed/" + IdUtil.fastSimpleUUID() + "/" + name).setState("PENDING");
            // 受管 RPC 不接收浏览器登录头，审计主体明确使用已认证服务身份。
            operation.setCreator(owner);
            operation.setUpdater(owner);
            try { managedFileMapper.insert(operation); }
            catch (DuplicateKeyException duplicate) { operation = managedFileMapper.find(owner, request.getOperationKey()); }
        }
        if (operation == null || !Objects.equals(name, operation.getName()) || !Objects.equals(hash, operation.getSha256())
                || !Objects.equals((long) content.length, operation.getSize()) || "DELETED".equals(operation.getState()))
            throw exception(MANAGED_FILE_CONFLICT);
        if ("READY".equals(operation.getState())) return receipt(operation);
        // UNKNOWN 上传不得盲目重发，先验证确定路径上的对象。FAILED 仅表示对象补偿已经完成。
        if (!Set.of("PENDING", "FAILED").contains(operation.getState())) return getManaged(owner, request.getOperationKey());
        if (managedFileMapper.transition(operation.getId(), operation.getState(), "UPLOADING") != 1)
            return getManaged(owner, request.getOperationKey());
        FileClient client = fileConfigService.getFileClient(operation.getConfigId());
        Assert.notNull(client, "文件存储配置不可用");
        try {
            String url = client.upload(content, operation.getPath(), type);
            return finishManaged(operation, url, type);
        } catch (Exception failure) {
            ManagedFileDO current = managedFileMapper.find(owner, request.getOperationKey());
            // 查询请求可能已完成未知回执对账；不得误删已提交文件。
            if (current != null && "READY".equals(current.getState())) return receipt(current);
            int cleanupClaim = managedFileMapper.transition(operation.getId(), "UPLOADING", "CLEANUP_PENDING");
            if (cleanupClaim != 1) {
                current = managedFileMapper.find(owner, request.getOperationKey());
                if (current != null && "READY".equals(current.getState())) return receipt(current);
                if (current == null || !Set.of("CLEANUP_PENDING", "DELETED").contains(current.getState()))
                    throw exception(MANAGED_FILE_NOT_READY);
            }
            try {
                client.delete(operation.getPath());
                managedFileMapper.transition(operation.getId(), "CLEANUP_PENDING", "FAILED");
            } catch (Exception cleanupFailure) {
                // 保留路径与指纹供调用方对账和重试删除，不在日志/异常中泄漏存储位置。
            }
            throw exception(MANAGED_FILE_NOT_READY);
        }
    }

    @Override
    public ManagedFileRespDTO getManaged(String owner, String operationKey) throws Exception {
        validateManagedIdentity(owner, operationKey);
        ManagedFileDO operation = managedFileMapper.find(owner, operationKey);
        if (operation == null) return null;
        if ("UPLOADING".equals(operation.getState())) {
            FileClient client = fileConfigService.getFileClient(operation.getConfigId());
            Assert.notNull(client, "文件存储配置不可用");
            byte[] content;
            try { content = client.getContent(operation.getPath(), MANAGED_MAX_BYTES); }
            catch (java.io.FileNotFoundException missing) { return receipt(operation); }
            if (content != null) {
                verifyManagedContent(operation, content);
                String type = FileTypeUtils.getMineType(content, operation.getName());
                // URL 仅内部存储，受管文件回执及浏览器均不获得此地址。
                return finishManaged(operation, "", type);
            }
        }
        return receipt(operation);
    }

    private ManagedFileRespDTO finishManaged(ManagedFileDO operation, String url, String type) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ManagedFileDO locked = managedFileMapper.lock(operation.getId());
            if (locked == null) throw exception(FILE_NOT_EXISTS);
            if ("READY".equals(locked.getState())) return receipt(locked);
            if (!"UPLOADING".equals(locked.getState())) throw exception(MANAGED_FILE_NOT_READY);
            FileDO file = new FileDO().setConfigId(locked.getConfigId()).setName(locked.getName())
                    .setPath(locked.getPath()).setUrl(url).setType(type).setSize(locked.getSize());
            file.setCreator(locked.getOwnerService());
            file.setUpdater(locked.getOwnerService());
            fileMapper.insert(file);
            if (managedFileMapper.ready(locked.getId(), file.getId()) != 1) throw exception(MANAGED_FILE_NOT_READY);
            return receipt(locked.setState("READY").setFileId(file.getId()));
        });
    }

    @Override
    public byte[] readManaged(String owner, Long fileId, String operationKey) throws Exception {
        ManagedFileDO operation = requireManaged(owner, fileId, operationKey);
        if (!"READY".equals(operation.getState())) throw exception(MANAGED_FILE_NOT_READY);
        FileClient client = fileConfigService.getFileClient(operation.getConfigId());
        Assert.notNull(client, "文件存储配置不可用");
        byte[] content = client.getContent(operation.getPath(), MANAGED_MAX_BYTES);
        verifyManagedContent(operation, content);
        return content;
    }

    /** 元数据解析只读上传者自己的普通文件；受管业务素材继续走原操作键协议。 */
    @Override
    public cn.iocoder.yudao.module.infra.api.file.dto.UserFileContentRespDTO readUserUpload(Long fileId, Long actorId) throws Exception {
        if (fileId == null || fileId <= 0 || actorId == null || actorId <= 0) throw exception(FILE_NOT_EXISTS);
        FileDO file = validateFileExists(fileId);
        if (!actorId.toString().equals(file.getCreator()) || file.getPath() == null || file.getPath().startsWith("managed/"))
            throw exception(FILE_NOT_EXISTS);
        if (file.getSize() == null || file.getSize() <= 0 || file.getSize() > ManagedFileApi.MAX_USER_UPLOAD_BYTES)
            throw exception(USER_UPLOAD_LIMIT_EXCEEDED);
        FilePathUtils.validatePath(file.getPath());
        FileClient client = fileConfigService.getFileClient(file.getConfigId());
        if (client == null) throw exception(FILE_CONFIG_NOT_EXISTS);
        byte[] content = client.getContent(file.getPath(), ManagedFileApi.MAX_USER_UPLOAD_BYTES);
        if (content == null || content.length != file.getSize()) throw exception(MANAGED_FILE_INTEGRITY);
        return new cn.iocoder.yudao.module.infra.api.file.dto.UserFileContentRespDTO()
                .setName(file.getName()).setType(file.getType()).setSize(file.getSize()).setContent(content);
    }

    @Override
    public void deleteManaged(String owner, Long fileId, String operationKey) throws Exception {
        ManagedFileDO operation = requireManaged(owner, fileId, operationKey);
        if (!"DELETED".equals(operation.getState())
                && managedFileMapper.transition(fileId, operation.getState(), "DELETED") != 1)
            throw exception(MANAGED_FILE_NOT_READY);
        FileClient client = fileConfigService.getFileClient(operation.getConfigId());
        Assert.notNull(client, "文件存储配置不可用");
        // 墓碑状态重复调用也重放物理删除，覆盖失联写入晚于取消回执的情况。
        client.delete(operation.getPath());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ManagedFileDO locked = managedFileMapper.lock(fileId);
            if (locked.getFileId() != null) fileMapper.deleteById(locked.getFileId());
        });
    }

    @Override
    public ManagedCleanupResult cleanupManagedFiles(int pageSize, int maxPages) {
        if (pageSize < 1 || pageSize > 200 || maxPages < 1 || maxPages > 20)
            throw new IllegalArgumentException("受管文件清理批次无效");
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusMinutes(5);
        java.time.LocalDateTime afterTime = null;
        Long afterId = null;
        int attempted = 0, succeeded = 0;
        for (int page = 0; page < maxPages; page++) {
            List<ManagedFileDO> batch = managedFileMapper.cleanupCandidates(cutoff, afterTime, afterId, pageSize);
            if (batch.isEmpty()) break;
            for (ManagedFileDO operation : batch) {
                attempted++;
                try {
                    // 永久墓碑周期重放，覆盖取消成功后才落盘的失联上传。
                    // 不扫描可重试状态，避免并发补偿与同键重试上传相互删除。
                    deleteManaged(operation.getOwnerService(), operation.getId(), operation.getOperationKey());
                    succeeded++;
                } catch (Exception ignored) {
                    // 保留状态与路径，下一周期继续；批次结果显式报告失败数量。
                } finally {
                    managedFileMapper.touchCleanup(operation.getId());
                }
                afterTime = operation.getUpdateTime();
                afterId = operation.getId();
            }
            if (batch.size() < pageSize) break;
        }
        return new ManagedCleanupResult(attempted, succeeded, attempted - succeeded);
    }

    private ManagedFileDO requireManaged(String owner, Long fileId, String operationKey) {
        validateManagedIdentity(owner, operationKey);
        ManagedFileDO operation = managedFileMapper.find(owner, operationKey);
        if (operation == null || !Objects.equals(fileId, operation.getId())) throw exception(FILE_NOT_EXISTS);
        return operation;
    }

    private void verifyManagedContent(ManagedFileDO operation, byte[] content) {
        if (content == null || content.length != operation.getSize() || !Objects.equals(DigestUtil.sha256Hex(content), operation.getSha256()))
            throw exception(MANAGED_FILE_INTEGRITY);
    }

    private void validateManagedIdentity(String owner, String key) {
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{1,64}") || key == null || !key.matches("[A-Za-z0-9_-]{16,128}"))
            throw exception(FILE_PATH_INVALID);
    }

    private ManagedFileRespDTO receipt(ManagedFileDO operation) {
        return new ManagedFileRespDTO().setId(operation.getId()).setSize(operation.getSize())
                .setSha256(operation.getSha256()).setState(operation.getState());
    }

    @Override
    public PageResult<FileDO> getFilePage(FilePageReqVO pageReqVO) {
        return fileMapper.selectPage(pageReqVO);
    }

    @Override
    @SneakyThrows
    public String createFile(byte[] content, String name, String directory, String type) {
        return createFile(content, name, directory, type, false).getUrl();
    }

    @Override
    @SneakyThrows
    public FileDO createFileWithInfo(byte[] content, String name, String directory, String type) {
        return createFile(content, name, directory, type, true);
    }

    private FileDO createFile(byte[] content, String name, String directory, String type, boolean uniquePath) throws Exception {
        rejectManagedLocation(directory);
        CheckedFile checked = checkFileContent(content, name);
        name = checked.name();
        type = checked.type();

        // 2.1 生成上传的 path，需要保证唯一
        String path = uniquePath ? generateUniqueUploadPath(name, directory) : generateUploadPath(name, directory);
        // 2.2 上传到文件存储器
        FileClient client = fileConfigService.getMasterFileClient();
        Assert.notNull(client, "客户端(master) 不能为空");
        String url = client.upload(content, path, type);

        // 3. 保存到数据库
        FileDO file = new FileDO().setConfigId(client.getId())
                .setName(name).setPath(path).setUrl(url)
                .setType(type).setSize((long) content.length);
        try {
            fileMapper.insert(file);
        } catch (RuntimeException registrationFailure) {
            // 仅补偿本次生成唯一目录的对象；旧上传路径可能覆盖已有文件，不能删除。
            if (uniquePath) {
                try {
                    client.delete(path);
                } catch (Exception cleanupFailure) {
                    if (cleanupFailure != registrationFailure) {
                        registrationFailure.addSuppressed(cleanupFailure);
                    }
                    // 记录配置与相对对象键以便人工重试，不记录可能包含签名的 URL 或异常消息。
                    log.error("[createFileWithInfo][文件登记失败且补偿删除失败，需清理对象 configId={} path={} cleanupError={}]",
                            file.getConfigId(), path, cleanupFailure.getClass().getSimpleName());
                }
            }
            throw registrationFailure;
        }
        return file;
    }

    private record CheckedFile(String name, String type) { }

    private CheckedFile checkFileContent(byte[] content, String name) {
        // 1.1 处理 name 的合法性，禁止携带目录路径
        name = FilePathUtils.validateFileName(name);

        // 1.2.1 校验文件内容和类型，不信任客户端传入的 type
        if (ArrayUtil.isEmpty(content)) {
            throw exception(FILE_IS_EMPTY);
        }
        String type = FileTypeUtils.getMineType(content, name);
        String nameType = StrUtil.isNotEmpty(FileUtil.extName(name)) ? validateFileType(name) : null;
        if (!ALLOWED_FILE_TYPES.contains(type)
                || (FileTypeUtils.isImage(nameType) && ObjectUtil.notEqual(type, nameType))) {
            throw exception(FILE_TYPE_NOT_ALLOWED);
        }
        // 1.2.2 处理 name 为空的情况
        if (StrUtil.isEmpty(name)) {
            name = DigestUtil.sha256Hex(content);
        }
        if (StrUtil.isEmpty(FileUtil.extName(name))) {
            // 如果 name 没有后缀 type，则补充后缀
            String extension = FileTypeUtils.getExtension(type);
            if (StrUtil.isNotEmpty(extension)) {
                name = name + extension;
            }
        }

        return new CheckedFile(name, type);
    }

    /**
     * 生成详情上传链路专用路径。UUID 目录不受旧路径开关影响，确保每次上传都不会覆盖同名对象。
     */
    private String generateUniqueUploadPath(String name, String directory) {
        name = FilePathUtils.validateFileName(name);
        FilePathUtils.validatePath(name);
        FilePathUtils.validateDirectory(directory);
        String prefix = PATH_PREFIX_DATE_ENABLE
                ? LocalDateTimeUtil.format(LocalDateTimeUtil.now(), PURE_DATE_PATTERN) : null;
        String path = IdUtil.fastSimpleUUID() + StrUtil.SLASH + name;
        if (StrUtil.isNotEmpty(prefix)) {
            path = prefix + StrUtil.SLASH + path;
        }
        if (StrUtil.isNotEmpty(directory)) {
            path = directory + StrUtil.SLASH + path;
        }
        return path;
    }

    @VisibleForTesting
    String generateUploadPath(String name, String directory) {
        // 1.1 处理 name 和 directory 的合法性
        name = FilePathUtils.validateFileName(name);
        FilePathUtils.validatePath(name);
        FilePathUtils.validateDirectory(directory);
        // 1.2 生成前缀、后缀
        String prefix = null;
        if (PATH_PREFIX_DATE_ENABLE) {
            prefix = LocalDateTimeUtil.format(LocalDateTimeUtil.now(), PURE_DATE_PATTERN);
        }
        String suffix = null;
        if (PATH_SUFFIX_TIMESTAMP_ENABLE) {
            // 5 位随机数，避免同一毫秒内的重复
            suffix = String.valueOf(System.currentTimeMillis()) + RandomUtil.randomInt(10000, 100000);
        }

        // 2.1 先拼接 suffix 后缀
        if (StrUtil.isNotEmpty(suffix)) {
            if (PATH_SUFFIX_AS_DIRECTORY) {
                name = suffix + StrUtil.SLASH + name;
            } else {
                String ext = FileUtil.extName(name);
                if (StrUtil.isNotEmpty(ext)) {
                    name = FileUtil.mainName(name) + StrUtil.C_UNDERLINE + suffix + StrUtil.DOT + ext;
                } else {
                    name = name + StrUtil.C_UNDERLINE + suffix;
                }
            }
        }
        // 2.2 再拼接 prefix 前缀
        if (StrUtil.isNotEmpty(prefix)) {
            name = prefix + StrUtil.SLASH + name;
        }
        // 2.3 最后拼接 directory 目录
        if (StrUtil.isNotEmpty(directory)) {
            name = directory + StrUtil.SLASH + name;
        }
        return name;
    }

    @Override
    @SneakyThrows
    public FilePresignedUrlRespVO presignPutUrl(String name, String directory) {
        rejectManagedLocation(directory);
        String path = generateUploadPath(name, directory);
        // 前端直传无法获取文件内容，此处仅校验文件名类型。
        validateFileType(name);
        return presignPutUrl(path);
    }

    @Override
    @SneakyThrows
    public FilePresignedUrlRespVO presignPutUrlWithInfo(String name, String directory) {
        rejectManagedLocation(directory);
        String path = generateUniqueUploadPath(name, directory);
        validateFileType(name);
        return presignPutUrl(path);
    }

    private FilePresignedUrlRespVO presignPutUrl(String path) {
        FileClient fileClient = fileConfigService.getMasterFileClient();
        String uploadUrl = fileClient.presignPutUrl(path);
        String visitUrl = fileClient.presignGetUrl(path, null);
        return new FilePresignedUrlRespVO().setConfigId(fileClient.getId())
                .setPath(path).setUploadUrl(uploadUrl).setUrl(visitUrl);
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        rejectManagedLocation(url);
        FileClient fileClient = fileConfigService.getMasterFileClient();
        return fileClient.presignGetUrl(url, expirationSeconds);
    }

    @Override
    public String getFileAccessUrl(Long id, Integer expirationSeconds) {
        FileDO file = validateFileExists(id);
        if (file.getPath().startsWith("managed/")) throw exception(FILE_NOT_EXISTS);
        FileClient fileClient = fileConfigService.getFileClient(file.getConfigId());
        Assert.notNull(fileClient, "客户端({}) 不能为空", file.getConfigId());
        try {
            return fileClient.presignGetUrl(file.getPath(), expirationSeconds);
        } catch (UnsupportedOperationException ignored) {
            return file.getUrl();
        }
    }

    @Override
    public Long createFile(FileCreateReqVO createReqVO) {
        rejectManagedLocation(createReqVO.getPath());
        // 1.1 校验参数的合法性
        FilePathUtils.validatePath(createReqVO.getPath());
        createReqVO.setName(FilePathUtils.validateFileName(createReqVO.getName()));
        // 1.2 校验原文件名和存储路径的类型，前端直传不校验文件内容
        String type = validateFileType(createReqVO.getName());
        if (ObjectUtil.notEqual(type, validateFileType(FileUtil.getName(createReqVO.getPath())))) {
            throw exception(FILE_TYPE_NOT_ALLOWED);
        }
        createReqVO.setType(type);
        // 1.3 处理 URL 的合法性，移除 URL 中的查询参数（例如签名参数），保证 URL 的唯一性
        createReqVO.setUrl(HttpUtils.removeUrlQuery(createReqVO.getUrl())); // 目的：移除私有桶情况下，URL 的签名参数

        // 2. 保存到数据库
        FileDO file = BeanUtils.toBean(createReqVO, FileDO.class);
        fileMapper.insert(file);
        return file.getId();
    }

    private String validateFileType(String name) {
        // Tika 对 JSON Lines 的文件名可能返回二进制；仅映射扩展名，上传内容仍经过独立检测。
        String extension = StrUtil.nullToDefault(FileUtil.extName(name), "").toLowerCase(java.util.Locale.ROOT);
        String type = Set.of("jsonl", "ndjson").contains(extension) ? "application/json" : FileTypeUtils.getMineType(name);
        if (!ALLOWED_FILE_TYPES.contains(type)) {
            throw exception(FILE_TYPE_NOT_ALLOWED);
        }
        return type;
    }

    private void rejectManagedLocation(String location) {
        if (location == null) return;
        String path = location;
        try {
            String parsed = java.net.URI.create(location).getPath();
            if (parsed != null) path = parsed;
        } catch (IllegalArgumentException ignored) {
            // 普通文件名可包含空格；后续路径校验保持既有行为。
        }
        if (path.equals("managed") || path.startsWith("managed/") || path.contains("/managed/"))
            throw exception(FILE_PATH_INVALID);
    }

    @Override
    public FileDO getFile(Long id) {
        return validateFileExists(id);
    }

    @Override
    public void deleteFile(Long id) throws Exception {
        // 1.1 校验存在
        FileDO file = validateFileExists(id);
        // 1.2 校验路径合法性，避免误删文件存储器中的其他文件
        FilePathUtils.validatePath(file.getPath());

        // 2.1 从文件存储器中删除
        FileClient client = fileConfigService.getFileClient(file.getConfigId());
        Assert.notNull(client, "客户端({}) 不能为空", file.getConfigId());
        client.delete(file.getPath());

        // 2.2 删除记录
        fileMapper.deleteById(id);
    }

    @Override
    @SneakyThrows
    public void deleteFileList(List<Long> ids) {
        // 删除文件
        List<FileDO> files = fileMapper.selectByIds(ids);
        for (FileDO file : files) {
            FilePathUtils.validatePath(file.getPath());
            // 获取客户端
            FileClient client = fileConfigService.getFileClient(file.getConfigId());
            Assert.notNull(client, "客户端({}) 不能为空", file.getPath());
            // 删除文件
            client.delete(file.getPath());
        }

        // 删除记录
        fileMapper.deleteByIds(ids);
    }

    private FileDO validateFileExists(Long id) {
        FileDO fileDO = fileMapper.selectById(id);
        if (fileDO == null) {
            throw exception(FILE_NOT_EXISTS);
        }
        return fileDO;
    }

    @Override
    public byte[] getFileContent(Long configId, String path) throws Exception {
        // 受管文件只能通过经过服务认证和操作键校验的接口读取。
        if (path != null && path.startsWith("managed/")) throw exception(FILE_NOT_EXISTS);
        FilePathUtils.validatePath(path);

        // 2.1 获取客户端
        FileClient client = fileConfigService.getFileClient(configId);
        Assert.notNull(client, "客户端({}) 不能为空", configId);
        // 2.2 获取文件内容
        return client.getContent(path);
    }

    @Override
    public FileDO getFileByConfigIdAndPath(Long configId, String path) {
        return fileMapper.selectLatestByConfigIdAndPath(configId, path);
    }

}
