# 表输入组件隔离视觉验证

日期：2026-11-03。范围：`scope=fixture`。机器可读结果见 `verification.json`。

## 方法与边界

忽略目录 `apps/web-antdv-next/.temp/table-input-reference` 导入本次正式 `DatabaseNodeEditor`、`ReferenceNodeBasics`、`designer.css` 与 `integrationReferenceTheme`，使用真实 Antdv Next 控件。连接、类型、表、字段接口统一返回静态元数据；写入请求明确拒绝。没有保存真实任务，没有执行数据同步。

REA 每个场景以 `launch` 新建独立 headless Chromium 156.0.8078.76，结束后由 provider 清理。未接管用户浏览器或 9336 会话。夹具服务位于 6008，验证结束后已停止。

## 有效截图

| 文件 | 范围 | 观察 |
| --- | --- | --- |
| `full-1823.png` | 1823×1040，全量 | 包含节点描述、连接、读取方式、where、分批开关与字段表 |
| `id-1823.png` | 1823×1040，ID增量 | 数值字段与超安全范围十进制字符串起始值可回显 |
| `time-1823.png` | 1823×1040，时间增量 | 时间格式、条件新增与空表可见 |
| `batch-id-1823.png` | 1823×1040，ID分批 | 起止、跨度、重试配置可见 |
| `batch-time-1823.png` | 1823×1040，时间分批 | 时间单位、跨度、起止日期、重试配置可见 |
| `condition-modal-1823.png` | 1823×1040，新增条件 | 真实点击新增后弹窗显示于父弹窗之上 |
| `picker-1823.png` | 1823×1040，连接选择器 | 真实点击打开三列分类/类型/连接，数量与元数据一致 |
| `full-1200.png` | 1200×900 | 表单无横向溢出，操作按钮可见 |
| `full-768.png` | 768×1024 | 表单响应式排列，操作按钮可见 |
| `full-390.png` | 390×844 | 单列布局，内容在弹窗内纵向滚动，操作按钮可见 |

上述10个有效场景均未捕获到 `page-error` 或 console error。除早期 picker 场景未加入自动布局日志外，另外9个场景均记录 `documentWidth == viewportWidth`、`modalScrollWidth == modalWidth`、表单控件超出视口数量0。picker 的布局结论仅来自截图目视检查，不将缺少测量当作自动检查通过。

此外，真实点击打开了基准类型下拉，观察到固定值、时间范围、SQL表达式三个选项。该场景未产生截图，REA Evidence ID 见 JSON。

## 未完成项与发现

- 三种基准分支的完整日期选择、SQL编辑及保存补测未完成。期间6008进程收到 SIGTERM，后续导航失败；这些失败尝试没有计入通过场景。
- 第一次 FULL 截图缺少描述行。当前 `full-1823.png` 已被后续含描述的有效结果替换，SHA-256 与字节数保存在 JSON。空白截图没有作为证据保留。
- 实测 Antdv Next Modal 没有将 `data-component-code` 转发到 dialog DOM，基于此属性的13px顶部规则未命中。已交根任务改为显式class，并由正式5999页面复验。这里的截图不证明最终顶部规则已生效。
- 额外重启时出现 Vite `Outdated Optimize Dep`；夹具随后设置独立 cacheDir，避免与5999共用优化缓存。该失败尝试不属于有效截图。
- 这些结果只覆盖组件夹具；正式5999页面保存、重新读取、网关请求和后端执行需看根任务的独立验收，不能由本记录推断。
