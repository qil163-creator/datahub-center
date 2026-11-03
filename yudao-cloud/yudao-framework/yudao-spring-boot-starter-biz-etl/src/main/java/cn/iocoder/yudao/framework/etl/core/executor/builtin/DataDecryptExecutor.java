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
 * 数据解密节点（toolCode: data-decrypt）
 *
 * 字段级解密配置 decryptFields:
 * [{fieldName, algorithm AES128|AES256|DES|3DES|BASE64, secretKey,
 *   outputFieldName（空覆盖原字段）, keepOriginal}]；哈希算法不可逆不支持解密
 *
 * @author Li
 */
public class DataDecryptExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "data-decrypt";

    /**
     * 初始化 {@code DataDecryptExecutor}及其父类状态。
     */
    public DataDecryptExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        JSONArray fields = config().getJSONArray("decryptFields");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(String.format("节点【%s】未配置解密字段", node.getName()));
        }
        for (Object item : fields) {
            String algorithm = ((JSONObject) item).getStr("algorithm", "AES256");
            if (!EtlCryptoUtils.isReversible(algorithm)) {
                throw new IllegalStateException(
                        String.format("算法【%s】不可逆，无法解密", algorithm));
            }
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
                String decrypted = value == null ? null
                        : EtlCryptoUtils.decrypt(field.getStr("algorithm", "AES256"),
                        field.getStr("secretKey"), String.valueOf(value));
                String outputField = StrUtil.blankToDefault(field.getStr("outputFieldName"), fieldName);
                if (!field.getBool("keepOriginal", false) && !outputField.equals(fieldName)) {
                    result.remove(fieldName);
                }
                result.put(outputField, decrypted);
            }
            pushRow(result);
        }
        logLine(String.format("解密完成，共 %s 行", writeCount.get()));
    }

}
