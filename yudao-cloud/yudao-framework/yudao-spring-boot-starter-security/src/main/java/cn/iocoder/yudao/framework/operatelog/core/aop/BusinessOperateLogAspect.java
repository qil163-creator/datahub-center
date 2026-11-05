package cn.iocoder.yudao.framework.operatelog.core.aop;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.operatelog.core.service.LogRecordServiceImpl;
import com.mzt.logapi.beans.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 在接口成功且事务提交后，复用系统日志 RPC 登记操作摘要；领域日志优先，避免重复。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
@Slf4j
public class BusinessOperateLogAspect {

    private static final ExpressionParser EXPRESSIONS = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer PARAMETERS = new DefaultParameterNameDiscoverer();

    private final OperateLogCommonApi operateLogApi;

    @Around("@annotation(operation)")
    public Object around(ProceedingJoinPoint point, BusinessOperateLog operation) throws Throwable {
        int recordedBefore = LogRecordServiceImpl.recordedCount();
        Object response = point.proceed();
        if (response instanceof CommonResult<?> result
                && (!result.isSuccess() || (!operation.recordOnFalse() && Boolean.FALSE.equals(result.getData())))) {
            return response;
        }
        if (LogRecordServiceImpl.recordedCount() > recordedBefore) {
            return response;
        }
        Method method = AopUtils.getMostSpecificMethod(((MethodSignature) point.getSignature()).getMethod(),
                point.getTarget().getClass());
        try {
            Object result = response instanceof CommonResult<?> common ? common.getData() : response;
            List<Long> ids = resolveIds(operation.bizId(), method, point.getArgs(), result);
            Operation api = method.getAnnotation(Operation.class);
            String action = operation.action().isBlank()
                    ? api == null ? method.getName() : api.summary() : operation.action();
            // 先捕获可信身份、请求和链路上下文，提交回调不再读取可变的请求数据。
            List<OperateLogCreateReqDTO> records = new ArrayList<>();
            for (Long id : ids) {
                LogRecord record = LogRecord.builder().type(shorten(operation.type(), 50))
                        .subType(shorten(action, 50)).bizNo(String.valueOf(id))
                        .action(shorten("操作请求成功：" + action, 2000))
                        .extra(JsonUtils.toJsonString(Map.of("result", "REQUEST_SUCCEEDED",
                                "source", "BUSINESS_OPERATION", "objectScope", id == 0L ? "MODULE" : "OBJECT")))
                        .build();
                OperateLogCreateReqDTO request = LogRecordServiceImpl.createRequest(record);
                // 认证失败、匿名开放入口不制造无操作人的系统成功记录。
                if (request.getUserId() != null && request.getUserType() != null) {
                    records.add(request);
                }
            }
            Runnable write = () -> records.forEach(this::write);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 兼容领域日志也在同一外层事务提交时派发的调用方式。
                        if (LogRecordServiceImpl.recordedCount() == recordedBefore) {
                            write.run();
                        }
                    }
                });
            } else {
                write.run();
            }
        } catch (RuntimeException failure) {
            // 不输出参数、表达式计算结果或异常正文，避免配置与凭据进入服务日志。
            log.error("[businessOperateLog][操作({}.{}) 日志构造失败，异常类型({})]",
                    method.getDeclaringClass().getSimpleName(), method.getName(), failure.getClass().getSimpleName());
        }
        return response;
    }

    private void write(OperateLogCreateReqDTO request) {
        try {
            operateLogApi.createOperateLog(request).checkError();
        } catch (RuntimeException failure) {
            // 业务已经提交，审计投递失败不能诱导客户端重复提交业务写入。
            log.error("[businessOperateLog][操作日志投递失败 type({}) bizId({}) trace({}) failureType({})]",
                    request.getType(), request.getBizId(), request.getTraceId(), failure.getClass().getSimpleName());
        }
    }

    static List<Long> resolveIds(String expression, Method method, Object[] args, Object result) {
        if (expression.isBlank()) {
            return List.of(0L);
        }
        // 表达式只来自源码注解，仍限制为只读属性访问，不允许执行任意类型/构造器/方法。
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("result", result);
        String[] names = PARAMETERS.getParameterNames(method);
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
            if (names != null) {
                context.setVariable(names[i], args[i]);
            }
        }
        Object value = EXPRESSIONS.parseExpression(expression).getValue(context);
        if (value == null) {
            return List.of(0L);
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                ids.add(toId(item));
            }
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                ids.add(toId(Array.get(value, i)));
            }
        } else {
            ids.add(toId(value));
        }
        return List.copyOf(ids);
    }

    private static Long toId(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Long id) {
            return id;
        }
        if (value instanceof Integer id) {
            return id.longValue();
        }
        if (value instanceof String id && id.matches("[0-9]{1,19}")) {
            return Long.parseLong(id);
        }
        throw new IllegalArgumentException("业务编号必须为完整整数编号");
    }

    private static String shorten(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
