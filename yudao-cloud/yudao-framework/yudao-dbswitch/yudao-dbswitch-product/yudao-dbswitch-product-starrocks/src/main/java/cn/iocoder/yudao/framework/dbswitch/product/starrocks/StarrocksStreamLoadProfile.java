package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadProfile;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 在固定 FE/BE 配置上观察到的 StarRocks 3.5 Stream Load 事实。 */
public final class StarrocksStreamLoadProfile implements OlapStreamLoadProfile {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 返回产品名称。
     */
    @Override
    public String productName() {
        return "StarRocks";
    }

    /**
     * 已观察原生配置的精确 FE/BE {@code Version}；其他版本全部拒绝。
     */
    @Override
    public String admittedVersion() {
        return "3.5.21-85fccf8";
    }

    /**
     * 写入COPY能力。
     */
    @Override
    public OperationCapability writeCopyCapability() {
        return new StarrocksProductProvider().products().stream()
                .filter(item -> item.product() == ProductTypeEnum.STARROCKS)
                .map(item -> item.capabilities().get(DatabaseOperation.WRITE_COPY))
                .findFirst().orElse(null);
    }

    /**
     * 说明当前 Stream Load 配置是否支持原生二进制列。
     */
    @Override
    public boolean nativeBinaryColumns() {
        return true;
    }

    /** 二进制列通过临时列中的 Base64 文本传输，并由 {@code to_binary} 解码。 */
    @Override
    public Map<String, String> binaryColumnHeaders(List<String> fields, Set<String> binary) throws Exception {
        if (binary.isEmpty()) {
            return Map.of();
        }
        if (fields.size() > 1024 || new HashSet<>(fields).size() != fields.size() || !fields.containsAll(binary)) {
            throw new IllegalArgumentException("Invalid binary column mapping");
        }
        var used = new HashSet<>(fields.stream().map(f -> f.toLowerCase(Locale.ROOT)).toList());
        if (used.size() != fields.size()) {
            throw new IllegalArgumentException("Case-insensitive Stream Load columns collide");
        }
        var declared = new ArrayList<String>();
        var mapped = new ArrayList<String>();
        var paths = new ArrayList<String>();
        int sequence = 0;
        for (String field : fields) {
            if (field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid Stream Load header identifier");
            }
            String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.STARROCKS, field);
            paths.add("$." + JSON.writeValueAsString(field));
            if (binary.contains(field)) {
                String temporary;
                do {
                    temporary = "__dbswitch_binary_" + sequence++;
                } while (!used.add(temporary));
                declared.add(temporary);
                mapped.add(
                        quoted + "=CASE WHEN " + temporary + " IS NULL THEN NULL WHEN " + temporary + "='' THEN CAST('' AS VARBINARY) ELSE to_binary(" + temporary + ",'encode64') END");
            } else {
                declared.add(quoted);
            }
        }
        declared.addAll(mapped);
        String columns = String.join(",", declared), jsonpaths = JSON.writeValueAsString(paths);
        if (columns.getBytes(StandardCharsets.UTF_8).length + jsonpaths.getBytes(
                StandardCharsets.UTF_8).length > 131072) {
            throw new IllegalArgumentException("Stream Load mapping exceeds the header budget");
        }
        return Map.of("columns", columns, "jsonpaths", jsonpaths, "strict_mode", "true", "max_filter_ratio", "0");
    }

    /**
     * 计划 CREATE 语法：除共享标量外准入的文本或二进制标量类型。
     */
    @Override
    public String targetTextOrBinaryKind() {
        return "varbinary";
    }

    /**
     * 计划 CREATE 语法：除 DUPLICATE 外准入的键表模型。
     */
    @Override
    public String targetKeyedModel() {
        return "PRIMARY";
    }

    /**
     * 计划 CREATE 语法：拒绝超出此产品已验证原生类型范围的列类型。
     */
    @Override
    public void requireTargetNativeType(String type) {
        StarrocksJdbcSupport.nativeSql(type);
    }
}
