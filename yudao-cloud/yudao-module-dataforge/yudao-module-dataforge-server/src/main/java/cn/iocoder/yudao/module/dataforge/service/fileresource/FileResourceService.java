package cn.iocoder.yudao.module.dataforge.service.fileresource;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.file.core.remote.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fileresource.FileResourceOperationDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fileresource.FileResourceOperationMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 文件操作权限内平台可见；每次操作解析已保存且启用的连接，不接受浏览器提供凭据或任意连接地址。 */
@Service
public class FileResourceService {

    static final long MAX_BYTES = 500L * 1024 * 1024;

    static final int MAX_SCAN_ENTRIES = 10000;

    static final int MAX_DEPTH = 32;

    private static final int PREVIEW_BYTES = 1024 * 1024;

    private static final Set<String> TEXT_TYPES = Set.of("TXT", "CSV", "JSON", "XML", "SQL", "LOG", "MD", "YAML", "YML",
            "PROPERTIES");

    @Resource
    private FileResourceConnectionResolver connections;

    @Resource
    private FileResourceOperationMapper operationMapper;

    /**
     * 获得可管理的文件连接。
     *
     * @return 可管理的文件连接结果集合
     */
    public List<FileResourceConnectionRespVO> connections() {
        return connections.connections();
    }

    /**
     * 统计连接根目录文件。
     *
     * @param connectionId 连接编号
     * @return 文件资源汇总响应数据
     */
    public FileResourceSummaryRespVO summary(Long connectionId) {
        FileResourceSummaryRespVO result = new FileResourceSummaryRespVO();
        result.setConnectionStatus("FAILED");
        try (RemoteFileClient client = connections.open(connectionId)) {
            if (!client.stat("/").directory()) {
                throw new IOException();
            }
            result.setConnectionStatus("CONNECTED");
            result.setTodayUploadCount(operationMapper.countTodayUploads(connectionId));
            List<RemoteFileEntry> entries = RemoteFileOperations.walk(client, "/", MAX_SCAN_ENTRIES, MAX_DEPTH);
            long files = 0, directories = 0, bytes = 0;
            for (RemoteFileEntry entry : entries) {
                if (entry.directory()) {
                    if (!"/".equals(entry.path())) {
                        directories++;
                    }
                } else {
                    files++;
                    bytes = Math.addExact(bytes, entry.size());
                }
            }
            result.setFileCount(files);
            result.setDirectoryCount(directories);
            result.setStorageBytes(bytes);
            result.setComplete(true);
        } catch (Exception failure) {
            result.setComplete(false);
            result.setMessage("CONNECTED".equals(result.getConnectionStatus())
                    ? "目录统计未完成，请检查目录权限或缩小连接根目录（最多10000项、32层）"
                    : "连接失败，请检查连接配置、服务状态和访问权限");
        }
        return result;
    }

    /**
     * 获取文件资源的树。
     *
     * @param id 记录编号
     * @param path 路径
     * @return 文件资源的树结果集合
     */
    public List<FileResourceEntryRespVO> tree(Long id, String path) {
        return read(id, client -> client.list(normalize(path)).stream().filter(RemoteFileEntry::directory)
                .sorted(Comparator.comparing(RemoteFileEntry::name)).map(FileResourceService::entry).toList());
    }

    /**
     * 分页筛选当前目录。
     *
     * @param req 请求参数
     * @return 分页筛选当前目录结果
     */
    public PageResult<FileResourceEntryRespVO> page(FileResourcePageReqVO req) {
        return read(req.getConnectionId(), client -> {
            String name = req.getName() == null ? "" : req.getName().trim().toLowerCase(Locale.ROOT);
            String type = req.getType() == null ? "" : req.getType().toUpperCase(Locale.ROOT);
            List<RemoteFileEntry> filtered = new ArrayList<>(client.list(normalize(req.getPath())).stream()
                    .filter(item -> item.name().toLowerCase(Locale.ROOT).contains(name))
                    .filter(item -> type.isEmpty() || type.equals(fileType(item))).toList());
            Comparator<RemoteFileEntry> order = Comparator.comparing(RemoteFileEntry::name);
            if (req.getOrder() != null) {
                order = Comparator.comparingLong(RemoteFileEntry::modifiedTime);
                if ("desc".equals(req.getOrder())) {
                    order = order.reversed();
                }
            }
            filtered.sort(Comparator.comparing(RemoteFileEntry::directory).reversed().thenComparing(order));
            int start = (int) Math.min(filtered.size(), (long) (req.getPageNo() - 1) * req.getPageSize());
            int end = Math.min(filtered.size(), start + req.getPageSize());
            return new PageResult<>(filtered.subList(start, end).stream().map(FileResourceService::entry).toList(),
                    (long) filtered.size());
        });
    }

    /**
     * 上传文件到选定连接目录。
     *
     * @param id 记录编号
     * @param parent 父级
     * @param file 文件
     * @param overwrite 覆盖
     */
    public void upload(Long id, String parent, MultipartFile file, boolean overwrite) {
        if (file == null || file.getSize() > MAX_BYTES) {
            throw exception(FILE_RESOURCE_LIMIT_EXCEEDED);
        }
        String name = file.getOriginalFilename();
        if (name == null || name.length() > 255) {
            throw exception(FILE_RESOURCE_PATH_INVALID);
        }
        String path = child(parent, name);
        mutate(id, "UPLOAD", path, null, client -> {
            try (InputStream input = file.getInputStream()) {
                client.upload(path, input, file.getSize(), overwrite);
            }
        });
    }

    /**
     * 校验远程目录路径及名称，登记操作意图后创建目录。
     *
     * @param req 请求参数
     */
    public void mkdir(FileResourceNameReqVO req) {
        String path = child(req.getPath(), validName(req.getName()));
        mutate(req.getConnectionId(), "MKDIR", path, null, client -> client.mkdir(path));
    }

    /**
     * 校验路径及目标名称，登记操作意图后执行远程重命名。
     *
     * @param req 请求参数
     */
    public void rename(FileResourceNameReqVO req) {
        String path = mutablePath(req.getPath());
        String name = validName(req.getName());
        String parent = path.substring(0, path.lastIndexOf('/'));
        String target = child(parent.isEmpty() ? "/" : parent, name);
        if (path.equals(target)) {
            return;
        }
        mutate(req.getConnectionId(), "RENAME", path, target, client -> client.rename(path, target));
    }

    /**
     * 逐项删除远程文件或目录。
     *
     * @param req 请求参数
     * @return 文件资源批次响应数据
     */
    public FileResourceBatchRespVO delete(FileResourceBatchReqVO req) {
        FileResourceBatchRespVO result = new FileResourceBatchRespVO();
        // 全部路径先校验，避免包含根路径的错误请求产生部分删除。
        List<String> paths = req.getPaths().stream().map(FileResourceService::mutablePath).distinct().toList();
        for (String path : paths) {
            try {
                mutate(req.getConnectionId(), "DELETE", path, null,
                        client -> RemoteFileOperations.deleteRecursively(client, path, MAX_SCAN_ENTRIES, MAX_DEPTH));
                result.getSucceededPaths().add(path);
            } catch (Exception failure) {
                result.getFailures().add(new FileResourceBatchRespVO.Failure(path, "删除未完成，请刷新目录核对后重试"));
            }
        }
        return result;
    }

    /**
     * 预览文件资源处理结果。
     *
     * @param id 记录编号
     * @param path 路径
     * @return 文件资源预览响应数据
     */
    public FileResourcePreviewRespVO preview(Long id, String path) {
        return read(id, client -> {
            RemoteFileEntry file = client.stat(normalize(path));
            String fileType = fileType(file);
            if (!file.directory() && Set.of("PNG", "JPG", "JPEG", "GIF", "WEBP", "BMP", "PDF").contains(fileType)) {
                if (file.size() > 20L * 1024 * 1024) {
                    throw exception(FILE_RESOURCE_LIMIT_EXCEEDED);
                }
                return new FileResourcePreviewRespVO(file.name(), "", false, "PDF".equals(fileType) ? "PDF" : "IMAGE");
            }
            if (file.directory() || !TEXT_TYPES.contains(fileType)) {
                return new FileResourcePreviewRespVO(file.name(), "", false, "UNSUPPORTED");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream limited = new OutputStream() {
                /**
                 * 写入文件资源数据。
                 *
                 * @param value 待处理的值
                 */
                @Override
                public void write(int value) throws IOException {
                    if (bytes.size() >= PREVIEW_BYTES) {
                        throw new PreviewLimit();
                    }
                    bytes.write(value);
                }

                /**
                 * 写入文件资源数据。
                 *
                 * @param data 待处理数据
                 * @param offset 读取偏移量
                 * @param length 长度
                 */
                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    int count = Math.min(length, PREVIEW_BYTES - bytes.size());
                    bytes.write(data, offset, count);
                    if (count < length) {
                        throw new PreviewLimit();
                    }
                }
            };
            boolean truncated = false;
            try {
                client.download(file.path(), limited, MAX_BYTES);
            } catch (PreviewLimit limit) {
                truncated = true;
            }
            try {
                // 截断时允许末尾 UTF-8 半字符替换；完整文件不把二进制内容当作文本。
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(truncated ? CodingErrorAction.REPLACE : CodingErrorAction.REPORT);
                String content = decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                return new FileResourcePreviewRespVO(file.name(), content, truncated, "TEXT");
            } catch (CharacterCodingException binary) {
                return new FileResourcePreviewRespVO(file.name(), "", false, "UNSUPPORTED");
            }
        });
    }

    /** 图像和 PDF 以认证二进制响应返回，不向外部预览站发布源端地址。 */
    public PreviewContent previewContent(Long id, String path) {
        return read(id, client -> {
            RemoteFileEntry file = client.stat(normalize(path));
            if (file.directory() || file.size() > 20L * 1024 * 1024) {
                throw exception(FILE_RESOURCE_LIMIT_EXCEEDED);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            client.download(file.path(), bytes, 20L * 1024 * 1024);
            byte[] content = bytes.toByteArray();
            String mime = previewMime(fileType(file), content);
            if (mime == null) {
                throw exception(FILE_RESOURCE_OPERATION_FAILED);
            }
            return new PreviewContent(file.name(), mime, content);
        });
    }

    /**
     * 获取文件资源的预览MIME。
     *
     * @param type 类型标识
     * @param bytes 字节
     * @return 文件资源的预览MIME文本
     */
    static String previewMime(String type, byte[] bytes) {
        if (bytes.length < 12) {
            return null;
        }
        if ("PDF".equals(type) && starts(bytes, new byte[]{37, 80, 68, 70, 45})) {
            return "application/pdf";
        }
        if ("PNG".equals(type) && starts(bytes, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
            return "image/png";
        }
        if (Set.of("JPG", "JPEG").contains(type) && starts(bytes, new byte[]{(byte) 255, (byte) 216, (byte) 255})) {
            return "image/jpeg";
        }
        if ("GIF".equals(type) && (starts(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII)) || starts(bytes,
                "GIF89a".getBytes(StandardCharsets.US_ASCII)))) {
            return "image/gif";
        }
        if ("BMP".equals(type) && starts(bytes, new byte[]{66, 77})) {
            return "image/bmp";
        }
        if ("WEBP".equals(type) && starts(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "image/webp";
        }
        return null;
    }

    /**
     * 判断开始条件是否成立。
     *
     * @param value 待处理的值
     * @param signature 签名
     * @return 处理是否成功
     */
    private static boolean starts(byte[] value, byte[] signature) {
        return value.length >= signature.length && Arrays.equals(value, 0, signature.length, signature, 0,
                signature.length);
    }

    public record PreviewContent(String name, String mime, byte[] content) {

    }

    /** 调用方提供临时文件输出；完成全部传输后才向浏览器发送，避免错误响应混入二进制。 */
    public String download(Long id, List<String> requested, OutputStream output, boolean batch) {
        return read(id, client -> {
            List<String> paths = requested.stream().map(FileResourceService::normalize).distinct().toList();
            if (paths.isEmpty() || paths.size() > 100) {
                throw exception(FILE_RESOURCE_LIMIT_EXCEEDED);
            }
            RemoteFileEntry first = client.stat(paths.getFirst());
            if (!batch && paths.size() == 1 && !first.directory()) {
                if (first.size() > MAX_BYTES) {
                    throw exception(FILE_RESOURCE_LIMIT_EXCEEDED);
                }
                client.download(first.path(), output, MAX_BYTES);
                return first.name();
            }
            RemoteFileOperations.zip(client, paths, output, MAX_SCAN_ENTRIES, MAX_DEPTH, MAX_BYTES);
            return paths.size() == 1 && !"/".equals(first.path()) ? first.name() + ".zip" : "files.zip";
        });
    }

    /**
     * 先持久化远程文件变更意图，再执行源端操作；结果或登记失联时保留待核对状态。
     *
     * @param id 记录编号
     * @param action 待执行的操作
     * @param path 路径
     * @param target 目标数据
     * @param mutation 变更
     */
    private void mutate(Long id, String action, String path, String target, Mutation mutation) {
        // 先解析连接和路径再登记意图；远程 I/O 不在本地数据库事务/锁内，也不做自动重试。
        try (RemoteFileClient client = connections.open(id)) {
            FileResourceOperationDO operation = new FileResourceOperationDO();
            operation.setConnectionId(id);
            operation.setAction(action);
            operation.setPath(path);
            operation.setTargetPath(target);
            operation.setStatus("PENDING");
            operationMapper.insert(operation);
            try {
                mutation.apply(client);
            } catch (Exception failure) {
                operation.setStatus("UNKNOWN");
                operation.setResultMessage("源端未确认完成，请刷新目录核对");
                try {
                    operationMapper.updateById(operation);
                } catch (RuntimeException ignored) { /* 保留已登记的待核对意图。 */ }
                throw exception(FILE_RESOURCE_RESULT_UNKNOWN);
            }
            operation.setStatus("SUCCESS");
            operation.setResultMessage("操作完成");
            try {
                operationMapper.updateById(operation);
            } catch (RuntimeException auditFailure) {
                throw exception(FILE_RESOURCE_RESULT_UNKNOWN);
            }
        } catch (IOException failure) {
            throw exception(FILE_RESOURCE_OPERATION_FAILED);
        }
    }

    /**
     * 读取文件资源。
     *
     * @param <T> 处理数据的类型
     * @param id 记录编号
     * @param action 待执行的操作
     * @return 文件资源
     */
    private <T> T read(Long id, Read<T> action) {
        try (RemoteFileClient client = connections.open(id)) {
            return action.apply(client);
        } catch (IllegalArgumentException invalid) {
            throw exception(FILE_RESOURCE_PATH_INVALID);
        } catch (IOException failure) {
            throw exception(FILE_RESOURCE_OPERATION_FAILED);
        }
    }

    /**
     * 获取文件资源的有效名称。
     *
     * @param value 待处理的值
     * @return 文件资源的有效名称文本
     */
    private static String validName(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 50) {
            throw exception(FILE_RESOURCE_PATH_INVALID);
        }
        return value.trim();
    }

    /**
     * 获取文件资源的可变路径。
     *
     * @param path 路径
     * @return 文件资源的可变路径文本
     */
    private static String mutablePath(String path) {
        String normalized = normalize(path);
        if ("/".equals(normalized)) {
            throw exception(FILE_RESOURCE_PATH_INVALID);
        }
        return normalized;
    }

    /**
     * 规范化文件资源。
     *
     * @param path 路径
     * @return 规范化文件资源文本
     */
    private static String normalize(String path) {
        try {
            return RemoteFilePaths.normalize(path);
        } catch (IllegalArgumentException invalid) {
            throw exception(FILE_RESOURCE_PATH_INVALID);
        }
    }

    /**
     * 获取文件资源的子级。
     *
     * @param parent 父级
     * @param name 名称
     * @return 文件资源的子级文本
     */
    private static String child(String parent, String name) {
        try {
            return RemoteFilePaths.child(normalize(parent), name);
        } catch (IllegalArgumentException invalid) {
            throw exception(FILE_RESOURCE_PATH_INVALID);
        }
    }

    /**
     * 获取文件资源的文件类型。
     *
     * @param item 当前条目
     * @return 文件资源的文件类型文本
     */
    private static String fileType(RemoteFileEntry item) {
        if (item.directory()) {
            return "DIRECTORY";
        }
        int dot = item.name().lastIndexOf('.');
        return dot >= 0 && dot < item.name().length() - 1 ? item.name().substring(dot + 1)
                .toUpperCase(Locale.ROOT) : "FILE";
    }

    /**
     * 获取文件资源的条目。
     *
     * @param item 当前条目
     * @return 文件资源条目响应数据
     */
    static FileResourceEntryRespVO entry(RemoteFileEntry item) {
        FileResourceEntryRespVO result = new FileResourceEntryRespVO();
        result.setName(item.name());
        result.setPath(item.path());
        result.setDirectory(item.directory());
        result.setType(fileType(item));
        result.setSize(item.directory() ? null : item.size());
        result.setUpdateTime(item.modifiedTime() > 0 ? item.modifiedTime() : null);
        return result;
    }

    @FunctionalInterface
    private interface Read<T> {

        /**
         * 应用读取处理规则。
         *
         * @param client 客户端
         * @return 读取
         */
        T apply(RemoteFileClient client) throws IOException;
    }

    @FunctionalInterface
    private interface Mutation {

        /**
         * 应用变更处理规则。
         *
         * @param client 客户端
         */
        void apply(RemoteFileClient client) throws IOException;
    }

    private static class PreviewLimit extends IOException {

    }
}

