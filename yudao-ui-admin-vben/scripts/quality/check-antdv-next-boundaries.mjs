import { readdirSync, readFileSync } from 'node:fs';
import { extname, join, relative, sep } from 'node:path';

const repositoryRoot = process.cwd();
const sourceRoot = join(repositoryRoot, 'apps', 'web-antdv-next', 'src');
const sourceExtensions = new Set([
  '.js',
  '.jsx',
  '.mjs',
  '.ts',
  '.tsx',
  '.vue',
]);
const violations = [];

function toRepositoryPath(file) {
  return relative(repositoryRoot, file).split(sep).join('/');
}

function lineAt(content, index) {
  return content.slice(0, index).split(/\r?\n/u).length;
}

function report(file, content, match, message) {
  violations.push(
    `${toRepositoryPath(file)}:${lineAt(content, match.index)} ${message}`,
  );
}

function checkFile(file) {
  const repositoryPath = toRepositoryPath(file);
  const content = readFileSync(file, 'utf8');
  const checks = [
    {
      pattern: /(?:from\s+|import\s*\()\s*['"]ant-design-vue(?:['"/])/gu,
      message: '活动应用禁止导入 ant-design-vue，请使用 antdv-next。',
    },
    {
      pattern: /(?:from\s+|import\s*\()\s*['"]axios(?:['"/])/gu,
      message: '业务代码不得直接导入 axios，请复用 #/api/request。',
    },
  ];

  if (
    !repositoryPath.startsWith('apps/web-antdv-next/src/adapter/component/')
  ) {
    checks.push({
      pattern: /['"]antdv-next\/dist\//gu,
      message: 'antdv-next/dist 深层导入只允许存在于集中 component adapter。',
    });
  }

  if (repositoryPath.startsWith('apps/web-antdv-next/src/api/')) {
    checks.push({
      pattern: /(?:'|"|\u0060)\/(?:admin-api|app-api)\//gu,
      message: 'API 资源路径不得重复网关的 /admin-api 或 /app-api 前缀。',
    });
  }

  if (repositoryPath !== 'apps/web-antdv-next/src/api/request.ts') {
    checks.push({
      pattern: /new\s+RequestClient\s*\(/gu,
      message: 'RequestClient 只能在 #/api/request 中集中创建。',
    });
  }

  for (const check of checks) {
    for (const match of content.matchAll(check.pattern)) {
      report(file, content, match, check.message);
    }
  }
}

function walk(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(file);
    } else if (sourceExtensions.has(extname(entry.name))) {
      checkFile(file);
    }
  }
}

walk(sourceRoot);

if (violations.length > 0) {
  console.error('Antdv Next 边界检查失败：');
  for (const violation of violations) {
    console.error(`- ${violation}`);
  }
  process.exitCode = 1;
} else {
  console.log('Antdv Next 边界检查通过。');
}
