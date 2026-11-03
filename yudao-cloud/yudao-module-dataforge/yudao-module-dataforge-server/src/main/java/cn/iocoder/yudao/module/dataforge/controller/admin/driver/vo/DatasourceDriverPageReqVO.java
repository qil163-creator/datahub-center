package cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据源驱动管理分页 Request VO")
@Data
public class DatasourceDriverPageReqVO extends PageParam {

    @Schema(description = "驱动名称", example = "赵六")
    private String driverName;

    @Schema(description = "数据库产品类型：MYSQL、POSTGRESQL、ORACLE、HIVE、DORIS、CLICKHOUSE   字典数据", example = "1")
    private String productType;

    @Schema(description = "驱动来源：BUILTIN 内置驱动，UPLOAD 上传驱动    字典数据")
    private String driverSource;

    @Schema(description = "状态", example = "2")
    private Short status;

}