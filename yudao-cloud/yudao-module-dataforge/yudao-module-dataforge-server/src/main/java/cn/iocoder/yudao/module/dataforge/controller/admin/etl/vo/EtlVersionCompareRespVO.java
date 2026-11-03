package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EtlVersionCompareRespVO {

    private Long sourceVersionId;

    private Integer sourceRevision;

    private Long targetVersionId;

    private Integer targetRevision;

    private List<Change> changes;

    private Map<String, Integer> changeCounts;

    /** 差异项只暴露对象编号、类别和数量，不暴露变更前后的配置值。 */
    public record Change(String type, String objectId, int count) {

    }
}
