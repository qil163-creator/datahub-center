package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import lombok.Data;

/** 全局控制面的固定分类汇总；不暴露租户、任务编号或原始数据。 */
@Data
public class EtlOperationsSnapshot {

    private String kind;

    private Long pending;

    private Long failed;

    private Double oldestAge;
}
