package cn.iocoder.yudao.module.dataforge.controller.admin.push;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据推送配置")
@RestController
@RequestMapping("/dataforge/data-push-config")
@Validated
public class DataPushConfigController {

    @Resource
    private DataPushConfigService configService;

    /**
     * 创建推送配置。
     *
     * @param createReqVO 创建参数
     * @return 数据推送配置
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据开发-数据推送配置", bizId = "#result")
    @Operation(summary = "创建推送配置")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:create')")
    public CommonResult<Long> createConfig(@Valid @RequestBody DataPushConfigSaveReqVO createReqVO) {
        return success(configService.createConfig(createReqVO));
    }

    /**
     * 更新推送配置。
     *
     * @param updateReqVO 更新参数
     * @return 数据推送配置
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据开发-数据推送配置", bizId = "#updateReqVO.id")
    @Operation(summary = "更新推送配置")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:update')")
    public CommonResult<Boolean> updateConfig(@Valid @RequestBody DataPushConfigSaveReqVO updateReqVO) {
        configService.updateConfig(updateReqVO);
        return success(true);
    }

    /**
     * 删除推送配置。
     *
     * @param id 记录编号
     * @return 数据推送配置
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据开发-数据推送配置", bizId = "#id")
    @Operation(summary = "删除推送配置")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:delete')")
    public CommonResult<Boolean> deleteConfig(@RequestParam("id") Long id) {
        configService.deleteConfig(id);
        return success(true);
    }

    /**
     * 获得推送配置。
     *
     * @param id 记录编号
     * @return 数据推送配置响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得推送配置")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<DataPushConfigRespVO> getConfig(@RequestParam("id") Long id) {
        return success(configService.getConfig(id));
    }

    /**
     * 获得推送配置分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 推送配置分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得推送配置分页")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<PageResult<DataPushConfigRespVO>> getConfigPage(@Valid DataPushConfigPageReqVO pageReqVO) {
        return success(configService.getConfigPage(pageReqVO));
    }

    /**
     * 测试推送通道连通性。
     *
     * @param reqVO 请求参数
     * @return 数据推送配置
     */
    @PostMapping("/test-connection")
    @Operation(summary = "测试推送通道连通性")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false)
    public CommonResult<Boolean> testConnection(@Valid @RequestBody DataPushConfigSaveReqVO reqVO) {
        configService.testConnection(reqVO);
        return success(true);
    }

    /**
     * 复制推送配置。
     *
     * @param id 记录编号
     * @return 数据推送配置
     */
    @PostMapping("/copy")
    @BusinessOperateLog(type = "数据开发-数据推送配置", bizId = "#result")
    @Operation(summary = "复制推送配置")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:create')")
    public CommonResult<Long> copyConfig(@RequestParam("id") Long id) {
        return success(configService.copyConfig(id));
    }

    /**
     * 获得推送配置精简列表。
     *
     * @return 精简列表
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得推送配置精简列表")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<List<DataPushConfigRespVO>> getSimpleList() {
        return success(configService.getSimpleList());
    }

}
