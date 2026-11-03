# SQL 精确写授权落地与接入说明

## 适用范围

治理负责角色、对象、列与行范围策略，DB Switch 负责 SQL 解析、改写及同连接执行，Supply 消费正式治理 API。写入必须具有独立的 INSERT、UPDATE 或 DELETE 授权；历史表权限的“读写”枚举、读取列权限、管理员身份均不自动授予写入权限。

当前开放经过真实 PostgreSQL 验证的受控 DML。对象精确绑定到数据源、模式名和表名，执行时在同一连接和事务内锁定当前关系并确认 OID 未在获取锁期间变化。这是当前授权名称的对象校验，不是跨建模部署版本的物理表代际绑定。

目标 schema 及其 DBA 必须受信任。本能力限制业务调用者可提交的 DML，并非面向恶意数据库 schema 的 SQL 沙箱；自定义类型、默认表达式、约束函数等由目标库管理者负责。不得将本能力描述为能隔离恶意 DBA 或任意数据库函数副作用。

## 管理员操作流程

1. 打开治理的数据权限配置，选择已绑定的数据源、精确模式和表，再选择角色。历史未绑定的策略须先完成对象绑定。
2. 保留或配置读取权限。禁止访问的角色策略优先于允许策略。
3. 在独立写权限区域启用写授权，选择允许的 INSERT、UPDATE、DELETE 操作。
4. 选择可写列和过滤列。过滤列决定调用者 WHERE 可以引用哪些列；它与可写列独立。
5. 配置等值行范围。例如市政务服务事项办理表以 `district_code = "370102"` 限制某区经办人员。多字段条件使用 AND；必须明确勾选全表范围才能不设置行条件，空条件不会默认放开。
6. 填写 5 至 500 字的授权变更原因并保存。新增、修改、撤销均与审计记录在同一事务提交；策略并发修改通过旧值比对拒绝覆盖。撤销会将写策略清空，不保留隐式写权限。
7. 保存后执行写预检。输入单条 SQL，参数使用 JSON 字符串或 null 的数组，例如 `["12345678901234567890.000001", "370102"]`。预检展示实际改写结果，不执行数据库写入，也不会给当前操作人授予选中角色。
8. 在 Supply 配置并调用数据服务。应用网关使用持久化授权中的执行用户，管理端调试使用当前登录用户。执行前重新读取有效策略，页面预检结果不能充当永久执行凭证。

预检按钮权限为 `governance:safety-permission:write-preflight`，升级脚本只创建菜单权限，不自动分配给所有角色。

## 受控语义

| 项目 | 行为 |
| --- | --- |
| INSERT | 明确列清单的 VALUES，最多 1000 行；每行须显式提供并符合范围列值。 |
| UPDATE | 单表 SET 简单参数或字面值，受控 WHERE；禁止更改当前授权的范围列。 |
| DELETE | 单表受控 WHERE。 |
| 行范围 | UPDATE/DELETE 将授权行条件与原 WHERE 结合；同一充分授权内字段条件 AND，多个独立充分授权的范围 OR。 |
| 多角色 | 至少一个角色须完整覆盖操作、写列和过滤列；不能把不同角色的列权限拼成新权限。明确禁止访问优先。 |
| 字段与标识符 | 校验真实物理列；支持 PostgreSQL 双引号标识符和精确大小写；执行对象完整限定模式名。 |
| 参数 | 原始占位参数数量与顺序保持不变；范围改写不新增占位参数。精确小数、大整数和布尔值以规范文本参与授权，执行保留原类型绑定。 |
| 不支持语法 | JOIN、别名、CTE、子查询、函数表达式、RETURNING、UPSERT/ON CONFLICT、DDL 及未验证语义均拒绝。 |
| 不支持对象 | 视图、物化视图、外部表、分区父表/子表、继承关系、具有规则或任何触发器的表；触发器限制包含外键内部触发器。 |
| 其他数据库 | 目前拒绝，须扩展 DB Switch 并提供目标产品真实数据库验证后才能开放。 |

行范围值按文本配置。INSERT 使用精确文本匹配，例如数值范围 `"10"` 与输入 `"10.0"` 不自动视作同一配置；应使用统一的规范文本。UPDATE/DELETE 的条件交给 PostgreSQL 按物理列类型比较。范围值禁止单引号、反斜线和 NUL，避免依赖数据库字符串转义会话设置。

## 服务间接入契约

调用 `SafetyPermissionApi.authorizeWrite(SafetyWritePermissionReqDTO)`，请求包含 `databaseId`、可信执行 `userId`、单条 SQL、`expectedConfigurationRevision` 和有序 `parameterValues`。执行用户由受认证服务上下文校验，不能接受外部伪造身份头指定授权主体。

响应包含 `sql`、`operation`、`schemaName`、`tableName`、`configurationRevision` 和 `parameterCount`。消费方必须验证配置修订及参数数量，按响应 SQL 重建执行计划，执行返回的同一条 SQL。原 SQL 通过校验后直接执行、只调用返回布尔值的旧接口，均不符合接入契约。旧 `validate-access` 对 DML 保持拒绝。

多语句脚本先逐条授权并核验绑定切片，全部通过后才连接目标执行。调用 DB Switch 的 `MetaDataByJdbcServiceImpl.executeAuthorizedDmlPlan(...)`，提供按语句 ordinal 对齐的 `RestrictedDmlTarget` 清单。组件统一创建可写 READ COMMITTED 事务，在所有写入前按顺序获取 `SHARE ROW EXCLUSIVE` 表锁，校验目标，再执行全部语句。获取锁使用本事务局部 5 秒超时，失败或任一语句异常则回滚；不在独立预检连接上代替实际执行连接校验。

授权后的 UPDATE/DELETE 保留 PostgreSQL `ONLY` 目标限定。DB Switch 通过解析器的 token/AST 扩展识别 `UPDATE ONLY`，重新规划与执行时保留该语义，不对 SQL 源文本做关键词替换。真实 PostgreSQL 回归覆盖授权输出、重新规划、同连接锁定和执行的完整链路，以及名称/字符串中包含 `ONLY` 时的原义保持。

## SQL 执行顺序

1. 已有平台 PostgreSQL 库先完成仓库截至 `0099` 的有序升级。其中必须已经执行 `sql/postgresql/migrations/0074_governance_permission-schema-binding.sql`，并具有完整治理权限页菜单；紧邻前置脚本为 `sql/postgresql/migrations/0099_supply_consumption-quota.sql`。
2. 执行 `sql/postgresql/migrations/0100_governance_dml-authorization.sql`，新增独立写策略列、变更审计表及预检按钮权限。脚本可重放，不迁移历史读权限为写权限。
3. 本文件之后的迁移仍按仓库全局编号继续。新库使用已包含相应迁移的 `sql/postgresql/datahub-center.sql`；已有库禁止通过导入全量基线替代增量升级。

回退时先回退消费方与治理写权限代码，再停用预检权限。保留写策略和审计证据，不自动删除审计表。平台迁移不会向外部业务数据源写入业务数据。
