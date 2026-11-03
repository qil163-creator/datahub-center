package cn.iocoder.yudao.module.supply.api.dataapi;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCandidateRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import cn.iocoder.yudao.module.supply.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 数据 API 绑定查询：供资产申请核验服务与库表的可证明关联。
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 数据 API 绑定")
public interface SupplyDataApiApi {

    String PREFIX = ApiConstants.PREFIX + "/data-api";

    /**
     * 分页读取当前已发布 API 注册摘要。
     *
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param keyword 关键字
     * @return 分页读取当前已发布 API 注册摘要结果
     */
    @GetMapping(PREFIX + "/published-candidate-page")
    @Operation(summary = "分页读取当前已发布 API 注册摘要")
    CommonResult<PageResult<SupplyApiCandidateRespDTO>> getPublishedCandidatePage(
            @RequestParam("pageNo") Integer pageNo, @RequestParam("pageSize") Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword);

    /**
     * 按编号读取 API 绑定摘要。
     *
     * @param id 记录编号
     * @return 供应API绑定响应数据
     */
    @GetMapping(PREFIX + "/get-binding")
    @Operation(summary = "按编号读取 API 绑定摘要")
    CommonResult<SupplyApiBindingRespDTO> getApiBinding(@RequestParam("id") Long id);

    /**
     * 读取当前已发布 API 的公开文档投影。
     *
     * @param id 记录编号
     * @return 供应已发布API文档响应数据
     */
    @GetMapping(PREFIX + "/get-published-document")
    @Operation(summary = "读取当前已发布 API 的公开文档投影")
    CommonResult<SupplyPublishedApiDocumentRespDTO> getPublishedApiDocument(@RequestParam("id") Long id);

    /**
     * 列出已上线、单表向导、查询类 API 绑定。
     *
     * @param databaseId 数据源编号
     * @return 已上线、单表向导、查询类 API 绑定结果集合
     */
    @GetMapping(PREFIX + "/list-table-query-bindings")
    @Operation(summary = "列出已上线、单表向导、查询类 API 绑定")
    CommonResult<List<SupplyApiBindingRespDTO>> listTableQueryBindings(
            @RequestParam("databaseId") Long databaseId);

}
