package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import java.util.List;
import java.util.Map;

public record EtlTargetRowsRespVO(List<Map<String, Object>> rows, long nextAfterRow, boolean hasMore) {

}
