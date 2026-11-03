package cn.iocoder.yudao.framework.file.core.client.sftp;

import cn.iocoder.yudao.framework.file.core.client.AbstractFileClient;
import cn.iocoder.yudao.framework.file.core.client.FileStoragePaths;
import cn.iocoder.yudao.framework.file.core.client.FileUrlResolver;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFilePaths;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

/** SFTP 会话按操作隔离；验证主机公钥，上传下载直接使用流。 */
public class SftpFileClient extends AbstractFileClient<SftpFileClientConfig> {

    public SftpFileClient(Long id, SftpFileClientConfig config) {
        super(id, config);
    }

    public SftpFileClient(Long id, SftpFileClientConfig config, FileUrlResolver urls) {
        super(id, config, urls);
    }

    @Override
    protected void doInit() {
        validateConfiguration();
    }

    private void validateConfiguration() {
        RemoteFilePaths.normalize(config.getBasePath());
        if (config.getHostKeyFingerprint() == null
                || !config.getHostKeyFingerprint().matches("^SHA256:[A-Za-z0-9+/]{43}$")) {
            throw new IllegalArgumentException("SFTP 需要可信的服务器公钥指纹");
        }
    }

    /** 复用存储会话验证服务器身份、认证和根目录，不创建任何目录或文件。 */
    public void testConnection() throws Exception {
        try (Connection connection = new Connection()) {
            checkRoot(connection, null);
        }
    }

    /** 按调用方的总时间预算完成握手与根目录校验；原有文件操作保持其会话超时。 */
    public void testConnection(Duration timeout) throws Exception {
        if (timeout == null || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("SFTP 连接超时必须在 1 毫秒到 30 秒之间");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Connection connection = new Connection(deadline)) {
            checkRoot(connection, deadline);
        }
    }

    private void checkRoot(Connection connection, Long deadline) throws JSchException, SftpException {
        String root = RemoteFilePaths.normalize(config.getBasePath());
        String cursor = "";
        for (String part : root.substring(1).split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            cursor += "/" + part;
            if (deadline != null) connection.session.setTimeout(remaining(deadline));
            SftpATTRS attributes = connection.channel.lstat(cursor);
            if (attributes.isLink() || !attributes.isDir()) {
                throw new IllegalArgumentException("SFTP 根目录必须为真实目录，禁止符号链接");
            }
        }
        if (deadline != null) connection.session.setTimeout(remaining(deadline));
        connection.channel.cd(root);
        if (deadline != null) connection.session.setTimeout(remaining(deadline));
        connection.channel.pwd();
    }

    private static int remaining(long deadline) throws JSchException {
        long millis = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
        if (Thread.currentThread().isInterrupted() || millis < 1) {
            throw new JSchException("SFTP 连接测试已超时或中断");
        }
        return Math.toIntExact(millis);
    }

    private final class Connection implements AutoCloseable {

        private Session session;
        private ChannelSftp channel;

        Connection() throws JSchException {
            this(null);
        }

        Connection(Long deadline) throws JSchException {
            validateConfiguration();
            try {
                JSch jsch = new JSch();
                jsch.setHostKeyRepository(new HostKeyRepository() {
                    @Override
                    public int check(String host, byte[] key) {
                        try {
                            String actual = "SHA256:" + Base64.getEncoder().withoutPadding()
                                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
                            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                                    config.getHostKeyFingerprint().getBytes(StandardCharsets.US_ASCII)) ? OK : CHANGED;
                        } catch (Exception failure) {
                            return CHANGED;
                        }
                    }

                    @Override
                    public void add(HostKey key, UserInfo userInfo) {
                        throw new UnsupportedOperationException("禁止自动信任主机密钥");
                    }

                    @Override
                    public void remove(String host, String type) { }

                    @Override
                    public void remove(String host, String type, byte[] key) { }

                    @Override
                    public String getKnownHostsRepositoryID() {
                        return "configured-fingerprint";
                    }

                    @Override
                    public HostKey[] getHostKey() {
                        return new HostKey[0];
                    }

                    @Override
                    public HostKey[] getHostKey(String host, String type) {
                        return new HostKey[0];
                    }
                });
                session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
                session.setPassword(config.getPassword());
                session.setConfig("StrictHostKeyChecking", "yes");
                session.setConfig("PreferredAuthentications", "password");
                session.setTimeout(deadline == null ? 10000 : remaining(deadline));
                session.connect(deadline == null ? 3000 : remaining(deadline));
                channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(deadline == null ? 3000 : remaining(deadline));
            } catch (JSchException e) {
                close();
                throw new JSchException("SFTP 连接失败，请检查连接参数和服务器公钥");
            }
        }

        @Override
        public void close() {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private String checkedPath(ChannelSftp channel, String key, boolean createParents) throws SftpException {
        String path = RemoteFilePaths.normalize(config.getBasePath());
        String relative = FileStoragePaths.validate(key);
        String target = (path.equals("/") ? "" : path) + "/" + relative;
        String cursor = "";
        String[] parts = target.substring(1).split("/");
        for (int i = 0; i < parts.length; i++) {
            cursor += "/" + parts[i];
            try {
                SftpATTRS attributes = channel.lstat(cursor);
                if (attributes.isLink()) {
                    throw new IllegalArgumentException("SFTP 路径禁止符号链接");
                }
            } catch (SftpException missing) {
                if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw missing;
                }
                if (i < parts.length - 1 && createParents) {
                    channel.mkdir(cursor);
                } else if (i < parts.length - 1) {
                    throw missing;
                }
            }
        }
        return target;
    }

    @Override
    public String upload(byte[] content, String key, String type) throws Exception {
        FileStoragePaths.validate(key);
        try (Connection connection = new Connection()) {
            connection.channel.put(new ByteArrayInputStream(content),
                    checkedPath(connection.channel, key, true), ChannelSftp.OVERWRITE);
        }
        return formatFileUrl(config.getDomain(), key);
    }

    @Override
    public void delete(String key) throws Exception {
        FileStoragePaths.validate(key);
        try (Connection connection = new Connection()) {
            connection.channel.rm(checkedPath(connection.channel, key, false));
        } catch (SftpException absent) {
            if (absent.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw absent;
            }
        }
    }

    @Override
    public byte[] getContent(String key) throws Exception {
        FileStoragePaths.validate(key);
        try (Connection connection = new Connection();
                InputStream input = connection.channel.get(checkedPath(connection.channel, key, false))) {
            byte[] content = input.readNBytes(32 * 1024 * 1024 + 1);
            if (content.length > 32 * 1024 * 1024) {
                throw new IOException("文件超过内存读取上限");
            }
            return content;
        } catch (SftpException absent) {
            if (absent.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw absent;
        }
    }
}
