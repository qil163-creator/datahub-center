# Database Type Icon Thumbnail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the database type list's `iconPath` value as a clickable image thumbnail instead of URL text.

**Architecture:** Keep the change inside the existing table-column configuration. Reuse the globally registered `CellImage` VXE renderer already used by the database-driver and system-source lists; do not add a component, state, API transformation, or backend change.

**Tech Stack:** Vue 3.5, TypeScript, Vben VXE Grid, `antdv-next` Image, Vitest, pnpm workspace.

## Global Constraints

- Modify only the `web-antdv-next` database-type column configuration and its focused unit test.
- Keep the API field name `iconPath` and all form/upload behavior unchanged.
- Change the visible column title from `数据源图标路径` to `数据源图标`.
- Use the existing `CellImage` renderer so thumbnail preview and image behavior stay consistent with other project lists.
- Preserve all unrelated SVN working-copy changes.
- The repository is an SVN working copy and remote authentication is unavailable in the current environment, so commit is deferred to the user.

## File Structure

- `apps/web-antdv-next/src/views/foundation/databasetype/data.ts`: owns database-type form schemas and table-column declarations; only the `iconPath` column changes.
- `apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts`: focused contract test for the `iconPath` column returned by `useGridColumns`.

---

### Task 1: Render database type icons with the shared image renderer

**Files:**

- Create: `apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts`
- Modify: `apps/web-antdv-next/src/views/foundation/databasetype/data.ts:239`

**Interfaces:**

- Consumes: `useGridColumns(): VxeTableGridOptions<FoundationDatabaseTypeApi.DatabaseType>['columns']` and the globally registered VXE renderer named `CellImage`.
- Produces: an `iconPath` column titled `数据源图标` with `cellRender: { name: 'CellImage' }`.

- [ ] **Step 1: Write the failing column-contract test**

```ts
import { describe, expect, it } from 'vitest';

import { useGridColumns } from './data';

describe('useGridColumns', () => {
  it('configures the data-source icon column as an image', () => {
    const iconColumn = useGridColumns()?.find(
      (column) => column.field === 'iconPath',
    );

    expect(iconColumn).toMatchObject({
      cellRender: {
        name: 'CellImage',
      },
      field: 'iconPath',
      title: '数据源图标',
    });
  });
});
```

This test catches removal of the image renderer, selection of the wrong renderer, or regression to a path-oriented column label. It exercises the real column factory without mocking the renderer configuration.

- [ ] **Step 2: Run the focused test and verify RED**

Run from the repository root:

```powershell
pnpm --filter @vben/web-antdv-next exec vitest run src/views/foundation/databasetype/data.test.ts --environment happy-dom
```

Expected: one failing test. The received `iconPath` column has title `数据源图标路径` and no `cellRender`, so it does not match the expected object.

- [ ] **Step 3: Add the minimal image-renderer configuration**

Change only the `iconPath` column in `useGridColumns`:

```ts
{
  field: 'iconPath',
  title: '数据源图标',
  minWidth: 120,
  cellRender: {
    name: 'CellImage',
  },
},
```

- [ ] **Step 4: Run the focused test and verify GREEN**

```powershell
pnpm --filter @vben/web-antdv-next exec vitest run src/views/foundation/databasetype/data.test.ts --environment happy-dom
```

Expected: one test file and one test pass with zero failures.

- [ ] **Step 5: Run static verification**

```powershell
pnpm --filter @vben/web-antdv-next run typecheck
```

Expected: `vue-tsc --noEmit --skipLibCheck` exits with code 0.

- [ ] **Step 6: Review the exact SVN change set**

```powershell
svn diff apps/web-antdv-next/src/views/foundation/databasetype/data.ts
svn status apps/web-antdv-next/src/views/foundation/databasetype
```

Expected: `data.ts` contains only the title and `CellImage` configuration change; `data.test.ts` is the only new implementation test. Do not commit or alter unrelated working-copy paths.
