import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SystemUserApi } from '#/api/system/user';

import { shallowRef } from 'vue';

import { handleTree } from '@vben/utils';

import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import { getDwLayerList } from '#/api/modeling/dwlayer';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import { getSimpleUserList } from '#/api/system/user';

/** 用户缓存（创建人/负责人昵称展示用） */
const userList = shallowRef<SystemUserApi.User[]>([]);
getSimpleUserList().then((data) => {
  userList.value = data;
});

/** 主题域/数据域码缓存：列表 VO 常无 code，按 id 补「名 (码)」 */
const subjectDomainList = shallowRef<
  { code?: string; id?: number | string; name?: string }[]
>([]);
const dataDomainList = shallowRef<
  { code?: string; id?: number | string; name?: string }[]
>([]);
getSubjectDomainList({}).then((list) => {
  subjectDomainList.value = list ?? [];
});
getDataDomainSimpleList().then((list) => {
  dataDomainList.value = list ?? [];
});

function formatHierarchySegment(name?: null | string, code?: null | string) {
  const n = (name || '').trim();
  const c = (code || '').trim();
  if (!n && !c) return '';
  if (n && c) return `${n} (${c})`;
  return n || c;
}

/**
 * 归属层级：分层(码) / 主题域(=业务域)(码) / 数据域(码)
 * 禁止直接展示后端 hierarchyPath（「数据域：…｜技术分层：…」文案）
 */
export function formatBelongingHierarchy(row?: {
  dataDomainId?: null | number | string;
  dataDomainName?: null | string;
  layerCode?: null | string;
  layerName?: null | string;
  subjectDomainId?: null | number | string;
  subjectDomainName?: null | string;
}) {
  if (!row) return '-';
  const subject = subjectDomainList.value.find(
    (item) =>
      row.subjectDomainId !== undefined &&
      row.subjectDomainId !== null &&
      String(item.id) === String(row.subjectDomainId),
  );
  const dataDomain = dataDomainList.value.find(
    (item) =>
      row.dataDomainId !== undefined &&
      row.dataDomainId !== null &&
      String(item.id) === String(row.dataDomainId),
  );
  const parts = [
    formatHierarchySegment(row.layerName, row.layerCode),
    formatHierarchySegment(
      row.subjectDomainName || subject?.name,
      subject?.code,
    ),
    formatHierarchySegment(
      row.dataDomainName || dataDomain?.name,
      dataDomain?.code,
    ),
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(' / ') : '-';
}

export function getUserNickname(userId?: null | number | string) {
  if (userId === null || userId === undefined || userId === '') {
    return '-';
  }
  const user = userList.value.find(
    (item) => String(item.id) === String(userId),
  );
  return user?.nickname || user?.username || String(userId);
}

/** 创建人手机号（simple-user）；无则返回空串供列表副行判断 */
export function getUserMobile(userId?: null | number | string) {
  if (userId === null || userId === undefined || userId === '') {
    return '';
  }
  const user = userList.value.find(
    (item) => String(item.id) === String(userId),
  );
  return (user?.mobile || '').trim();
}

/** 模型状态（逻辑/物理） */
export const MODEL_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT', color: 'warning' },
  { label: '已发布', value: 'PUBLISHED', color: 'success' },
  { label: '已废弃', value: 'DISCARDED', color: 'default' },
];

/** 建模风格（三正交维度之一） */
export const MODEL_STYLE_OPTIONS = [
  { label: '关系建模', value: 'RELATIONAL' },
  { label: '维度建模', value: 'DIMENSIONAL' },
  { label: '宽表', value: 'WIDE' },
  { label: '时序', value: 'TIME_SERIES' },
  { label: '空间', value: 'SPATIAL' },
  { label: '事件流', value: 'EVENT_STREAM' },
];

/** 数仓分层编码（后端派生字段 warehouseLayer 的展示口径，归属以 layerId 为准） */
export const WAREHOUSE_LAYER_OPTIONS = [
  { label: '无', value: 'NONE' },
  { label: 'ODS 贴源层', value: 'ODS' },
  { label: 'DWD 明细层', value: 'DWD' },
  { label: 'DIM 维度层', value: 'DIM' },
  { label: 'DWS 汇总层', value: 'DWS' },
  { label: 'ADS 应用层', value: 'ADS' },
];

/** 表类型，与数仓分层的 default_table_type 同一套枚举 */
export const TABLE_TYPE_OPTIONS = [
  { label: '贴源表', value: 'SOURCE', color: 'blue' },
  { label: '明细表', value: 'DETAIL', color: 'cyan' },
  { label: '维度表', value: 'DIMENSION', color: 'purple' },
  { label: '汇总表', value: 'SUMMARY', color: 'gold' },
  { label: '应用表', value: 'APPLICATION', color: 'green' },
];

/**
 * 分层下拉数据：分类节点作为分组保留但不可选，只能选实际分层节点。
 * 归属打通后模型的 layerId 必须指向 LAYER 节点。
 * 统一补一个 name 字段，让下拉组件的 fieldNames 与其他规划选项保持一致。
 */
export async function getLayerTreeOptions() {
  const data = await getDwLayerList({});
  return handleTree(
    data.map((item) => ({
      ...item,
      name: item.layerCode
        ? `${item.layerCode} ${item.layerName}`
        : item.layerName,
      selectable: item.nodeType === 'LAYER',
    })),
  );
}

/** 分析主题下拉数据（多级树） */
export async function getSubjectDomainTreeOptions() {
  const data = await getSubjectDomainList({});
  return handleTree(data as any[]);
}

/** Doris 表模型 */
export const DORIS_TABLE_MODEL_OPTIONS = [
  { label: '明细模型（DUPLICATE）', value: 'DUPLICATE' },
  { label: '主键模型（UNIQUE）', value: 'UNIQUE' },
  { label: '聚合模型（AGGREGATE）', value: 'AGGREGATE' },
];

/** Doris 分区模式 */
export const DORIS_PARTITION_OPTIONS = [
  { label: '不分区', value: 'NONE' },
  { label: 'RANGE 手工分区', value: 'RANGE' },
  { label: 'LIST 手工分区', value: 'LIST' },
  { label: 'AUTO RANGE 自动分区', value: 'AUTO_RANGE' },
  { label: 'AUTO LIST 自动分区', value: 'AUTO_LIST' },
];

/** Doris 分桶方式 */
export const DORIS_DISTRIBUTION_OPTIONS = [
  { label: 'HASH 分桶', value: 'HASH' },
  { label: 'RANDOM 分桶（仅明细模型）', value: 'RANDOM' },
];

/** Doris 聚合类型（聚合模型 Value 列） */
export const DORIS_AGG_TYPE_OPTIONS = [
  'SUM',
  'MAX',
  'MIN',
  'REPLACE',
  'REPLACE_IF_NOT_NULL',
].map((type) => ({ label: type, value: type }));

/** 字段数据类型 */
export const DATA_TYPE_OPTIONS = [
  'varchar',
  'char',
  'text',
  'int',
  'bigint',
  'smallint',
  'decimal',
  'numeric',
  'double',
  'float',
  'boolean',
  'date',
  'timestamp',
].map((type) => ({ label: type, value: type }));

/**
 * 列表通用归属筛选项：数据域 / 分析主题 / 表类型。
 * 数仓分层由左侧树承担，不在搜索区重复出现。
 */
export function useHierarchyFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'dataDomainId',
      label: '数据域',
      component: 'ApiSelect',
      componentProps: {
        api: getDataDomainSimpleList,
        labelField: 'name',
        valueField: 'id',
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
        placeholder: '请选择数据域',
      },
    },
    {
      fieldName: 'subjectDomainId',
      label: '分析主题',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: getSubjectDomainTreeOptions,
        childrenField: 'children',
        labelField: 'name',
        placeholder: '请选择分析主题',
        treeDefaultExpandAll: true,
        valueField: 'id',
      },
    },
    {
      fieldName: 'tableType',
      label: '表类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: TABLE_TYPE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择表类型',
      },
    },
  ];
}

/**
 * 部署状态：实库结构层面的状态，与设计状态（草稿/已发布）是两回事。
 * 「已发布」只代表设计固化，不代表实库已经有表。
 */
export const DEPLOY_STATUS_OPTIONS = [
  { label: '未部署', value: 'NOT_DEPLOYED', color: 'default' },
  { label: '执行中', value: 'RUNNING', color: 'processing' },
  { label: '已部署', value: 'SUCCEEDED', color: 'success' },
  { label: '部署失败', value: 'FAILED', color: 'error' },
  { label: '结果未知', value: 'UNKNOWN', color: 'warning' },
];

/** 部署记录的执行状态，比模型上的部署状态多一个核验中 */
export const DEPLOY_RECORD_STATUS_OPTIONS = [
  { label: '排队中', value: 'QUEUED', color: 'default' },
  { label: '执行中', value: 'RUNNING', color: 'processing' },
  { label: '核验中', value: 'VERIFYING', color: 'processing' },
  { label: '成功', value: 'SUCCEEDED', color: 'success' },
  { label: '失败', value: 'FAILED', color: 'error' },
  { label: '结果未知', value: 'UNKNOWN', color: 'warning' },
];

// ==================== 表类型专属配置枚举（与后端 ModelTypeConfigValidator 一致） ====================

export const TYPE_CONFIG_INGEST_MODE_OPTIONS = [
  { label: '当前状态', value: 'CURRENT_STATE' },
  { label: '周期全量快照', value: 'PERIODIC_SNAPSHOT' },
  { label: '增量事件日志', value: 'CDC_EVENT' },
];

export const TYPE_CONFIG_DELETE_STRATEGY_OPTIONS = [
  { label: '物理删除', value: 'PHYSICAL' },
  { label: '逻辑删除标识', value: 'LOGICAL_FLAG' },
  { label: '保留删除事件', value: 'KEEP_EVENT' },
];

export const TYPE_CONFIG_HISTORY_STRATEGY_OPTIONS = [
  { label: '固定值', value: 'FIXED' },
  { label: '覆盖最新值', value: 'OVERWRITE' },
  { label: '保留历史版本', value: 'SCD2' },
];

export const TYPE_CONFIG_UNKNOWN_VALUE_OPTIONS = [
  { label: '预置未知维度行', value: 'UNKNOWN_ROW' },
  { label: '保留空值', value: 'NULL' },
  { label: '拒绝入库', value: 'REJECT' },
];

export const TYPE_CONFIG_ORGANIZATION_MODE_OPTIONS = [
  { label: '事务 / 事件明细', value: 'TRANSACTION' },
  { label: '周期快照', value: 'PERIODIC_SNAPSHOT' },
  { label: '累积快照', value: 'ACCUMULATING_SNAPSHOT' },
];

export const TYPE_CONFIG_LATE_ARRIVAL_OPTIONS = [
  { label: '覆盖', value: 'OVERWRITE' },
  { label: '追加更正记录', value: 'APPEND_CORRECTION' },
  { label: '忽略', value: 'IGNORE' },
];

export const TYPE_CONFIG_TIME_GRANULARITY_OPTIONS = [
  { label: '自然日', value: 'DAY' },
  { label: '自然周', value: 'WEEK' },
  { label: '自然月', value: 'MONTH' },
  { label: '季度', value: 'QUARTER' },
  { label: '年', value: 'YEAR' },
  { label: '滚动窗口', value: 'ROLLING_WINDOW' },
  { label: '无时间维度', value: 'NONE' },
];

export const TYPE_CONFIG_WRITE_MODE_OPTIONS = [
  { label: '覆盖指定范围', value: 'OVERWRITE_RANGE' },
  { label: '按键更新', value: 'UPSERT_BY_KEY' },
  { label: '事件增量累加', value: 'APPEND_INCREMENT' },
];

export const TYPE_CONFIG_STALE_GROUP_OPTIONS = [
  { label: '删除已消失分组', value: 'DELETE' },
  { label: '保留', value: 'KEEP' },
  { label: '标记失效', value: 'MARK' },
];

/** 保存时的归属评估快照；规划停用等变化需使用实时归属检查 */
export const CLASSIFICATION_STATUS_OPTIONS = [
  { label: '归属不完备', value: 'UNCLASSIFIED', color: 'warning' },
  { label: '归属完备（保存时）', value: 'CONFIRMED', color: 'success' },
];

/**
 * 分类确认状态筛选项，仅逻辑/物理模型有该状态。
 * 归属改为发布时才强制补齐，存量与逆向接入的模型靠这个筛选项定位。
 */
export function useClassificationFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'classificationStatus',
      label: '保存时归属',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: CLASSIFICATION_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择分类状态',
      },
    },
  ];
}

/** 列表通用归属列：业务/技术归属摘要 + 表类型 */
export function useHierarchyGridColumns<T = any>(): NonNullable<
  VxeTableGridOptions<T>['columns']
> {
  return [
    {
      field: 'hierarchyPath',
      title: '业务 / 技术归属',
      minWidth: 280,
      align: 'left',
      className: 'whitespace-normal break-words leading-5',
      showOverflow: false,
      // 归属没填时明确显示归属不完备，避免与「查不到分层名称」混淆
      formatter: ({ cellValue, row }: any) =>
        cellValue ||
        (row?.classificationStatus === 'UNCLASSIFIED' ? '归属不完备' : '-'),
    },
    {
      field: 'tableType',
      title: '表类型',
      width: 90,
      formatter: ({ cellValue }: any) =>
        optionLabel(TABLE_TYPE_OPTIONS, cellValue),
    },
  ];
}

export function optionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

export function optionColor(
  options: { color?: string; label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.color ?? 'default';
}
