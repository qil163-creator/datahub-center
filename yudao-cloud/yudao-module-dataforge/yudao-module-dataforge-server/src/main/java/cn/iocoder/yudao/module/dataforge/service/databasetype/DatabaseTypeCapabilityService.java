package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.module.dataforge.service.database.ConnectionSettings;

/** 将已安装组件的不可变能力快照映射到数据库选择契约。 */
@Service
public class DatabaseTypeCapabilityService {

    @Resource
    private DbSwitchRuntime runtime;

    /**
     * 读取数据库类型能力的描述及当前状态。
     *
     * @param type 类型标识
     * @return 数据库类型响应数据
     */
    public DatabaseTypeRespVO describe(DatabaseTypeDO type) {
        if (type == null) {
            return null;
        }
        var result = BeanUtils.toBean(type, DatabaseTypeRespVO.class);
        String code = ConnectionSettings.code(type);
        if (ConnectionSettings.external(type) || "FTP".equals(code) || "HDFS".equals(code)) {
            var capabilities = new LinkedHashMap<String, DatabaseTypeRespVO.Capability>();
            for (var operation : DatabaseOperation.values()) {
                capabilities.put(operation.name(),
                        new DatabaseTypeRespVO.Capability("UNSUPPORTED", "该文件、缓存或消息连接未接入此数据库操作"));
            }
            capabilities.put("CONNECT", new DatabaseTypeRespVO.Capability("DECLARED",
                    "通过独立目标客户端只读验证连接，实际结果以本次诊断为准"));
            result.setCapabilities(java.util.Collections.unmodifiableMap(capabilities));
            return result;
        }
        runtime.products().resolve(type.getTypeCode()).ifPresent(product -> {
            result.setProductId(product.name());
            result.setCursorTargets(
                    runtime.products().cursorTargets(product).stream().map(Enum::name).sorted().toList());
            var capabilities = new LinkedHashMap<String, DatabaseTypeRespVO.Capability>();
            runtime.products().product(product).capabilities().forEach((operation, capability) ->
                    capabilities.put(operation.name(), new DatabaseTypeRespVO.Capability(
                            capability.status().name(), capability.reason())));
            result.setCapabilities(java.util.Collections.unmodifiableMap(capabilities));
        });
        return result;
    }

}
