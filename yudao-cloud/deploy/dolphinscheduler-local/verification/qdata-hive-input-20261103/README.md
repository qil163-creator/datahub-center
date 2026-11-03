# Hive 输入组件参考交互验证

参考来源：用户本轮提供的 11 张 Hive 输入截图，及已缓存的 `/private/tmp/qdata-20261102-forms/hiveForm-B08U2D1l.js`。截图内容只作为产品行为与视觉资料。

本次对齐：全量七列只读属性；ID/时间模式隐藏主属性；时间格式 240px；条件表 310px；条件编辑弹窗 912px；固定日期选择器 220px；SQL 编辑区 140px，解析按钮位于实例行右侧。SQL 分支隐藏读取模式和 where，但保留并展示此前的时间条件。全量、ID、时间和 SQL 之间切换保留相应草稿，持久配置沿用现有 `incrementStart` / `dateFormat` 键。

验证范围：现有 `localhost:5999` 上 `.temp/odps-input-reference/index.html?tool=hive&case=...` 的受控夹具，使用真实组件、Antdv 控件与 Monaco；importmap 将请求客户端限定为静态元数据，拒绝全部写入及未声明请求。没有启动、停止或重启服务，没有操作用户 Chrome，没有提交同步。

- `rea-verification.json` 记录 10 个场景：全量、ID、空时间条件、时间格式下拉、固定日期/范围/SQL 基准编辑、SQL+时间条件、SQL+格式下拉、390px 视口。
- 10 个 REA 场景均完整返回，页面错误 0、console error 0、真实 `/admin-api/` 请求 0。桌面主弹窗 1200px、时间格式 240px、条件表 310px；390px 视口文档宽度仍为 390px。
- 5 个聚焦测试文件 29 项通过，覆盖 Hive SQL/table 草稿往返、条件编辑、只读与迟到请求保护、SQL 编辑器失败降级、超长 ID 字符串、关系族兼容代理校验。测试日志 `/tmp/hive-input-vitest.log`。
- 本代理拥有的 8 个文件通过 scoped Antdv Next lint（含类型感知 oxlint、ESLint、Stylelint）；日志 `/tmp/hive-input-lint.log`。

边界：夹具证明的是本地组件渲染和受控交互，不能替代真实 Hive 服务目录/SQL 解析、后端持久化或 Hive 执行验收。全应用类型检查由主代理汇总。现有 Hive 执行层对非全量/独立 SQL 的约束保持不变，ID 开始值按截图不显示星号，但保留原协议必填与整数校验。
