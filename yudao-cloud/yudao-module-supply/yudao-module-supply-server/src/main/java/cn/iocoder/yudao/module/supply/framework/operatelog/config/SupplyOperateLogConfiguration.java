package cn.iocoder.yudao.module.supply.framework.operatelog.config;

import cn.iocoder.yudao.framework.operatelog.core.function.CodeLabelParseFunction;
import cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiOperationDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiReleaseDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiOperationMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiReleaseMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.loader;

/**
 * 数据服务模块的操作日志「编号 → 名称」翻译函数，模板写法如 {@code {getDataApiName{#apiId}}}。
 */
@Configuration(proxyBeanMethods = false)
public class SupplyOperateLogConfiguration {

    @Bean
    public EntityNameParseFunction dataApiNameParseFunction(DataApiMapper mapper) {
        return new EntityNameParseFunction("getDataApiName",
                loader(mapper::selectBatchIds, DataApiDO::getId, DataApiDO::getName));
    }

    /** API 发布快照：显示 API 名称与版本号 */
    @Bean
    public EntityNameParseFunction dataApiReleaseNameParseFunction(DataApiReleaseMapper mapper) {
        return new EntityNameParseFunction("getDataApiReleaseName",
                loader(mapper::selectBatchIds, DataApiReleaseDO::getId,
                        row -> row.getApiVersion() == null ? null : "版本" + row.getApiVersion()));
    }

    /** API 写操作记录：显示所属 API 名称 */
    @Bean
    public EntityNameParseFunction dataApiOperationNameParseFunction(DataApiOperationMapper operationMapper,
                                                                     DataApiMapper apiMapper) {
        return new EntityNameParseFunction("getDataApiOperationName", ids -> {
            Map<Long, Long> apiIds = new HashMap<>();
            for (DataApiOperationDO row : operationMapper.selectBatchIds(ids)) {
                if (row.getApiId() != null) {
                    apiIds.put(row.getId(), row.getApiId());
                }
            }
            Map<Long, String> apiNames = apiNames(apiMapper, apiIds.values());
            Map<Long, String> result = new HashMap<>();
            apiIds.forEach((id, apiId) -> result.put(id, apiNames.get(apiId)));
            return result;
        });
    }

    /** 应用 API 授权：显示「应用名称 / API 名称」 */
    @Bean
    public EntityNameParseFunction appApiGrantNameParseFunction(AppApiMapper grantMapper, SupplyAppMapper appMapper,
                                                                DataApiMapper apiMapper) {
        return new EntityNameParseFunction("getAppApiGrantName", ids -> {
            List<AppApiDO> grants = grantMapper.selectBatchIds(ids);
            Set<Long> appIds = new HashSet<>();
            Set<Long> apiIds = new HashSet<>();
            grants.forEach(grant -> {
                if (grant.getAppId() != null) {
                    appIds.add(grant.getAppId());
                }
                if (grant.getApiId() != null) {
                    apiIds.add(grant.getApiId());
                }
            });
            Map<Long, String> appNames = new HashMap<>();
            if (!appIds.isEmpty()) {
                for (SupplyAppDO app : appMapper.selectBatchIds(appIds)) {
                    appNames.put(app.getId(), app.getName());
                }
            }
            Map<Long, String> apiNames = apiNames(apiMapper, apiIds);
            Map<Long, String> result = new HashMap<>();
            for (AppApiDO grant : grants) {
                String app = Objects.toString(appNames.get(grant.getAppId()), "编号" + grant.getAppId());
                String api = Objects.toString(apiNames.get(grant.getApiId()), "编号" + grant.getApiId());
                result.put(grant.getId(), app + " / " + api);
            }
            return result;
        });
    }

    /** API 调用记录：显示被调用的 API 名称 */
    @Bean
    public EntityNameParseFunction apiLogNameParseFunction(ApiLogMapper mapper) {
        return new EntityNameParseFunction("getApiLogApiName",
                loader(mapper::selectBatchIds, ApiLogDO::getId, ApiLogDO::getApiName));
    }

    private static Map<Long, String> apiNames(DataApiMapper apiMapper, Collection<Long> apiIds) {
        Map<Long, String> names = new HashMap<>();
        if (apiIds.isEmpty()) {
            return names;
        }
        for (DataApiDO api : apiMapper.selectBatchIds(apiIds)) {
            names.put(api.getId(), api.getName());
        }
        return names;
    }

    // ========== 业务代码翻译（类型、模式、级别等），未收录的值原样输出 ==========

    @Bean
    public CodeLabelParseFunction supplyApiTestModeLabelParseFunction() {
        return new CodeLabelParseFunction("getApiTestModeLabel", Map.ofEntries(Map.entry("REGISTERED", "平台已注册 API"), Map.entry("EXTERNAL", "外部接口")));
    }

}
