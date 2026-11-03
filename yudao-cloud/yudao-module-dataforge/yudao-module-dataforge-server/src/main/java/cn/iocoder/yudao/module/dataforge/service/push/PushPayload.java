package cn.iocoder.yudao.module.dataforge.service.push;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 推送给接收方的批次载荷
 *
 * @author Li
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PushPayload {

    private String objectName;

    private List<Map<String, Object>> dataList;

}
