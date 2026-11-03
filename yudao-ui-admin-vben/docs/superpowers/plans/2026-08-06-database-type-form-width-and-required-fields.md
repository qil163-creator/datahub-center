# Database Type Form Width and Required Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将数据库类型定义弹窗固定为 1024px 宽，并为截图标出的 9 个业务字段增加必填校验。

**Architecture:** 保留现有 Vue 3 `<script setup>` 组件边界。`data.ts` 继续作为表单 schema 的唯一来源，`form.vue` 继续负责 Modal 与表单编排；测试直接验证 schema 行为和表单组件向 Modal 传递的宽度类。

**Tech Stack:** Vue 3、TypeScript、Vben Form、Vben Modal、Vitest、happy-dom、pnpm、SVN。

## Global Constraints

- Modal 固定宽度为 `1024px`，使用项目既有的 `w-[1024px]` 类写法。
- 保持单列表单、字段顺序、滚动区域和操作按钮不变。
- 新增必填字段仅限 `typeCode`、`typeName`、`categoryCode`、`protocolType`、`iconPath`、`urlTemplates`、`portTemplates`、`driverClass`、`validationQuery`。
- `sort`、`status` 继续必填；`remark` 继续可选。
- 不修改列表搜索表单、接口类型、后端校验或其他工作副本内容。
- 当前为 SVN 工作副本；只检查本地差异，不执行 `svn commit`。

## File Map

- `apps/web-antdv-next/src/views/foundation/databasetype/data.ts`：定义新增/编辑表单 schema；只补充 required 规则。
- `apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts`：验证必填字段集合和可选字段边界。
- `apps/web-antdv-next/src/views/foundation/databasetype/modules/form.vue`：数据库类型弹窗编排；只补充宽度类。
- `apps/web-antdv-next/src/views/foundation/databasetype/modules/form.test.ts`：验证表单组件将 1024px 宽度类传给 Modal。

---

### Task 1: 表单必填规则

**Files:**
- Create: `apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts`
- Modify: `apps/web-antdv-next/src/views/foundation/databasetype/data.ts:21-91`

**Interfaces:**
- Consumes: `useFormSchema(): VbenFormSchema[]`
- Produces: 11 个 `rules: 'required'` 字段，其中 9 个为本次新增，`sort`、`status` 为既有规则。

- [x] **Step 1: 写入失败测试**

```ts
import { describe, expect, it, vi } from 'vitest';

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => []),
}));

import { useFormSchema } from './data';

describe('database type form schema', () => {
  it('requires every database connection field marked in the form', () => {
    const requiredFields = useFormSchema()
      .filter(({ rules }) => rules === 'required')
      .map(({ fieldName }) => fieldName);

    expect(requiredFields).toEqual([
      'typeCode',
      'typeName',
      'categoryCode',
      'protocolType',
      'iconPath',
      'urlTemplates',
      'portTemplates',
      'driverClass',
      'validationQuery',
      'sort',
      'status',
    ]);
  });

  it('keeps remark optional', () => {
    const remark = useFormSchema().find(({ fieldName }) => fieldName === 'remark');

    expect(remark?.rules).toBeUndefined();
  });
});
```

- [x] **Step 2: 运行测试并确认因缺失新增规则而失败**

Run:

```powershell
pnpm exec vitest run apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts --config apps/web-antdv-next/vite.config.ts --environment happy-dom
```

Expected: 第一个用例失败；实际 required 字段只有 `sort`、`status`。第二个用例通过。

- [x] **Step 3: 写入最小实现**

在以下 9 个 schema 项的 `label` 与 `component` 之间增加同一规则：

```ts
rules: 'required',
```

字段：`typeCode`、`typeName`、`categoryCode`、`protocolType`、`iconPath`、`urlTemplates`、`portTemplates`、`driverClass`、`validationQuery`。

- [x] **Step 4: 运行测试并确认通过**

Run:

```powershell
pnpm exec vitest run apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts --config apps/web-antdv-next/vite.config.ts --environment happy-dom
```

Expected: 2 tests passed。

### Task 2: 弹窗宽度

**Files:**
- Create: `apps/web-antdv-next/src/views/foundation/databasetype/modules/form.test.ts`
- Modify: `apps/web-antdv-next/src/views/foundation/databasetype/modules/form.vue:78-81`

**Interfaces:**
- Consumes: `Modal` component returned by `useVbenModal()`。
- Produces: `<Modal class="w-[1024px]" :title="getTitle">`。

- [x] **Step 1: 写入失败的组件测试**

测试使用 Vue `createApp` 挂载真实 `form.vue`，仅替换网络 API、Vben Form 和 Modal 边界；Modal 测试替身把收到的 `class` 渲染到可观察 DOM 上。项目未直接依赖 `@vue/test-utils`，因此不新增依赖：

```ts
import { createApp } from 'vue';
import { describe, expect, it, vi } from 'vitest';

const { formApi, modalApi } = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(async () => ({})),
    setValues: vi.fn(async () => undefined),
    validate: vi.fn(async () => ({ valid: true })),
  },
  modalApi: {
    close: vi.fn(async () => undefined),
    getData: vi.fn(() => undefined),
    lock: vi.fn(),
    unlock: vi.fn(),
  },
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await vi.importActual<typeof import('vue')>(
    'vue',
  );

  return {
    useVbenModal: vi.fn(() => [
      defineComponent({
        inheritAttrs: false,
        setup(_props, { attrs, slots }) {
          return () =>
            h(
              'section',
              { ...attrs, 'data-testid': 'database-type-modal' },
              slots.default?.(),
            );
        },
      }),
      modalApi,
    ]),
  };
});

vi.mock('antdv-next', () => ({
  message: {
    success: vi.fn(),
  },
}));

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await vi.importActual<typeof import('vue')>(
    'vue',
  );

  return {
    useVbenForm: vi.fn(() => [
      defineComponent({
        inheritAttrs: false,
        setup(_props, { attrs }) {
          return () => h('form', attrs);
        },
      }),
      formApi,
    ]),
  };
});

vi.mock('#/api/foundation/databasetype', () => ({
  createDatabaseType: vi.fn(),
  getDatabaseType: vi.fn(),
  updateDatabaseType: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: vi.fn((key: string) => key),
}));

vi.mock('../data', () => ({
  useFormSchema: vi.fn(() => []),
}));

import DatabaseTypeForm from './form.vue';

describe('database type form modal', () => {
  it('renders the modal at 1024px wide', () => {
    const container = document.createElement('div');
    const app = createApp(DatabaseTypeForm);

    app.mount(container);

    expect(
      container
        .querySelector('[data-testid="database-type-modal"]')
        ?.classList.contains('w-[1024px]'),
    ).toBe(true);

    app.unmount();
  });
});
```

- [x] **Step 2: 运行测试并确认因缺失宽度类而失败**

Run:

```powershell
pnpm exec vitest run apps/web-antdv-next/src/views/foundation/databasetype/modules/form.test.ts --config apps/web-antdv-next/vite.config.ts --environment happy-dom
```

Expected: 宽度断言失败，DOM 中没有 `w-[1024px]`。

- [x] **Step 3: 写入最小实现**

```vue
<Modal :title="getTitle" class="w-[1024px]">
  <Form class="mx-4" />
</Modal>
```

- [x] **Step 4: 运行测试并确认通过**

Run:

```powershell
pnpm exec vitest run apps/web-antdv-next/src/views/foundation/databasetype/modules/form.test.ts --config apps/web-antdv-next/vite.config.ts --environment happy-dom
```

Expected: 1 test passed。

### Task 3: 综合验证与差异审查

**Files:**
- Verify: `apps/web-antdv-next/src/views/foundation/databasetype/data.ts`
- Verify: `apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts`
- Verify: `apps/web-antdv-next/src/views/foundation/databasetype/modules/form.vue`
- Verify: `apps/web-antdv-next/src/views/foundation/databasetype/modules/form.test.ts`

**Interfaces:**
- Consumes: Task 1 和 Task 2 的本地修改。
- Produces: 通过的针对性测试、前端类型检查和限定范围的 SVN 差异证据。

- [ ] **Step 1: 同时运行两个回归测试**

```powershell
pnpm exec vitest run apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts apps/web-antdv-next/src/views/foundation/databasetype/modules/form.test.ts --config apps/web-antdv-next/vite.config.ts --environment happy-dom
```

Expected: 2 test files passed，3 tests passed。

- [ ] **Step 2: 运行目标应用类型检查**

```powershell
pnpm --filter @vben/web-antdv-next run typecheck
```

Expected: exit code 0。

- [ ] **Step 3: 检查格式与限定范围差异**

```powershell
pnpm exec oxfmt --check apps/web-antdv-next/src/views/foundation/databasetype/data.ts apps/web-antdv-next/src/views/foundation/databasetype/data.test.ts apps/web-antdv-next/src/views/foundation/databasetype/modules/form.vue apps/web-antdv-next/src/views/foundation/databasetype/modules/form.test.ts
svn diff apps/web-antdv-next/src/views/foundation/databasetype/data.ts apps/web-antdv-next/src/views/foundation/databasetype/modules/form.vue
svn status apps/web-antdv-next/src/views/foundation/databasetype docs/superpowers
```

Expected: 格式检查 exit code 0；生产代码差异仅包含 9 条 required 规则和一个 Modal 宽度类；不提交 SVN。
