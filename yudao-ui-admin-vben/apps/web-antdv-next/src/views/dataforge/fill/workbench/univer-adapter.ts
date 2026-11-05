/**
 * Univer Sheets 适配器：把 Univer 封装在领域操作协议（rowId + fieldCode）后面。
 *
 * 适配器纪律（单轨选型后的唯一保险）：
 * 1. 所有 Univer API 调用只允许出现在本文件，业务层只依赖 types.ts 的接口；
 * 2. 只使用公开 Facade API 与命令拦截，不 import 任何 @univerjs 内部模块；
 * 3. 锁定/只读一期先用命令拦截实现（最可靠的单一收口点），
 *    正式版验证 Permission API 后可平滑替换，后端校验始终兜底。
 */
import type {
  DomainOperation,
  FillFieldSchema,
  FillRowData,
  SheetEditorAdapter,
  SheetEditorOptions,
} from './types';

import { UniverSheetsCorePreset } from '@univerjs/preset-sheets-core';
import UniverPresetSheetsCoreZhCN from '@univerjs/preset-sheets-core/locales/zh-CN';
import { UniverSheetsDataValidationPreset } from '@univerjs/preset-sheets-data-validation';
import UniverPresetSheetsDataValidationZhCN from '@univerjs/preset-sheets-data-validation/locales/zh-CN';
import { createUniver, LocaleType, mergeLocales } from '@univerjs/presets';

import '@univerjs/preset-sheets-core/lib/index.css';
import '@univerjs/preset-sheets-data-validation/lib/index.css';

/** 数据区之外预留的空行数（供新增行使用） */
const ROW_BUFFER = 100;
/** 表头占用的表格行 */
const HEADER_ROW = 0;

/** 写类 mutation：只读模式或锁定目标时统一在此收口拦截 */
const SET_RANGE_VALUES_MUTATION = 'sheet.mutation.set-range-values';
const INSERT_ROW_MUTATION = 'sheet.mutation.insert-row';
const REMOVE_ROW_MUTATION = 'sheet.mutation.remove-row';

const WRITE_MUTATION_IDS = new Set([
  INSERT_ROW_MUTATION,
  REMOVE_ROW_MUTATION,
  SET_RANGE_VALUES_MUTATION,
  'sheet.mutation.move-rows',
]);

/** 结构性命令：填报人永远不允许（模板结构由模板版本固化） */
const STRUCTURE_BLOCKED_COMMAND_IDS = new Set([
  'sheet.command.copy-sheet',
  'sheet.command.insert-col',
  'sheet.command.insert-col-after',
  'sheet.command.insert-col-before',
  'sheet.command.insert-sheet',
  'sheet.command.move-cols',
  'sheet.command.move-range',
  'sheet.command.remove-col',
  'sheet.command.remove-sheet',
  'sheet.command.set-worksheet-name',
  'sheet.command.set-worksheet-order',
]);

/** 抛出该错误用于取消命令执行（Univer 约定：before 钩子抛错即中止） */
class CommandBlockedError extends Error {
  constructor(reason: string) {
    super(`[fill] 已拦截：${reason}`);
    this.name = 'CommandBlockedError';
  }
}

interface CommandLike {
  id: string;
  params?: unknown;
}

export class UniverSheetAdapter implements SheetEditorAdapter {
  private applyingInternal = false;
  private readonly disposables: Array<{ dispose: () => void }> = [];
  private disposed = false;
  private readonly fieldIndexByCode = new Map<string, number>();
  private readonly fields: FillFieldSchema[];
  /** key = rowId\\0fieldCode，整行锁 fieldCode 为空 */
  private readonly foreignLocks = new Map<string, number>();
  private readonly onOperation: (op: DomainOperation) => void;
  private readonly onSelectionChange?: (cell?: {
    fieldCode: string;
    rowId: string;
  }) => void;
  private readonly: boolean;
  /** 表格数据区第 i 行（sheet 行 i+1）对应的 rowId */
  private rowIds: string[];
  private rowSeq = 0;
  private readonly univer: ReturnType<typeof createUniver>['univer'];
  private readonly univerAPI: ReturnType<typeof createUniver>['univerAPI'];

  constructor(options: SheetEditorOptions) {
    this.fields = options.fields;
    this.onOperation = options.onOperation;
    this.onSelectionChange = options.onSelectionChange;
    this.readonly = options.readonly ?? false;
    this.rowIds = options.rows.map((row) => row.rowId);
    this.fields.forEach((field, index) =>
      this.fieldIndexByCode.set(field.fieldCode, index),
    );

    const { univer, univerAPI } = createUniver({
      locale: LocaleType.ZH_CN,
      locales: {
        [LocaleType.ZH_CN]: mergeLocales(
          UniverPresetSheetsCoreZhCN,
          UniverPresetSheetsDataValidationZhCN,
        ),
      },
      presets: [
        UniverSheetsCorePreset({ container: options.container }),
        UniverSheetsDataValidationPreset(),
      ],
    });
    this.univer = univer;
    this.univerAPI = univerAPI;

    const workbookData = this.buildWorkbookData(options.rows);
    this.univerAPI.createWorkbook(
      workbookData as Parameters<typeof univerAPI.createWorkbook>[0],
    );

    this.installCommandGuards();
    this.installSelectionHooks();
    this.installDictValidations(options.rows.length);
  }

  /** 应用远端（其他填报人）已确认的操作：内部写入不重复发出 operation 事件 */
  applyRemoteOperation(op: DomainOperation): void {
    switch (op.type) {
      case 'addRow': {
        this.applyRemoteAddRow(op.rowId, op.afterRowId, op.data);
        break;
      }
      case 'removeRow': {
        const index = this.rowIds.indexOf(op.rowId);
        const sheet = this.getActiveSheet();
        if (index === -1 || !sheet) return;
        this.runInternal(() => sheet.deleteRows(index + 1, 1));
        this.rowIds.splice(index, 1);
        break;
      }
      case 'setCell': {
        const rowIndex = this.rowIds.indexOf(op.rowId);
        const colIndex = this.fieldIndexByCode.get(op.fieldCode);
        if (rowIndex === -1 || colIndex === undefined) return;
        this.runInternal(() => {
          this.getActiveSheet()
            ?.getRange(rowIndex + 1, colIndex, 1, 1)
            .setValue(op.value as string);
        });
        // 远端输入变化后本地重算展示公式列（服务端仍是权威）
        this.recomputeFormulaCells(rowIndex + 1, [colIndex]);
        break;
      }
    }
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    for (const disposable of this.disposables) {
      try {
        disposable.dispose();
      } catch {
        // Univer 实例销毁时监听器可能已被回收
      }
    }
    this.univer.dispose();
  }

  /** 定位并选中单元格（校验问题点击定位）；fieldCode 为空时选中整行 */
  focusCell(rowId: string, fieldCode?: string): void {
    const sheet = this.getActiveSheet();
    const rowIndex = this.rowIds.indexOf(rowId);
    if (!sheet || rowIndex === -1) {
      return;
    }
    const colIndex = fieldCode
      ? this.fieldIndexByCode.get(fieldCode)
      : undefined;
    const range =
      colIndex === undefined
        ? sheet.getRange(rowIndex + 1, 0, 1, this.fields.length)
        : sheet.getRange(rowIndex + 1, colIndex, 1, 1);
    range.activate();
  }

  getRows(): FillRowData[] {
    const sheet = this.getActiveSheet();
    if (!sheet || this.rowIds.length === 0) return [];
    const values = sheet
      .getRange(1, 0, this.rowIds.length, this.fields.length)
      .getValues();
    return this.rowIds.map((rowId, rowIndex) => {
      const data: Record<string, unknown> = {};
      this.fields.forEach((field, colIndex) => {
        data[field.fieldCode] = values?.[rowIndex]?.[colIndex] ?? null;
      });
      return { data, rowId };
    });
  }

  setForeignLock(
    rowId: string,
    fieldCode: string | undefined,
    holderUserId?: null | number,
  ): void {
    const key = `${rowId}\0${fieldCode ?? ''}`;
    if (holderUserId === undefined || holderUserId === null) {
      this.foreignLocks.delete(key);
      return;
    }
    this.foreignLocks.set(key, holderUserId);
  }

  setReadonly(readonly: boolean): void {
    this.readonly = readonly;
  }

  private applyRemoteAddRow(
    rowId: string,
    afterRowId: string | undefined,
    data: Record<string, unknown> | undefined,
  ): void {
    const sheet = this.getActiveSheet();
    if (!sheet || this.rowIds.includes(rowId)) {
      return;
    }
    // 操作日志不含 afterRowId 时追加到末尾；精确顺序以快照重载（rowOrder）为准
    const afterIndex = afterRowId
      ? this.rowIds.indexOf(afterRowId)
      : this.rowIds.length - 1;
    const insertIndex = afterIndex >= 0 ? afterIndex + 1 : this.rowIds.length;
    const sheetRow = insertIndex + 1;
    this.runInternal(() => {
      sheet.insertRowAfter(sheetRow - 1);
      if (data) {
        for (const [fieldCode, value] of Object.entries(data)) {
          const colIndex = this.fieldIndexByCode.get(fieldCode);
          if (colIndex !== undefined && value !== undefined && value !== null) {
            sheet.getRange(sheetRow, colIndex, 1, 1).setValue(value as string);
          }
        }
      }
    });
    this.rowIds.splice(insertIndex, 0, rowId);
  }

  private buildWorkbookData(rows: FillRowData[]): Record<string, unknown> {
    const cellData: Record<
      number,
      Record<number, Record<string, unknown>>
    > = {};

    const headerCells: Record<number, Record<string, unknown>> = {};
    this.fields.forEach((field, colIndex) => {
      headerCells[colIndex] = {
        s: 'headerStyle',
        v: field.required ? `${field.name} *` : field.name,
      };
    });
    cellData[HEADER_ROW] = headerCells;

    rows.forEach((row, rowIndex) => {
      const rowCells: Record<number, Record<string, unknown>> = {};
      this.fields.forEach((field, colIndex) => {
        const value = row.data[field.fieldCode];
        rowCells[colIndex] = {
          s: field.editable ? undefined : 'lockedStyle',
          v:
            value === undefined || value === null
              ? ''
              : (value as number | string),
        };
      });
      cellData[rowIndex + 1] = rowCells;
    });

    const columnData: Record<number, { w: number }> = {};
    this.fields.forEach((field, colIndex) => {
      columnData[colIndex] = { w: field.width ?? 140 };
    });

    return {
      id: 'dataforge-fill-sheet',
      locale: LocaleType.ZH_CN,
      name: '在线填报',
      sheetOrder: ['data-sheet'],
      sheets: {
        'data-sheet': {
          cellData,
          columnCount: this.fields.length,
          columnData,
          freeze: { startColumn: -1, startRow: 1, xSplit: 0, ySplit: 1 },
          id: 'data-sheet',
          name: '填报数据',
          rowCount: rows.length + ROW_BUFFER,
        },
      },
      styles: {
        headerStyle: {
          bg: { rgb: '#F3F4F6' },
          bl: 1,
          ht: 2,
          vt: 2,
        },
        lockedStyle: {
          bg: { rgb: '#FAFAFA' },
        },
      },
    };
  }

  private emitSelection(): void {
    if (!this.onSelectionChange) {
      return;
    }
    const sheet = this.getActiveSheet() as
      | null
      | (ReturnType<UniverSheetAdapter['getActiveSheet']> & {
          getActiveRange?: () => null | {
            getColumn?: () => number;
            getRow?: () => number;
          };
          getSelection?: () => {
            getActiveRange?: () => null | {
              getColumn?: () => number;
              getRow?: () => number;
            };
          };
        });
    const range =
      sheet?.getSelection?.()?.getActiveRange?.() ?? sheet?.getActiveRange?.();
    const sheetRow = range?.getRow?.();
    const colIndex = range?.getColumn?.();
    if (
      sheetRow === undefined ||
      sheetRow <= HEADER_ROW ||
      colIndex === undefined
    ) {
      this.onSelectionChange(undefined);
      return;
    }
    const rowId = this.rowIds[sheetRow - 1];
    const field = this.fields[colIndex];
    if (!rowId || !field) {
      this.onSelectionChange(undefined);
      return;
    }
    this.onSelectionChange({ fieldCode: field.fieldCode, rowId });
  }

  // ---------------------------------------------------------------- 内部实现

  private emitSetCellOps(cellValue: unknown): void {
    const matrix = cellValue as Record<
      string,
      Record<string, null | { v?: unknown }>
    >;
    if (!matrix) return;
    for (const [rowKey, cols] of Object.entries(matrix)) {
      const sheetRow = Number(rowKey);
      if (sheetRow === HEADER_ROW || !cols) continue;
      const rowIndex = sheetRow - 1;
      let rowId = this.rowIds[rowIndex];
      if (!rowId) {
        // 粘贴/编辑越过现有数据区：为缓冲区行按需分配 rowId（正式版由服务端签发）
        for (let i = this.rowIds.length; i <= rowIndex; i++) {
          const newRowId = this.generateRowId();
          this.rowIds[i] = newRowId;
          this.onOperation({
            afterRowId: this.rowIds[i - 1],
            rowId: newRowId,
            type: 'addRow',
          });
        }
        rowId = this.rowIds[rowIndex]!;
      }
      for (const [colKey, cell] of Object.entries(cols)) {
        const colIndex = Number(colKey);
        const field = this.fields[colIndex];
        if (!field) continue;
        this.onOperation({
          fieldCode: field.fieldCode,
          rowId,
          type: 'setCell',
          value: cell?.v ?? null,
        });
      }
      this.recomputeFormulaCells(sheetRow, Object.keys(cols).map(Number));
    }
  }

  private generateRowId(): string {
    this.rowSeq += 1;
    return `local-${Date.now()}-${this.rowSeq}`;
  }

  private getActiveSheet() {
    return this.univerAPI.getActiveWorkbook()?.getActiveSheet() ?? null;
  }

  private guardBeforeCommand(command: CommandLike): void {
    if (this.applyingInternal || this.disposed) return;

    if (STRUCTURE_BLOCKED_COMMAND_IDS.has(command.id)) {
      throw new CommandBlockedError(
        `模板结构由模板版本固化，禁止执行 ${command.id}`,
      );
    }

    if (this.readonly && WRITE_MUTATION_IDS.has(command.id)) {
      throw new CommandBlockedError('当前为只读状态（已提交或无编辑权限）');
    }

    if (command.id === SET_RANGE_VALUES_MUTATION) {
      this.guardSetRangeValues(command.params);
      return;
    }

    if (
      command.id === INSERT_ROW_MUTATION ||
      command.id === REMOVE_ROW_MUTATION
    ) {
      const range = (command.params as { range?: { startRow: number } })?.range;
      if (range && range.startRow <= HEADER_ROW) {
        throw new CommandBlockedError('表头行不允许插入或删除');
      }
      if (range) {
        const rowId = this.rowIds[range.startRow - 1];
        if (rowId && this.isForeignLocked(rowId)) {
          throw new CommandBlockedError('该行正被其他填报人编辑');
        }
      }
    }
  }

  private guardSetRangeValues(params: unknown): void {
    const matrix = (
      params as { cellValue?: Record<string, Record<string, unknown>> }
    )?.cellValue;
    if (!matrix) return;
    for (const [rowKey, cols] of Object.entries(matrix)) {
      if (Number(rowKey) === HEADER_ROW) {
        throw new CommandBlockedError('表头不允许编辑');
      }
      if (!cols) continue;
      for (const colKey of Object.keys(cols)) {
        const field = this.fields[Number(colKey)];
        if (!field) {
          throw new CommandBlockedError('禁止在模板列之外输入数据');
        }
        if (!field.editable) {
          throw new CommandBlockedError(`"${field.name}"为公式/系统列，只读`);
        }
        const rowId = this.rowIds[Number(rowKey) - 1];
        if (rowId && this.isForeignLocked(rowId, field.fieldCode)) {
          throw new CommandBlockedError('该单元格正被其他填报人编辑');
        }
      }
    }
  }

  private handleCommandExecuted(command: CommandLike): void {
    if (this.applyingInternal || this.disposed) return;

    switch (command.id) {
      case INSERT_ROW_MUTATION: {
        const range = (
          command.params as { range?: { endRow: number; startRow: number } }
        )?.range;
        if (!range || range.startRow <= HEADER_ROW) return;
        const insertAt = range.startRow - 1;
        const count = range.endRow - range.startRow + 1;
        for (let i = 0; i < count; i++) {
          const rowId = this.generateRowId();
          this.rowIds.splice(insertAt + i, 0, rowId);
          this.onOperation({
            afterRowId:
              insertAt + i > 0 ? this.rowIds[insertAt + i - 1] : undefined,
            rowId,
            type: 'addRow',
          });
        }
        break;
      }
      case REMOVE_ROW_MUTATION: {
        const range = (
          command.params as { range?: { endRow: number; startRow: number } }
        )?.range;
        if (!range || range.startRow <= HEADER_ROW) return;
        const removeAt = range.startRow - 1;
        const count = range.endRow - range.startRow + 1;
        const removed = this.rowIds.splice(removeAt, count);
        for (const rowId of removed) {
          if (rowId) this.onOperation({ rowId, type: 'removeRow' });
        }
        break;
      }
      case SET_RANGE_VALUES_MUTATION: {
        this.emitSetCellOps(
          (command.params as { cellValue?: unknown })?.cellValue,
        );
        this.emitSelection();
        break;
      }
      default: {
        if (command.id.includes('selection')) {
          this.emitSelection();
        }
      }
    }
  }

  private installCommandGuards(): void {
    this.disposables.push(
      this.univerAPI.onBeforeCommandExecute((command) =>
        this.guardBeforeCommand(command as CommandLike),
      ),
      this.univerAPI.onCommandExecuted((command) =>
        this.handleCommandExecuted(command as CommandLike),
      ),
    );
  }

  /** 为字典列设置"列表内取值"数据验证（下拉选择） */
  private installDictValidations(dataRowCount: number): void {
    const sheet = this.getActiveSheet();
    if (!sheet) return;
    this.fields.forEach((field, colIndex) => {
      if (field.fieldType !== 'dict' || !field.dictOptions?.length) return;
      const range = sheet.getRange(1, colIndex, dataRowCount + ROW_BUFFER, 1);
      const rule = this.univerAPI
        .newDataValidation()
        .requireValueInList(field.dictOptions, false, true)
        .setOptions({
          error: `请从字典中选择：${field.dictOptions.join(' / ')}`,
          showErrorMessage: true,
        })
        .build();
      range.setDataValidation(rule);
    });
  }

  /**
   * 尝试订阅 Univer 选区/编辑事件。Facade 未暴露 CellEditStarted 时回退到命令 id 含 selection 的拦截。
   */
  private installSelectionHooks(): void {
    // Facade 版本未公开这些事件字段；运行时能力探测后才订阅。
    const api = this.univerAPI as unknown as {
      addEvent?: (
        event: unknown,
        handler: (payload: unknown) => void,
      ) => { dispose?: () => void };
      Event?: Record<string, unknown>;
    };
    const Event = api.Event;
    if (typeof api.addEvent !== 'function' || !Event) {
      return;
    }
    const eventIds = [
      Event.SelectionChanged,
      Event.SelectionChange,
      Event.SheetEditStarted,
      Event.CellEditStarted,
      Event.BeforeSheetEditStart,
    ].filter((item) => item !== undefined);
    for (const eventId of eventIds) {
      try {
        const disposable = api.addEvent(eventId, () => this.emitSelection());
        if (disposable?.dispose) {
          this.disposables.push(disposable as { dispose: () => void });
        }
      } catch {
        // 当前 Univer 版本可能没有该事件
      }
    }
  }

  private isForeignLocked(rowId: string, fieldCode?: string) {
    return (
      this.foreignLocks.has(`${rowId}\0`) ||
      (!!fieldCode && this.foreignLocks.has(`${rowId}\0${fieldCode}`))
    );
  }

  /**
   * 公式列即时重算（服务端为权威）。当前仅支持 "a * b" 形式的两字段乘法。
   */
  private recomputeFormulaCells(sheetRow: number, changedCols: number[]): void {
    for (const [colIndex, field] of this.fields.entries()) {
      if (!field.formula) continue;
      const match = field.formula.match(/^\s*(\w+)\s*\*\s*(\w+)\s*$/);
      if (!match) continue;
      const [, leftCode, rightCode] = match;
      const leftIndex = this.fieldIndexByCode.get(leftCode!);
      const rightIndex = this.fieldIndexByCode.get(rightCode!);
      if (leftIndex === undefined || rightIndex === undefined) continue;
      if (!changedCols.includes(leftIndex) && !changedCols.includes(rightIndex))
        continue;

      const sheet = this.getActiveSheet();
      if (!sheet) continue;
      // 空单元格不是 0：任一操作数为空时公式列保持空白，与服务端“缺值不计算”一致
      const toNumber = (value: unknown) =>
        value === null || value === undefined || String(value).trim() === ''
          ? Number.NaN
          : Number(value);
      const left = toNumber(sheet.getRange(sheetRow, leftIndex, 1, 1).getValue());
      const right = toNumber(
        sheet.getRange(sheetRow, rightIndex, 1, 1).getValue(),
      );
      const result =
        Number.isFinite(left) && Number.isFinite(right)
          ? Math.round(left * right * 100) / 100
          : '';
      this.runInternal(() => {
        sheet.getRange(sheetRow, colIndex, 1, 1).setValue(result);
      });
    }
  }

  private runInternal(action: () => void): void {
    this.applyingInternal = true;
    try {
      action();
    } finally {
      this.applyingInternal = false;
    }
  }
}
