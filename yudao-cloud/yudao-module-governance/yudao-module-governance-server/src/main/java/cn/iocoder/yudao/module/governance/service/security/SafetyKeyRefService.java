package cn.iocoder.yudao.module.governance.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyCenterStatusRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyRefCreateReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyRefRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyRefStatusReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyKeyRefDO;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyKeyRefMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.KEY_CENTER_NOT_CONFIGURED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.KEY_REF_CODE_DUPLICATE;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.KEY_REF_NOT_EXISTS;

/**
 * 密钥引用管理：只管理 KEY_CENTER 引用的登记与启停，不存储、不返回任何密钥材料。
 * <p>
 * 数据范围：平台级安全配置，按权限码控制，平台内全可见。引用编码与派生盐创建后不可修改（改了就解不开历史密文），
 * 也不提供删除；不再使用时停用即可。
 */
@Service
public class SafetyKeyRefService {

    private final SecureRandom random = new SecureRandom();

    @Resource
    private SafetyKeyRefMapper keyRefMapper;
    @Resource
    private SafetyKeyCenterService keyCenter;
    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper ruleMapper;
    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.rulever.SafetyRuleVerMapper ruleVerMapper;

    /**
     * 全部引用。
     */
    public List<SafetyKeyRefRespVO> list() {
        return BeanUtils.toBean(keyRefMapper.selectAllOrdered(), SafetyKeyRefRespVO.class);
    }

    /**
     * 密钥中心状态。
     */
    public SafetyKeyCenterStatusRespVO status() {
        SafetyKeyCenterStatusRespVO vo = new SafetyKeyCenterStatusRespVO();
        vo.setAvailable(keyCenter.available());
        vo.setActiveVersion(keyCenter.activeVersion());
        vo.setInlineSecretRuleCount(countInlineSecretRules());
        return vo;
    }

    /**
     * 当前版本仍在规则配置里内联密钥（secretKey/privateKey）的加密规则数量。只读取键是否存在，不读取取值。
     */
    long countInlineSecretRules() {
        List<Long> currentVersions = ruleMapper.selectList().stream()
                .filter(rule -> "ENCRYPT".equals(rule.getMethodCode()) && rule.getCurrentVerId() != null)
                .map(cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO::getCurrentVerId).toList();
        if (currentVersions.isEmpty()) {
            return 0;
        }
        return ruleVerMapper.selectByIds(currentVersions).stream()
                .filter(version -> hasInlineSecret(version.getConfigJson())).count();
    }

    /**
     * 配置是否含非空的内联密钥。
     */
    static boolean hasInlineSecret(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return false;
        }
        try {
            var config = cn.hutool.json.JSONUtil.parseObj(configJson);
            return StrUtil.isNotBlank(config.getStr("secretKey")) || StrUtil.isNotBlank(config.getStr("privateKey"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * 新建 KEY_CENTER 引用；主密钥未配置时拒绝，避免登记出无法使用的引用。
     */
    public Long create(SafetyKeyRefCreateReqVO req) {
        if (!keyCenter.available()) {
            throw exception(KEY_CENTER_NOT_CONFIGURED);
        }
        if (keyRefMapper.selectByCode(req.getKeyCode()) != null) {
            throw exception(KEY_REF_CODE_DUPLICATE, req.getKeyCode());
        }
        byte[] salt = new byte[32];
        random.nextBytes(salt);
        SafetyKeyRefDO ref = new SafetyKeyRefDO();
        ref.setKeyCode(req.getKeyCode());
        ref.setKeyName(req.getKeyName().trim());
        ref.setProvider(SafetyKeyRefDO.PROVIDER_KEY_CENTER);
        ref.setKeyType(SafetyKeyRefDO.KEY_TYPE_SM4);
        ref.setExternalKeyId("kc:" + HexFormat.of().formatHex(salt));
        ref.setKeyVersion(keyCenter.activeVersion());
        ref.setStatus(SafetyKeyRefDO.STATUS_ENABLED);
        ref.setRemark(StrUtil.trimToNull(req.getRemark()));
        try {
            keyRefMapper.insert(ref);
        } catch (DuplicateKeyException concurrent) {
            throw exception(KEY_REF_CODE_DUPLICATE, req.getKeyCode());
        }
        keyCenter.evict(ref.getKeyCode());
        return ref.getId();
    }

    /**
     * 启停引用；停用后最迟 30 秒内引用它的加密规则失败关闭。
     */
    public void updateStatus(SafetyKeyRefStatusReqVO req) {
        SafetyKeyRefDO ref = keyRefMapper.selectById(req.getId());
        if (ref == null) {
            throw exception(KEY_REF_NOT_EXISTS, String.valueOf(req.getId()));
        }
        SafetyKeyRefDO update = new SafetyKeyRefDO();
        update.setId(ref.getId());
        update.setStatus(req.getStatus());
        keyRefMapper.updateById(update);
        keyCenter.evict(ref.getKeyCode());
    }
}
