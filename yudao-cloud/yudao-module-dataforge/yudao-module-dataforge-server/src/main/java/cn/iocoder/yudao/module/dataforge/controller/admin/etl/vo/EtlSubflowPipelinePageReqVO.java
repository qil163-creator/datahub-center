package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 可引用子流程候选分页")
public class EtlSubflowPipelinePageReqVO extends PageParam {

    @Size(max = 50)
    @Schema(description = "流程名称搜索")
    private String name;

    /**
     * 读取每页行数。
     *
     * @return 每页行数
     */
    @Override
    @Max(value = 50, message = "子流程候选每页最多50条")
    public Integer getPageSize() {
        return super.getPageSize();
    }
}
