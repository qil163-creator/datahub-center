package cn.iocoder.yudao.module.supply.api.dataapi;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCandidateRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyApiBindingService;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyPublishedApiDocumentService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

@RestController
@Validated
public class SupplyDataApiApiImpl implements SupplyDataApiApi {

    @Resource
    private DataApiMapper apiMapper;

    /**
     * 分页读取当前已发布 API 注册摘要。
     *
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param keyword 关键字
     * @return 分页读取当前已发布 API 注册摘要结果
     */
    @Override
    public CommonResult<PageResult<SupplyApiCandidateRespDTO>> getPublishedCandidatePage(Integer pageNo,
            Integer pageSize, String keyword) {
        PageParam page = new PageParam();
        page.setPageNo(Math.max(1, pageNo == null ? 1 : pageNo));
        page.setPageSize(Math.max(1, Math.min(200, pageSize == null ? 10 : pageSize)));
        var query = Wrappers.lambdaQuery(DataApiDO.class).eq(DataApiDO::getStatus, DataApiDO.STATUS_ONLINE)
                .isNotNull(DataApiDO::getActiveReleaseId);
        // 搜索生效发布快照中的名称，不能使用尚未发布的草稿名称。
        if (cn.hutool.core.util.StrUtil.isNotBlank(keyword)) {
            query.apply(
                    "EXISTS (SELECT 1 FROM dh_supply_api_release r WHERE r.id=active_release_id AND r.api_id=dh_supply_api.id AND r.deleted=0 AND r.config_json->>'name' ILIKE {0})",
                    "%" + keyword + "%");
        }
        var rows = apiMapper.selectPage(page, query.orderByDesc(DataApiDO::getId));
        var views = rows.getList().stream().map(row -> publishedDocumentService.getPublishedApiDocument(row.getId()))
                .map(view -> BeanUtils.toBean(view, SupplyApiCandidateRespDTO.class)).toList();
        return success(new PageResult<>(views, rows.getTotal()));
    }

    @Resource
    private DataApiService dataApiService;

    @Resource
    private SupplyApiBindingService bindingService;

    @Resource
    private SupplyPublishedApiDocumentService publishedDocumentService;

    /**
     * 按编号读取 API 绑定摘要。
     *
     * @param id 记录编号
     * @return 供应API绑定响应数据
     */
    @Override
    public CommonResult<SupplyApiBindingRespDTO> getApiBinding(Long id) {
        return success(bindingService.resolve(dataApiService.getPublishedDataApiEntity(id)));
    }

    /**
     * 读取当前已发布 API 的公开文档投影。
     *
     * @param id 记录编号
     * @return 供应已发布API文档响应数据
     */
    @Override
    public CommonResult<SupplyPublishedApiDocumentRespDTO> getPublishedApiDocument(Long id) {
        return success(publishedDocumentService.getPublishedApiDocument(id));
    }

    /**
     * 列出已上线、单表向导、查询类 API 绑定。
     *
     * @param databaseId 数据源编号
     * @return 已上线、单表向导、查询类 API 绑定结果集合
     */
    @Override
    public CommonResult<List<SupplyApiBindingRespDTO>> listTableQueryBindings(Long databaseId) {
        // 列表仅供选择候选；批准前必须按编号获取已核验的完整绑定。
        return success(convertList(dataApiService.listOnlineTableQueryApis(databaseId), bindingService::describe));
    }

}
