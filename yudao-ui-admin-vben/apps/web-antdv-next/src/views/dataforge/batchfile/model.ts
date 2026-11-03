import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

export const LIST_PATH = '/develop/integrate/batch-file';
export const FIELD_TYPES: Api.FieldType[] = [
  'string',
  'bigint',
  'int',
  'decimal',
  'double',
  'date',
  'datetime',
  'boolean',
];
export const LIMITS = {
  maxFiles: 20,
  maxFileBytes: 20 * 1024 * 1024,
  maxTotalBytes: 100 * 1024 * 1024,
  maxRows: 100_000,
  maxColumns: 256,
};
export const STATUS: Record<
  'PENDING' | Api.LastStatus,
  { label: string; tone: string }
> = {
  NOT_EXECUTED: { label: '未执行', tone: '' },
  PENDING: { label: '等待执行', tone: '' },
  QUEUED: { label: '等待执行', tone: 'running' },
  RUNNING: { label: '执行中', tone: 'running' },
  SUCCESS: { label: '执行成功', tone: 'success' },
  PARTIAL_SUCCESS: { label: '部分成功', tone: 'failed' },
  FAILED: { label: '执行失败', tone: 'failed' },
  UNKNOWN: { label: '结果待核查', tone: 'failed' },
};
export const statusOptions = Object.entries(STATUS)
  .filter(([value]) => value !== 'PENDING')
  .map(([value, item]) => ({ value, label: item.label }));
export function statusOf(value?: string) {
  return (
    STATUS[value as keyof typeof STATUS] ?? {
      label: value || '未执行',
      tone: '',
    }
  );
}
export const activeRun = (value?: string, resolvedTime?: number) =>
  ['QUEUED', 'RUNNING'].includes(value ?? '') ||
  (value === 'UNKNOWN' && !resolvedTime);
export const idOf = String;
export function routeId(value: unknown) {
  return typeof value === 'string' && /^\d+$/u.test(value) ? value : undefined;
}
export function fieldError(fields: Api.Field[]) {
  if (fields.length === 0) return '至少保留一个字段';
  const names = new Set<string>();
  for (const field of fields) {
    const name = field.name.trim();
    if (!name) return '字段名称不能为空';
    if (
      name.length > 64 ||
      [...name].some(
        (char) => char.codePointAt(0)! < 32 || char.codePointAt(0) === 127,
      )
    )
      return '字段名称最多64个字符且不能包含控制字符';
    if (names.has(name.toLocaleLowerCase())) return `字段名称重复：${name}`;
    if (!FIELD_TYPES.includes(field.type))
      return `不支持的字段类型：${field.type}`;
    names.add(name.toLocaleLowerCase());
  }
  return '';
}
export function sameStructure(left: Api.File, right: Api.File) {
  return (
    left.fields.length === right.fields.length &&
    left.fields.every(
      (field, index) =>
        field.name.trim() === right.fields[index]?.name.trim() &&
        field.type === right.fields[index]?.type,
    )
  );
}
export function uniqueTargetName(fileName: string, used: Iterable<string>) {
  const names = new Set([...used].map((name) => name.toLocaleLowerCase()));
  const stem = fileName.replace(/\.[^.]+$/u, '');
  if (/^[A-Z_a-z]\w{0,63}$/u.test(stem) && !names.has(stem.toLocaleLowerCase()))
    return stem;
  let index = 1;
  while (names.has(`import_${String(index).padStart(3, '0')}`)) index++;
  return `import_${String(index).padStart(3, '0')}`;
}
export function reconcileTargets(files: Api.File[], current: Api.Target[]) {
  const valid = new Set(files.map((file) => idOf(file.id)));
  const assigned = new Set<string>();
  const targets = current
    .map((target) => ({
      name: target.name,
      fileIds: target.fileIds.filter((id) => {
        const key = idOf(id);
        if (!valid.has(key) || assigned.has(key)) return false;
        assigned.add(key);
        return true;
      }),
    }))
    .filter((target) => target.fileIds.length > 0);
  for (const file of files)
    if (!assigned.has(idOf(file.id))) {
      targets.push({
        name: uniqueTargetName(
          file.name,
          targets.map((target) => target.name),
        ),
        fileIds: [file.id],
      });
    }
  return targets;
}
export function mergeTargets(
  targets: Api.Target[],
  selected: number[],
  files: Api.File[],
) {
  const chosen = new Set(selected);
  const ids = targets.flatMap((target, index) =>
    chosen.has(index) ? target.fileIds : [],
  );
  const rows = ids
    .map((id) => files.find((file) => idOf(file.id) === idOf(id)))
    .filter((file): file is Api.File => !!file);
  if (selected.length < 2 || rows.length !== ids.length)
    return {
      error: '请至少选择两个目标表',
      conflicts: [] as Api.File[],
      targets,
    };
  if (rows.some((file) => !sameStructure(rows[0]!, file)))
    return {
      error: '所选文件字段名称、类型或顺序不一致，无法合并',
      conflicts: rows,
      targets,
    };
  const first = Math.min(...selected);
  return {
    error: '',
    conflicts: [],
    targets: targets.flatMap((target, index) => {
      if (index === first) return [{ name: target.name, fileIds: ids }];
      return chosen.has(index)
        ? []
        : [{ ...target, fileIds: [...target.fileIds] }];
    }),
  };
}
export function splitTargets(
  targets: Api.Target[],
  selected: number[],
  files: Api.File[],
) {
  const used = targets.map((target) => target.name);
  return targets.flatMap((target, index) => {
    if (!selected.includes(index) || target.fileIds.length < 2)
      return [{ ...target, fileIds: [...target.fileIds] }];
    return target.fileIds.map((id, position) => {
      const file = files.find((row) => idOf(row.id) === idOf(id));
      const name =
        position === 0 ? target.name : uniqueTargetName(file?.name ?? '', used);
      used.push(name);
      return { name, fileIds: [id] };
    });
  });
}
export function detachFile(
  targets: Api.Target[],
  group: number,
  fileId: Api.Id,
  files: Api.File[],
) {
  const target = targets[group];
  if (!target || target.fileIds.length < 2) return targets;
  const file = files.find((row) => idOf(row.id) === idOf(fileId));
  if (!file) return targets;
  return [
    ...targets.map((row, index) =>
      index === group
        ? {
            ...row,
            fileIds: row.fileIds.filter((id) => idOf(id) !== idOf(fileId)),
          }
        : { ...row, fileIds: [...row.fileIds] },
    ),
    {
      name: uniqueTargetName(
        file.name,
        targets.map((row) => row.name),
      ),
      fileIds: [fileId],
    },
  ];
}
export function moveFile(
  target: Api.Target,
  position: number,
  direction: -1 | 1,
) {
  const next = position + direction;
  if (next < 0 || next >= target.fileIds.length) return target;
  const fileIds = [...target.fileIds];
  [fileIds[position], fileIds[next]] = [fileIds[next]!, fileIds[position]!];
  return { ...target, fileIds };
}
export function targetError(files: Api.File[], targets: Api.Target[]) {
  if (files.length === 0) return '请先上传文件';
  const names = new Set<string>();
  const assigned: string[] = [];
  for (const target of targets) {
    if (!/^[A-Z_a-z]\w{0,63}$/u.test(target.name))
      return '目标表名称须以英文字母或下划线开头，且仅包含字母、数字、下划线，最多64位';
    if (names.has(target.name.toLocaleLowerCase()))
      return `目标表名称重复：${target.name}`;
    names.add(target.name.toLocaleLowerCase());
    const rows = target.fileIds.map((id) =>
      files.find((file) => idOf(file.id) === idOf(id)),
    );
    if (rows.length === 0 || rows.some((file) => !file))
      return '目标表包含无效文件';
    if (rows.some((file) => !sameStructure(rows[0]!, file!)))
      return `目标表 ${target.name} 的文件结构不一致`;
    assigned.push(...target.fileIds.map(idOf));
  }
  if (
    assigned.length !== files.length ||
    new Set(assigned).size !== files.length ||
    files.some((file) => !assigned.includes(idOf(file.id)))
  )
    return '每个文件必须且只能分配到一个目标表';
  return '';
}
export function uploadError(
  incoming: Pick<File, 'name' | 'size'>[],
  existing: Api.File[],
  limits = LIMITS,
) {
  if (existing.length + incoming.length > limits.maxFiles)
    return `最多选择${limits.maxFiles}个文件`;
  if (incoming.some((file) => !/\.(csv|xlsx?)$/iu.test(file.name)))
    return '仅支持CSV、XLS和XLSX文件';
  if (
    incoming.some((file) => file.size > limits.maxFileBytes || file.size === 0)
  )
    return `文件不能为空，且单个文件不得超过${limits.maxFileBytes / 1024 / 1024}MB`;
  const total = [
    ...existing.map((file) => file.size),
    ...incoming.map((file) => file.size),
  ].reduce<bigint>((sum, size) => sum + BigInt(size), 0n);
  return total > BigInt(limits.maxTotalBytes)
    ? `文件总大小不得超过${limits.maxTotalBytes / 1024 / 1024}MB`
    : '';
}
