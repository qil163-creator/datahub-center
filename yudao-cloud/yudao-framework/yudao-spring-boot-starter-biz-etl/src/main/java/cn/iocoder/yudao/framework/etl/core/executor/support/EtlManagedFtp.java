package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.core.text.csv.CsvParser;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/** 工作节点FTP适配。认证只在连接时读取，所有远端路径限制在显式授权根目录中。 */
public final class EtlManagedFtp {
    private EtlManagedFtp() { }

    public static Session fromEnvironment(JSONObject plan) {
        String roots = System.getenv("DATAHUB_ETL_FTP_ROOTS");
        Set<String> allowed = roots == null ? Set.of() : Set.copyOf(JSONUtil.parseArray(roots).toList(String.class));
        return new Session(plan, allowed, EtlManagedSecrets::readFromEnvironment);
    }

    public static URI root(String value, Set<String> allowed) {
        try {
            URI root = URI.create(value);
            if (!Set.of("ftp", "ftps").contains(root.getScheme()) || root.getHost() == null || root.getUserInfo() != null || root.getQuery() != null
                    || root.getFragment() != null || root.getPort() < 1 || root.getPort() > 65535 || !allowed.contains(root.toASCIIString())) throw invalid("FTP根目录未获执行环境授权");
            path(root.getPath(), true); return root;
        } catch (RuntimeException failure) { throw invalid("FTP根目录无效或未获执行环境授权"); }
    }

    public static void path(String value, boolean absolute) {
        if (value == null || value.isBlank() || value.startsWith("/") != absolute || value.length() > 2048 || value.indexOf('\\') >= 0
                || value.chars().anyMatch(character -> character < 32 || character == 127) || value.matches(".*[?*\"<>|].*")) throw invalid("FTP路径无效");
        String clean = absolute ? value.substring(1) : value;
        if (!clean.isEmpty() && Arrays.stream(clean.split("/", -1)).anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) throw invalid("FTP路径不可包含目录跳转或空路径段");
    }

    public static void read(JSONObject plan, Consumer<String> receiver) {
        int bytesLimit = plan.getInt("maxBytes", 8_388_608), rowsLimit = plan.getInt("maxRows", 100_000);
        if (bytesLimit < 1 || bytesLimit > 67_108_864 || rowsLimit < 1 || rowsLimit > 1_000_000) throw invalid("FTP读取预算无效");
        var columns = plan.getJSONArray("columns");
        String charset = plan.getStr("encoding", "UTF-8");
        if (!Set.of("UTF-8", "GBK", "GB2312", "ISO-8859-1").contains(charset)) throw invalid("FTP字符集尚未支持");
        int consumed = 0, expandedTotal = 0, rows = 0;
        try (Session session = fromEnvironment(plan)) {
            for (String file : session.files(plan.getStr("path"), plan.getStr("syncMode", "AUTO"))) {
                byte[] content = session.read(file, bytesLimit - consumed); consumed += content.length;
                InputStream source = new ByteArrayInputStream(content);
                if (plan.getStr("compression", "").equals("gzip")) source = new GZIPInputStream(source);
                byte[] expanded;
                try (InputStream input = source) { expanded = input.readNBytes(bytesLimit + 1); }
                if (expanded.length > bytesLimit) throw invalid("FTP解压数据超过预算");
                expandedTotal += expanded.length; if (expandedTotal > bytesLimit) throw invalid("FTP解压后总字节数超过预算");
                String text = java.nio.charset.Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(expanded)).toString();
                var config = new CsvReadConfig().setFieldSeparator(plan.getStr("fieldDelimiter", ",").charAt(0)).setSkipEmptyRows(false);
                if (plan.getStr("fileType", "csv").equals("text")) config.setTextDelimiter('\0');
                try (var parser = new CsvParser(new StringReader(text), config)) {
                    cn.hutool.core.text.csv.CsvRow line; boolean first = true;
                    while ((line = parser.nextRow()) != null) {
                        if (Thread.currentThread().isInterrupted()) throw invalid("FTP读取已取消");
                        if (first) { first = false; if (plan.getBool("hasHeader", false)) continue; }
                        if (++rows > rowsLimit) throw invalid("FTP读取行数超过预算");
                        var row = JSONUtil.createObj();
                        for (Object entry : columns) {
                            var column = JSONUtil.parseObj(entry); int index = column.getInt("index");
                            if (index < 0 || index >= line.size()) throw invalid("FTP文件字段数量少于声明索引");
                            String value = line.get(index); if (index == 0 && value.startsWith("\uFEFF")) value = value.substring(1);
                            row.set(column.getStr("name"), value.equals(plan.getStr("nullLiteral", "\\N")) ? null : value);
                        }
                        receiver.accept(row.toString());
                    }
                }
            }
        } catch (Exception failure) { throw invalid("受管FTP读取失败，请检查授权、认证、路径、字段与读取预算"); }
    }

    public static final class Session implements AutoCloseable {
        private final FTPClient client;
        private final String rootPath;
        public Session(JSONObject plan, Set<String> allowed, Function<String, String> secrets) {
            URI root = root(plan.getStr("rootUri"), allowed); rootPath = root.getPath();
            JSONObject options = plan.getJSONObject("ftpConfig"); if (options == null) options = JSONUtil.createObj();
            client = root.getScheme().equals("ftps") ? new FTPSClient(options.getBool("implicitTls", false)) : new FTPClient();
            try {
                client.setConnectTimeout(options.getInt("connectTimeout", 10_000)); client.setDefaultTimeout(options.getInt("dataTimeout", 30_000));
                client.setDataTimeout(Duration.ofMillis(options.getInt("dataTimeout", 30_000))); client.setControlEncoding("UTF-8"); client.setAutodetectUTF8(true);
                client.setRemoteVerificationEnabled(true); client.setIpAddressFromPasvResponse(false);
                if (client instanceof FTPSClient tls) tls.setEndpointCheckingEnabled(true);
                client.connect(root.getHost(), root.getPort());
                if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw invalid("FTP服务拒绝连接");
                JSONObject credential = JSONUtil.parseObj(secrets.apply(plan.getStr("credentialRef")));
                if (!credential.keySet().equals(Set.of("username", "password")) || credential.getStr("username", "").isBlank() || credential.getStr("password", "").isEmpty()) throw invalid("FTP秘密引用格式不符");
                for (Object value : credential.values()) if (!(value instanceof String text) || text.chars().anyMatch(character -> character < 32 || character == 127)) throw invalid("FTP秘密引用内容无效");
                if (!client.login(credential.getStr("username"), credential.getStr("password"))) throw invalid("FTP认证失败");
                if (client instanceof FTPSClient tls) { tls.execPBSZ(0); tls.execPROT("P"); }
                if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw invalid("FTP二进制模式不可用");
                if (options.getBool("passiveMode", true)) client.enterLocalPassiveMode(); else client.enterLocalActiveMode();
                if (!client.changeWorkingDirectory(rootPath)) throw invalid("FTP根目录不可访问");
                FTPFile directory = client.mlistFile(".");
                if (directory == null || !directory.isDirectory() || directory.isSymbolicLink()) throw invalid("FTP服务需要MLST目录证据");
            } catch (Exception failure) { close(); throw invalid("受管FTP连接失败，请检查资源、认证与FTP功能支持"); }
        }

        public List<String> files(String relative) throws Exception { return files(relative, "AUTO"); }
        public List<String> files(String relative, String mode) throws Exception {
            if (!Set.of("AUTO", "SINGLE_FILE", "DIRECTORY").contains(mode)) throw invalid("FTP同步方式无效");
            path(relative, false); String[] parts = relative.split("/"); cwd(parts, parts.length - 1);
            FTPFile target = client.mlistFile(parts[parts.length - 1]);
            if (target == null || target.isSymbolicLink()) throw invalid("FTP输入路径不可用或为符号链接");
            String parent = parts.length > 1 ? String.join("/", Arrays.copyOf(parts, parts.length - 1)) + "/" : "";
            if (target.isFile()) {
                if (mode.equals("DIRECTORY")) throw invalid("FTP指定目录模式不能读取单文件");
                return List.of(relative);
            }
            if (mode.equals("SINGLE_FILE")) throw invalid("FTP单文件模式不能读取目录");
            if (!target.isDirectory() || !client.changeWorkingDirectory(parts[parts.length - 1])) throw invalid("FTP输入必须为普通文件或目录");
            FTPFile[] entries = client.mlistDir();
            if (entries == null || entries.length > 1000) throw invalid("FTP目录超过文件预算或不能枚举");
            var result = new ArrayList<String>();
            for (FTPFile entry : entries) {
                if (entry.getName().startsWith(".")) continue;
                if (!entry.isFile() || entry.isSymbolicLink() || entry.getName().contains("/")) throw invalid("FTP输入目录包含非普通文件");
                result.add(parent + parts[parts.length - 1] + "/" + entry.getName());
            }
            result.sort(String::compareTo); return result;
        }

        public byte[] read(String relative, int budget) throws Exception {
            if (budget < 1) throw invalid("FTP总字节数超过预算");
            path(relative, false); String[] parts = relative.split("/"); cwd(parts, parts.length - 1);
            FTPFile file = client.mlistFile(parts[parts.length - 1]);
            if (file == null || !file.isFile() || file.isSymbolicLink() || file.getSize() > budget) throw invalid("FTP文件无效或超过读取预算");
            byte[] bytes;
            try (InputStream input = client.retrieveFileStream(parts[parts.length - 1])) {
                if (input == null) throw invalid("FTP文件不能读取"); bytes = input.readNBytes(budget + 1);
            }
            if (!client.completePendingCommand() || bytes.length > budget) throw invalid("FTP传输不完整或超过预算");
            return bytes;
        }

        /** STOU由服务器原子分配新名称，避免STOR/先检查再写产生覆盖竞争。 */
        public String storeUnique(String directory, String requested, Path local, long limit) throws Exception {
            path(directory, false); if (!requested.matches("[A-Za-z0-9_.-]{1,220}") || Files.size(local) > limit) throw invalid("FTP输出文件名或预算无效");
            String[] parts = directory.split("/"); cwd(parts, parts.length);
            String actual = null;
            try (var output = client.storeUniqueFileStream(requested)) {
                if (output == null) throw invalid("FTP服务不支持STOU新文件写入");
                var matcher = java.util.regex.Pattern.compile("(?i)FILE(?: NAME)?:\\s*([^\\r\\n]+)").matcher(client.getReplyString());
                if (!matcher.find()) throw invalid("FTP服务没有返回STOU唯一文件名");
                actual = matcher.group(1).strip();
                if (!actual.startsWith(requested) || actual.contains("/") || actual.contains("\\") || actual.chars().anyMatch(character -> character < 32)) throw invalid("FTP唯一文件名回执无效");
                Files.copy(local, output);
            } catch (Exception failure) {
                if (actual != null) { try { client.completePendingCommand(); client.deleteFile(actual); } catch (Exception ignored) { } }
                throw invalid("FTP新文件传输失败");
            }
            if (!client.completePendingCommand()) { client.deleteFile(actual); throw invalid("FTP新文件传输未完成"); }
            return directory + "/" + actual;
        }
        public void deleteCreated(String relative) throws Exception {
            path(relative, false); String[] parts = relative.split("/"); cwd(parts, parts.length - 1);
            if (!client.deleteFile(parts[parts.length - 1])) throw invalid("FTP本次新文件清理失败");
        }
        private void cwd(String[] parts, int count) throws Exception {
            if (!client.changeWorkingDirectory(rootPath)) throw invalid("FTP根目录不可访问");
            for (int index = 0; index < count; index++) {
                FTPFile directory = client.mlistFile(parts[index]);
                if (directory == null || !directory.isDirectory() || directory.isSymbolicLink() || !client.changeWorkingDirectory(parts[index])) throw invalid("FTP目录不可用或存在符号链接");
            }
        }
        @Override public void close() {
            if (client != null && client.isConnected()) try { client.disconnect(); } catch (Exception ignored) { }
        }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
