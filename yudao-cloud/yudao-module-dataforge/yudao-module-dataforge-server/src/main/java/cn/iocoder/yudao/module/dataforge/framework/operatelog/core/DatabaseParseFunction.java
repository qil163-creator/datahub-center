package cn.iocoder.yudao.module.dataforge.framework.operatelog.core;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import com.mzt.logapi.service.IParseFunction;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据源名称的 {@link IParseFunction} 实现类，操作日志模板写法：{@code {getDataforgeDatabaseName{#databaseId}}}。
 * <p>
 * 把数据源编号翻译成名称；数据源不存在或读取失败时退回「编号X」，不影响业务结果。
 */
@Slf4j
@Component
public class DatabaseParseFunction implements IParseFunction {

    public static final String NAME = "getDataforgeDatabaseName";

    @Resource
    private DatabaseService databaseService;

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
            DatabaseDO database = databaseService.getDatabase(id);
            if (database == null || StrUtil.isBlank(database.getName())) {
                log.warn("[apply][数据源({})不存在或名称为空]", id);
                return fallback;
            }
            return database.getName();
        } catch (RuntimeException ex) {
            // 审计名称读取失败时保留编号，不改变原有业务结果。
            log.warn("[apply][读取数据源({})名称失败]", id, ex);
            return fallback;
        }
    }

}
