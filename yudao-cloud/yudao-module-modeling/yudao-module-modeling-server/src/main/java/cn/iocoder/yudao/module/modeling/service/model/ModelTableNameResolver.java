package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 物理表名规范解析器
 *
 * 表名拆成两段：数仓分层的 table_prefix + 用户填写的表英文名，例如 dwd_ + accept_event。
 * 当前用于逻辑模型物化发布及分层前缀预览；物化记录保存最终表名（tableName），
 * 目标数据库 DDL 按该名称定位表，用户填写的英文名称不必重复携带分层前缀。
 *
 * 逆向接入的表不走拼接：实库里叫什么就是什么，英文名反过来由完整表名剥前缀得到。
 *
 * @author Li
 */
@Component
public class ModelTableNameResolver {

    /**
     * 表英文名格式：小写字母开头，只允许小写字母、数字、下划线
     */
    private static final Pattern ENGLISH_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * 完整表名长度上限；沿用既有命名契约，不依赖已退役的物理模型表。
     */
    private static final int FULL_NAME_MAX_LENGTH = 128;

    /**
     * 校验表英文名格式并归一化
     *
     * 大小写与首尾空格对未加引号的 SQL 标识符没有意义，直接归一化；
     * 中划线、点号等真正非法的字符则报错，不做静默替换。
     *
     * @param englishName 表英文名
     * @return 归一化后的表英文名
     */
    public String validateEnglishName(String englishName) {
        if (StrUtil.isBlank(englishName)) {
            throw exception(MODEL_TABLE_ENGLISH_NAME_REQUIRED);
        }
        String name = englishName.trim().toLowerCase(Locale.ROOT);
        if (!ENGLISH_NAME_PATTERN.matcher(name).matches()) {
            throw exception(MODEL_TABLE_ENGLISH_NAME_INVALID);
        }
        return name;
    }

    /**
     * 拼接完整表名：分层前缀 + 表英文名
     *
     * 英文名已经带上前缀时不重复拼接，避免出现 dwd_dwd_accept_event。
     *
     * @param layer       所属分层，可空（未归属分层时表名就是英文名本身）
     * @param englishName 表英文名
     * @return 完整表名
     */
    public String buildFullTableName(DwLayerDO layer, String englishName) {
        String name = validateEnglishName(englishName);
        String prefix = resolvePrefix(layer);
        String fullName = StrUtil.isBlank(prefix) || name.startsWith(prefix) ? name : prefix + name;
        if (fullName.length() > FULL_NAME_MAX_LENGTH) {
            throw exception(MODEL_TABLE_NAME_TOO_LONG, FULL_NAME_MAX_LENGTH);
        }
        return fullName;
    }

    /**
     * 从完整表名反推表英文名，用于逆向接入与存量数据
     *
     * @param layer         所属分层，可空
     * @param fullTableName 完整表名
     * @return 表英文名，完整表名为空时返回 null
     */
    public String resolveEnglishName(DwLayerDO layer, String fullTableName) {
        if (StrUtil.isBlank(fullTableName)) {
            return null;
        }
        String name = fullTableName.trim().toLowerCase(Locale.ROOT);
        String prefix = resolvePrefix(layer);
        if (StrUtil.isNotBlank(prefix) && name.startsWith(prefix) && name.length() > prefix.length()) {
            return name.substring(prefix.length());
        }
        return name;
    }

    /**
     * 解析前缀。
     *
     * @param layer 分层
     * @return 解析前缀文本
     */
    private String resolvePrefix(DwLayerDO layer) {
        if (layer == null || StrUtil.isBlank(layer.getTablePrefix())) {
            return null;
        }
        return layer.getTablePrefix().trim().toLowerCase(Locale.ROOT);
    }

}
