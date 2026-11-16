import type {
  CompletionService,
  ICompletionItem,
} from 'monaco-sql-languages';

import type {
  CompletionColumn,
  CompletionTable,
  SqlCompletionSource,
} from './sql-completion';

import * as monaco from 'monaco-editor';
import {
  EntityContextType,
  LanguageIdEnum,
  setupLanguageFeatures,
  vsPlusTheme,
} from 'monaco-sql-languages';

import { registerLogLanguage } from './javalog';
import {
  columnTableReferences,
  loadCompletionColumns,
  qualifierBeforeDot,
  quoteIdentifier,
  quoteTableReference,
  resolveTable,
  tablesInSchema,
} from './sql-completion';
import EditorWorker from './workers/editor.worker?worker';
import FlinkSQLWorker from './workers/flink.worker?worker';
import MySQLWorker from './workers/mysql.worker?worker';
import PGSQLWorker from './workers/pgsql.worker?worker';
import SparkSQLWorker from './workers/spark.worker?worker';

// @ts-expect-error 包未导出子路径类型声明，运行时为合法 ESM
import 'monaco-sql-languages/esm/languages/mysql/mysql.contribution';
// @ts-expect-error 同上
import 'monaco-sql-languages/esm/languages/pgsql/pgsql.contribution';
// @ts-expect-error 同上
import 'monaco-sql-languages/esm/languages/spark/spark.contribution';
// @ts-expect-error 同上
import 'monaco-sql-languages/esm/languages/flink/flink.contribution';

/**
 * 页面级补全来源（数据查询、数据开发按当前数据源填充）。没有自带来源的编辑器使用它；
 * 每次设置都换成新对象，旧数据源的字段缓存随之失效。
 */
let pageSource: SqlCompletionSource = { tables: [] };
/** 编辑器自带的补全来源，按模型 URI 区分，互不串用 */
const modelSources = new Map<string, SqlCompletionSource>();

/** 设置当前数据源的表清单（切换数据源时调用） */
export function setCompletionTables(tables: CompletionTable[]) {
  pageSource = { tables: [...tables], loadColumns: pageSource.loadColumns };
}

/** 注册字段懒加载器（补全触发到字段上下文时按表名拉取） */
export function setColumnLoader(
  loader: ((tableName: string) => Promise<CompletionColumn[]>) | null,
) {
  pageSource = { tables: pageSource.tables, loadColumns: loader ?? undefined };
}

/** 为单个编辑器模型指定补全来源；传空时回到页面级来源 */
export function setModelCompletionSource(
  modelUri: string,
  source?: null | SqlCompletionSource,
) {
  if (source) modelSources.set(modelUri, source);
  else modelSources.delete(modelUri);
}

const { CompletionItemKind } = monaco.languages;

function columnItems(
  columns: CompletionColumn[],
  reference: string,
  languageId: string,
): ICompletionItem[] {
  return columns.map((column) => ({
    label: column.name,
    kind: CompletionItemKind.Field,
    insertText: quoteIdentifier(column.name, languageId),
    filterText: column.name,
    detail: [reference, column.dataType, column.comment]
      .filter(Boolean)
      .join(' · '),
    sortText: `0${column.name}`,
  }));
}

/**
 * 语法上下文感知补全：关键字 + 表名 + 字段。
 * 表清单来自数据源元数据；字段按当前语句可访问的表懒加载，“别名.”/“表名.”只补该表的字段，
 * “schema.”只补该 schema 下的表；需要引号的名字按方言加引号插入。
 */
const completionService: CompletionService = async (
  model,
  _position,
  _context,
  suggestions,
  entities,
) => {
  if (!suggestions) {
    return [];
  }
  const source = modelSources.get(model.uri.toString()) ?? pageSource;
  const languageId = model.getLanguageId();
  const { keywords, syntax } = suggestions;
  const words = (type: EntityContextType) =>
    syntax
      .find((item) => item.syntaxContextType === type)
      ?.wordRanges.map((word) => word.text);

  const tableWords = words(EntityContextType.TABLE);
  const columnWords = words(EntityContextType.COLUMN);
  const schema = tableWords ? qualifierBeforeDot(tableWords) : undefined;
  const columnQualifier = columnWords
    ? qualifierBeforeDot(columnWords)
    : undefined;

  const items: ICompletionItem[] = [];
  if (schema) {
    for (const match of tablesInSchema(source.tables, schema)) {
      items.push({
        label: match.filterText,
        kind: CompletionItemKind.Class,
        insertText: match.insertText,
        filterText: match.filterText,
        detail: match.table.comment ? `表 · ${match.table.comment}` : '表',
        sortText: `0${match.filterText}`,
      });
    }
  } else if (tableWords) {
    for (const table of source.tables) {
      items.push({
        label: table.name,
        kind: CompletionItemKind.Class,
        insertText: quoteTableReference(table.name, languageId),
        detail: table.comment ? `表 · ${table.comment}` : '表',
        sortText: `0${table.name}`,
      });
    }
  }

  if (columnWords && entities) {
    const references = columnTableReferences(entities, columnQualifier);
    const groups = await Promise.all(
      references.map(async (reference) => {
        const table = resolveTable(source.tables, reference);
        const columns = await loadCompletionColumns(
          source,
          table?.name ?? reference,
        );
        return columnItems(columns, reference, languageId);
      }),
    );
    items.push(...groups.flat());
  }

  // “限定名.”之后只可能是字段或表，关键字只会干扰
  if (!schema && !columnQualifier) {
    items.push(
      ...keywords.map((keyword) => ({
        label: keyword,
        kind: CompletionItemKind.Keyword,
        detail: '关键字',
        sortText: `2${keyword}`,
      })),
    );
  }
  return items;
};

let initialized = false;

/** 初始化 Monaco 环境（worker、SQL 语言特性、日志语言），只执行一次 */
export function setupMonaco() {
  if (initialized) {
    return;
  }
  initialized = true;

  (globalThis as any).MonacoEnvironment = {
    getWorker(_: unknown, label: string) {
      if (label === LanguageIdEnum.MYSQL) {
        return new MySQLWorker();
      }
      if (label === LanguageIdEnum.PG) {
        return new PGSQLWorker();
      }
      if (label === LanguageIdEnum.SPARK) {
        return new SparkSQLWorker();
      }
      if (label === LanguageIdEnum.FLINK) {
        return new FlinkSQLWorker();
      }
      return new EditorWorker();
    },
  };

  const completionItems = {
    enable: true,
    triggerCharacters: [' ', '.'],
    completionService,
  };
  for (const languageId of [
    LanguageIdEnum.MYSQL,
    LanguageIdEnum.PG,
    LanguageIdEnum.SPARK,
    LanguageIdEnum.FLINK,
  ]) {
    setupLanguageFeatures(languageId, {
      completionItems,
      diagnostics: true,
    });
  }

  // 注册 monaco-sql-languages 官方亮色主题（SQL token 精准着色）
  // 注意：Monaco 主题是全局单例，所有编辑器实例共用，因此统一只使用亮色主题
  monaco.editor.defineTheme('sql-light', {
    ...vsPlusTheme.lightThemeData,
    colors: {
      ...vsPlusTheme.lightThemeData.colors,
      // 代码地图：加深视口滑块，使其类似 IDEA CodeGlance 中的可视区域框
      'minimap.background': '#f8f9fb',
      'minimapSlider.background': '#4c8dff26',
      'minimapSlider.hoverBackground': '#4c8dff40',
      'minimapSlider.activeBackground': '#4c8dff59',
    },
  });

  registerLogLanguage();
}

/** 任务类型 + 数据库产品类型 → 编辑器语言 */
export function resolveSqlLanguage(dbType?: string, taskType?: string): string {
  const type = (taskType ?? '').toUpperCase();
  if (type === 'SPARK_SQL') {
    return LanguageIdEnum.SPARK;
  }
  if (type === 'FLINK_SQL') {
    return LanguageIdEnum.FLINK;
  }
  if (type === 'SHELL') {
    return 'shell';
  }
  const db = (dbType ?? '').toUpperCase();
  if (
    db.includes('POSTGRE') ||
    db.includes('KINGBASE') ||
    db.includes('GAUSS') ||
    db.includes('GREENPLUM')
  ) {
    return LanguageIdEnum.PG;
  }
  return LanguageIdEnum.MYSQL;
}

export { LanguageIdEnum };
