package cn.iocoder.yudao.module.dataforge.controller.admin.dashboard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dashboard.vo.DataforgeDashboardRespVO;
import cn.iocoder.yudao.module.dataforge.service.dashboard.DataforgeDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 首页看板（数据集成 / 数据研发）
 * <p>
 * 供工作台 / 数据总览页调用。只返回数量级汇总，不含任何明细数据，
 * 因此登录即可访问，不再单独设置功能权限；明细仍由各功能接口自身的权限控制。
 *
 * @author Li
 */
@Tag(name = "管理后台 - 首页看板（数据集成 / 数据研发）")
@RestController
@RequestMapping("/dataforge/dashboard")
@Validated
public class DataforgeDashboardController {

    @Resource
    private DataforgeDashboardService dashboardService;

    /**
     * 获得首页看板汇总（数据源 / 同步任务 / 开发任务 / 今日执行）。
     *
     * @return 汇总
     */
    @GetMapping("/summary")
    @Operation(summary = "获得首页看板汇总（数据源 / 同步任务 / 开发任务 / 今日执行）")
    public CommonResult<DataforgeDashboardRespVO> getSummary() {
        return success(dashboardService.getSummary());
    }

}
