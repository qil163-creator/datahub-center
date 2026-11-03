/**
 * 在线填报领域模型（与表格引擎无关）。
 *
 * 铁律：业务层只认 rowId + fieldCode，A1/行号只是显示坐标。
 * 后续协同层、草稿存储、公式引擎都基于这套类型，替换表格引擎不影响本文件。
 */

/** 模板字段（对应模板版本 fieldSchema 中的一列） */
export interface FillFieldSchema {
  /** 永久不可变字段码，操作日志/公式/校验只引用它 */
  fieldCode: string;
  /** 列显示名（表头） */
  name: string;
  fieldType: 'date' | 'decimal' | 'dict' | 'integer' | 'string';
  required?: boolean;
  /** false = 公式列/系统列，填报人不可编辑 */
  editable: boolean;
  /** fieldType=dict 时的字典选项 */
  dictOptions?: string[];
  /** 小数位数（fieldType=decimal） */
  scale?: number;
  /** 列宽（px） */
  width?: number;
  /** 服务端权威公式的 fieldCode 表达式（仅用于前端即时显示重算） */
  formula?: string;
}

/** 一行草稿数据 */
export interface FillRowData {
  /** 稳定行标识，由服务端（或受控客户端）生成，排序/插删不改变 */
  rowId: string;
  data: Record<string, unknown>;
}

/** 领域操作：协同协议与操作日志的最小单元 */
export type DomainOperation =
  | {
      /** 插入位置的前一行；undefined 表示插在首行前或追加 */
      afterRowId?: string;
      /** 初始行数据（远端 ADD_ROW 操作回放时携带） */
      data?: Record<string, unknown>;
      rowId: string;
      type: 'addRow';
    }
  | {
      fieldCode: string;
      oldValue?: unknown;
      rowId: string;
      type: 'setCell';
      value: unknown;
    }
  | {
      rowId: string;
      type: 'removeRow';
    };

/** 编辑器适配器对外能力（业务页面只依赖这个接口，不接触 Univer） */
export interface SheetEditorAdapter {
  /** 应用远端（其他填报人）已确认的操作，不回发 operation 事件 */
  applyRemoteOperation: (op: DomainOperation) => void;
  dispose: () => void;
  /** 读取当前全部行（按显示顺序） */
  getRows: () => FillRowData[];
  /** 定位并选中单元格 */
  focusCell: (rowId: string, fieldCode?: string) => void;
  /** 更新他人持有的单元格锁，写入时拦截 */
  setForeignLock: (
    rowId: string,
    fieldCode: string | undefined,
    holderUserId?: null | number,
  ) => void;
  setReadonly: (readonly: boolean) => void;
}

export interface SheetEditorOptions {
  container: HTMLElement;
  fields: FillFieldSchema[];
  /** 本地编辑产生领域操作时回调 */
  onOperation: (op: DomainOperation) => void;
  /** 选区变化：用于申请/释放单元格锁 */
  onSelectionChange?: (cell?: { fieldCode: string; rowId: string }) => void;
  readonly?: boolean;
  rows: FillRowData[];
}
