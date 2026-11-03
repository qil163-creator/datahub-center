package cn.iocoder.yudao.module.dataforge.service;

import cn.hutool.core.util.StrUtil;

import java.util.function.Predicate;

/**
 * 复制任务/配置时生成不重名的「原名_副本」名称。
 */
public final class CopyNameSupport {

    /**
     * 限制复制名称支持工具的实例化，统一通过静态能力使用。
     */
    private CopyNameSupport() {
    }

    /**
     * 获取复制名称支持的下次。
     *
     * @param sourceName 来源名称
     * @param maxBaseLen 最大值基础长度
     * @param exists 存在性
     * @return 复制名称支持的下次文本
     */
    public static String next(String sourceName, int maxBaseLen, Predicate<String> exists) {
        String base = StrUtil.maxLength(StrUtil.nullToEmpty(sourceName), maxBaseLen) + "_副本";
        String name = base;
        for (int i = 2; exists.test(name) && i < 100; i++) {
            name = base + i;
        }
        return name;
    }

}
