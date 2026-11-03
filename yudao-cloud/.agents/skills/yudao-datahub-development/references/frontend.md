# Frontend Constraints

Read this reference for frontend implementation or review in `yudao-ui-admin-vben`.

## Scope and Tooling

- Put new business pages and APIs in `apps/web-antdv-next` unless the user explicitly requests another app or a shared package.
- Do not mirror changes into `web-antd`, `web-ele`, `web-naive`, or `web-tdesign`.
- Use pnpm only. Read the root and app `package.json`, `.node-version`, workspace catalog, TypeScript configuration, and current scripts rather than preserving versions in this skill.
- Use `antdv-next`; reject new `ant-design-vue` imports. Business pages must not add `antdv-next/dist/**` imports. The centralized component adapters are the current deliberate lazy-loading exception; changes there require wider typecheck/build verification.
- Use TypeScript Composition API and `script setup`. Prefer `import type`; follow strict TypeScript behavior from the active configuration.

## Requests and Types

- Ordinary APIs use `#/api/request` `requestClient`. Do not create another Axios/fetch client in a page.
- The client already adds shared headers, handles `code === 0`, reports global errors, and returns the unwrapped `data`; callers must not use `result.data` again.
- Use `baseRequestClient` only for an established special platform protocol such as token refresh, captcha, or obtaining/finalizing a presigned upload. It does not provide the full ordinary authentication/business-error behavior, and its current interceptors may still attach platform context headers; never send it directly to an external absolute URL until the concrete client path proves those headers are absent. The caller owns the response, headers, status, redirect, and error contract.
- Do not duplicate a global error toast in a page. Use `silentError: true` only for an explicitly optional/degraded request that has a local fallback and still releases loading state.
- The frontend calls the gateway. API functions use resource paths without duplicating `/admin-api`.
- Prefer `requestClient.upload` and `requestClient.download` for the established upload/download protocols.
- Define a concrete feature `PageReqVO extends PageParam`; do not expose the broad indexed `PageParam` as the final endpoint contract. Map grid `currentPage` to backend `pageNo` and preserve `pageSize`, `list`, and `total`.
- Derive request and response types from the current backend contract. Split create/update/detail types when their shapes differ rather than forcing one entity type onto every request.
- Declare the real return type for create, update, delete, batch, and collection APIs. Prefer `{ params }` over hand-built, unescaped query strings. The current request client repeats array query keys by default; add an explicit serializer only when the backend contract requires a different representation, and cover that representation with a contract test.

## Lossless IDs and Dates

- A backend `Long` may arrive as JSON `number` or decimal `string` depending on its value. For new or changed contracts, use a lossless ID type such as `string | number` or normalize to a decimal string at the API boundary. Never pass an ID through `Number()`, unary `+`, `parseInt()`, arithmetic, or another lossy conversion. Keep route params as validated strings; normalize comparisons, map keys, and tree/table keys with `String(id)` when needed.
- Do not put native `bigint` into a JSON request body without a deliberate serializer and matching backend contract.
- Do not assume all Java date/time types are strings. Inspect the response VO and `@JsonFormat`: the current default `LocalDateTime` representation is epoch milliseconds, while explicitly formatted fields follow their annotation. `LocalDate` and `LocalTime` follow their actual endpoint contract.
- API DTOs represent JSON primitives, not `Date` or `Dayjs` objects. Keep `Dayjs` in the UI/form model and use the form-level typed `codec`, registered timestamp picker formats such as `x`, and shared date helpers to convert at the boundary.
- Keep money, ratios, measurements, and other exact decimals as decimal strings when that is the backend wire contract. Do not pass them through `Number`, `parseFloat`, native arithmetic, or `toFixed`; use the project's explicitly adopted decimal implementation when calculation is required and serialize the canonical decimal string expected by the backend. A TypeScript `string` declaration is insufficient unless the raw backend JSON is also a string.
- New form dependencies use the current `dependencies.resolve` API. Do not add deprecated dependency shortcuts or deprecated schema time-mapping fields.

## 异步状态与登录身份变化

- Apply latest-wins semantics to dependent options, dictionaries, detail loads, previews, validation, and search results. Use cancellation or a monotonically increasing request identity and let only the request matching the current authenticated user, owner selection, route/entity identity, and component lifetime mutate state.
- A shared loading boolean is unsafe when requests overlap; bind loading/error state to the current request or use a counter. Clear it in `finally` without allowing an older request to hide a newer one.
- DataHub 字典和选项按实际的平台/用户范围缓存，不依赖租户 store 或切换。登出、身份、路由或对象选择变化时丢弃旧响应，并清理新用户无权访问的数据。
- Clean up watchers, timers, subscriptions, object URLs, and in-flight requests when a modal/page is reset or unmounted.

## Standard CRUD

A conventional CRUD feature usually contains:

```text
src/api/<domain>/<feature>/index.ts
src/views/<domain>/<feature>/data.ts
src/views/<domain>/<feature>/index.vue
src/views/<domain>/<feature>/modules/form.vue
```

This is preferred, not universal. Dashboards, wizards, editors, and detail pages may use a different shape.

- Forms: use `#/adapter/form`, `useVbenForm`, its exported `z`, and currently registered schema components. New conditional fields use the current dependency resolver API rather than legacy compatibility syntax.
- Tables: ordinary paged CRUD uses `#/adapter/vxe-table`, `useVbenVxeGrid`, `TableAction`, and a stable `rowConfig.keyField` that does not coerce IDs. Complex embedded or visual tables may use direct `antdv-next` components.
- Dictionaries: reuse `DICT_TYPE`, `getDictOptions`, and `CellDict`; do not duplicate labels and status maps in individual pages.
- Modals: use the connected-component pattern when it fits. Fetch current detail by its lossless ID on edit instead of treating a list row as the complete entity. Validate before submit, lock during async work, unlock in `finally`, close on success, emit success, and refresh the list. Destroy or explicitly reset modal form/loading/local state between openings.
- Deletion: require confirmation and close loading state in `finally`.
- Clear selected rows after successful batch deletion. For asynchronously loaded options, prevent a stale response from overwriting a later selection.
- Components: search `@vben/common-ui`, `@vben/hooks`, `@vben/utils`, `#/adapter`, `#/components`, then the current feature before creating another abstraction. Keep domain-aware reuse in the app or feature. Move code to `packages` only when a real cross-app consumer exists and the code does not depend on app APIs, dictionaries, permissions, or domain models.

### 操作列统一右侧冻结

- 新建业务列表、树表、接入代码生成结果及修改现有操作列时，操作列统一置于最右侧，使用表格组件原生的右侧冻结能力。VXE 列显式配置 `fixed: 'right'` 和 `showOverflow: false`，避免继承全局省略设置而裁切按钮；其他表格组件采用其对应的原生固定列与横向滚动配置。
- 操作列使用足够的显式宽度，按当前实际按钮文案、图标、间距及“更多”入口测量，并覆盖有权显示全部操作的情况。不要机械复用 `200px` 或统一硬编码同一宽度；例如“新增下级、修改、更多”已验证可使用 `260px`，四项直出操作已验证可使用 `290px`，仍需按实际内容确认。保留既有 `TableAction`、权限、图标及操作入口，不以隐藏操作解决裁切。

```ts
{
  title: '操作',
  width: 260, // 示例宽度，按实际按钮内容调整
  fixed: 'right',
  showOverflow: false,
  slots: { default: 'actions' },
}
```

- 验收覆盖正常宽度和至少一个实际产生横向溢出的窄容器：将表格横向滚至两端，确认操作列位置保持不变、按钮完整位于单元格及可视区域内；按现有触发方式展开“更多”、确认等浮层，确认菜单和内容未被表格裁切。只检查配置或通过类型检查不能代替这项显示验证。

## Dictionaries and Domain Enums

- Pass the DTO's actual value type to `getDictOptions`; numeric statuses must request numeric values rather than relying on its string default.
- Use `CellDict` in grids and the current dictionary tag component for ordinary display. Submit the code/value, never the presentation label, and render unknown values with an explicit fallback.
- For a domain enum that is not a system dictionary, keep one typed constant/display map. Do not invent a `DICT_TYPE` or duplicate magic values across pages.

## Routes and Permissions

- Ordinary business menus are delivered by the backend. Do not add a duplicate static menu route for a standard CRUD page.
- In backend access mode, route `meta.authority`, hidden controls, and frontend permission helpers are presentation gates only. They neither grant authority nor replace backend `@PreAuthorize` and row/object-scope checks; do not treat backend-delivered route metadata as a trusted authorization decision for an API call.
- Keep backend menu component paths resolvable under `views/**`; files under a feature's `modules/` are not menu entry pages.
- Use static route modules only for auxiliary details, designers, redirects, and similar pages. Give each a unique name/path, hide it from the menu where appropriate, set the correct `activePath`, and implement predictable return navigation. Route params remain lossless strings.
- Copy permission strings from the current backend `@PreAuthorize` and menu SQL into `TableAction.auth`; never infer them solely from naming convention. Arrays passed to `TableAction.auth` or `hasAccessByCodes` have any-of/OR semantics. Use one exact code for normal actions; implement an AND requirement explicitly rather than listing multiple codes.
- Client visibility is presentation only. Backend endpoints must enforce operation and row-level authorization.

## Uploads, Downloads, and Exports

- Model a presigned upload as at least two successful stages: obtain a user-scoped upload grant, transfer the bytes, then finalize/register owned platform metadata. Do not report completion or persist the business reference until finalization succeeds. Handle expired grants, duplicate finalization, checksum/size/type verification, identity changes during upload, retry, cancellation, and orphan cleanup with the backend contract.
- Use the established authenticated download/export client. Do not fetch an entire protected dataset into the browser to create an export, and do not reuse a presigned URL across users. Loading, failure, cancellation, and filename/content-disposition handling must remain truthful.
- Backend export authorization and data scope are authoritative. The UI may constrain selections and warn about size, but it cannot widen rows/columns, include hidden sensitive fields, or turn an expired/failed export job into success. Read `references/security.md` for spreadsheet-formula, signed URL, and audit boundaries.

## Content Safety

- Server- or user-controlled HTML must use the existing DOM-purification directive or be sanitized before rendering. Raw `v-html` is allowed only for demonstrably trusted internal content with the reason documented.

## Source Anchors

- root `README.md`, `package.json`, `.node-version`, `pnpm-workspace.yaml`, and `lefthook.yml`;
- `apps/web-antdv-next/src/api/request.ts`;
- `apps/web-antdv-next/src/adapter/{form,vxe-table}.ts` and adapter component registry;
- backend `NumberSerializer`, `TimestampLocalDateTimeSerializer`, and Jackson auto-configuration when defining wire types;
- tracked CRUD slices such as `views/system/post` and `views/dataforge/databasetype`, while preferring current APIs over their legacy names;
- router access, backend route generation, auth store, and table-action component;
- current Zod/form migration documentation for deprecated API decisions.
