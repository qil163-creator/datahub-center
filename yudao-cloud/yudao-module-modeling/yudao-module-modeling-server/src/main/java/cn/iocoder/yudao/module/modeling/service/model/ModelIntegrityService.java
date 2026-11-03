package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelIntegrityCheckRespVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Locale;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_KIND_INVALID;

/** 平台内全可见性检查接口；仅逻辑模型。 */
@Service
public class ModelIntegrityService {

    @Resource
    private ModelHierarchyResolver resolver;

    @Resource
    private LogicModelService logics;

    /**
     * 只读完整性检测（不定主域、不改模型）。
     *
     * @param kind 种类
     * @param id 记录编号
     * @return 模型完整性检查响应数据
     */
    public ModelIntegrityCheckRespVO check(String kind, Long id) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "LOGIC" -> {
                var m = logics.getModel(id);
                yield resolver.buildIntegrityCheck(m.getLayerId(), m.getSubjectDomainId(),
                        m.getDataDomainId(), m.getTableType(), m.getBizCategoryId());
            }
            default -> throw exception(MODEL_KIND_INVALID);
        };
    }
}
