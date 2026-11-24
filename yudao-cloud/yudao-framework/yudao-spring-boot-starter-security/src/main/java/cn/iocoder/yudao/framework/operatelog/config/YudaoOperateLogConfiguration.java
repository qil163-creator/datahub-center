package cn.iocoder.yudao.framework.operatelog.config;

import cn.iocoder.yudao.framework.operatelog.core.function.CodeLabelParseFunction;
import cn.iocoder.yudao.framework.operatelog.core.service.LogRecordServiceImpl;
import com.mzt.logapi.service.ILogRecordService;
import com.mzt.logapi.starter.annotation.EnableLogRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;

/**
 * 操作日志配置类
 *
 * @author HUIHUI
 */
@EnableLogRecord(tenant = "") // 貌似用不上 tenant 这玩意给个空好啦
@AutoConfiguration
@Slf4j
public class YudaoOperateLogConfiguration {

    @Bean
    @Primary
    public ILogRecordService iLogRecordServiceImpl() {
        return new LogRecordServiceImpl();
    }

    // ========== 状态码翻译，各业务模块的操作日志模板均可使用，例如 {getCommonStatus{#status}} ==========

    @Bean
    public CodeLabelParseFunction commonStatusParseFunction() {
        return new CodeLabelParseFunction(CodeLabelParseFunction.COMMON_STATUS, Map.of("0", "启用", "1", "停用"));
    }

    @Bean
    public CodeLabelParseFunction enabledStatusParseFunction() {
        return CodeLabelParseFunction.ofPositive(CodeLabelParseFunction.ENABLED_STATUS, "启用", "停用");
    }

    @Bean
    public CodeLabelParseFunction onlineStatusParseFunction() {
        return CodeLabelParseFunction.ofPositive(CodeLabelParseFunction.ONLINE_STATUS, "上线", "下线");
    }

    @Bean
    public CodeLabelParseFunction commonOnlineStatusParseFunction() {
        return new CodeLabelParseFunction(CodeLabelParseFunction.COMMON_ONLINE_STATUS, Map.of("0", "上线", "1", "下线"));
    }

    @Bean
    public CodeLabelParseFunction switchStatusParseFunction() {
        return CodeLabelParseFunction.ofPositive(CodeLabelParseFunction.SWITCH_STATUS, "开启", "关闭");
    }

    @Bean
    public CodeLabelParseFunction stateLabelParseFunction() {
        return new CodeLabelParseFunction(CodeLabelParseFunction.STATE_LABEL, Map.ofEntries(
                // 执行 / 运行
                Map.entry("PENDING", "待处理"), Map.entry("WAITING", "等待中"), Map.entry("WAIT_START", "待发起"),
                Map.entry("SUBMITTING", "提交中"), Map.entry("SUBMITTED", "已提交"), Map.entry("RUNNING", "运行中"),
                Map.entry("SUCCESS", "成功"), Map.entry("FAILED", "失败"), Map.entry("STOPPED", "已停止"),
                Map.entry("CANCEL_REQUESTED", "已请求取消"), Map.entry("CANCELLED", "已取消"), Map.entry("UNKNOWN", "结果未知"),
                Map.entry("NEVER", "未运行"), Map.entry("RETRY", "待重试"), Map.entry("RETRYING", "重试中"),
                Map.entry("EXHAUSTED", "重试已耗尽"), Map.entry("MANUAL", "待人工处理"), Map.entry("RESOLVED", "已解决"),
                // 审批 / 生效
                Map.entry("DRAFT", "草稿"), Map.entry("APPROVED", "已通过"), Map.entry("REJECTED", "已驳回"),
                Map.entry("APPLYING", "生效中"), Map.entry("APPLIED", "已生效"), Map.entry("NO_EFFECT", "未生效"),
                Map.entry("COMPENSATED", "已完整补偿"), Map.entry("PASSED", "通过"), Map.entry("UNVERIFIED", "未验证"),
                Map.entry("CONFIRMED_SUCCEEDED", "确认已成功"), Map.entry("CONFIRMED_NOT_APPLIED", "确认未生效"),
                Map.entry("DELIVERED", "已送达"), Map.entry("NOT_DELIVERED", "未送达"),
                Map.entry("KEEP_PENDING", "保持待处理"), Map.entry("REAPPLY_REQUIRED", "需要重新执行"),
                // 处理结果
                Map.entry("OPEN", "待处理"), Map.entry("PROCESSED", "已处理"), Map.entry("UNPROCESSED", "未处理"),
                Map.entry("CONFIRMED", "确认风险"), Map.entry("IGNORED", "已忽略"), Map.entry("MERGING", "合并审批中"),
                Map.entry("MERGED", "已合并"), Map.entry("STALE", "已失效"),
                // 上下线 / 启停
                Map.entry("ACTIVE", "启用"), Map.entry("ONLINE", "上线"), Map.entry("OFFLINE", "下线"),
                Map.entry("PUBLISHED", "已发布"), Map.entry("DEPRECATED", "已废止")));
    }

}
