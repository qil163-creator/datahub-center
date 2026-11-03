/** ER 画布字段/属性行 */
export interface ErFieldRow {
  change?: 'ADDED' | 'MODIFIED' | 'REMOVED';
  changeDetail?: string;
  /** 行标识（字段名/属性编码），行级连线的锚点 */
  name: string;
  /** 展示名（中文名） */
  label?: string;
  /** 类型文本（概念属性可为空） */
  type?: string;
  /** 主键/主标识 */
  pk?: boolean;
}

/** ER 画布表/实体节点 */
export interface ErTableNode {
  /** 业务编号（实体 id / 模型 id），字符串化 */
  id: string;
  /** 标题（实体/模型名称） */
  title: string;
  /** 副标题（编码/表名） */
  subtitle?: string;
  /** 状态徽标文本（如：已发布/修订中） */
  badge?: string;
  /** 徽标颜色 */
  badgeColor?: string;
  /** 字段/属性行 */
  rows: ErFieldRow[];
  /** 画布坐标（空表示未布局，渲染时自动布局） */
  x?: null | number;
  y?: null | number;
  /** 原始业务数据 */
  raw?: any;
}

/** ER 画布关系边 */
export interface ErRelationEdge {
  /** 关系编号，字符串化 */
  id: string;
  sourceId: string;
  targetId: string;
  /** 行级锚点（逻辑/物理层字段名；概念层为空表示节点级连接） */
  sourceField?: string;
  targetField?: string;
  /** 基数标签：1:1 / 1:N / N:M */
  label?: string;
  /** 关系名称（如：拥有） */
  name?: string;
  /** 虚拟关系（数仓无强外键约束）以虚线展示 */
  virtual?: boolean;
  /** 原始业务数据 */
  raw?: any;
}

/** 连线完成事件载荷（由父组件确认后落库） */
export interface ErConnectPayload {
  sourceId: string;
  targetId: string;
  sourceField?: string;
  targetField?: string;
}

/** 节点坐标（保存布局用） */
export interface ErNodePosition {
  id: string;
  x: number;
  y: number;
}

/** 关系类型 -> 基数标签 */
export const RELATION_TYPE_LABELS: Record<string, string> = {
  ONE_TO_ONE: '1:1',
  ONE_TO_MANY: '1:N',
  MANY_TO_MANY: 'N:M',
};

/** 关系类型下拉选项 */
export const RELATION_TYPE_OPTIONS = [
  { label: '一对一（1:1）', value: 'ONE_TO_ONE' },
  { label: '一对多（1:N）', value: 'ONE_TO_MANY' },
  { label: '多对多（N:M）', value: 'MANY_TO_MANY' },
];
