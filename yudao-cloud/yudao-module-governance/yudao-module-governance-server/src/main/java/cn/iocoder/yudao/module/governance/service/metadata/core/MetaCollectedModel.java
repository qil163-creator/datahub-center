package cn.iocoder.yudao.module.governance.service.metadata.core;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集读取结果模型（READ 阶段产物，DIFF 阶段输入）
 *
 * 直连采集、Excel 导入、手工登记三个通道统一转换为本模型后进入同一 diff 引擎
 *
 * @author Li
 */
public final class MetaCollectedModel {

    /**
     * 限制元数据采集已处理模型工具类的直接实例化。
     */
    private MetaCollectedModel() {
    }

    /**
     * 采集到的表结构
     */
    @Data
    @Builder
    public static class CollectedTable {

        /** 表名 */
        private String tableName;

        /** 类型：TABLE/VIEW */
        private String tableType;

        /** 源库表注释 */
        private String remarks;

        /** 主键字段名列表 */
        private List<String> primaryKeys;

        /** 字段列表 */
        @Builder.Default
        private List<CollectedColumn> columns = new ArrayList<>();

    }

    /**
     * 采集到的字段结构
     */
    @Data
    @Builder
    public static class CollectedColumn {

        /** 字段名 */
        private String columnName;

        /** 字段顺序（从 1 开始） */
        private Integer ordinalPosition;

        /** 源库字段注释 */
        private String remarks;

        /** 数据类型 */
        private String dataType;

        /** 数据长度 */
        private Long dataLength;

        /** 数据精度 */
        private Integer dataPrecision;

        /** 小数位数 */
        private Integer dataScale;

        /** 是否可空：0-否 1-是 */
        private Integer nullableFlag;

        /** 默认值 */
        private String defaultValue;

        /** 是否主键：0-否 1-是 */
        private Integer pkFlag;

        /** 是否自增：0-否 1-是 */
        private Integer autoIncrementFlag;

    }

}
