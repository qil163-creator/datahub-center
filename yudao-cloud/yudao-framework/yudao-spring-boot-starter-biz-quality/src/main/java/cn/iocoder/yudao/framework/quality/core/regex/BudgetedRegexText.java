package cn.iocoder.yudao.framework.quality.core.regex;

import java.util.Objects;
import java.util.function.Supplier;

/** 正则求值共用的同步字符访问预算，不使用遗留工作线程或超时线程池。 */
public final class BudgetedRegexText implements CharSequence {

    private final String value;

    private final Supplier<? extends RuntimeException> exhausted;

    private int remaining;

    /**
     * 校验构造参数并初始化 {@code BudgetedRegexText}。
     */
    public BudgetedRegexText(String value, int maxSteps, Supplier<? extends RuntimeException> exhausted) {
        this.value = Objects.requireNonNull(value);
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("正则访问预算必须大于零");
        }
        this.remaining = maxSteps;
        this.exhausted = Objects.requireNonNull(exhausted);
    }

    /** 同时覆盖空匹配、替换分组等不一定读取字符的循环。 */
    public void checkWork() {
        if (--remaining < 0 || Thread.currentThread().isInterrupted()) {
            throw exhausted.get();
        }
    }

    /**
     * 返回长度。
     */
    @Override
    public int length() {
        if (Thread.currentThread().isInterrupted()) {
            throw exhausted.get();
        }
        return value.length();
    }

    /**
     * 检查正则计算预算后读取指定位置的字符。
     */
    @Override
    public char charAt(int index) {
        checkWork();
        return value.charAt(index);
    }

    /**
     * 检查正则计算预算后取得指定文本子串。
     */
    @Override
    public CharSequence subSequence(int start, int end) {
        checkWork();
        return value.substring(start, end);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        if (Thread.currentThread().isInterrupted()) {
            throw exhausted.get();
        }
        return value;
    }
}
