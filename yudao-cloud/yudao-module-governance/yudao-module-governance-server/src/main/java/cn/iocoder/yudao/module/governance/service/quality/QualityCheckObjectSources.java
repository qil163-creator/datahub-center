package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;

import java.util.Collection;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_SOURCE_MISSING;

final class QualityCheckObjectSources {

    private QualityCheckObjectSources() {}

    /** 与检查对象选择器共用启用数据源清单，批量校验不读取连接密码。 */
    static void requireAvailable(DatabaseApi databaseApi, Collection<Long> ids) {
        var sources = databaseApi.getDatabaseList(null).getCheckedData();
        if (sources == null || !sources.stream().map(DatabaseConnectionRespDTO::getId)
                .collect(Collectors.toSet()).containsAll(ids)) {
            throw exception(QUALITY_CHECK_OBJECT_SOURCE_MISSING);
        }
    }
}
