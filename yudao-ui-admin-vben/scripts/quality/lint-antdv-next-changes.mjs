import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { extname, isAbsolute, join, relative, sep } from 'node:path';

const repositoryRoot = process.cwd();
const appSourcePrefix = 'apps/web-antdv-next/src/';
const qualityScriptPrefix = 'scripts/quality/';
const scriptExtensions = new Set([
  '.js',
  '.jsx',
  '.mjs',
  '.ts',
  '.tsx',
  '.vue',
]);
const styleExtensions = new Set(['.css', '.less', '.scss', '.vue']);

function runGit(args, allowFailure = false) {
  const result = spawnSync('git', args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
  });
  if (result.status !== 0 && !allowFailure) {
    throw new Error(
      `git ${args.join(' ')} 执行失败：${result.stderr || result.stdout}`,
    );
  }
  return result.status === 0
    ? result.stdout.trim().split(/\r?\n/u).filter(Boolean)
    : [];
}

function normalizePath(file) {
  const absoluteFile = isAbsolute(file) ? file : join(repositoryRoot, file);
  return relative(repositoryRoot, absoluteFile).split(sep).join('/');
}

function collectChangedFiles() {
  const explicitFiles = process.argv.slice(2).filter((file) => file !== '--');
  if (explicitFiles.length > 0) {
    return explicitFiles.map((file) => normalizePath(file));
  }

  const baseRef = process.env.QUALITY_BASE_REF;
  if (baseRef) {
    if (!/^[./\w-]+$/u.test(baseRef)) {
      throw new Error('QUALITY_BASE_REF 包含不允许的字符。');
    }
    return runGit([
      'diff',
      '--name-only',
      '--diff-filter=ACMR',
      `${baseRef}...HEAD`,
    ]);
  }

  const workingFiles = new Set([
    ...runGit(['diff', '--name-only', '--diff-filter=ACMR']),
    ...runGit(['diff', '--cached', '--name-only', '--diff-filter=ACMR']),
    ...runGit(['ls-files', '--others', '--exclude-standard']),
  ]);
  if (workingFiles.size > 0) {
    return [...workingFiles];
  }

  return runGit(
    ['diff', '--name-only', '--diff-filter=ACMR', 'HEAD^', 'HEAD'],
    true,
  );
}

function runPnpm(command, files) {
  if (files.length === 0) {
    return;
  }
  const pnpmScript = process.env.npm_execpath;
  // pnpm 支持 JavaScript CLI 和独立原生二进制，后者不能交给 node 解释。
  const isJavaScriptCli = pnpmScript && /\.[cm]?js$/u.test(pnpmScript);
  const executable = isJavaScriptCli ? process.execPath : (pnpmScript || 'pnpm');
  // pnpm exec invokes Windows command shims whose total command line is limited
  // to 8191 characters. Bound each file batch, retaining every path and the same
  // checker options. Leave room for the executable, pnpm shim and quoting.
  const maximumFileCharacters = process.platform === 'win32' ? 5500 : Infinity;
  const batches = [];
  let batch = [];
  let characters = 0;
  for (const file of files) {
    const cost = file.length + 3;
    if (batch.length > 0 && characters + cost > maximumFileCharacters) {
      batches.push(batch);
      batch = [];
      characters = 0;
    }
    batch.push(file);
    characters += cost;
  }
  if (batch.length > 0) batches.push(batch);
  for (const paths of batches) {
    const args = isJavaScriptCli ? [pnpmScript, ...command(paths)] : command(paths);
    const result = spawnSync(executable, args, {
      cwd: repositoryRoot,
      stdio: 'inherit',
    });
    if (result.status !== 0) {
      if (result.error) console.error(result.error);
      process.exit(result.status ?? 1);
    }
  }
}

const changedFiles = [...new Set(collectChangedFiles())]
  .map((file) => normalizePath(file))
  .filter(
    (file) =>
      (file.startsWith(appSourcePrefix) ||
        file.startsWith(qualityScriptPrefix)) &&
      existsSync(join(repositoryRoot, file)),
  )
  .toSorted((left, right) => left.localeCompare(right));
const scriptFiles = changedFiles.filter((file) =>
  scriptExtensions.has(extname(file)),
);
const styleFiles = changedFiles.filter((file) =>
  styleExtensions.has(extname(file)),
);

if (scriptFiles.length === 0 && styleFiles.length === 0) {
  console.log('没有需要 lint 的 Antdv Next 变更文件。');
  process.exit(0);
}

console.log('检查 Antdv Next 变更文件：');
for (const file of changedFiles) {
  console.log(`- ${file}`);
}

runPnpm(
  (files) => [
    'exec',
    'oxlint',
    '--type-aware',
    '--threads=4',
    '--no-error-on-unmatched-pattern',
    ...files,
  ],
  scriptFiles,
);
runPnpm((files) => ['exec', 'eslint', ...files], scriptFiles);
runPnpm((files) => ['exec', 'stylelint', ...files], styleFiles);
