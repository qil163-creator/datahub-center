package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadProfile;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 在固定 FE/BE 配置上观察到的 Doris 4.0 Stream Load 事实。 */
public final class DorisStreamLoadProfile implements OlapStreamLoadProfile {

    private static final Map<String, String> HEADERS;

    static {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("group_commit", "off_mode");
        headers.put("partial_columns", "false");
        HEADERS = Collections.unmodifiableMap(headers);
    }

    /**
     * 返回产品名称。
     */
    @Override
    public String productName() {
        return "Doris";
    }

    /**
     * 已观察原生配置的精确 FE/BE {@code Version}；其他版本全部拒绝。
     */
    @Override
    public String admittedVersion() {
        return "doris-4.0.8-rc02-bc8ea1bac6d";
    }

    /**
     * 写入COPY能力。
     */
    @Override
    public OperationCapability writeCopyCapability() {
        return new DorisProductProvider().products().stream()
                .filter(item -> item.product() == ProductTypeEnum.DORIS)
                .map(item -> item.capabilities().get(DatabaseOperation.WRITE_COPY))
                .findFirst().orElse(null);
    }

    /**
     * 返回表头集合。
     */
    @Override
    public Map<String, String> requestHeaders() {
        return HEADERS;
    }

    /** Doris 4.0 FE 拒绝合法物理名称中的 URL 保留字符。 */
    @Override
    public boolean directBackendForReservedNames() {
        return true;
    }

    /** Doris FE 会在重定向 URI 中回显现有 Basic 账号。 */
    @Override
    public boolean redirectEchoesBasicAccount() {
        return true;
    }

    /**
     * 计划 CREATE 语法：除共享标量外准入的文本或二进制标量类型。
     */
    @Override
    public String targetTextOrBinaryKind() {
        return "string";
    }

    /**
     * 计划 CREATE 语法：除 DUPLICATE 外准入的键表模型。
     */
    @Override
    public String targetKeyedModel() {
        return "UNIQUE";
    }

    /**
     * 计划 CREATE 语法：拒绝超出此产品已验证原生类型范围的列类型。
     */
    @Override
    public void requireTargetNativeType(String type) {
        DorisNativeSchema.type(type);
    }
}
