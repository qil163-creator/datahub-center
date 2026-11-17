package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.dataapi.PublishedWriteClaimApi.Contract;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiOperationDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.LongFunction;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_NOT_AUTHORIZED;

/** 仅在台账首次认领成功后签发；兑换原子删除，网络超时不得重发。 */
@Service
@RequiredArgsConstructor
public class PublishedWriteClaims {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final DataApiOperationMapper operations;

    String issue(long appId, DataApiDO api, String operationKey, LongFunction<Contract> build) {
        var row = operations.find(appId, api.getId(), operationKey);
        requireClaim(row, appId, api.getId(), api.getActiveReleaseId());
        String token = HexFormat.of().formatHex(randomBytes());
        boolean stored = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(token),
                JsonUtils.toJsonString(build.apply(row.getId())), Duration.ofSeconds(30)));
        if (!stored) throw exception(DATA_API_NOT_AUTHORIZED);
        return token;
    }

    public Contract redeem(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) throw exception(DATA_API_NOT_AUTHORIZED);
        String value = redis.opsForValue().getAndDelete(key(token));
        if (value == null) throw exception(DATA_API_NOT_AUTHORIZED);
        var contract = JsonUtils.parseObject(value, Contract.class);
        requireClaim(operations.activeClaim(contract.operationId()), contract.appId(), contract.apiId(), contract.releaseId());
        return contract;
    }

    private static byte[] randomBytes() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String key(String token) {
        return "supply:published-write:claim:" + DigestUtil.sha256Hex(token);
    }

    private static void requireClaim(DataApiOperationDO row, long app, long api, long release) {
        if (row == null || !"RUNNING".equals(row.getState()) || !Objects.equals(app, row.getAppId())
                || !Objects.equals(api, row.getApiId()) || !Objects.equals(release, row.getReleaseId())) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
    }
}
