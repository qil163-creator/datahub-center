package cn.iocoder.yudao.framework.operatelog.core.function;

import cn.hutool.core.util.StrUtil;
import com.mzt.logapi.service.IParseFunction;

import java.util.Map;

/**
 * 把状态码、开关值翻译为中文的 {@link IParseFunction}，操作日志模板写法：{@code {getCommonStatus{#status}}}。
 * <p>
 * 同一个类按不同的函数名注册多份（见 {@code YudaoOperateLogConfiguration}），每份对应一种取值约定；
 * 空值显示「未设置」，无法识别的值原样输出（例如上下文里预置的「未知」），避免误译。
 */
public class CodeLabelParseFunction implements IParseFunction {

    /** 0 启用、1 停用（CommonStatusEnum） */
    public static final String COMMON_STATUS = "getCommonStatus";
    /** 1 启用、0 停用 */
    public static final String ENABLED_STATUS = "getEnabledStatus";
    /** 1 上线、0 下线 */
    public static final String ONLINE_STATUS = "getOnlineStatus";
    /** 0 上线、1 下线 */
    public static final String COMMON_ONLINE_STATUS = "getCommonOnlineStatus";
    /** 1 开启、0 关闭 */
    public static final String SWITCH_STATUS = "getSwitchStatus";
    /** 通用英文状态码（SUCCESS、APPROVED、PROCESSED 等）译为中文 */
    public static final String STATE_LABEL = "getStateLabel";

    private final String functionName;
    private final Map<String, String> labels;

    public CodeLabelParseFunction(String functionName, Map<String, String> labels) {
        this.functionName = functionName;
        this.labels = labels;
    }

    public static CodeLabelParseFunction ofPositive(String functionName, String onLabel, String offLabel) {
        return new CodeLabelParseFunction(functionName,
                Map.of("1", onLabel, "0", offLabel, "true", onLabel, "false", offLabel));
    }

    @Override
    public String functionName() {
        return functionName;
    }

    @Override
    public String apply(Object value) {
        if (StrUtil.isEmptyIfStr(value)) {
            return "未设置";
        }
        String code = StrUtil.trim(String.valueOf(value));
        return labels.getOrDefault(code, code);
    }

}
