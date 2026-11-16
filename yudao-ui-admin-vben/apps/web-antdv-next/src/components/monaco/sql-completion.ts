/**
 * SQL 编辑器元数据补全的纯逻辑：标识符解析、表引用与目录对齐、别名/限定名解析、标识符引用与字段缓存。
 * 不依赖 Monaco，便于单测；Monaco 适配在 setup.ts。
 */

/** 补全用的表（name 为元数据接口返回的原样表引用，字段接口也按它查询） */
export interface CompletionTable {
  name: string;
  comment?: string;
}

export interface CompletionColumn {
  name: string;
  comment?: string;
  dataType?: string;
}

/** 一个编辑器的补全元数据来源：当前数据源的表清单与按表懒加载字段 */
export interface SqlCompletionSource {
  tables: readonly CompletionTable[];
  loadColumns?: (tableName: string) => Promise<CompletionColumn[]>;
}

export interface IdentifierSegment {
  /** 原文（含引号），用于原样插入 */
  raw: string;
  /** 去引号后的名字 */
  value: string;
  quoted: boolean;
}

/** 解析器给出的实体（只取用到的字段） */
export interface CompletionEntity {
  entityContextType: string;
  text: string;
  isAccessible?: boolean | null;
  _alias?: null | { text: string };
}

const CLOSING_QUOTE: Record<string, string> = { '"': '"', '`': '`', '[': ']' };

/** 按未加引号的点拆分 "a"."b" / `a`.`b` / [a].[b] / a.b；引号内的点和转义引号不拆分 */
export function identifierSegments(text: string): IdentifierSegment[] {
  const source = text.trim();
  const segments: IdentifierSegment[] = [];
  let index = 0;
  while (index < source.length) {
    const open = source[index]!;
    const close = CLOSING_QUOTE[open];
    if (close) {
      let value = '';
      let cursor = index + 1;
      while (cursor < source.length) {
        const char = source[cursor]!;
        if (char === close) {
          if (close !== ']' && source[cursor + 1] === close) {
            value += close;
            cursor += 2;
            continue;
          }
          break;
        }
        value += char;
        cursor++;
      }
      segments.push({
        raw: source.slice(index, cursor + 1),
        value,
        quoted: true,
      });
      index = cursor + 1;
    } else {
      let cursor = index;
      while (cursor < source.length && source[cursor] !== '.') cursor++;
      const raw = source.slice(index, cursor).trim();
      if (raw) segments.push({ raw, value: raw, quoted: false });
      index = cursor;
    }
    while (source[index] === ' ') index++;
    if (source[index] === '.') index++;
    while (source[index] === ' ') index++;
  }
  return segments;
}

function samePart(left: string, right: string) {
  return left.toLowerCase() === right.toLowerCase();
}

/** suffix 是否为 full 的后缀（逐段比较，不区分大小写） */
function endsWithSegments(
  full: IdentifierSegment[],
  suffix: IdentifierSegment[],
) {
  if (suffix.length === 0 || suffix.length > full.length) return false;
  const offset = full.length - suffix.length;
  return suffix.every((part, index) =>
    samePart(part.value, full[offset + index]!.value),
  );
}

function unique<T>(items: T[]) {
  return items.length === 1 ? items[0] : undefined;
}

/**
 * 把语句里写的表引用对齐到元数据目录中的表：引号写法、大小写、省略或多写 schema 都能对上；
 * 有歧义时返回 undefined，由调用方按原样引用查询。
 */
export function resolveTable(
  tables: readonly CompletionTable[],
  reference: string,
): CompletionTable | undefined {
  const target = identifierSegments(reference);
  if (target.length === 0) return undefined;
  const parsed = tables.map((table) => ({
    table,
    segments: identifierSegments(table.name),
  }));
  const exact = parsed.filter(
    (item) =>
      item.segments.length === target.length &&
      endsWithSegments(item.segments, target),
  );
  if (exact.length > 0) return unique(exact)?.table;
  return (
    unique(parsed.filter((item) => endsWithSegments(item.segments, target)))
      ?.table ??
    unique(parsed.filter((item) => endsWithSegments(target, item.segments)))
      ?.table
  );
}

/** 光标前“限定名.”中的限定名：["r", "."] → r，["r", ".", "st"] → r，没有点时为 undefined */
export function qualifierBeforeDot(words: readonly string[]) {
  const dot = words.lastIndexOf('.');
  if (dot <= 0) return undefined;
  const qualifier = words.slice(0, dot).join('').trim();
  return qualifier || undefined;
}

/**
 * 字段补全要读取的表引用：有限定名时只取别名或表名与之匹配的表，否则取当前语句可访问的全部表。
 */
export function columnTableReferences(
  entities: readonly CompletionEntity[],
  qualifier?: string,
  limit = 5,
): string[] {
  const tables = entities.filter(
    (entity) =>
      entity.entityContextType === 'table' && entity.isAccessible !== false,
  );
  let matched = tables;
  if (qualifier) {
    const target = identifierSegments(qualifier);
    const byAlias =
      target.length === 1
        ? tables.filter((entity) => {
            const alias = entity._alias?.text;
            const aliasValue = alias
              ? identifierSegments(alias)[0]?.value
              : undefined;
            return aliasValue && samePart(aliasValue, target[0]!.value);
          })
        : [];
    const byName = tables.filter((entity) =>
      endsWithSegments(identifierSegments(entity.text), target),
    );
    // 起了别名的表在 SQL 中只能用别名限定，按表名匹配时优先未起别名的表
    const unaliased = byName.filter((entity) => !entity._alias);
    matched =
      byAlias.length > 0
        ? byAlias
        : (unaliased.length > 0
          ? unaliased
          : byName);
  }
  return [...new Set(matched.map((entity) => entity.text))].slice(0, limit);
}

/** “schema.” 之后可补全的表：只插入最后一段（保留原引号） */
export function tablesInSchema(
  tables: readonly CompletionTable[],
  schema: string,
) {
  const prefix = identifierSegments(schema);
  if (prefix.length === 0) return [];
  return tables.flatMap((table) => {
    const segments = identifierSegments(table.name);
    if (
      segments.length !== prefix.length + 1 ||
      !prefix.every((part, index) =>
        samePart(part.value, segments[index]!.value),
      )
    )
      return [];
    const last = segments.at(-1)!;
    return [{ table, insertText: last.raw, filterText: last.value }];
  });
}

/** 按方言给需要引号的标识符加引号（PostgreSQL 系用双引号，其余用反引号）；已带引号的原样返回 */
export function quoteIdentifier(name: string, languageId: string) {
  if (/["`[]/.test(name)) return name;
  const postgres = languageId === 'pgsql';
  const plain = postgres ? /^[_a-z][\d$_a-z]*$/ : /^[_a-z][\w$]*$/i;
  if (plain.test(name)) return name;
  const quote = postgres ? '"' : '`';
  return `${quote}${name.replaceAll(quote, quote + quote)}${quote}`;
}

/** 表引用逐段加引号：未带引号的 schema.table 按点拆分后分别处理 */
export function quoteTableReference(name: string, languageId: string) {
  if (/["`[]/.test(name)) return name;
  return name
    .split('.')
    .map((part) => quoteIdentifier(part, languageId))
    .join('.');
}

/** 读取失败后的重试间隔：避免每次按键都重复请求，也不会永久缓存失败 */
export const COLUMN_FAILURE_RETRY_MS = 10_000;

interface ColumnCacheEntry {
  failedAt?: number;
  promise: Promise<CompletionColumn[]>;
}

const columnCache = new WeakMap<
  SqlCompletionSource,
  Map<string, ColumnCacheEntry>
>();

/** 按来源缓存字段；同一时刻的重复请求合并，失败结果短暂缓存后允许重试 */
export function loadCompletionColumns(
  source: SqlCompletionSource,
  tableName: string,
  now: () => number = Date.now,
): Promise<CompletionColumn[]> {
  const loader = source.loadColumns;
  if (!loader) return Promise.resolve([]);
  let entries = columnCache.get(source);
  if (!entries) {
    entries = new Map();
    columnCache.set(source, entries);
  }
  const cached = entries.get(tableName);
  if (
    cached &&
    (cached.failedAt === undefined ||
      now() - cached.failedAt < COLUMN_FAILURE_RETRY_MS)
  ) {
    return cached.promise;
  }
  const entry: ColumnCacheEntry = {
    promise: Promise.resolve()
      .then(() => loader(tableName))
      .then(
        (columns) => columns ?? [],
        () => {
          entry.failedAt = now();
          return [];
        },
      ),
  };
  entries.set(tableName, entry);
  return entry.promise;
}
