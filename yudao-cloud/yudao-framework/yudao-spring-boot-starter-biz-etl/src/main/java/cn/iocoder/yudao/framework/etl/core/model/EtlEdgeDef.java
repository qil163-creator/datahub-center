package cn.iocoder.yudao.framework.etl.core.model;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

/**
 * ETL 流程连线定义
 *
 * @author Li
 */
@Data
public class EtlEdgeDef {

    /** 数据边：上游逐行向下游传递数据 */
    public static final String TYPE_DATA = "DATA";

    /** 控制边：仅表示"上游执行完成后继续"，不传数据 */
    public static final String TYPE_CONTROL = "CONTROL";

    public static final String PORT_MAIN = "main";

    /**
     * 源节点编号
     */
    private String sourceNo;

    /**
     * 目标节点编号
     */
    private String targetNo;

    /**
     * 边类型：DATA / CONTROL
     */
    private String edgeType;

    /**
     * 源节点输出端口（预留分支能力），默认 main
     */
    private String outputPort;

    /** 稳定输入槽；多输入时按照 input-1、input-2 等显式次序处理。 */
    private String inputPort;

    /**
     * 判断控制。
     */
    public boolean isControl() {
        return TYPE_CONTROL.equalsIgnoreCase(edgeType);
    }

    /**
     * 解析输出端口。
     */
    public String resolveOutputPort() {
        return StrUtil.blankToDefault(outputPort, PORT_MAIN);
    }

}
