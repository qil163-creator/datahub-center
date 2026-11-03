import type { DataforgeAccessApi } from '#/api/dataforge/access';

type Mappings = Record<string, DataforgeAccessApi.TableMapping>;

export function getTableMapping(mappings: Mappings, name: string) {
  return Object.hasOwn(mappings, name) ? mappings[name]! : {};
}

export function targetTableName(
  row: DataforgeAccessApi.PreviewNameMapper,
  mappings: Mappings,
) {
  const mapping = getTableMapping(mappings, row.originalName);
  return mapping.targetTableName ?? row.targetName;
}

export function targetTableComment(
  row: DataforgeAccessApi.PreviewNameMapper,
  mappings: Mappings,
) {
  return (
    getTableMapping(mappings, row.originalName).targetTableComment ??
    row.remarks ??
    ''
  );
}

/** Copy only changed entries; computed property definitions also support __proto__ table names. */
export function updateTableMappings(
  mappings: Mappings,
  names: string[],
  update: (
    mapping: DataforgeAccessApi.TableMapping,
    name: string,
  ) => DataforgeAccessApi.TableMapping,
): Mappings {
  const next = { ...mappings };
  for (const name of names) {
    Object.defineProperty(next, name, {
      configurable: true,
      enumerable: true,
      writable: true,
      value: update({ ...getTableMapping(mappings, name) }, name),
    });
  }
  return next;
}

export function defaultDorisOptions(): DataforgeAccessApi.DorisOptions {
  return {
    distributionType: 'HASH',
    distributionColumns: [],
    bucketMode: 'AUTO',
    replicationNum: 1,
  };
}

export function copyDorisOptions(
  options?: DataforgeAccessApi.DorisOptions,
): DataforgeAccessApi.DorisOptions {
  if (!options) return defaultDorisOptions();
  return {
    ...options,
    distributionColumns: [...options.distributionColumns],
    partition: options.partition
      ? {
          ...options.partition,
          columns: [...options.partition.columns],
          partitions: options.partition.partitions.map((partition) => ({
            name: partition.name,
            values: partition.values.map((tuple) => [...tuple]),
          })),
        }
      : undefined,
  };
}

function validColumns(columns: string[]) {
  return (
    columns.length > 0 &&
    columns.every((column) => column.trim().length > 0) &&
    new Set(columns.map((column) => column.trim())).size === columns.length
  );
}

export function validateDorisPartition(
  partition?: DataforgeAccessApi.DorisOptions['partition'],
) {
  if (!partition) return '';
  if (!['LIST', 'RANGE'].includes(partition.kind))
    return '请选择有效的分区方式';
  if (!validColumns(partition.columns)) return '分区字段不能为空或重复';
  if (partition.partitions.length === 0) return '请至少添加一个分区';
  const names = new Set<string>();
  for (const item of partition.partitions) {
    const name = item.name.trim();
    if (!name || names.has(name)) return '分区名称不能为空或重复';
    names.add(name);
    if (
      item.values.length === 0 ||
      (partition.kind === 'RANGE' && item.values.length !== 2)
    ) {
      return partition.kind === 'RANGE'
        ? '每个 RANGE 分区需要下界和上界两组值'
        : '每个 LIST 分区至少需要一组枚举值';
    }
    if (
      item.values.some(
        (tuple) =>
          tuple.length !== partition.columns.length ||
          tuple.some((value) => !value.trim()),
      )
    ) {
      return '每组分区值必须与分区字段一一对应，且不能为空';
    }
    if (partition.kind === 'RANGE') {
      if (
        item.values.some((tuple) =>
          tuple.some((value) => value.trim().toUpperCase() === 'MINVALUE'),
        )
      )
        return 'RANGE 分区不支持 MINVALUE，请填写具体下界值';
      if (
        item.values[0]?.some(
          (value) => value.trim().toUpperCase() === 'MAXVALUE',
        )
      )
        return 'MAXVALUE 只能用于 RANGE 分区上界';
    }
  }
  return '';
}

export function validateDorisOptions(options: DataforgeAccessApi.DorisOptions) {
  if (!['HASH', 'RANDOM'].includes(options.distributionType))
    return '请选择有效的分桶方式';
  if (
    options.distributionType === 'HASH' &&
    !validColumns(options.distributionColumns)
  ) {
    return 'HASH 分桶字段不能为空或重复';
  }
  if (!['AUTO', 'FIXED'].includes(options.bucketMode))
    return '请选择有效的桶数方式';
  if (
    options.bucketMode === 'FIXED' &&
    (!Number.isInteger(options.bucketCount) || (options.bucketCount ?? 0) < 1)
  ) {
    return '固定桶数必须为正整数';
  }
  if (!Number.isInteger(options.replicationNum) || options.replicationNum < 1)
    return '副本数必须为正整数';
  return validateDorisPartition(options.partition);
}

export function validateTableMappings(
  rows: DataforgeAccessApi.PreviewNameMapper[],
  mappings: Mappings,
  doris: boolean,
) {
  const targets = new Set<string>();
  for (const row of rows) {
    const target = targetTableName(row, mappings).trim();
    if (!target) return `来源表 ${row.originalName} 的目标表名称不能为空`;
    if (targets.has(target))
      return `目标表名称 ${target} 重复，请为每张来源表设置不同的目标表`;
    targets.add(target);
    const options = getTableMapping(mappings, row.originalName).dorisOptions;
    if (doris && options) {
      const error = validateDorisOptions(options);
      if (error) return `${row.originalName}：${error}`;
    }
  }
  return '';
}
