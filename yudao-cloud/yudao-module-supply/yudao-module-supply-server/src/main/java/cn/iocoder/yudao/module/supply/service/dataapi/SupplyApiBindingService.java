package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 共享绑定的领域核验。复用 DataForge 元数据 API 和现有发布合同，不实现数据库解析器。
 * 不开启持锁事务；连接凭据不进入返回值、日志或缓存。
 */
@Service
public class SupplyApiBindingService {

    @Resource
    private DatabaseApi databaseApi;

    /**
     * 读取供应API绑定的描述及当前状态。
     *
     * @param api 本次使用的 API 定义
     * @return 供应API绑定响应数据
     */
    public SupplyApiBindingRespDTO describe(DataApiDO api) {
        if (api == null) {
            return null;
        }
        var dto = new SupplyApiBindingRespDTO();
        dto.setId(api.getId());
        dto.setName(api.getName());
        dto.setPath(api.getPath());
        dto.setVersion(api.getVersion());
        dto.setStatus(api.getStatus());
        dto.setApiType(api.getApiType());
        dto.setConfigType(api.getConfigType());
        dto.setDatabaseId(api.getDatabaseId());
        dto.setTableName(api.getTableName());
        dto.setActiveReleaseId(api.getActiveReleaseId());
        dto.setContractHash(api.getActiveReleaseId() == null ? null : api.getContractHash());
        return dto;
    }

    /** 失败/缺失证据绝不降级为 ALL；fieldMappingVerified=false 必须进入复核。 */
    public SupplyApiBindingRespDTO resolve(DataApiDO published) {
        var dto = describe(published);
        if (dto == null || !Objects.equals(DataApiDO.STATUS_ONLINE, published.getStatus())
                || !DataApiDO.CONFIG_TYPE_TABLE.equals(published.getConfigType())
                || !DataApiDO.API_TYPE_QUERY.equals(published.getApiType())
                || published.getDatabaseId() == null || StrUtil.isBlank(published.getTableName())
                || StrUtil.isBlank(dto.getContractHash())
                || !dto.getContractHash().equals(DataApiContract.contractHash(published))) {
            return dto;
        }
        var connection = databaseApi.getDatabaseConnection(published.getDatabaseId()).getCheckedData();
        if (connection == null || StrUtil.isBlank(connection.getSchema())) {
            return dto;
        }
        dto.setSchemaName(connection.getSchema());
        var columns = databaseApi.getTableColumnList(published.getDatabaseId(), published.getTableName())
                .getCheckedData();
        if (columns == null || columns.isEmpty()) {
            return dto;
        }
        Set<String> physical = columns.stream().map(DatabaseColumnRespDTO::getColumnName)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<DataApiSaveReqVO.FieldItem> fields = StrUtil.isBlank(published.getResponseFieldsJson()) ? List.of()
                : JsonUtils.parseObject(published.getResponseFieldsJson(), new TypeReference<>() {
        });
        if (fields == null) {
            return dto;
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        if (fields.isEmpty()) {
            // 发布配置为全列时，按本次真实元数据展开；调用方仍须冻结为明确批准字段。
            physical.stream().sorted().forEach(name -> mapping.put(name, name));
        } else {
            for (var field : fields) {
                if (field == null || !physical.contains(field.getName())) {
                    return dto;
                }
                if ("HIDE".equalsIgnoreCase(field.getModel())) {
                    continue;
                }
                String output = StrUtil.blankToDefault(field.getOutputName(), field.getName());
                if (mapping.putIfAbsent(output, field.getName()) != null) {
                    return dto;
                }
            }
        }
        if (!mapping.isEmpty()) {
            dto.setOutputFieldMapping(Map.copyOf(mapping));
            dto.setFieldMappingVerified(true);
        }
        return dto;
    }
}
