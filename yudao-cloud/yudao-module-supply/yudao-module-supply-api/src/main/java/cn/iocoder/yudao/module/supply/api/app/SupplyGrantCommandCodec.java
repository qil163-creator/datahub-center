package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 来源命令的规范语义摘要。 */
public final class SupplyGrantCommandCodec {

    /**
     * 限制供应授权命令工具的实例化，统一通过静态能力使用。
     */
    private SupplyGrantCommandCodec() {
    }

    /**
     * 计算输入内容摘要。
     *
     * @param command 待处理命令
     * @return 计算后的摘要或签名字符串
     */
    public static String hash(SupplyGrantSourceCommandDTO command) {
        Objects.requireNonNull(command, "command");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeText(out, "supply-grant-source/v2");
            out.writeInt(command.protocolVersion());

            writeText(out, command.sourceSystem());
            writeText(out, command.sourceKey());
            out.writeLong(command.sourceRevision());
            out.writeLong(command.appId());
            out.writeLong(command.apiId());
            writeText(out, command.action().name());
            var scope = command.scope();
            out.writeBoolean(scope != null);
            if (scope != null) {
                writeText(out, scope.approvalRef());
                writeText(out, scope.sourceType());
                out.writeLong(scope.permissionUserId());
                out.writeLong(scope.binding().databaseId());
                writeText(out, scope.binding().schemaName());
                writeText(out, scope.binding().tableName());
                writeText(out, scope.approvedContractHash());
                writeText(out, scope.apiVersion());
                writeText(out, scope.purpose());
                writeText(out, scope.dutyMatter());
                writeText(out, scope.policyMode());
                out.writeInt(scope.approvedColumns().size());
                for (String field : scope.approvedColumns()) {
                    writeText(out, field);
                }
                out.writeBoolean(scope.forever());
                writeLong(out, scope.validFromMillis());
                writeLong(out, scope.validToMillis());
            }
            writeText(out, command.reason());
            // 老的无限额 v2 字节序保持不变，已有幂等回执仍能校验。
            if (scope != null && scope.quota() != null) {
                writeText(out, "consumption-quota/v1");
                writeText(out, scope.quota().metric());
                out.writeLong(scope.quota().limit());
            }
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot encode grant command", ex);
        }
    }

    /** 兼容性不包含批准编号、可并集字段及期限，但必须包含主体与完整物理绑定。 */
    public static String compatibilityHash(SupplyGrantSourceCommandDTO.Scope scope) {
        Objects.requireNonNull(scope, "scope");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeText(out, "supply-grant-compatibility/v2");
            writeText(out, scope.sourceType());
            out.writeLong(scope.permissionUserId());
            out.writeLong(scope.binding().databaseId());
            writeText(out, scope.binding().schemaName());
            writeText(out, scope.binding().tableName());
            writeText(out, scope.approvedContractHash());
            writeText(out, scope.apiVersion());
            writeText(out, scope.purpose());
            writeText(out, scope.dutyMatter());
            writeText(out, scope.policyMode());
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot encode grant compatibility", ex);
        }
    }

    /**
     * 写入文本。
     *
     * @param out 输出
     * @param value 待处理的值
     */
    private static void writeText(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    /**
     * 写入长整数。
     *
     * @param out 输出
     * @param value 待处理的值
     */
    private static void writeLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeLong(value);
        }
    }
}
