package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiReleaseMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.*;

/**
 * 消费文档的领域投影，复用发布快照解析和既有物理绑定核验，不暴露管理配置。
 */
@Service
public class SupplyPublishedApiDocumentService {

    @Resource
    private DataApiService dataApiService;

    @Resource
    private DataApiReleaseMapper dataApiReleaseMapper;

    @Resource
    private SupplyApiBindingService bindingService;

    /** 同一数据库快照内校验生效指针、发布归属和合同；不锁表、不写业务数据。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public SupplyPublishedApiDocumentRespDTO getPublishedApiDocument(Long id) {
        DataApiDO current = dataApiService.getDataApiEntity(id);
        if (current == null || !Objects.equals(id, current.getId())) {
            throw exception(DATA_API_NOT_EXISTS);
        }
        if (!Objects.equals(DataApiDO.STATUS_ONLINE, current.getStatus())) {
            throw exception(DATA_API_NOT_RELEASED);
        }
        if (current.getActiveReleaseId() == null || StrUtil.isBlank(current.getContractHash())) {
            throw exception(DATA_API_RELEASE_NOT_EXISTS);
        }
        var release = dataApiReleaseMapper.selectById(current.getActiveReleaseId());
        if (release == null || !Objects.equals(id, release.getApiId())
                || !Objects.equals(current.getActiveReleaseId(), release.getId())
                || StrUtil.isBlank(release.getConfigJson())) {
            throw exception(DATA_API_RELEASE_NOT_EXISTS);
        }
        DataApiDO published = dataApiService.getPublishedDataApiEntity(id);
        if (published == null || !Objects.equals(id, published.getId())
                || !Objects.equals(current.getActiveReleaseId(), published.getActiveReleaseId())
                || !Objects.equals(current.getContractHash(), release.getContractHash())
                || !Objects.equals(release.getContractHash(), published.getContractHash())
                || !Objects.equals(release.getContractHash(), DataApiContract.contractHash(published))
                || !Objects.equals(release.getConfigHash(), DataApiContract.configHash(published))
                || StrUtil.isBlank(published.getPath()) || StrUtil.isBlank(published.getMethod())
                || StrUtil.isBlank(published.getVersion())) {
            throw exception(DATA_API_CONTRACT_MISMATCH);
        }

        List<DataApiSaveReqVO.ParamItem> params = parse(published.getRequestParamsJson(), new TypeReference<>() {
        });
        List<DataApiSaveReqVO.FieldItem> fields = parse(published.getResponseFieldsJson(), new TypeReference<>() {
        });
        var binding = bindingService.resolve(published);
        var document = new SupplyPublishedApiDocumentRespDTO();
        document.setId(published.getId());
        document.setName(published.getName());
        document.setPath(published.getPath());
        document.setMethod(published.getMethod());
        document.setVersion(published.getVersion());
        document.setContractHash(published.getContractHash());
        document.setActiveReleaseId(published.getActiveReleaseId());
        document.setDatabaseId(published.getDatabaseId());
        document.setSchemaName(binding.getSchemaName());
        document.setTableName(published.getTableName());
        document.setFieldMappingVerified(binding.isFieldMappingVerified());
        document.setOutputFieldMapping(binding.getOutputFieldMapping());
        document.setRequestParams(requestParams(params));
        document.setResponseFields(responseFields(fields, document));
        return document;
    }

    /**
     * 请求参数。
     *
     * @param definitions 定义
     * @return 请求参数结果集合
     */
    private List<SupplyPublishedApiDocumentRespDTO.RequestParam> requestParams(
            List<DataApiSaveReqVO.ParamItem> definitions) {
        var result = new ArrayList<SupplyPublishedApiDocumentRespDTO.RequestParam>();
        Set<String> names = new HashSet<>();
        for (var definition : definitions) {
            if (definition == null || StrUtil.isBlank(definition.getName()) || !names.add(definition.getName())) {
                throw exception(DATA_API_CONFIG_INVALID, "已发布请求参数定义不完整");
            }
            var item = new SupplyPublishedApiDocumentRespDTO.RequestParam();
            item.setName(definition.getName());
            item.setType(definition.getParamType());
            item.setRequired(definition.getNullable() == null || definition.getNullable() == 0);
            item.setDescription(definition.getDescription());
            result.add(item);
        }
        return List.copyOf(result);
    }

    /**
     * 获取供应已发布API文档的响应字段。
     *
     * @param definitions 定义
     * @param document 文档
     * @return 供应已发布API文档的响应字段结果集合
     */
    private List<SupplyPublishedApiDocumentRespDTO.ResponseField> responseFields(
            List<DataApiSaveReqVO.FieldItem> definitions, SupplyPublishedApiDocumentRespDTO document) {
        var result = new ArrayList<SupplyPublishedApiDocumentRespDTO.ResponseField>();
        Set<String> names = new HashSet<>();
        for (var definition : definitions) {
            if (definition == null || StrUtil.isBlank(definition.getName())) {
                throw exception(DATA_API_CONFIG_INVALID, "已发布输出字段定义不完整");
            }
            if ("HIDE".equalsIgnoreCase(definition.getModel())) {
                continue;
            }
            String name = StrUtil.blankToDefault(definition.getOutputName(), definition.getName());
            if (!names.add(name)) {
                throw exception(DATA_API_CONFIG_INVALID, "已发布输出字段名称重复");
            }
            var item = new SupplyPublishedApiDocumentRespDTO.ResponseField();
            item.setName(name);
            item.setType(definition.getDataType());
            item.setDescription(definition.getDescription());
            result.add(item);
        }
        if (definitions.isEmpty() && document.isFieldMappingVerified()) {
            // 全列合同没有冻结类型说明；只投影已核验名称，最终由消费者按批准列收窄。
            document.getOutputFieldMapping().keySet().stream().sorted().forEach(name -> {
                var item = new SupplyPublishedApiDocumentRespDTO.ResponseField();
                item.setName(name);
                result.add(item);
            });
        }
        return List.copyOf(result);
    }

    /**
     * 解析供应已发布API文档。
     *
     * @param <T> 处理数据的类型
     * @param json JSON 内容
     * @param type 类型标识
     * @return 解析供应已发布API文档结果集合
     */
    private <T> List<T> parse(String json, TypeReference<List<T>> type) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        List<T> values = JsonUtils.parseObjectQuietly(json, type);
        if (values != null) {
            return values;
        }
        // 对消费者只返回领域错误，不回显包含默认值、示例或执行配置的原始 JSON。
        throw exception(DATA_API_CONFIG_INVALID, "已发布参数文档无法读取");
    }

}
