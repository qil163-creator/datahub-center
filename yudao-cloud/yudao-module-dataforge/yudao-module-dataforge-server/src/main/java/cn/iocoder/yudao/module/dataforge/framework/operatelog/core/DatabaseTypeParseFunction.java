package cn.iocoder.yudao.module.dataforge.framework.operatelog.core;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import com.mzt.logapi.service.IParseFunction;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据源类型名称的 {@link IParseFunction} 实现类，操作日志模板写法：{@code {getDataforgeDatabaseTypeName{#databaseType}}}。
 * <p>
 * 数据源的「数据库类型」字段保存的是类型表编号，日志中译为类型名称（例如 MySQL、FTP）；
 * 类型不存在或读取失败时退回「编号X」，不影响业务结果。
 */
@Slf4j
@Component
public class DatabaseTypeParseFunction implements IParseFunction {

    public static final String NAME = "getDataforgeDatabaseTypeName";

    @Resource
    private DatabaseTypeService databaseTypeService;

    @Override
    public String functionName() {
        return NAME;
    }

    @Override
    public String apply(Object value) {
        if (StrUtil.isEmptyIfStr(value)) {
            return "未设置";
        }
        Long id = Convert.toLong(value, null);
        if (id == null) {
            return String.valueOf(value);
        }
        String fallback = "编号" + id;
        try {
            DatabaseTypeDO type = databaseTypeService.getDatabaseType(id);
            if (type == null || StrUtil.isBlank(type.getTypeName())) {
                return fallback;
            }
            return type.getTypeName();
        } catch (RuntimeException ex) {
            // 审计名称读取失败时保留编号，不改变原有业务结果。
            log.warn("[apply][读取数据源类型({})名称失败]", id, ex);
            return fallback;
        }
    }

}
