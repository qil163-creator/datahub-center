import type {
  CompletionService,
  ICompletionItem,
} from 'monaco-sql-languages';

import * as monaco from 'monaco-editor';
import {
  EntityContextType,
  LanguageIdEnum,
  setupLanguageFeatures,
  vsPlusTheme,
} from 'monaco-sql-languages';

import { registerLogLanguage } from './javalog';
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

/** 编辑器补全元数据（页面按当前数据源填充） */
interface TableMeta {
  name: string;
  comment?: string;
}
interface ColumnMeta {
  name: string;
  comment?: string;
  dataType?: string;
}

const completionTables: TableMeta[] = [];
const completionColumns = new Map<string, ColumnMeta[]>();
let columnLoader: ((tableName: string) => Promise<ColumnMeta[]>) | null = null;

/** 设置当前数据源的表清单（切换数据源时调用） */
export function setCompletionTables(tables: TableMeta[]) {
  completionTables.length = 0;
  completionTables.push(...tables);
  completionColumns.clear();
}

/** 注册字段懒加载器（补全触发到字段上下文时按表名拉取） */
export function setColumnLoader(
  loader: ((tableName: string) => Promise<ColumnMeta[]>) | null,
) {
  columnLoader = loader;
}

async function loadColumns(tableName: string): Promise<ColumnMeta[]> {
  const key = tableName.toLowerCase();
  const cached = completionColumns.get(key);
  if (cached) {
    return cached;
  }
  if (!columnLoader) {
    return [];
  }
  try {
    const columns = await columnLoader(tableName);
    completionColumns.set(key, columns);
    return columns;
  } catch {
    completionColumns.set(key, []);
    return [];
  }
}

/**
 * 语法上下文感知补全：关键字 + 表名 + 字段
 * （表清单来自数据源元数据；字段按当前语句涉及的表懒加载）
 */
const completionService: CompletionService = async (
  _model,
  _position,
  _context,
  suggestions,
  entities,
) => {
  if (!suggestions) {
    return [];
  }
  const { keywords, syntax } = suggestions;
  const items: ICompletionItem[] = keywords.map((keyword) => ({
    label: keyword,
    kind: monaco.languages.CompletionItemKind.Keyword,
    detail: '关键字',
    sortText: `2${keyword}`,
  }));

  const needTable = syntax.some(
    (item) =>
      item.syntaxContextType === EntityContextType.TABLE ||
      item.syntaxContextType === EntityContextType.TABLE_CREATE,
  );
  const needColumn = syntax.some(
    (item) => item.syntaxContextType === EntityContextType.COLUMN,
  );

  if (needTable) {
    for (const table of completionTables) {
      items.push({
        label: table.name,
        kind: monaco.languages.CompletionItemKind.Class,
        detail: table.comment ? `表 · ${table.comment}` : '表',
        sortText: `0${table.name}`,
      });
    }
  }

  if (needColumn && entities) {
    // 当前语句涉及的表 → 懒加载字段
    const tableNames = entities
      .filter((entity) => entity.entityContextType === EntityContextType.TABLE)
      .map((entity) => entity.text.replaceAll(/[`"']/g, ''))
      .filter(Boolean);
    for (const tableName of [...new Set(tableNames)].slice(0, 5)) {
      const columns = await loadColumns(tableName);
      for (const column of columns) {
        items.push({
          label: column.name,
          kind: monaco.languages.CompletionItemKind.Field,
          detail: [tableName, column.dataType, column.comment]
            .filter(Boolean)
            .join(' · '),
          sortText: `1${column.name}`,
        });
      }
    }
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
