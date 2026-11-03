# DataHub Development Skill 使用与维护

## 三层职责

- 根级和目录级 `AGENTS.md`：进入对应目录工作时自动生效，是无须显式触发的最低约束。
- `.agents/skills/yudao-datahub-development`：把前后端联合开发、组件检索、风险判断和验证组织成可复用工作流。
- `tools/quality` 与 `.workflow`：把可以确定判断的规则交给脚本和 CI 阻断；不依赖模型“记得检查”。

三层互补。Skill 不能替代 CI，CI 也不能替代组件归属、事务边界等需要上下文判断的规则。

## 如何触发

在 `yudao-cloud` 仓库内工作时，Codex 可根据 Skill 的 `description` 自动选择它。需要强制指定时，在请求中写出名称，例如：

```text
请使用 $yudao-datahub-development，为治理模块新增标准发布功能，包含后端、Antdv Next 前端、编号 SQL 和验证。
```

也可以自然语言明确范围：

```text
按 DataHub 项目开发规范修复物理模型部署；先检查 DB Switch 是否已有对应数据库的 DDL 能力，并提交已有库升级所需的有序迁移。
```

Skill 的唯一版本源是本仓库 `.agents/skills/yudao-datahub-development`。从前端仓库单独发起任务或希望在任意目录显式触发时，应把这个目录安装/同步到个人 Skills 目录；个人副本不要反向成为权威版本。

## 维护流程

1. 先修改仓库内 Skill，不直接只改个人全局副本。
2. 保持 `SKILL.md` 为短工作流，将后端、前端、数据库、安全、测试等细节放入按需读取的 `references`。
3. 规则能被确定判断时，优先补充 `tools/quality` 测试和统一入口；不要在 Skill 与 CI 各实现一套不同算法。
4. 运行 Skill 校验和质量脚本，并用 `references/skill-evaluation.md` 的正反场景复核是否会选错能力或遗漏高风险边界。
5. 校验通过后，将仓库版本同步到个人安装位置，并比较文件清单与哈希；删除、重命名或新增文件也要同步。

代码生成器和生成模板不是这次治理落地的修改对象。将来若单独修改它们，仍须按 Skill 中的代码生成审查规则执行专测和产物复核。

参考：[Codex AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)、[Codex Skills](https://learn.chatgpt.com/docs/build-skills)。
