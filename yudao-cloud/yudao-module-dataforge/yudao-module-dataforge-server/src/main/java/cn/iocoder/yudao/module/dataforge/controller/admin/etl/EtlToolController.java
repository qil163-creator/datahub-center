package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlToolRespVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlToolMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

@Tag(name = "管理后台 - ETL 工具箱")
@RestController
@RequestMapping("/dataforge/etl-tool")
@Validated
public class EtlToolController {

    @Resource
    private EtlToolMapper toolMapper;

    /**
     * 获得工具箱组件平铺列表（前端组树）。
     *
     * @return 工具列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得工具箱组件平铺列表（前端组树）")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")

    public CommonResult<List<EtlToolRespVO>> getToolList() {
        return success(convertList(toolMapper.selectEnabledList(),
                tool -> BeanUtils.toBean(tool, EtlToolRespVO.class)));
    }

}
