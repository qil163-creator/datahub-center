package cn.iocoder.yudao.framework.etl.core.model;

import lombok.Data;

/**
 * ETL 流程节点定义（引擎输入模型，与画布/存储解耦）
 *
 * @author Li
 */
@Data
public class EtlNodeDef {

    /** 工具类型：输入 */
    public static final int TOOL_TYPE_INPUT = 1;

    /** 工具类型：转换 */
    public static final int TOOL_TYPE_TRANSFORM = 2;

    /** 工具类型：输出 */
    public static final int TOOL_TYPE_OUTPUT = 3;

    /** 工具类型：脚本分类；Java/JS 的 execute(rows) 同时支持行传输，其余脚本仅控制顺序。 */
    public static final int TOOL_TYPE_SCRIPT = 4;

    /**
     * 画布节点编号（流程内唯一）
     */
    private String no;

    /**
     * 节点名称
     */
    private String name;

    /**
     * 工具代码（决定执行器），如 read-db / insert-db / column-map / data-filter / sql-script
     */
    private String toolCode;

    /**
     * 工具分类：1-输入 2-转换 3-输出 4-脚本；Java/JS 行语义另按工具代码确定。
     */
    private Integer toolType;

    /**
     * 节点专属配置 JSON 字符串
     */
    private String nodeJson;

    /**
     * 节点失败后流程是否继续调度其控制下游：0-终止 1-继续
     */
    private Integer failGoOn;

    /**
     * 超时时长（分钟），小于等于 0 表示不限制
     */
    private Integer overTimeMinutes;

    /**
     * 判断当前节点是否配置为失败后继续运行。
     */
    public boolean isFailGoOn() {
        return failGoOn != null && failGoOn == 1;
    }

    /**
     * 是否多数据输入节点（合并、排序合并、多 JOIN 和笛卡尔积允许多个数据上游）
     */
    public boolean isMultiInput() {
        return "data-merge".equals(toolCode) || "sort-merge".equals(toolCode)
                || "multi-join".equals(toolCode) || "cartesian-product".equals(toolCode);
    }

    /** 工具分组不决定执行语义：这两类脚本会转换数据行。 */
    public boolean isRowScript() {
        return "java-script".equals(toolCode) || "js-script".equals(toolCode);
    }

}
