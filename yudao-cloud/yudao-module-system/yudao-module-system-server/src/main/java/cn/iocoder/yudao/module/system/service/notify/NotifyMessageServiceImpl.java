package cn.iocoder.yudao.module.system.service.notify;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.message.NotifyMessageMyPageReqVO;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.message.NotifyMessagePageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.notify.NotifyMessageDO;
import cn.iocoder.yudao.module.system.dal.dataobject.notify.NotifyTemplateDO;
import cn.iocoder.yudao.module.system.dal.mysql.notify.NotifyMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 站内信 Service 实现类
 *
 * @author xrcoder
 */
@Service
@Validated
public class NotifyMessageServiceImpl implements NotifyMessageService {

    @Resource
    private NotifyMessageMapper notifyMessageMapper;

    @Override
    public Long createNotifyMessage(Long userId, Integer userType,
                                    NotifyTemplateDO template, String templateContent, Map<String, Object> templateParams) {
        NotifyMessageDO message = new NotifyMessageDO().setUserId(userId).setUserType(userType)
                .setTemplateId(template.getId()).setTemplateCode(template.getCode())
                .setTemplateType(template.getType()).setTemplateNickname(template.getNickname())
                .setTemplateContent(templateContent).setTemplateParams(templateParams).setReadStatus(false);
        notifyMessageMapper.insert(message);
        return message.getId();
    }

    @Override
    public Long createNotifyMessage(Long userId, Integer userType, NotifyTemplateDO template, String templateContent,
                                    Map<String, Object> templateParams, String idempotencyKey) {
        if (cn.hutool.core.util.StrUtil.isBlank(idempotencyKey)) {
            return createNotifyMessage(userId, userType, template, templateContent, templateParams);
        }
        NotifyMessageDO existing = notifyMessageMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return assertSameRecipient(existing, userId, userType, template);
        }
        NotifyMessageDO message = new NotifyMessageDO().setUserId(userId).setUserType(userType)
                .setTemplateId(template.getId()).setTemplateCode(template.getCode())
                .setTemplateType(template.getType()).setTemplateNickname(template.getNickname())
                .setTemplateContent(templateContent).setTemplateParams(templateParams).setReadStatus(false)
                .setIdempotencyKey(idempotencyKey);
        try {
            notifyMessageMapper.insert(message);
            return message.getId();
        } catch (org.springframework.dao.DuplicateKeyException race) {
            // 并发重复调用由唯一索引裁决，失败方读取先提交者的结果
            NotifyMessageDO winner = notifyMessageMapper.selectByIdempotencyKey(idempotencyKey);
            if (winner == null) {
                throw race;
            }
            return assertSameRecipient(winner, userId, userType, template);
        }
    }

    @Override
    public NotifyMessageDO getNotifyMessageByIdempotencyKey(String idempotencyKey) {
        return cn.hutool.core.util.StrUtil.isBlank(idempotencyKey) ? null
                : notifyMessageMapper.selectByIdempotencyKey(idempotencyKey);
    }

    private static Long assertSameRecipient(NotifyMessageDO existing, Long userId, Integer userType,
                                            NotifyTemplateDO template) {
        if (!java.util.Objects.equals(existing.getUserId(), userId)
                || !java.util.Objects.equals(existing.getUserType(), userType)
                || !java.util.Objects.equals(existing.getTemplateCode(), template.getCode())) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.NOTIFY_SEND_IDEMPOTENCY_CONFLICT);
        }
        return existing.getId();
    }

    @Override
    public PageResult<NotifyMessageDO> getNotifyMessagePage(NotifyMessagePageReqVO pageReqVO) {
        return notifyMessageMapper.selectPage(pageReqVO);
    }

    @Override
    public PageResult<NotifyMessageDO> getMyMyNotifyMessagePage(NotifyMessageMyPageReqVO pageReqVO, Long userId, Integer userType) {
        return notifyMessageMapper.selectPage(pageReqVO, userId, userType);
    }

    @Override
    public NotifyMessageDO getNotifyMessage(Long id) {
        return notifyMessageMapper.selectById(id);
    }

    @Override
    public List<NotifyMessageDO> getUnreadNotifyMessageList(Long userId, Integer userType, Integer size) {
        return notifyMessageMapper.selectUnreadListByUserIdAndUserType(userId, userType, size);
    }

    @Override
    public Long getUnreadNotifyMessageCount(Long userId, Integer userType) {
        return notifyMessageMapper.selectUnreadCountByUserIdAndUserType(userId, userType);
    }

    @Override
    public int updateNotifyMessageRead(Collection<Long> ids, Long userId, Integer userType) {
        return notifyMessageMapper.updateListRead(ids, userId, userType);
    }

    @Override
    public int updateAllNotifyMessageRead(Long userId, Integer userType) {
        return notifyMessageMapper.updateListRead(userId, userType);
    }

}
