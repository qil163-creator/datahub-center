package cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemTreeReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.service.standardcodeitem.StandardCodeItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据标准--码值")
@RestController
@RequestMapping("/governance/standard-code-item")
@Validated
public class StandardCodeItemController {

    @Resource
    private StandardCodeItemService standardCodeItemService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService catalog;

    /**
     * 创建码值
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-码值", subType = "创建数据标准--码值",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建数据标准--码值",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建数据标准--码值")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:create')")
    public CommonResult<Integer> createStandardCodeItem(@Valid @RequestBody StandardCodeItemSaveReqVO createReqVO) {
        return success(standardCodeItemService.createStandardCodeItem(createReqVO));
    }

    /**
     * 更新码值
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-码值", subType = "更新数据标准--码值",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新数据标准--码值",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新数据标准--码值")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:update')")
    public CommonResult<Boolean> updateStandardCodeItem(@Valid @RequestBody StandardCodeItemSaveReqVO updateReqVO) {
        standardCodeItemService.updateStandardCodeItem(updateReqVO);
        return success(true);
    }

    /**
     * 删除码值
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-码值", subType = "删除数据标准--码值",
            bizNo = "{{(#id) ?: 0}}", success = "删除数据标准--码值",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除数据标准--码值")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:delete')")
    public CommonResult<Boolean> deleteStandardCodeItem(@RequestParam("id") Integer id) {
        standardCodeItemService.deleteStandardCodeItem(id);
        return success(true);
    }

    /**
     * 批量删除数据标准--码值。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据治理-码值", subType = "批量删除数据标准--码值",
            bizNo = "{{(#ids) ?: 0}}", success = "批量删除数据标准--码值",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除数据标准--码值")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:delete')")
    public CommonResult<Boolean> deleteStandardCodeItemList(@RequestParam("ids") List<Integer> ids) {
        standardCodeItemService.deleteStandardCodeItemListByIds(ids);
        return success(true);
    }

    /**
     * 获得码值
     *
     *  @param id 编号
     *  @return 码值
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据标准--码值")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<StandardCodeItemRespVO> getStandardCodeItem(@RequestParam("id") Integer id) {
        StandardCodeItemDO item = standardCodeItemService.getStandardCodeItem(id);
        var result = BeanUtils.toBean(item, StandardCodeItemRespVO.class);
        if (result != null) {
            catalog.fillItems(List.of(result));
        }
        return success(result);
    }

    /**
     * 获得码值分页
     *
     *  @param pageReqVO 分页查询
     *  @return 码值分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据标准--码值分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<PageResult<StandardCodeItemRespVO>> getStandardCodeItemPage(
            @Valid StandardCodeItemPageReqVO pageReqVO) {
        PageResult<StandardCodeItemDO> pageResult = standardCodeItemService.getStandardCodeItemPage(pageReqVO);
        var result = BeanUtils.toBean(pageResult, StandardCodeItemRespVO.class);
        catalog.fillItems(result.getList());
        return success(result);
    }

    /**
     * 获得指定码表的层级码值（按层级、排序返回）。
     */
    @GetMapping("/tree")
    @Operation(summary = "获得标准码表层级码值")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<List<StandardCodeItemRespVO>> getStandardCodeItemTree(
            @Valid StandardCodeItemTreeReqVO reqVO) {
        return success(BeanUtils.toBean(standardCodeItemService.getStandardCodeItemTree(reqVO),
                StandardCodeItemRespVO.class));
    }

}
