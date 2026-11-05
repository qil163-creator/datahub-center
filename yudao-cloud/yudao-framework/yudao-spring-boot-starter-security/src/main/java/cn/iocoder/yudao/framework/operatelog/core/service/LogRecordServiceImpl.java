package cn.iocoder.yudao.framework.operatelog.core.service;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 操作日志 ILogRecordService 实现类
 *
 * 基于 {@link OperateLogCommonApi} 实现，记录操作日志
 *
 * @author HUIHUI
 */
@Slf4j
public class LogRecordServiceImpl implements ILogRecordService {

    private static final String RECORDED_COUNT = LogRecordServiceImpl.class.getName() + ".recordedCount";

    @Resource
    private OperateLogCommonApi operateLogApi;

    @Override
    public void record(LogRecord logRecord) {
        try {
            if ("BUSINESS_OPERATION".equals(logRecord.getExtra())) {
                recordBusinessOperation(logRecord);
                return;
            }
            // 保持既有注解与领域详细日志的写入路径。
            operateLogApi.createOperateLogAsync(createRequest(logRecord));
            HttpServletRequest request = ServletUtils.getRequest();
            if (request != null) {
                request.setAttribute(RECORDED_COUNT, recordedCount() + 1);
            }
        } catch (Throwable ex) {
            // 不输出日志载荷或异常正文，避免业务内容与凭据进入错误日志。
            log.error("[record][操作日志处理失败 type({}) trace({}) failureType({})]",
                    logRecord.getType(), TracerUtils.getTraceId(), ex.getClass().getSimpleName());
        }
    }

    /** 复用既有记录器，处理管理端批量编号、提交时机和已存在的领域详细日志。 */
    private void recordBusinessOperation(LogRecord record) {
        if (recordedCount() > 0) {
            return;
        }
        List<OperateLogCreateReqDTO> requests = new ArrayList<>();
        for (Long id : parseBusinessIds(record.getBizNo())) {
            LogRecord item = LogRecord.builder().type(shorten(record.getType(), 50))
                    .subType(shorten(record.getSubType(), 50)).bizNo(String.valueOf(id))
                    .action(shorten(record.getAction(), 2000))
                    .extra(JsonUtils.toJsonString(Map.of("source", "BUSINESS_OPERATION", "result", "REQUEST_SUCCEEDED",
                            "objectScope", id == 0L ? "MODULE" : "OBJECT"))).build();
            // 在当前请求线程捕获可信操作人和请求信息，回调和异步投递只持有 DTO。
            OperateLogCreateReqDTO request = createRequest(item);
            if (request.getUserId() != null && request.getUserType() != null) {
                requests.add(request);
            }
        }
        Runnable write = () -> {
            if (recordedCount() > 0) {
                return;
            }
            for (OperateLogCreateReqDTO request : requests) {
                try {
                    operateLogApi.createOperateLogAsync(request);
                } catch (RuntimeException failure) {
                    log.error("[record][操作日志投递失败 type({}) bizId({}) trace({}) failureType({})]",
                            request.getType(), request.getBizId(), request.getTraceId(), failure.getClass().getSimpleName());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    /** 原生注解会将集合表达式转换成列表文本；仅接收完整整数编号，不解析业务载荷。 */
    private static List<Long> parseBusinessIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of(0L);
        }
        String text = value.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1).trim();
            if (text.isEmpty()) {
                return List.of();
            }
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String item : text.split(",", -1)) {
            String id = item.trim();
            if ("null".equals(id)) {
                ids.add(0L);
            } else if (id.matches("[0-9]{1,19}")) {
                ids.add(Long.parseLong(id));
            } else {
                throw new IllegalArgumentException("业务编号必须为完整整数编号");
            }
        }
        return List.copyOf(ids);
    }

    private static String shorten(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    /** 捕获调用时的可信身份与请求上下文，供提交后投递复用。 */
    public static OperateLogCreateReqDTO createRequest(LogRecord record) {
        OperateLogCreateReqDTO request = new OperateLogCreateReqDTO();
        request.setTraceId(TracerUtils.getTraceId());
        fillUserFields(request);
        fillModuleFields(request, record);
        fillRequestFields(request);
        return request;
    }

    /** 当前请求内已投递的详细日志数量，用于汇总入口去重。 */
    public static int recordedCount() {
        HttpServletRequest request = ServletUtils.getRequest();
        Object count = request == null ? null : request.getAttribute(RECORDED_COUNT);
        return count instanceof Integer number ? number : 0;
    }

    private static void fillUserFields(OperateLogCreateReqDTO reqDTO) {
        // 使用 SecurityFrameworkUtils。因为要考虑，rpc、mq、job，它其实不是 web；
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null) {
            return;
        }
        reqDTO.setUserId(loginUser.getId());
        reqDTO.setUserType(loginUser.getUserType());
    }

    public static void fillModuleFields(OperateLogCreateReqDTO reqDTO, LogRecord logRecord) {
        reqDTO.setType(logRecord.getType()); // 大模块类型，例如：CRM 客户
        reqDTO.setSubType(logRecord.getSubType());// 操作名称，例如：转移客户
        reqDTO.setBizId(Long.parseLong(logRecord.getBizNo())); // 业务编号，例如：客户编号
        reqDTO.setAction(logRecord.getAction());// 操作内容，例如：修改编号为 1 的用户信息，将性别从男改成女，将姓名从芋道改成源码。
        reqDTO.setExtra(logRecord.getExtra()); // 拓展字段，有些复杂的业务，需要记录一些字段 ( JSON 格式 )，例如说，记录订单编号，{ orderId: "1"}
    }

    private static void fillRequestFields(OperateLogCreateReqDTO reqDTO) {
        // 获得 Request 对象
        HttpServletRequest request = ServletUtils.getRequest();
        if (request == null) {
            return;
        }
        // 补全请求信息
        reqDTO.setRequestMethod(request.getMethod());
        reqDTO.setRequestUrl(request.getRequestURI());
        reqDTO.setUserIp(ServletUtils.getClientIP(request));
        reqDTO.setUserAgent(ServletUtils.getUserAgent(request));
    }

    @Override
    public List<LogRecord> queryLog(String bizNo, String type) {
        throw new UnsupportedOperationException("使用 OperateLogApi 进行操作日志的查询");
    }

    @Override
    public List<LogRecord> queryLogByBizNo(String bizNo, String type, String subType) {
        throw new UnsupportedOperationException("使用 OperateLogApi 进行操作日志的查询");
    }

}
