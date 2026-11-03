package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.*;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyQuotaMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.*;

/**
 * 所有入口共享的读消费账本。权限由各入口在读取前和返回前校验；本服务只接受可信来源快照。
 * 计量先预占后确认，RPC/外部查询不进入事务。过期读工作者由凭证及状态挡住，不能再披露结果。
 */
@Service
public class SupplyQuotaService {

    public static final String KEY_PARAM = "_consumeKey";

    @Resource
    private SupplyQuotaMapper mapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 以消费业务键认领请求，按稳定来源锁序检查并预留额度，重复请求不得重新取数。
     *
     * @param request 请求参数
     * @return 回执
     */
    public Receipt reserve(Reserve request) {
        validate(request);

        return tx().execute(ignored -> {
            String token = UUID.randomUUID().toString();
            if (mapper.claim(request.consumerKey(), request.requestKey(), request.actorId(), request.requestHash(),
                    token, request.rowLimit()) != 1) {
                // 重复请求不重新读取业务数据；成功回执也不能变成无限次免费重查。
                throw exception(QUOTA_REQUEST_CONFLICT);
            }
            List<Source> sources = request.sources().stream()
                    .sorted(Comparator.comparing(Source::sourceSystem).thenComparing(Source::sourceKey)).toList();
            Map<Source, SupplyQuotaMapper.Balance> balances = new LinkedHashMap<>();
            for (Source source : sources) {
                if (source.quota() == null) {
                    continue;
                }
                mapper.ensure(source.sourceSystem(), source.sourceKey(), source.quota().metric(),
                        source.quota().limit());
                var balance = mapper.lockBalance(source.sourceSystem(), source.sourceKey());
                if (!Objects.equals(balance.getMetric(),
                        source.quota().metric()) || balance.getQuotaLimit() != source.quota().limit()) {
                    throw exception(QUOTA_POLICY_CHANGED);
                }
                balances.put(source, balance);
            }
            Set<String> uncovered = new TreeSet<>(request.columns());
            sources.stream().filter(source -> source.quota() == null)
                    .forEach(source -> uncovered.removeAll(source.columns()));
            List<Allocation> allocations = new ArrayList<>();
            int rowLimit = request.rowLimit();
            for (var entry : balances.entrySet()) {
                Source source = entry.getKey();
                if (Collections.disjoint(uncovered, source.columns())) {
                    continue;
                }
                var balance = entry.getValue();
                long available = balance.getQuotaLimit() - balance.getUsedUnits() - balance.getReservedUnits();
                if (available <= 0) {
                    continue; // 可以由另一个真实有效来源提供剩余字段。
                }
                long units = "CALLS".equals(source.quota().metric()) ? 1 : Math.min(available, rowLimit);
                if ("ROWS".equals(source.quota().metric())) {
                    rowLimit = (int) units;
                }
                allocations.add(
                        new Allocation(source.sourceSystem(), source.sourceKey(), source.quota().metric(), units));
                mapper.change(source.sourceSystem(), source.sourceKey(), 0, units);
                uncovered.removeAll(source.columns());
            }
            if (!uncovered.isEmpty()) {
                throw exception(QUOTA_EXHAUSTED);
            }
            mapper.allocate(request.consumerKey(), request.requestKey(), rowLimit, JsonUtils.toJsonString(allocations));
            return new Receipt(request.consumerKey(), request.requestKey(), "RESERVED", token, rowLimit, null,
                    List.copyOf(allocations));
        });
    }

    /**
     * 锁定消费回执并校验认领令牌，按实际返回行数幂等结算或取消预留。
     *
     * @param request 请求参数
     * @return 回执
     */
    public Receipt settle(Settle request) {
        if (request == null || request.token() == null) {
            throw exception(QUOTA_REQUEST_INVALID);
        }

        Receipt result = tx().execute(ignored -> {
            var row = mapper.lockReceipt(request.consumerKey(), request.requestKey());
            if (row == null || !Objects.equals(row.getToken(), request.token())) {
                throw exception(QUOTA_REQUEST_INVALID);
            }
            if (!"RESERVED".equals(row.getState())) {
                if ("COMMITTED".equals(row.getState()) && !request.cancel() && !Objects.equals(row.getActualRows(),
                        request.actualRows())) {
                    throw exception(QUOTA_REQUEST_CONFLICT);
                }
                return receipt(row, true);
            }
            if (!request.cancel() && (request.actualRows() == null || request.actualRows() < 0 || request.actualRows() > row.getRowLimit())) {
                throw exception(QUOTA_REQUEST_INVALID);
            }
            return finish(row, row.isExpired() ? "EXPIRED" : request.cancel() ? "CANCELLED" : "COMMITTED",
                    request.actualRows());
        });
        // 必须在释放预占的事务成功后拒绝已过期的读结果。
        if (!request.cancel() && !"COMMITTED".equals(result.state())) {
            throw exception(QUOTA_RECEIPT_EXPIRED);
        }
        return result;
    }

    /**
     * 读取或构造消费回执，面向外部查询时隐藏内部认领令牌。
     *
     * @param consumer 消费结果的回调
     * @param key 标识键
     * @param actor 操作人编号
     * @return 回执
     */
    public Receipt receipt(String consumer, String key, long actor) {
        var row = mapper.receipt(consumer, key, actor);
        if (row == null) {
            throw exception(QUOTA_REQUEST_INVALID);
        }
        return receipt(row, false);
    }

    /**
     * 校验消费主体后读取其历史消费回执。
     *
     * @param actor 操作人编号
     * @return 校验消费主体后读取其历史消费回执结果集合
     */
    public List<Receipt> history(long actor) {
        if (actor <= 0) {
            throw exception(QUOTA_REQUEST_INVALID);
        }
        return mapper.history(actor).stream().map(row -> receipt(row, false)).toList();
    }

    /**
     * 在独立事务内将过期预留转为失效，并归还相应保留额度。
     *
     * @return 符合条件或受影响的记录数量
     */
    public int expire() {
        return tx().execute(ignored -> {
            var rows = mapper.expired();
            for (var row : rows) {
                finish(row, "EXPIRED", null);
            }
            return rows.size();
        });
    }

    /**
     * 校验消费主体后读取其可用额度余额。
     *
     * @param actor 操作人编号
     * @return 校验消费主体后读取其可用额度余额结果集合
     */
    public List<Balance> balances(long actor) {
        if (actor <= 0) {
            throw exception(QUOTA_REQUEST_INVALID);
        }
        return mapper.balances(actor);
    }

    /**
     * 按稳定来源锁序释放预留并记入实际消耗，再保存最终消费状态。
     *
     * @param row 当前行数据
     * @param state 状态
     * @param actualRows 实际行
     * @return 回执
     */
    private Receipt finish(SupplyQuotaMapper.Row row, String state, Integer actualRows) {
        var allocations = allocations(row);
        for (Allocation allocation : allocations.stream()
                .sorted(Comparator.comparing(Allocation::sourceSystem).thenComparing(Allocation::sourceKey)).toList()) {
            mapper.lockBalance(allocation.sourceSystem(), allocation.sourceKey());
            long used = "COMMITTED".equals(state) ? "CALLS".equals(allocation.metric()) ? 1 : actualRows : 0;
            mapper.change(allocation.sourceSystem(), allocation.sourceKey(), used, -allocation.reserved());
        }
        Integer count = "COMMITTED".equals(state) ? actualRows : null;
        if (mapper.finish(row.getConsumerKey(), row.getRequestKey(), state, count) != 1) {
            throw exception(QUOTA_REQUEST_CONFLICT);
        }
        row.setState(state);
        row.setActualRows(count);
        return receipt(row, true);
    }

    /**
     * 解析消费回执中持久化的各来源额度分配。
     *
     * @param row 当前行数据
     * @return 解析消费回执中持久化的各来源额度分配结果集合
     */
    private static List<Allocation> allocations(SupplyQuotaMapper.Row row) {
        return JsonUtils.parseArray(row.getAllocations(), Allocation.class);
    }

    /**
     * 读取或构造消费回执，面向外部查询时隐藏内部认领令牌。
     *
     * @param row 当前行数据
     * @param internal 内部
     * @return 回执
     */
    private static Receipt receipt(SupplyQuotaMapper.Row row, boolean internal) {
        return new Receipt(row.getConsumerKey(), row.getRequestKey(), row.getState(), internal ? row.getToken() : null,
                row.getRowLimit(), row.getActualRows(), allocations(row));
    }

    /**
     * 校验消费键、主体、摘要、行数、批准列及额度来源的格式和数量边界。
     *
     * @param r 当前记录
     */
    private static void validate(Reserve r) {
        if (r == null || r.consumerKey() == null || !r.consumerKey().matches("[A-Za-z0-9:_.-]{1,160}")
                || r.requestKey() == null || !r.requestKey().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}")
                || r.actorId() <= 0 || r.requestHash() == null || !r.requestHash().matches("[a-f0-9]{64}")
                || r.rowLimit() < 1 || r.rowLimit() > 100000 || r.columns().isEmpty() || r.columns().size() > 2048
                || r.sources().isEmpty() || r.sources().size() > 256) {
            throw exception(QUOTA_REQUEST_INVALID);
        }
        Set<List<String>> keys = new HashSet<>();
        for (Source s : r.sources()) {
            if (s.sourceSystem() == null || s.sourceSystem().isBlank() || s.sourceSystem().length() > 64
                    || s.sourceKey() == null || s.sourceKey().isBlank() || s.sourceKey().length() > 256
                    || s.columns().isEmpty() || s.columns().size() > 2048 || !keys.add(
                    List.of(s.sourceSystem(), s.sourceKey()))) {
                throw exception(QUOTA_REQUEST_INVALID);
            }
        }
    }

    /**
     * 创建带十秒超时的独立额度事务模板。
     *
     * @return 事务模板
     */
    private TransactionTemplate tx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return tx;
    }
}
