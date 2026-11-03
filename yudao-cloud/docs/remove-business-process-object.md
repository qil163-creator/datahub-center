# 业务过程与业务对象退役

本次删除业务过程与业务对象的页面、接口和持久化结构，保留业务域、数据域、主题域、数仓分层、概念模型及统一设计。部署前必须停止旧建模实例和写入任务，备份平台数据库，并安排维护窗口。迁移中的物理删除不可逆，回退必须恢复完整数据库备份与匹配旧应用，不能只回退应用版本。

## 数据处理

- 删除业务过程、主域候选与支撑域关系、主题域过程关系，以及业务对象、属性、过程关系、对象版本和外部引用表及序列。
- 删除概念模型和统一设计的 `business_process_id`，删除概念实体的 `business_object_id`、`business_object_version`。概念实体名称、说明、属性、布局及模型字段和关系原样保留。
- 删除前，仅在主模型数据域为空、主模型和过程均未逻辑删除、过程及数据域属于相同租户且数据域有效时，补齐原先由业务过程隐式提供的 `data_domain_id`。已有明确归属不覆盖；失效或跨租户来源不用于回填。
- 根据新的直接归属规则重算活动主设计的 `classification_status`：实际分层、合法表用途、数据域及已填写的主题域必须有效且未停用。仅修改不一致的派生状态；旧版仅因缺少业务过程而未归属的设计可恢复为已归属，失效引用仍为未归属。重算期间在事务写锁内短暂关闭并恢复该表的引用触发器，其他会话不能写入，引用编号保持不变。
- 业务过程和业务对象的菜单、按钮、角色关联及租户套餐菜单同步清理，保留业务架构父目录与业务域入口。通用状态、类型字典保留；两项功能明确命名空间内的专属字典清理。
- 历史概念版本、设计版本、物理部署版本、规划审计及既有退役归档不修改。历史 JSON 中的旧字段仅是历史证据，新代码兼容读取，不恢复相关业务能力。

历史快照没有 `dataDomainId` 时，必须新建修订并明确选择数据域后重新发布；不能从已经删除的业务过程推导归属，也不能重写旧快照来伪造原发布口径。模型和治理服务须协调升级，移除旧字段后重新执行标准检查，不复用旧 `requestDigest`、`targetHash` 或标准门禁凭证。

迁移继续保留数据域、主题域、分层的租户与模型引用保护。脚本不使用 `CASCADE`；待删菜单下若挂有其他功能页面，或数据库存在未声明依赖，整个事务回滚，先处理具体依赖再重试。

## SQL 执行顺序

1. **已有数据库前置条件**：按全局编号完成历史迁移至 [0126_modeling_remove-workspace.sql](../sql/postgresql/migrations/0126_modeling_remove-workspace.sql)，其前置统一设计迁移 `0123`、`0124`、`0125` 必须完成。确认工作台退役账本 `WORKSPACE_REMOVAL_V1` 已为 `CLEANED`，并完成旧实例停机与完整备份。
2. **已有数据库本次增量**：执行 [0127_modeling_remove-business-process-object.sql](../sql/postgresql/migrations/0127_modeling_remove-business-process-object.sql)。文件自带事务，设置十秒锁等待上限；成功后账本增加 `BUSINESS_PLANNING_REMOVAL_V1=CLEANED`。可重复执行。
3. **全新空数据库**：只导入 [datahub-center.sql](../sql/postgresql/datahub-center.sql)，其头部标记为 `Included migrations through: 0127`，最终状态已包含本次删除；不要另行重放 `0123` 至 `0127`。已有库禁止重新导入全量基线。

本次仅提供迁移并在专用隔离测试库验证，不代表已对共享、开发或生产平台执行升级。

完成数据库迁移并切换新版前后端后，已登录用户应退出后重新登录，重新取得菜单与按钮权限，关闭浏览器中仍打开的旧功能页签。

## 验证

配套验证脚本为 [verify_business_planning_removal.py](../yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling-unified/verify_business_planning_removal.py)。仅连接显式给定端口的 `127.0.0.1`；源库必须是完成 `0126`、尚未执行 `0127` 的无模型专用测试库。脚本复制源库后才写入夹具，不修改源库，不自动删除测试数据库。

```powershell
python yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling-unified/verify_business_planning_removal.py --port 15437 --source-db modeling_fresh_0126_final --verify-baseline
python tools/quality/check_migrations.py
```

覆盖新增迁移首次执行与重放、域归属回填、明确值与无效/跨租户来源、新归属状态重算与无效引用降级、实体及字段保留、历史快照和审计原样保留、角色/套餐/字典清理、残存函数依赖，以及未知页面或数据库依赖导致整笔回滚。可选 `--verify-baseline` 导入完整新库基线并核对最终结构。

2026-10-10 已在本机专用 PostgreSQL 18 实例实跑上述命令：九组全部通过，包括完整新库基线导入；没有跳过用例。迁移结构检查通过。验证没有连接共享或生产业务库，也没有执行用户目标数据源 DDL。

后端最终验证使用 Java 25，先单独清理建模 API/server 的构建产物，再通过 Maven reactor 编译并执行 14 类受影响测试：136 项通过，失败、错误、跳过均为零；其中规划引用查询有 5 项在独立 PostgreSQL 测试库实际执行。治理标准门禁另有 14 项测试通过且无跳过。清理后的 `target/classes` 不再含业务过程或业务对象类。

`python tools/quality/run_all.py` 最终通过：DB Switch 架构、96 份编号迁移、1076 个错误码声明以及 258 个 POM/6225 份 Java 源码的模块边界检查均通过；工具按 `HEAD` 保留既有错误码和依赖历史债务，没有关闭迁移不可变检查。

配对前端的完整质量门禁各阶段最终均通过：`pnpm run check:antdv-next:boundaries`、`pnpm run check:antdv-next:lint`、`pnpm --filter @vben/web-antdv-next run typecheck`、`pnpm run test:antdv-next --maxWorkers=2`、`pnpm run build:antdv-next`。全应用 Vitest 为 147 个文件、1591 项全部通过；建模子集另行验证为 18 个文件、98 项通过。lint 无错误，仍有 13 条既有测试桩警告。初次组合门禁停在 lint，修复后补跑该阶段和未完成的阶段，没有将初次中断记为整体通过。

尚未进行真实登录态的浏览器端到端联调；本次未切换实际平台数据库和运行中的服务，验证依据为组件测试、类型检查、构建和隔离数据库回归。

生产规模锁等待、备份恢复演练和多服务切换窗口仍需在实际部署环境确认。
