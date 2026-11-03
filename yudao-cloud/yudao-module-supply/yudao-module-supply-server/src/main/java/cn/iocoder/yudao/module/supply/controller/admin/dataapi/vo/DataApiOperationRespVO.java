package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiOperationDO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 开放写操作台账（平台内获授权的运维人员可见）")
public class DataApiOperationRespVO {

    private Long id;

    private Long appId;

    private Long apiId;

    private Long releaseId;

    private String operationKey;

    private String state;

    private String traceId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long resolvedBy;

    private String resolution;

    private String evidence;

    private LocalDateTime resolvedAt;

    /**
     * 将输入转换为数据 API操作响应数据。
     *
     * @param value 待处理的值
     * @return 数据 API操作响应数据
     */
    public static DataApiOperationRespVO from(DataApiOperationDO value) {
        return BeanUtils.toBean(value, DataApiOperationRespVO.class);
    }
}
