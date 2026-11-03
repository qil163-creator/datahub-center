package cn.iocoder.yudao.module.governance.api.identify;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.api.identify.dto.SafetyIdentifyRuleRespDTO;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyIdentifyRuleMapper;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 敏感识别规则只读 RPC
 */
@RestController
@Validated
public class SafetyIdentifyApiImpl implements SafetyIdentifyApi {

    @Resource
    private SafetyIdentifyRuleMapper identifyRuleMapper;

    /**
     * 获得启用中的敏感识别规则。
     */
    @Override
    public CommonResult<List<SafetyIdentifyRuleRespDTO>> getEnabledRules() {
        return success(BeanUtils.toBean(identifyRuleMapper.selectEnabledList(),
                SafetyIdentifyRuleRespDTO.class));
    }

}
