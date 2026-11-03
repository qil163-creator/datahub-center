package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 元数据树节点 VO：来源系统 → 数据源 → 库
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据树节点 VO")
@Data
public class MetaObjectTreeNodeVO {

    /** 节点类型：来源系统 */
    public static final String TYPE_SYSTEM = "system";

    /** 节点类型：数据源 */
    public static final String TYPE_DATASOURCE = "datasource";

    /** 节点类型：库 */
    public static final String TYPE_DB = "db";

    @Schema(description = "节点唯一键，如 system:1 / datasource:2 / db:3", example = "db:3")
    private String key;

    @Schema(description = "节点类型：system/datasource/db", example = "db")
    private String type;

    @Schema(description = "对象编号（来源系统/数据源/库元数据编号）", example = "3")
    private Long id;

    @Schema(description = "节点名称", example = "dwd")
    private String name;

    @Schema(description = "数据库类型（库节点）", example = "DORIS")
    private String dbType;

    @Schema(description = "表数量（库节点统计）", example = "11")
    private Integer tableCount;

    @Schema(description = "对象状态：1-正常 2-待确认删除（库节点，用于警示标记）", example = "1")
    private Integer objectStatus;

    @Schema(description = "子节点")
    private List<MetaObjectTreeNodeVO> children = new ArrayList<>();

}
