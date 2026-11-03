# qData 指标模型复刻说明

## 范围与证据

本次实现原子指标、业务限定、派生指标的业务区域，保留本项目导航。根据用户确认，派生指标管理及已存在的关联功能继续可用，并统一为参考页面样式；不能因参考站显示“功能建设中”而移除它们。

- 视觉依据：用户最新提供的 18 张截图，覆盖原子列表、类目树、新增/修改弹窗、各级联下拉、更多操作、两类详情和业务限定列表。新增图标裁切坐标及哈希记录在 `metric-asset-provenance.json`。
- REA 浏览器取证确认当前站点登录页及发布入口文件 `main-BKUMEHhG.js`。REA JavaScript 分析解析了 35 个领域分包，零语法解析失败。分析图存在未采集依赖和语义预算截断，不能视作完整服务端源码。
- 领域静态分析：原子表单 `metricAtomicFormDialog-cz4_xVcv.js`、原子列表 `index-CAZOUDH0.js`、原子详情 `index-DnhcyNbM.js`、业务限定当前列表 `index-B-tGDwb3.js`、当前两步表单 `index-DYOvNia3.js`、详情 `index-sjcgITh5.js`。
- 登录后使用独立的本地 Playwright 浏览器进行只读核对（这部分不冒充 REA 捕获证据）：进入数据建模、指标模型、原子指标并打开新增；抓取 `/system/category/tree/A01` 返回的真实 7 组、36 个单位叶子。未向参考系统创建、修改或删除业务记录；未保存账号、密码、令牌或浏览器登录状态。
- 线上后端源码不可获得。下面对线上接口、字段联动和约束的描述以发布包及可观察行为为依据；本项目的持久化与并发实现基于本项目现有服务。

## 页面与交互映射

| 区域 | 参考行为与本项目实现 |
| --- | --- |
| 类目树 | 板块 → 主题 → 来源逻辑表；根节点初始展开，支持收起、搜索和选择筛选。主题使用真实板块归属，无模型的主题也可筛选。 |
| 列表 | 名称、来源、状态或配置方式/数据时效筛选；查询、重置、刷新、自定义列、分页、多选、详情、修改、克隆、批量删除。来源目录读取完整分页。 |
| 原子新增/修改 | 提示说明、基本信息、来源与指标定义、结果属性与口径三段。板块 → 主题 → 业务实体；负责人联动只读电话；时效与来源范围联动；选来源后读取发布版本及稳定字段。 |
| 聚合与字段 | MAX/MIN/COUNT/SUM/AVG/COUNT DISTINCT；表达式随聚合与字段生成；统计时间只展示日期/时间字段。服务校验数值聚合、计数结果类型和稳定字段归属。 |
| 单位 | 使用项目系统字典，按真实目录组名分组；组不可选，已有自由文本单位仍可回显。 |
| 业务限定新增/修改 | 当前线上入口是完整页两步表单；基础信息 → 限定定义。复用 Monaco 的编辑、格式化、复制和字段插入能力。当前仅支持真实已有的基础限定/全时段统一语义。 |
| SQL 口径 | 保存业务表达式定义，不运行 SQL。“表单校验”不能表述为 SQL 语义编译、数据库试运行或结果正确性证明。 |
| 克隆 | 先读取真实详情；保留定义、清空身份与英文名、新建为禁用状态，不能沿用旧 revision 或审计字段。 |
| 详情 | 元数据头部、派生关联/基础信息等页签。保留真实派生列表；业务限定尚无服务接口的关联和版本页签维持参考建设中展示。 |

## 服务与数据库

线上当前发布包的接口对应关系如下；请求路径与本项目保持各自既有命名，不将参考站地址写入本项目 API client。

| 动作 | qData 发布包 | 本项目 |
| --- | --- | --- |
| 原子列表、详情 | `GET /dm/metric/atomic/list`、`GET /dm/metric/atomic/{id}` | `GET /modeling/indicator-model/page`、`GET /modeling/indicator-model/get` |
| 原子保存、删除 | `POST/PUT /dm/metric/atomic`、`DELETE /dm/metric/atomic/{ids}` | `POST /modeling/indicator-model/create`、`PUT /modeling/indicator-model/update`、`DELETE /modeling/indicator-model/delete` |
| 原子类目、单位 | `GET /dm/metric/atomic/getCatTree`、`GET /system/category/tree/A01` | 复用板块/主题/逻辑模型目录与系统单位字典 |
| 业务限定列表、详情 | `GET /dm/metric/bizCondition/list`、`GET /dm/metric/bizCondition/{id}` | `GET /modeling/business-condition/page`、`GET /modeling/business-condition/get` |
| 业务限定保存、状态 | `POST/PUT /dm/metric/bizCondition`、`POST /dm/metric/bizCondition/updateStatus/{id}/{flag}` | `POST /modeling/business-condition/create`、`PUT /modeling/business-condition/update`、`PUT /modeling/business-condition/update-status` |
| 业务限定删除检查 | `GET /dm/metric/bizCondition/batchDeleteCheck/{ids}` | 复用领域服务校验、revision 和逐条删除的真实失败反馈 |

复用 modeling 现有指标、限定、实体、逻辑模型和版本服务；不引入新的业务模块、直接跨模块表写入或另一套 SQL 执行器。联系人通过现有用户 API 富化，不重复存储。单位通过现有 system 字典提供。

原子指标及业务限定绑定实体，并校验来源为相同板块、主题、实体和时效的启用维度/事实逻辑表。字段来自不可变的已发布快照；缺少归属元数据的旧快照须重新发布。派生指标继承原子指标实体，汇总构建沿派生指标的原子定义读取冻结来源，不能假设派生记录自身有来源列。

创建/修改可在一次事务中保存禁用或启用状态；已启用定义仍须先禁用后编辑。保留接口权限、全平台业务可见范围、引用检查、revision CAS、数据库唯一约束和并发引用保护。英文名保持用户输入大小写，编码唯一性仍按不区分大小写校验。

已有库按顺序执行后端 `0213_modeling_metric-entity-binding.sql`、`0214_modeling_indicator-unit-dictionary.sql`，前置是 `0212_modeling_logic-table-definition.sql`；新库使用同步后的 `datahub-center.sql`。实际交付检查结果以最终说明及测试报告为准。

## 验证边界

浏览器自动化使用真实 Vue、Antdv Next、Vben 与 Monaco 组件，API 由可控响应替身提供，覆盖宽编号、来源版本、创建/修改/克隆和迟到响应等分支。它证明前端交互和协议，不代表已连接生产数据库。后端另有真实 PostgreSQL、MyBatis 与 Spring 事务链路验证。未部署到线上环境。

后端最终聚焦执行 37 例：指标 12、业务限定 8、限定 HTTP 契约 4、汇总定义 8、实体 PostgreSQL 2、单位字典 PostgreSQL 1、字典响应序列化 2；全部通过，零跳过。三个 PostgreSQL 用例覆盖 0213/0214 双重放、并发引用保护和实际发布 → 原子 → 限定 → 派生 → 汇总链路。新库基线同步到 0214 且相关区块一致，未重新完整导入整库基线；未执行共享或线上库升级。专有测试 PostgreSQL 已停止。

后端错误码及模块边界检查通过。统一 `python tools/quality/run_all.py` 被仓库既有 `0133_modeling_drop-planning-change.sql` 归档校验错误阻断，未修改历史脚本、校验哈希或门禁来绕过。

前端最终原样执行 `pnpm run quality:antdv-next`，退出 0：活动应用边界、Oxlint、ESLint、Stylelint、全量类型检查、319 个测试文件的 2916 项测试，以及生产构建全部通过；ESLint 有 28 条测试替身警告，无错误。共享字典另有 10 项测试通过。首次全量测试出现请求客户端第一例动态导入 20 秒超时，原样单跑 4/4、小批并发 53/53、随后原样完整门禁均通过，未提高超时、跳过用例或修改门禁。

最终浏览器报告覆盖原子表单/详情 18 张、三类列表及派生弹窗 30 张、业务限定表单/详情 17 张截图，均使用 Mock API，错误数组为空。覆盖桌面到 390px、真实列设置提交、级联选项、单位树、克隆和固定发布版本；不把这些截图当作真实服务端联调或生产部署证明。复核资料位于前端 `.temp/metric-editor-qa-report.md`、`.temp/metric-review.md`、`.temp/metric-quality.log` 与各截图目录。
