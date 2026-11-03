package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;

import java.util.List;

/**
 * 模型字段稳定标识（fieldUid）工具
 *
 * <p>fieldUid 是模型字段的不可变身份：字段改名保留原值，复制/新建字段生成新值。
 * 落标映射、标准比对等跨模块场景以 fieldUid 识别字段，禁止用字段名拼装伪标识。</p>
 *
 * @author Li
 */
public final class ModelFieldUidUtils {

    /**
     * 限制模型字段用户标识工具的实例化，统一通过静态能力使用。
     */
    private ModelFieldUidUtils() {
    }

    /**
     * 为缺失 fieldUid 的字段生成 UUID（持久化前统一调用，幂等）。
     *
     * @return 是否有字段被补充了 fieldUid
     */
    public static boolean ensureFieldUids(List<ModelFieldItem> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (ModelFieldItem field : fields) {
            if (field != null && StrUtil.isBlank(field.getFieldUid())) {
                field.setFieldUid(IdUtil.fastSimpleUUID());
                changed = true;
            }
        }
        return changed;
    }

}
