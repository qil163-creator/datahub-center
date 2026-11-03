package cn.iocoder.yudao.module.dataforge.service.access.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 业务名称映射契约，不包含组件运行状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessNameMapping {

    private String fromPattern;

    private String toValue;
}
