import type { FoundationDatabaseApi } from '#/api/dataforge/database';

export type RelationInputCode =
  | 'read-hive'
  | 'read-mongodb'
  | 'read-odps'
  | 'read-tdengine';

export interface RelationInputColumn {
  name: string;
  type?: string;
  comment?: string;
  alias?: string;
  length?: null | number | string;
  precision?: null | number | string;
  [key: string]: unknown;
}

export const RELATION_READ_KEYS = [
  'readMode',
  'incrementColumn',
  'incrementStart',
  'startValue',
  'dateColumn',
  'dateFormat',
  'timeFormat',
  'cursorTime',
  'timeConditions',
  'whereCondition',
] as const;

const incrementKeys = ['incrementColumn', 'incrementStart', 'startValue'];
const timeKeys = [
  'dateColumn',
  'dateFormat',
  'timeFormat',
  'cursorTime',
  'timeConditions',
];

/** 共用交互保留连接器既有参数键，不在打开弹窗时迁移用户配置。 */
export function createRelationReadConfig(config: Record<string, any>) {
  const modeDrafts = new Map<string, Record<string, unknown>>();
  return new Proxy(config, {
    get(target, key) {
      if (key === 'startValue')
        return target.incrementStart ?? target.startValue;
      if (key === 'timeFormat')
        return target.dateFormat ?? target.timeFormat ?? 'yyyy-MM-dd';
      if (
        key === 'timeConditions' &&
        !Array.isArray(target.timeConditions) &&
        target.dateColumn
      )
        return [
          {
            field: target.dateColumn,
            baselineType: 'TIME_RANGE',
            cursorTime: target.cursorTime,
          },
        ];
      return Reflect.get(target, key);
    },
    set(target, key, value: unknown) {
      if (key === 'readMode' && value !== (target.readMode ?? 'FULL')) {
        const previous = target.readMode ?? 'FULL';
        let keys: string[] = [];
        if (previous === 'ID_INCREMENT') keys = incrementKeys;
        else if (previous === 'TIME_INCREMENT') keys = timeKeys;
        modeDrafts.set(
          previous,
          Object.fromEntries(
            keys
              .filter((name) => target[name] !== undefined)
              .map((name) => [name, target[name]]),
          ),
        );
        for (const name of [...incrementKeys, ...timeKeys])
          Reflect.deleteProperty(target, name);
        Object.assign(target, modeDrafts.get(String(value)) ?? {});
      }
      if (key === 'startValue') {
        target.incrementStart = value;
        if ('startValue' in target) target.startValue = value;
      } else if (key === 'timeFormat') {
        target.dateFormat = value;
        if ('timeFormat' in target) target.timeFormat = value;
      } else Reflect.set(target, key, value);
      return true;
    },
  });
}

/** 兼容旧声明类型供增量字段筛选；不改写用于执行的原生类型。 */
export function relationColumnMetadata(
  column: RelationInputColumn,
): FoundationDatabaseApi.DatabaseTableColumn {
  const aliases: Record<string, string> = {
    LONG: 'BIGINT',
    BIGNUMBER: 'DECIMAL',
    NUMBER: 'DOUBLE',
    INTEGER: 'INTEGER',
  };
  return {
    fieldName: column.name,
    fieldTypeName: aliases[column.type?.toUpperCase() ?? ''] ?? column.type,
    remarks: column.comment,
  };
}

export function relationConnectionMatches(
  code: RelationInputCode,
  database: FoundationDatabaseApi.DatabaseSimple,
) {
  const product =
    `${database.productId ?? ''} ${database.databaseTypeName ?? ''}`
      .toUpperCase()
      .replaceAll(/[^A-Z0-9]/g, '');
  if (!product) return true;
  const patterns: Record<RelationInputCode, RegExp> = {
    'read-odps': /ODPS|MAXCOMPUTE/,
    'read-hive': /HIVE/,
    'read-mongodb': /MONGO/,
    'read-tdengine': /TDENGINE|TAOS/,
  };
  return patterns[code].test(product);
}

/** 元数据只补确定字段，未知附加配置与用户投影保留在各自来源。 */
export function relationColumnsFromMetadata(
  code: RelationInputCode,
  fields: FoundationDatabaseApi.DatabaseTableColumn[],
  current: RelationInputColumn[],
): RelationInputColumn[] {
  return fields.map((field) => {
    const previous = current.find((column) => column.name === field.fieldName);
    return {
      ...previous,
      name: field.fieldName,
      ...(field.fieldTypeName ? { type: field.fieldTypeName } : {}),
      ...(field.remarks ? { comment: field.remarks } : {}),
      ...(code === 'read-hive' && previous?.alias
        ? { alias: previous.alias }
        : {}),
    };
  });
}
