package cn.iocoder.yudao.framework.file.core.remote;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.parser.MLSxEntryParser;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class FtpRemoteFileClient implements RemoteFileClient {
    private final StreamingFtp ftp = new StreamingFtp();
    private final String root;
    private final RemoteFileSessionDeadline deadline;
    private volatile boolean closed;

    FtpRemoteFileClient(RemoteFileConfig config) throws IOException {
        root = config.rootPath();
        if (config.host() == null || config.host().isBlank() || config.port() < 1 || config.port() > 65535) {
            throw new IllegalArgumentException("FTP 连接地址不合法");
        }
        ftp.setConnectTimeout(10_000);
        ftp.setDefaultTimeout(30_000);
        ftp.setDataTimeout(Duration.ofSeconds(30));
        ftp.setControlEncoding(StandardCharsets.UTF_8.name());
        ftp.setAutodetectUTF8(true);
        deadline = new RemoteFileSessionDeadline(() -> {
            closed = true;
            try { if (ftp.isConnected()) ftp.disconnect(); } catch (IOException ignored) { }
        });
        try {
            ftp.connect(config.host(), config.port());
            ftp.setSoTimeout(30_000);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())
                    || !ftp.login(config.username(), config.password())) throw new IOException("FTP 认证失败");
            if (config.ftpPassive()) ftp.enterLocalPassiveMode();
            else ftp.enterLocalActiveMode();
            ftp.setIpAddressFromPasvResponse(false);
            ftp.setRemoteVerificationEnabled(true);
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) throw new IOException("FTP 不支持二进制传输");
            guard("/", true);
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    @Override
    public List<RemoteFileEntry> list(String path) throws IOException {
        path = RemoteFilePaths.normalize(path);
        String resolved = guard(path, true);
        List<RemoteFileEntry> result = new ArrayList<>();
        for (FTPFile file : ftp.listBounded(resolved)) {
            if (file.isSymbolicLink() || (!file.isDirectory() && !file.isFile())) continue;
            result.add(entry(path, file));
        }
        return result;
    }

    @Override
    public RemoteFileEntry stat(String path) throws IOException {
        path = RemoteFilePaths.normalize(path);
        String resolved = guard(path, false);
        if (resolved.equals("/")) return new RemoteFileEntry("/", path, true, 0, 0);
        FTPFile file = find(resolved);
        return new RemoteFileEntry(RemoteFilePaths.name(path), path, file.isDirectory(),
                file.isDirectory() ? 0 : file.getSize(), file.getTimestamp() == null ? 0 : file.getTimestamp().getTimeInMillis());
    }

    @Override
    public void upload(String path, InputStream input, long size, boolean overwrite) throws IOException {
        RemoteFilePaths.requireMutable(path);
        RemoteFileStreams.validateUploadSize(size);
        String parent = RemoteFilePaths.parent(path);
        guard(parent, true);
        RemoteFileOperations.checkUploadTarget(this, path, overwrite);
        String temporary = RemoteFilePaths.child(parent, ".remote-file-upload-" + UUID.randomUUID());
        String resolved = RemoteFilePaths.resolve(root, temporary);
        boolean stored = false;
        try {
            OutputStream stream = ftp.storeFileStream(resolved);
            if (stream == null) throw new IOException("FTP 无法建立上传流");
            long written;
            try {
                try (stream) { written = RemoteFileStreams.copy(input, stream, size); }
            } finally {
                if (!ftp.completePendingCommand()) throw new IOException("FTP 上传失败");
            }
            if (written != size) throw new IOException("上传大小与声明不一致");
            stored = true;
            RemoteFileOperations.commitUpload(this, temporary, path, overwrite);
        } finally {
            // 仅清理当前请求生成的随机暂存文件，绝不清理恢复失败的原件备份。
            if (ftp.isConnected()) {
                try { ftp.deleteFile(resolved); } catch (IOException ignored) { close(); }
            }
            if (!stored) close();
        }
    }

    @Override
    public void download(String path, OutputStream output, long maxBytes) throws IOException {
        RemoteFileEntry file = stat(path);
        if (file.directory()) throw new IOException("目录需要打包下载");
        if (file.size() > maxBytes) throw new IOException("文件超过下载上限");
        InputStream stream = ftp.retrieveFileStream(RemoteFilePaths.resolve(root, path));
        if (stream == null) throw new IOException("FTP 无法建立下载流");
        try {
            try (stream) { RemoteFileStreams.copy(stream, output, maxBytes); }
            if (!ftp.completePendingCommand()) throw new IOException("FTP 下载失败");
        } catch (IOException error) {
            close();
            throw error;
        }
    }

    @Override
    public void rename(String path, String target) throws IOException {
        RemoteFilePaths.requireMutable(path);
        RemoteFilePaths.requireMutable(target);
        guard(path, false);
        guard(RemoteFilePaths.parent(target), true);
        requireAbsent(target);
        if (!ftp.rename(RemoteFilePaths.resolve(root, path), RemoteFilePaths.resolve(root, target))) {
            throw new IOException("FTP 重命名失败");
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        RemoteFilePaths.requireMutable(path);
        guard(RemoteFilePaths.parent(path), true);
        requireAbsent(path);
        if (!ftp.makeDirectory(RemoteFilePaths.resolve(root, path))) throw new IOException("FTP 创建目录失败");
    }

    @Override
    public void delete(String path) throws IOException {
        RemoteFilePaths.requireMutable(path);
        RemoteFileEntry file = stat(path);
        String resolved = RemoteFilePaths.resolve(root, path);
        if (!(file.directory() ? ftp.removeDirectory(resolved) : ftp.deleteFile(resolved))) {
            throw new IOException("FTP 删除失败，目录必须为空");
        }
    }

    private void requireAbsent(String path) throws IOException {
        try { stat(path); } catch (FileNotFoundException missing) { return; }
        throw new IOException("目标名称已存在");
    }

    private String guard(String virtualPath, boolean directory) throws IOException {
        deadline.check();
        if (closed) throw new IOException("文件连接已关闭");
        String absolute = RemoteFilePaths.resolve(root, virtualPath);
        String current = "";
        FTPFile last = null;
        if (!absolute.equals("/")) {
            for (String part : absolute.substring(1).split("/")) {
                current += "/" + part;
                last = find(current);
                if (last.isSymbolicLink() || (!last.isDirectory() && !last.isFile())) {
                    throw new IOException("禁止访问符号链接或特殊文件");
                }
                if (!current.equals(absolute) && !last.isDirectory()) throw new IOException("父路径不是目录");
            }
        }
        if (directory && last != null && !last.isDirectory()) throw new IOException("路径不是目录");
        return absolute;
    }

    private FTPFile find(String absolute) throws IOException {
        String name = RemoteFilePaths.name(absolute);
        for (FTPFile file : ftp.listBounded(RemoteFilePaths.parent(absolute))) {
            if (file.getName().equals(name)) return file;
        }
        throw new FileNotFoundException("文件不存在");
    }

    private RemoteFileEntry entry(String parent, FTPFile file) {
        return new RemoteFileEntry(file.getName(), RemoteFilePaths.child(parent, file.getName()), file.isDirectory(),
                file.isDirectory() ? 0 : file.getSize(), file.getTimestamp() == null ? 0 : file.getTimestamp().getTimeInMillis());
    }

    @Override
    public void close() throws IOException {
        closed = true;
        deadline.close();
        if (ftp.isConnected()) ftp.disconnect();
    }

    /** 使用 MLSD 流式限制目录响应大小，避免 SDK 先完整分配大目录。 */
    private static final class StreamingFtp extends FTPClient {
        List<FTPFile> listBounded(String path) throws IOException {
            Socket socket = _openDataConnection_("MLSD", path);
            if (socket == null) throw new IOException("FTP 目录读取失败，需要 MLSD 支持");
            List<FTPFile> result = new ArrayList<>();
            boolean complete = false;
            try {
                try (socket; Reader reader = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)) {
                    StringBuilder line = new StringBuilder();
                    int total = 0;
                    for (int character; (character = reader.read()) != -1;) {
                        if (++total > 4 * 1024 * 1024 || line.length() > 8192) throw new IOException("FTP 目录响应超过上限");
                        if (character != '\n') { if (character != '\r') line.append((char) character); continue; }
                        String text = line.toString();
                        line.setLength(0);
                        if (text.isEmpty()) continue;
                        String facts = text.substring(0, text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ')).toLowerCase(java.util.Locale.ROOT);
                        if (facts.contains("type=cdir;") || facts.contains("type=pdir;")) continue;
                        FTPFile file = MLSxEntryParser.getInstance().parseFTPEntry(text);
                        if (file == null) throw new IOException("FTP 目录项格式不支持");
                        if (file.getName().equals(".") || file.getName().equals("..")) continue;
                        RemoteFilePaths.child("/", file.getName());
                        if (facts.contains("slink") || facts.contains("symlink")) file.setType(FTPFile.SYMBOLIC_LINK_TYPE);
                        result.add(file);
                        if (result.size() > RemoteFileStreams.MAX_ENTRIES) throw new IOException("目录项超过上限");
                    }
                    if (!line.isEmpty()) throw new IOException("FTP 目录响应未完整结束");
                }
                if (!completePendingCommand()) throw new IOException("FTP 目录读取未完成");
                complete = true;
                return result;
            } finally {
                if (!complete && isConnected()) disconnect();
            }
        }
    }
}
