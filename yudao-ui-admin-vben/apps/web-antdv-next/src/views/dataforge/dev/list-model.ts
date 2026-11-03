import type { DataforgeDevApi } from '#/api/dataforge/dev';

/** 周期编辑只使用字符串；接口空值在加载表单时规范化为 undefined。 */
export type TaskFormModel = Omit<DataforgeDevApi.Task, 'cronExpression'> & {
  cronExpression?: string;
};

export interface DirectoryNode {
  key: string;
  title: string;
  dir?: DataforgeDevApi.Dir;
  children: DirectoryNode[];
}

export interface DirectorySelectNode {
  value: string;
  label: string;
  disabled: boolean;
  children: DirectorySelectNode[];
}

export const ROOT_DIRECTORY_NAME = '数据开发类目';

/** 空类目与编号 0 均表示顶级归属；真实编号只做字符串规范化。 */
export function normalizeDirectoryId(
  id: DataforgeDevApi.Id | null | undefined,
): string {
  return id === null || id === undefined || String(id).trim() === ''
    ? '0'
    : String(id);
}

export function resolveDirectoryName(
  id: DataforgeDevApi.Id | null | undefined,
  dirs: DataforgeDevApi.Dir[],
): string {
  const key = normalizeDirectoryId(id);
  if (key === '0') return ROOT_DIRECTORY_NAME;
  const dir = dirs.find((item) => String(item.id) === key);
  return dir ? dir.name || `未命名类目（${key}）` : `类目不可用（${key}）`;
}

/** 根类目参与选择，停用及缺失类目保留名称与原始归属但不可重新选择。 */
export function buildDirectorySelectTree(
  dirs: DataforgeDevApi.Dir[],
  selectedId?: DataforgeDevApi.Id | null,
): DirectorySelectNode[] {
  const map = (
    node: DirectoryNode,
    parentDisabled = false,
  ): DirectorySelectNode => {
    const disabled = parentDisabled || node.dir?.status === 1;
    return {
      value: node.key,
      label: resolveDirectoryName(node.key, dirs),
      disabled,
      children: node.children.map((child) => map(child, disabled)),
    };
  };
  const children = buildDirectoryTree(
    dirs.filter((dir) => String(dir.id) !== '0'),
  ).map((node) => map(node));
  const selectedKey = normalizeDirectoryId(selectedId);
  if (
    selectedKey !== '0' &&
    !dirs.some((dir) => String(dir.id) === selectedKey)
  ) {
    children.push({
      value: selectedKey,
      label: resolveDirectoryName(selectedKey, dirs),
      disabled: true,
      children: [],
    });
  }
  return [
    { value: '0', label: ROOT_DIRECTORY_NAME, disabled: false, children },
  ];
}

/** 空负责人不转成字符串，未匹配的用户编号保留原值。 */
export function resolveUserName(
  id: DataforgeDevApi.Id | null | undefined,
  users: { id: string; name: string }[],
) {
  if (id === null || id === undefined || String(id).trim() === '') return '-';
  const key = String(id);
  return users.find((user) => user.id === key)?.name || key;
}

export function resolveUserContact(
  id: DataforgeDevApi.Id | null | undefined,
  users: { id: string; mobile?: string }[],
) {
  if (id === null || id === undefined || String(id).trim() === '') return '-';
  return users.find((user) => user.id === String(id))?.mobile || '-';
}

/** 树的 ID 始终按字符串比较，保留父级以便命中节点可定位。 */
export function buildDirectoryTree(
  dirs: DataforgeDevApi.Dir[],
  keyword = '',
): DirectoryNode[] {
  const nodes = new Map<string, DirectoryNode>();
  dirs.forEach((dir) => {
    if (dir.id !== undefined)
      nodes.set(String(dir.id), {
        key: String(dir.id),
        title: dir.name ?? String(dir.id),
        dir,
        children: [],
      });
  });
  const roots: DirectoryNode[] = [];
  nodes.forEach((node) => {
    const parent = nodes.get(String(node.dir?.parentId));
    if (parent && parent !== node) parent.children.push(node);
    else roots.push(node);
  });
  const filter = (
    items: DirectoryNode[],
    matchedParent = false,
  ): DirectoryNode[] =>
    items.flatMap((item) => {
      const matched =
        matchedParent ||
        item.title.toLowerCase().includes(keyword.trim().toLowerCase());
      const children = filter(item.children, matched);
      return matched || children.length > 0 ? [{ ...item, children }] : [];
    });
  return filter(roots);
}

export function cronSummary(value?: null | string) {
  if (!value) return '未配置调度周期';
  const [seconds, minutes, hours, day, month, week] = value.trim().split(/\s+/);
  if (
    seconds === '0' &&
    minutes === '*' &&
    hours === '*' &&
    day === '*' &&
    month === '*' &&
    week === '?'
  )
    return '每分钟执行';
  if (
    seconds === '0' &&
    minutes?.startsWith('0/') &&
    hours === '*' &&
    day === '*' &&
    month === '*' &&
    week === '?'
  )
    return `每 ${minutes.slice(2)} 分钟执行`;
  if (
    seconds === '0' &&
    minutes === '0' &&
    hours === '*' &&
    day === '*' &&
    month === '*' &&
    week === '?'
  )
    return '每小时执行';
  if (
    seconds === '0' &&
    /^\d+$/.test(minutes ?? '') &&
    /^\d+$/.test(hours ?? '') &&
    day === '*' &&
    month === '*' &&
    week === '?'
  )
    return `每天 ${hours!.padStart(2, '0')}:${minutes!.padStart(2, '0')} 执行`;
  return value;
}

export const TASK_STATUS_OPTIONS = [
  { label: '未上线', value: 0 },
  { label: '已上线', value: 1 },
];
export const PRIORITY_OPTIONS = [
  { label: '最高', value: 'HIGHEST' },
  { label: '高', value: 'HIGH' },
  { label: '中等', value: 'MEDIUM' },
  { label: '低', value: 'LOW' },
  { label: '最低', value: 'LOWEST' },
];
export const PARAM_TYPE_OPTIONS = [
  'VARCHAR',
  'INTEGER',
  'LONG',
  'DOUBLE',
  'BOOLEAN',
  'DATE',
  'TIME',
  'TIMESTAMP',
].map((value) => ({ label: value, value }));
export function defaultExecutionConfig(): DataforgeDevApi.ExecutionConfig {
  return {
    priority: 'MEDIUM',
    workerGroup: 'default',
    retryTimes: 0,
    retryIntervalMinutes: 1,
    delayMinutes: 0,
    sqlType: 'AUTO',
    statementSeparator: ';',
    localParams: [],
  };
}

export function buildProcedureTemplate(dbType?: string) {
  const executionConfig: DataforgeDevApi.ExecutionConfig = {
    ...defaultExecutionConfig(),
    sqlType: 'PROCEDURE',
    statementSeparator: '/',
    localParams: [{ prop: 'row_id', type: 'INTEGER', value: '1' }],
  };
  if (dbType === 'POSTGRESQL') {
    return {
      disabled: false,
      description: '定义并调用过程 · 请修改示例表与过程名称',
      executionConfig,
      content: [
        '-- 示例会创建表和过程，请确认对象名称及执行授权',
        'CREATE TABLE example_data (id INTEGER PRIMARY KEY);',
        '/',
        'CREATE PROCEDURE example_procedure(p_id INTEGER) LANGUAGE SQL AS $$',
        '  INSERT INTO example_data (id) VALUES (p_id);',
        '$$;',
        '/',
        `CALL example_procedure(\${row_id});`,
        '/',
      ].join('\n'),
    };
  }
  if (dbType === 'ORACLE' || dbType === 'DM') {
    return {
      disabled: false,
      description:
        dbType === 'DM'
          ? 'Oracle 兼容过程 · 达梦数据库执行需现场验证'
          : '定义并调用过程 · 使用调用者权限',
      executionConfig,
      content: [
        '-- 示例会创建表和过程，请确认对象名称及执行授权',
        'CREATE TABLE EXAMPLE_DATA (ID INTEGER PRIMARY KEY)',
        '/',
        'CREATE OR REPLACE PROCEDURE EXAMPLE_PROCEDURE(P_ID IN INTEGER)',
        'AUTHID CURRENT_USER AS',
        'BEGIN',
        '  INSERT INTO EXAMPLE_DATA (ID) VALUES (P_ID);',
        'END;',
        '/',
        `CALL EXAMPLE_PROCEDURE(\${row_id})`,
        '/',
      ].join('\n'),
    };
  }
  return {
    disabled: true,
    description: '请选择 PostgreSQL、Oracle 或达梦；其他产品过程执行尚未验证',
    executionConfig,
    content: '-- 当前连接类型没有可用的受管过程模板',
  };
}
