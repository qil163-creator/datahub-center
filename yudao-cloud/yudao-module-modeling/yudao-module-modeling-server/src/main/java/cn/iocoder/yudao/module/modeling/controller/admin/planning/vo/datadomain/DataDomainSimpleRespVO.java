package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 登录用户可见的板块选择项，不包含负责人、联系方式、规则或模型统计。 */
@Data
@Schema(description = "管理后台 - 数据板块选择项")
public class DataDomainSimpleRespVO {

    private Long id;

    private String name;

    private String code;

    private String icon;

    private Integer status;
}
