package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlCryptoUtils;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据加密节点（toolCode: data-encrypt）
 *
 * 字段级加密配置 encryptFields:
 * [{fieldName, algorithm AES128|AES256|DES|3DES|MD5|SHA1|SHA256|SHA512|BASE64,
 *   secretKey, outputFieldName（空覆盖原字段）, keepOriginal}]
 *
 * @author Li
 */
public class DataEncryptExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "data-encrypt";

    /**
     * 初始化 {@code DataEncryptExecutor}及其父类状态。
     */
    public DataEncryptExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        JSONArray fields = config().getJSONArray("encryptFields");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(String.format("节点【%s】未配置加密字段", node.getName()));
        }
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            for (Object item : fields) {
                JSONObject field = (JSONObject) item;
                String fieldName = field.getStr("fieldName");
                if (StrUtil.isBlank(fieldName) || !result.containsKey(fieldName)) {
                    continue;
                }
                Object value = result.get(fieldName);
                String encrypted = value == null ? null
                        : EtlCryptoUtils.encrypt(field.getStr("algorithm", "AES256"),
                        field.getStr("secretKey"), String.valueOf(value));
                String outputField = StrUtil.blankToDefault(field.getStr("outputFieldName"), fieldName);
                if (!field.getBool("keepOriginal", false) && !outputField.equals(fieldName)) {
                    result.remove(fieldName);
                }
                result.put(outputField, encrypted);
            }
            pushRow(result);
        }
        logLine(String.format("加密完成，共 %s 行", writeCount.get()));
    }

}
