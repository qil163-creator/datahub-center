package cn.iocoder.yudao.module.asset.service.common;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 审计字段（creator/updater 存放用户编号字符串）→ 用户昵称 的批量解析。
 * 系统服务不可用时降级返回原始编号，不影响主流程。
 */
@Slf4j
@Component
public class AssetUserNameResolver {

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * @param userIdTexts creator/updater 等字符串形式的用户编号
     * @return 用户编号字符串 -> 昵称
     */
    public Map<String, String> resolveByTexts(Collection<String> userIdTexts) {
        Set<Long> ids = userIdTexts == null ? Set.of() : userIdTexts.stream()
                .filter(text -> StrUtil.isNotBlank(text) && text.matches("\\d+"))
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        Map<Long, String> names = resolve(ids);
        Map<String, String> result = new HashMap<>();
        names.forEach((id, name) -> result.put(String.valueOf(id), name));
        return result;
    }

    /**
     * 批量查询用户并建立编号到昵称的映射，服务不可用时返回空映射供编号展示。
     */
    public Map<Long, String> resolve(Collection<Long> userIds) {
        Set<Long> ids = userIds == null ? Set.of() : userIds.stream()
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            List<AdminUserRespDTO> users = adminUserApi.getUserList(ids).getCheckedData();
            return users.stream().collect(Collectors.toMap(AdminUserRespDTO::getId,
                    user -> StrUtil.blankToDefault(user.getNickname(), String.valueOf(user.getId())),
                    (a, b) -> a));
        } catch (RuntimeException ex) {
            log.warn("[resolve][系统用户服务不可用，按编号展示：{}]", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 按标识从名称映射中读取显示名称。
     */
    public String nameOf(Map<String, String> names, String userIdText) {
        if (StrUtil.isBlank(userIdText)) {
            return null;
        }
        return names.getOrDefault(userIdText, userIdText);
    }

}
