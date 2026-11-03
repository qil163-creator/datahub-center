package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 由产品拥有的单个 MySQL 协议 OLAP Stream Load 方言事实。
 * <p>
 * 每个产品模块在 {@code META-INF/services} 注册实现。共享协议根据
 * 产品名解析，不拥有自身产品知识；未注册名称不会获得这些行为。
 */
public interface OlapStreamLoadProfile {

    /** 写入器、准入错误和 Stream Load 诊断使用的产品名称。 */
    String productName();

    /** 已观察原生配置的精确 FE/BE {@code Version}；其他版本全部拒绝。 */
    String admittedVersion();

    /** 此产品注册的 WRITE_COPY 声明。 */
    OperationCapability writeCopyCapability();

    /** 此产品 Stream Load 契约的固定请求头。 */
    default Map<String, String> requestHeaders() {
        return Map.of();
    }

    /** 名称包含 URL 保留字符时，在任何 FE 请求前提交到固定后端。 */
    default boolean directBackendForReservedNames() {
        return false;
    }

    /** FE 重定向可能回显配置的 Basic 账号；仅比较，绝不作为凭据使用。 */
    default boolean redirectEchoesBasicAccount() {
        return false;
    }

    /** 是否允许通过临时文本列加载原生二进制列。 */
    default boolean nativeBinaryColumns() {
        return false;
    }

    /** 非空原生二进制列集合的列映射请求头。 */
    default Map<String, String> binaryColumnHeaders(List<String> fields, Set<String> binary) throws Exception {
        throw new IllegalArgumentException("Invalid native binary Stream Load fields");
    }

    /** 计划 CREATE 语法：除共享标量外准入的文本或二进制标量类型。 */
    String targetTextOrBinaryKind();

    /** 计划 CREATE 语法：除 DUPLICATE 外准入的键表模型。 */
    String targetKeyedModel();

    /** 计划 CREATE 语法：拒绝超出此产品已验证原生类型范围的列类型。 */
    void requireTargetNativeType(String type);
}
