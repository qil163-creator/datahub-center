package cn.iocoder.yudao.module.dataforge.api.etl.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 精确 decimal 值以十进制字符串传输，游标表示技术行序号。 */
@Data
public class EtlTargetBatchRowsRespDTO {

    private List<Map<String, Object>> rows;

    private Long nextAfterRow;

    private Boolean hasMore;
}
