package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeRespVO;

@Schema(description = "管理后台 - 数据源中心--数据库信息 Response VO")
@Data
@ExcelIgnoreUnannotated
public class DatabaseRespVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "24164")
    @ExcelProperty("主键id")
    private Long id;

    @Schema(description = "连接名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "赵六")
    @ExcelProperty("连接名称")
    private String name;

    @Schema(description = "连接类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer databaseType;

    @Schema(description = "连接类型名称", example = "PostgreSQL")
    @ExcelProperty("连接类型")
    private String databaseTypeName;

    @Schema(description = "数据源编码，用于判断是否具备独立 Schema 概念", example = "POSTGRESQL")
    private String typeCode;

    @Schema(description = "已安装的数据库产品标识，文件与消息协议可为空")
    private String productId;

    @Schema(description = "连接器各操作能力，连接测试与元数据能力相互独立")
    private Map<String, DatabaseTypeRespVO.Capability> capabilities;

    @Schema(description = "连接图标")
    private String iconPath;

    @Schema(description = "主机ip地址", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("主机ip地址")
    private String databaseIp;

    @Schema(description = "端口", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("端口")
    private String databasePort;

    @Schema(description = "库名", requiredMode = Schema.RequiredMode.REQUIRED, example = "赵六")
    @ExcelProperty("库名")
    private String databaseName;

    @Schema(description = "schema", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("schema")
    private String databaseSchema;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Integer status;

    @Schema(description = "连通状态：0未检测 1在线 2离线", example = "1")
    @ExcelProperty("连通状态")
    private Integer connectionStatus;

    @Schema(description = "最近一次连接测试时间")
    @ExcelProperty("最近测试时间")
    private LocalDateTime lastTestTime;

    @Schema(description = "最近一次连接测试结果说明")
    private String lastTestMsg;

    @Schema(description = "用途分类：1-业务库 2-中台库", example = "1")
    @ExcelProperty("用途分类")
    private Integer usageType;

    @Schema(description = "用户名", example = "芋艿")
    @ExcelProperty("用户名")
    private String userName;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "是否保存了非空凭据，不包含凭据内容")
    private boolean hasPassword;

    @Schema(description = "jdbcUrl", example = "https://www.iocoder.cn")
    @ExcelProperty("jdbcUrl")
    private String jdbcUrl;

    @Schema(description = "单位id", example = "18943")
    private Long orgId;

    @Schema(description = "单位名称", example = "研发部")
    @ExcelProperty("单位")
    private String orgName;

    @Schema(description = "扩展配置")
    @ExcelProperty("扩展配置")
    private String extendConfig;

    @Schema(description = "密码解密密钥")
    private String secretKey;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("排序")
    private Integer sort;

    @Schema(description = "备注", example = "随便")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建者", example = "芋艿")
    @ExcelProperty("创建者")
    private String creatorName;

    @Schema(description = "更新者", example = "芋艿")
    private String updaterName;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @Schema(description = "修改时间")
    @ExcelProperty("修改时间")
    private LocalDateTime updateTime;

}
