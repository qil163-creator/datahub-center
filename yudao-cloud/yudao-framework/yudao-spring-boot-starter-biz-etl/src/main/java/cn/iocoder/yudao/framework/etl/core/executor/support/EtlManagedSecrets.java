package cn.iocoder.yudao.framework.etl.core.executor.support;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 受管工作节点共用的秘密引用读取，不接受节点传入绝对路径，不回显文件内容或底层异常。 */
public final class EtlManagedSecrets {
    private EtlManagedSecrets() { }

    public static void validate(String reference, Path root) {
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("受管秘密引用无效");
        if (root == null || !root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("工作节点未挂载受管秘密目录");
    }

    public static String read(String reference, Path root) {
        validate(reference, root);
        try {
            Path path = root.resolve(reference);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("秘密引用不可用");
            ByteBuffer buffer = ByteBuffer.allocate(4097);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (var channel = Files.newByteChannel(path, options)) {
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            }
            if (buffer.position() == 0 || buffer.position() > 4096) throw new IllegalStateException("受管秘密长度无效");
            buffer.flip();
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString();
            if (value.endsWith("\r\n")) return value.substring(0, value.length() - 2);
            return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
        } catch (Exception failure) { throw new IllegalStateException("受管秘密不可读取或格式无效"); }
    }

    public static String readFromEnvironment(String reference) {
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("受管秘密引用无效");
        String configured = System.getenv("DATAHUB_ETL_SECRET_REFS");
        Set<String> allowed = configured == null ? Set.of() : Arrays.stream(configured.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!allowed.contains(reference)) throw new IllegalArgumentException("执行环境未授权此秘密引用");
        String directory = System.getenv("DATAHUB_ETL_SECRET_DIR");
        return read(reference, directory == null ? null : Path.of(directory));
    }
}
