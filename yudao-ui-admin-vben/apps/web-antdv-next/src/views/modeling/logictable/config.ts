import type { ModelingLogicTableApi } from '#/api/modeling/logictable';

import adsIcon from './assets/ads-application-table.svg';
import dimensionIcon from './assets/dimension-logic-table.svg';
import factIcon from './assets/fact-logic-table.svg';
import sourceIcon from './assets/source-table.svg';
import summaryIcon from './assets/summary-logic-table.svg';

export type LogicTableKind =
  | 'application'
  | 'dimension'
  | 'fact'
  | 'source'
  | 'summary';

export type ColumnKey =
  | 'action'
  | 'belong'
  | 'businessType'
  | 'columnCount'
  | 'createTime'
  | 'id'
  | 'info'
  | 'objType'
  | 'owner'
  | 'selection'
  | 'source'
  | 'status'
  | 'timelinessGranularity'
  | 'timelinessRelation'
  | 'updateTime';

export interface LogicColumn {
  key: ColumnKey;
  label: string;
  /** 固定宽度；未设置时按 minWidth 参与剩余宽度分配（与 el-table fit 一致）。 */
  width?: number;
  minWidth?: number;
  align: 'center' | 'left';
  sortField?: 'columnCount' | 'createTime' | 'id' | 'updateTime';
  fixedRight?: boolean;
  tip?: string;
}

export type SearchKey =
  | 'businessType'
  | 'dataTimeliness'
  | 'enabled'
  | 'name'
  | 'owner'
  | 'sourceSystem';

export interface SearchItem {
  key: SearchKey;
  label: string;
  placeholder: string;
}

/** 负责人列取值：创建人 + 联系电话，或责任人姓名 + 电话。 */
export type OwnerMode = 'creator' | 'owner';

export interface LogicTablePageConfig {
  kind: LogicTableKind;
  tableType: ModelingLogicTableApi.TableType;
  label: string;
  tip: { content: string; subtitle: string };
  treeRoot: string;
  treePlaceholder: string;
  infoIcon: string;
  /** 标题括号样式：贴源为全角“（）”，其余为半角“ ()”。 */
  fullWidthTitle: boolean;
  addLabel: string;
  /** 批量删除按钮图标：贴源使用 Element 删除图标，其余使用灰色删除字形。 */
  batchDeleteIcon: 'delete' | 'deleteGray';
  ownerMode: OwnerMode;
  /** 状态切换：贴源直接发布/取消发布，汇总带“操作成功”确认，其余带启用/禁用确认。 */
  statusMode: 'confirm' | 'direct' | 'summary';
  enabledOptions: Array<{ label: string; value: number }>;
  search: SearchItem[];
  columns: LogicColumn[];
}

const ENABLE_OPTIONS = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
];

const OWNER_COLUMN: LogicColumn = {
  key: 'owner',
  label: '责任人',
  width: 125,
  align: 'left',
};

const ACTION_COLUMN: LogicColumn = {
  key: 'action',
  label: '操作',
  width: 240,
  align: 'center',
  fixedRight: true,
};

function modelColumns(
  infoLabel: string,
  typeColumn: LogicColumn | undefined,
  statusTip: string,
): LogicColumn[] {
  return [
    { key: 'selection', label: '', width: 55, align: 'center' },
    { key: 'id', label: '编号', width: 60, align: 'center', sortField: 'id' },
    { key: 'info', label: infoLabel, width: 450, align: 'left' },
    ...(typeColumn ? [typeColumn] : []),
    { key: 'belong', label: '归属信息', width: 170, align: 'left' },
    {
      key: 'timelinessRelation',
      label: '统计时效/关联业务对象',
      width: 200,
      align: 'left',
    },
    {
      key: 'columnCount',
      label: '字段数',
      width: 80,
      align: 'center',
      sortField: 'columnCount',
    },
    {
      key: 'status',
      label: '状态',
      width: 90,
      align: 'center',
      tip: statusTip,
    },
    OWNER_COLUMN,
    ACTION_COLUMN,
  ];
}

export const LOGIC_TABLE_PAGES: Record<LogicTableKind, LogicTablePageConfig> = {
  source: {
    kind: 'source',
    tableType: 'SOURCE',
    label: '贴源逻辑表',
    tip: {
      subtitle: '保留源端结构，统一承接原始数据',
      content:
        '用于承接源系统表结构和原始数据，尽量保持与源表字段、数据类型及业务语义一致，形成数仓中的标准化源数据入口，为后续维度、事实及汇总模型建设提供基础数据来源。',
    },
    treeRoot: '贴源逻辑表类目',
    treePlaceholder: '请输入来源系统/数据源名称/库名',
    infoIcon: sourceIcon,
    fullWidthTitle: true,
    addLabel: '从原表创建',
    batchDeleteIcon: 'delete',
    ownerMode: 'creator',
    statusMode: 'direct',
    enabledOptions: [
      { label: '已发布', value: 1 },
      { label: '未发布', value: 0 },
    ],
    search: [
      { key: 'name', label: '表名称', placeholder: '请输入表名或ODS表名' },
      { key: 'sourceSystem', label: '来源系统', placeholder: '请选择来源系统' },
      { key: 'enabled', label: '状态', placeholder: '请选择状态' },
    ],
    columns: [
      { key: 'selection', label: '', width: 50, align: 'center' },
      { key: 'id', label: '编号', width: 60, align: 'center', sortField: 'id' },
      { key: 'info', label: '贴源逻辑表信息', minWidth: 320, align: 'left' },
      { key: 'source', label: '来源信息', minWidth: 220, align: 'left' },
      { key: 'columnCount', label: '字段数', width: 80, align: 'center' },
      { key: 'status', label: '模型状态', width: 100, align: 'center' },
      { key: 'owner', label: '负责人', width: 150, align: 'left' },
      {
        key: 'updateTime',
        label: '更新时间',
        width: 155,
        align: 'left',
        sortField: 'updateTime',
      },
      {
        key: 'createTime',
        label: '创建时间',
        width: 155,
        align: 'left',
        sortField: 'createTime',
      },
      ACTION_COLUMN,
    ],
  },
  dimension: {
    kind: 'dimension',
    tableType: 'DIMENSION',
    label: '维度逻辑表',
    tip: {
      subtitle: '统一沉淀业务分析维度',
      content:
        '用于描述客户、商品、组织、地区等业务对象的属性信息，统一管理维度字段及数据来源，为事实数据关联、指标统计和多维分析提供一致的维度基础。',
    },
    treeRoot: '维度逻辑表类目',
    treePlaceholder: '请输入业务板块/主题域',
    infoIcon: dimensionIcon,
    fullWidthTitle: false,
    addLabel: '新增',
    batchDeleteIcon: 'deleteGray',
    ownerMode: 'owner',
    statusMode: 'confirm',
    enabledOptions: ENABLE_OPTIONS,
    search: [
      { key: 'name', label: '表名称', placeholder: '请输入表名称' },
      { key: 'owner', label: '责任人', placeholder: '请选择责任人' },
      {
        key: 'dataTimeliness',
        label: '数据时效',
        placeholder: '请选择数据时效',
      },
      { key: 'enabled', label: '状态', placeholder: '请选择状态' },
    ],
    columns: modelColumns(
      '维度逻辑表信息',
      { key: 'objType', label: '维度类型', width: 100, align: 'left' },
      '启用后可被事实表、汇总表等模型引用；停用前需确认当前的维度表未被其他模型依赖。',
    ),
  },
  fact: {
    kind: 'fact',
    tableType: 'DETAIL',
    label: '事实逻辑表',
    tip: {
      subtitle: '沉淀业务活动发生的明细事实',
      content:
        '用于记录订单、支付、退款等业务事件或业务流程产生的明细数据，统一管理业务主键、维度、度量和时间等字段，为汇总统计及指标计算提供明细数据基础。',
    },
    treeRoot: '事实逻辑表类目',
    treePlaceholder: '请输入业务板块/主题域',
    infoIcon: factIcon,
    fullWidthTitle: false,
    addLabel: '新增',
    batchDeleteIcon: 'deleteGray',
    ownerMode: 'owner',
    statusMode: 'confirm',
    enabledOptions: ENABLE_OPTIONS,
    search: [
      { key: 'name', label: '表名称', placeholder: '请输入表名称' },
      { key: 'owner', label: '责任人', placeholder: '请选择责任人' },
      { key: 'businessType', label: '业务类型', placeholder: '请选择业务类型' },
      {
        key: 'dataTimeliness',
        label: '数据时效',
        placeholder: '请选择数据时效',
      },
      { key: 'enabled', label: '状态', placeholder: '请选择状态' },
    ],
    columns: modelColumns(
      '事实逻辑表信息',
      { key: 'businessType', label: '业务类型', width: 100, align: 'left' },
      '启用后可被维度表、汇总表等模型引用；停用前需确认当前的事实表未被其他模型依赖。',
    ),
  },
  summary: {
    kind: 'summary',
    tableType: 'SUMMARY',
    label: '汇总逻辑表',
    tip: {
      subtitle: '统一沉淀业务分析维度',
      content:
        '用于定义客户、商品、组织、地区等业务分析维度，统一管理维度属性及数据来源，为事实数据关联、指标统计和多维分析提供一致的维度基础。',
    },
    treeRoot: '汇总逻辑表类目',
    treePlaceholder: '请输入业务板块/主题域',
    infoIcon: summaryIcon,
    fullWidthTitle: false,
    addLabel: '新增',
    batchDeleteIcon: 'deleteGray',
    ownerMode: 'creator',
    statusMode: 'summary',
    enabledOptions: [
      { label: '禁用', value: 0 },
      { label: '启用', value: 1 },
    ],
    search: [
      { key: 'name', label: '表名称', placeholder: '请输入表名称' },
      {
        key: 'dataTimeliness',
        label: '统计时效',
        placeholder: '请选择统计时效',
      },
      { key: 'enabled', label: '状态', placeholder: '请选择状态' },
    ],
    columns: [
      { key: 'selection', label: '', width: 55, align: 'center' },
      { key: 'id', label: '编号', width: 60, align: 'center', sortField: 'id' },
      { key: 'info', label: '汇总逻辑表信息', width: 450, align: 'left' },
      { key: 'belong', label: '归属信息', width: 200, align: 'left' },
      {
        key: 'timelinessGranularity',
        label: '统计时效/统计粒度',
        width: 200,
        align: 'left',
      },
      {
        key: 'columnCount',
        label: '字段数',
        width: 80,
        align: 'center',
        sortField: 'columnCount',
      },
      { key: 'status', label: '状态', minWidth: 80, align: 'center' },
      { key: 'owner', label: '责任人', width: 170, align: 'left' },
      {
        key: 'createTime',
        label: '创建时间',
        width: 150,
        align: 'center',
        sortField: 'createTime',
      },
      ACTION_COLUMN,
    ],
  },
  application: {
    kind: 'application',
    tableType: 'APPLICATION',
    label: 'ADS应用表',
    tip: {
      subtitle: '面向具体数据应用组织结果数据',
      content:
        '基于已建设的字段、指标、数据服务等应用成果，按照具体业务场景组织面向报表、看板、数据服务等应用所需的结果数据集，具有较强的应用属性和价值。',
    },
    treeRoot: 'ADS应用表类目',
    treePlaceholder: '请输入业务板块/主题域',
    infoIcon: adsIcon,
    fullWidthTitle: false,
    addLabel: '新增',
    batchDeleteIcon: 'deleteGray',
    ownerMode: 'owner',
    statusMode: 'confirm',
    enabledOptions: ENABLE_OPTIONS,
    search: [
      { key: 'name', label: '表名称', placeholder: '请输入表名称' },
      { key: 'owner', label: '责任人', placeholder: '请选择责任人' },
      { key: 'enabled', label: '状态', placeholder: '请选择状态' },
    ],
    columns: [
      { key: 'selection', label: '', width: 55, align: 'center' },
      { key: 'id', label: '编号', width: 60, align: 'center', sortField: 'id' },
      { key: 'info', label: 'ADS应用表信息', width: 450, align: 'left' },
      { key: 'belong', label: '归属信息', width: 170, align: 'left' },
      {
        key: 'columnCount',
        label: '字段数',
        width: 80,
        align: 'center',
        sortField: 'columnCount',
      },
      { key: 'status', label: '状态', width: 75, align: 'center' },
      OWNER_COLUMN,
      ACTION_COLUMN,
    ],
  },
};
