package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_CONFIG_INVALID;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_READONLY_REQUIRED;

/**
 * 只读执行边界：同时看 API 类型和解析后的真实 SQL，不信任前端 apiType。
 */
public final class DataApiReadOnlyGuard {

    /**
     * 限制数据 API只读门禁工具的实例化，统一通过静态能力使用。
     */
    private DataApiReadOnlyGuard() {
    }

    /**
     * 按执行模式校验 API 类型及 SQL 语义，限制只读预览并拒绝真实上游转发。
     *
     * @param api 本次使用的 API 定义
     * @param product 产品
     * @param mode 处理模式
     */
    public static void assertAllowed(DataApiDO api, ProductTypeEnum product, DataApiExecuteMode mode) {
        assertConfigurationAllowed(api, mode);
        if (mode == DataApiExecuteMode.APP_GATEWAY && !isQueryApi(api)) {
            return;
        }
        if (DataApiDO.CONFIG_TYPE_SQL.equalsIgnoreCase(api.getConfigType())) {
            assertSqlAllowed(product, api.getSqlText());
        }
    }

    /** 先校验配置门禁；SQL 模板展开和参数绑定后再检查实际执行文本。 */
    public static void assertConfigurationAllowed(DataApiDO api, DataApiExecuteMode mode) {
        if (mode == DataApiExecuteMode.APP_GATEWAY && !isQueryApi(api)) {
            return;
        }
        String configType = StrUtil.nullToEmpty(api.getConfigType());
        if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equalsIgnoreCase(configType)) {
            if (!isQueryApi(api)) {
                throw exception(DATA_API_READONLY_REQUIRED, "ETL 成果仅提供受控查询");
            }
            return;
        }
        if (DataApiDO.CONFIG_TYPE_FORWARD.equalsIgnoreCase(configType)) {
            if (mode.readOnly()) {
                throw exception(DATA_API_READONLY_REQUIRED, "转发属于真实上游调用，请走应用联调入口");
            }
            return;
        }
        if (!isQueryApi(api) && mode.readOnly()) {
            throw exception(DATA_API_READONLY_REQUIRED, "当前入口不执行写入或 MULTI");
        }
        if (DataApiDO.CONFIG_TYPE_TABLE.equalsIgnoreCase(configType)) {
            return;
        }
        if (!DataApiDO.CONFIG_TYPE_SQL.equalsIgnoreCase(configType)) {
            throw exception(DATA_API_CONFIG_INVALID, "未知配置方式 " + configType);
        }
        if (StrUtil.isBlank(api.getSqlText())) {
            throw exception(DATA_API_CONFIG_INVALID, "请配置 SQL 脚本");
        }
    }

    /** 校验与 JDBC 接收内容相同的 SQL，禁止依据模板或前端类型跳过语义门禁。 */
    public static void assertSqlAllowed(ProductTypeEnum product, String sql) {
        if (product == null) {
            throw exception(DATA_API_CONFIG_INVALID, "请配置数据源");
        }
        try {
            SqlStatementClassifier.assertReadOnlyQuery(product, sql);
        } catch (UnsupportedOperationException ex) {
            throw exception(DATA_API_READONLY_REQUIRED, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_API_READONLY_REQUIRED, ex.getMessage());
        }
    }

    /**
     * 判断 API 定义是否属于查询类接口。
     *
     * @param api 本次使用的 API 定义
     * @return 判断结果
     */
    public static boolean isQueryApi(DataApiDO api) {
        return api == null || StrUtil.isBlank(api.getApiType())
                || DataApiDO.API_TYPE_QUERY.equalsIgnoreCase(api.getApiType());
    }
}
