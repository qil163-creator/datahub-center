// @vitest-environment node
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect, it } from 'vitest';

const script = join(
  dirname(fileURLToPath(import.meta.url)),
  'lint-antdv-next-changes.mjs',
);

it.each(['.', 'yudao-ui-admin-vben'])(
  '从 %s 收集提交、基点及工作区内的前端文件',
  (subdir) => {
    const root = mkdtempSync(join(tmpdir(), 'antdv-lint-'));
    const frontend = join(root, subdir);
    const src = 'apps/web-antdv-next/src';
    const page = `${src}/页面 A.vue`;
    const state = `${src}/state.ts`;
    const stylesheetPath = `${src}/new.css`;
    const shim = join(root, 'pnpm.cjs');
    const git = (...args: string[]) =>
      execFileSync(
        'git',
        [
          '-c',
          'user.name=Quality',
          '-c',
          'user.email=quality@example.invalid',
          '-c',
          'commit.gpgSign=false',
          '-c',
          `core.hooksPath=${join(root, 'no-hooks')}`,
          ...args,
        ],
        { cwd: root, stdio: 'pipe' },
      );
    const check = (base = '') =>
      execFileSync(process.execPath, [script], {
        cwd: frontend,
        encoding: 'utf8',
        env: { ...process.env, npm_execpath: shim, QUALITY_BASE_REF: base },
      })
        .split('\n')
        .filter((line) => line.startsWith('['))
        .map((line) => JSON.parse(line) as string[]);

    try {
      mkdirSync(join(frontend, src), { recursive: true });
      writeFileSync(shim, 'console.log(JSON.stringify(process.argv.slice(2)))');
      writeFileSync(join(frontend, state), '');
      git('init', '-q');
      git('add', '.');
      git('commit', '-qm', 'initial');
      const base = git('rev-parse', 'HEAD').toString().trim();
      writeFileSync(join(frontend, page), '');
      git('add', '.');
      git('commit', '-qm', 'add page');

      const initial = check();
      expect(initial).toEqual([
        expect.arrayContaining(['oxlint', page]),
        ['exec', 'eslint', page],
        ['exec', 'stylelint', page],
      ]);
      expect(check(base)).toEqual(initial);

      writeFileSync(join(frontend, state), 'export {};');
      git('add', join(subdir, state));
      writeFileSync(join(frontend, page), '<template />');
      writeFileSync(join(frontend, stylesheetPath), '');
      const working = check();
      expect(working).toEqual([
        expect.arrayContaining(['oxlint', page, state]),
        [
          'exec',
          'eslint',
          ...[page, state].toSorted((a, b) => a.localeCompare(b)),
        ],
        [
          'exec',
          'stylelint',
          ...[page, stylesheetPath].toSorted((a, b) => a.localeCompare(b)),
        ],
      ]);
    } finally {
      rmSync(root, { force: true, recursive: true });
    }
  },
);
