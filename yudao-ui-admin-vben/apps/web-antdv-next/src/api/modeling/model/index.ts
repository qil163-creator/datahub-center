import { requestClient } from '#/api/request';

export namespace ModelingModelApi {
  /**
   * 模型规划归属与部署目标的展示信息。
   * layerId 属于技术轴；subjectDomainId 属于分析分组，
   * 其余为后端解析的展示字段。它们不是一棵父子树。
   */
  export interface ModelHierarchy {
    dataDomainId?: number | string;
    bizCategoryId?: number | string;
    bizCategoryName?: string;
    bizCategoryCode?: string;
    dataDomainName?: string;
    layerId?: number | string;
    layerCode?: string;
    layerName?: string;
    subjectDomainId?: number | string;
    subjectDomainName?: string;
    /** 组合导航摘要，如「DWD 明细层 / 分析主题」 */
    hierarchyPath?: string;
    tableType?: string; // SOURCE/DETAIL/DIMENSION/SUMMARY/APPLICATION
  }

  /** 模型字段定义项 */
  export interface ModelField {
    /** 字段稳定标识：用于字段编辑、重排与异步校验结果关联 */
    fieldUid?: string;
    cnName?: string;
    fieldName?: string;
    dataType?: string;
    dataLength?: null | number;
    dataPrecision?: null | number;
    /** 小数位数（关联标准时带出） */
    dataScale?: null | number;
    /** 数据格式，如 yyyy-MM-dd（关联标准时带出） */
    dataFormat?: string;
    /** 计量单位（关联标准时带出） */
    measureUnit?: string;
    /** 值域范围说明（关联标准时带出） */
    valueRange?: string;
    /** 默认值（关联标准时带出） */
    defaultValue?: string;
    sort?: number;
    comment?: string;
    /** 字段备注，随当前设计字段保存 */
    remark?: string;
    standardId?: null | number | string;
    standardCode?: string;
    standardName?: string;
    standardColumnType?: string;
    pkFlag?: boolean | null;
    nullableFlag?: boolean | null;
    /** 聚合类型（仅 Doris 聚合模型 Value 列）：SUM/MAX/MIN/REPLACE/REPLACE_IF_NOT_NULL */
    aggType?: string;
    fieldCategory?: string;
    dimensionId?: number | string;
    dimensionName?: string;
    dataClassificationId?: number | string;
    dataClassificationName?: string;
    dataGrade?: string;
    codeTableId?: number | string;
    codeTableName?: string;
    uniqueFlag?: boolean | null;
    indicatorId?: number | string;
    atomicIndicatorName?: string;
    businessCondition?: string;
    statisticsPeriod?: string;
    sourceModelName?: string;
  }

  /** Doris 物理建模扩展配置 */
  export interface DorisExt {
    tableModel?: string; // DUPLICATE/UNIQUE/AGGREGATE
    keyColumns?: string[];
    partitionMode?: string; // NONE/RANGE/LIST/AUTO_RANGE/AUTO_LIST
    partitionColumns?: string[];
    partitionExpr?: string;
    distributionType?: string; // HASH/RANDOM
    distributionColumns?: string[];
    bucketMode?: string; // AUTO/FIXED
    bucketCount?: number;
    replicationNum?: number;
    enableMow?: boolean;
    sequenceColumn?: string;
  }

  /** 逻辑模型关系 */
  export interface LogicRelation {
    id?: number | string;
    sourceModelId?: number | string;
    sourceModelName?: string;
    targetModelId?: number | string;
    targetModelName?: string;
    sourceField?: string;
    targetField?: string;
    name?: string;
    relationType?: string;
    description?: string;
  }

  /** 逻辑模型 ER 节点 */
  export interface LogicErNode {
    id?: number | string;
    name?: string;
    code?: string;
    status?: string;
    createMode?: string;
    tableType?: string;
    posX?: null | number;
    posY?: null | number;
    fields?: ModelField[];
  }

  /** 逻辑模型 */
  export interface LogicModel extends ModelHierarchy {
    id?: number | string;
    dataDomainId?: number | string;
    dataDomainName?: string;
    name?: string;
    code?: string;
    createMode?: string; // FORWARD/REVERSE
    status?: string; // DRAFT/PUBLISHED/DISCARDED
    modelStyle?: string; // RELATIONAL/DIMENSIONAL/WIDE/TIME_SERIES/SPATIAL/EVENT_STREAM
    /** 由 layerId 派生的分层编码，只读 */
    warehouseLayer?: string;
    /** 分类确认状态：UNCLASSIFIED 待分类 / CONFIRMED 已确认，由后端派生 */
    classificationStatus?: string;
    description?: string;
    /** 完整表名大小写：1 大写，2 小写 */
    tableCase?: number;
    /** 标准类型 */
    documentType?: string;
    /** 标准登记编号 */
    documentId?: number | string;
    /** 责任人用户编号 */
    contactUserId?: number | string;
    /** 责任人联系电话 */
    contactNumber?: string;
    /** 是否启用：0 禁用，1 启用。与设计状态 status 分开 */
    enabled?: number;
    /** 备注 */
    remark?: string;
    creator?: string;
    createTime?: number | string;
    updater?: string;
    updateTime?: number | string;
    tableNameNotation?: string;
    sourceSystemId?: number | string;
    sourceSystemName?: string;
    sourceTableName?: string;
    datasourceId?: number | string;
    dbType?: string;
    dbName?: string;
    schemaName?: string;
    entityId?: number | string;
    entityName?: string;
    objType?: string;
    businessType?: string;
    dataTimeliness?: string;
    statisticsStr?: string;
    grainEntityIds?: (number | string)[];
    ownerName?: string;
    ownerPhone?: string;
    creatorName?: string;
    // 逆向建模入参
    databaseId?: number | string;
    tableName?: string;
    fields?: ModelField[];
  }

  /**
   * 表类型专属配置：五段只有与模型 tableType 对应的那一段生效。
   * 字段引用统一用字段名，与当前设计 fields 的 fieldName 一致。
   */
  export interface ModelTypeConfig {
    source?: {
      /** PHYSICAL / LOGICAL_FLAG / KEEP_EVENT */
      deleteStrategy?: string;
      eventSequenceField?: string;
      /** CURRENT_STATE / PERIODIC_SNAPSHOT / CDC_EVENT */
      ingestMode?: string;
      retentionNote?: string;
      sourceKeyFields?: string[];
      sourceSystem?: string;
      sourceTable?: string;
    };
    dimension?: {
      businessKeyFields?: string[];
      currentFlagField?: string;
      effectiveFromField?: string;
      effectiveToField?: string;
      /** FIXED / OVERWRITE / SCD2 */
      historyStrategy?: string;
      /** UNKNOWN_ROW / NULL / REJECT */
      unknownValueStrategy?: string;
    };
    detail?: {
      businessTimeField?: string;
      dedupFields?: string[];
      grainDescription?: string;
      grainFields?: string[];
      /** TRANSACTION / PERIODIC_SNAPSHOT / ACCUMULATING_SNAPSHOT */
      lateArrivalStrategy?: string;
      /** TRANSACTION / PERIODIC_SNAPSHOT / ACCUMULATING_SNAPSHOT */
      organizationMode?: string;
    };
    summary?: {
      businessTimeField?: string;
      filterCondition?: string;
      /** DAY / WEEK / MONTH / QUARTER / YEAR / ROLLING_WINDOW / NONE */
      grainFields?: string[];
      inputModelIds?: (number | string)[];
      measureDefinition?: string;
      rollingWindowNote?: string;
      staleGroupStrategy?: string;
      timeGranularity?: string;
      /** OVERWRITE_RANGE / UPSERT_BY_KEY / APPEND_INCREMENT */
      writeMode?: string;
    };
    application?: {
      compatibilityNote?: string;
      consumers?: string[];
      consumerScene?: string;
      grainDescription?: string;
      refreshRequirement?: string;
    };
  }

  /** 物理模型部署记录：一次「把指定版本落到目标库」的执行 */

  /** 数据源精简信息 */
  export interface DatabaseSimple {
    id?: number | string;
    name?: string;
    productType?: string;
    databaseType?: number | string;
    databaseTypeName?: string;
    categoryCode?: string;
    iconPath?: string;
    schema?: string;
    /** 主机:端口，不含账号和库名 */
    host?: string;
  }
}

/**
 * 部署已发布版本到目标数据源：建表并核验实库结构
 *
 * requestKey 是幂等键，重试务必传同一个值，否则可能对实库重复变更。
 */

/** 某模型的部署记录列表 */

/** 数据源精简列表 */
export function getModelDatabaseList() {
  return requestClient.get<ModelingModelApi.DatabaseSimple[]>(
    '/modeling/logic-model/database-list',
  );
}

/** 数据源下的表名列表 */
export function getModelTableList(databaseId: number | string) {
  return requestClient.get<string[]>('/modeling/logic-model/table-list', {
    params: { databaseId },
  });
}

/** 逆向建模：预览源表字段，确认前可再改 */
export function getModelImportFields(
  databaseId: number | string,
  tableName: string,
) {
  return requestClient.get<ModelingModelApi.ModelField[]>(
    '/modeling/logic-model/import-fields',
    { params: { databaseId, tableName } },
  );
}
