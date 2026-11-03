# ADR-0003：ID、时间与精确小数线协议

- 状态：Accepted
- 日期：2026-09-12

## Context

Java 类型、TypeScript 类型和 JSON 线协议不是同一层。当前 Long 会根据 JavaScript 安全范围表现为 JSON number 或十进制 string，LocalDateTime 默认使用毫秒时间戳，字段注解可能覆盖格式。BigDecimal 若先作为 JSON number 被 JavaScript 解析，之后把 TypeScript 类型改成 string 也无法恢复精度。

## Decision

1. Long ID 的前端合同使用无损联合类型或在 API 边界规范化为十进制字符串；禁止经过 `Number`、`parseInt`、一元加号和算术转换。
2. 时间类型以实际 VO 和 `@JsonFormat` 为准。API DTO 保存 JSON 原语；Dayjs/Date 只存在于 UI 模型，并在边界通过 codec 转换。
3. 金额、比例、度量等必须精确的小数由后端以十进制字符串输出，前端 API、表单、表格、计算和提交全程保持字符串或使用项目明确采用的十进制实现。
4. 近似展示数值可以使用 JSON number，但合同必须明确它不承担精确计算。
5. 请求、响应、分页、枚举/字典值和可空性均从真实后端合同派生，不用一个实体类型覆盖所有方向。

## Consequences

- 前端类型可能比 Java 字段类型更复杂，但不会静默损失 ID 或小数精度。
- 格式化、表单组件和排序/比较必须按线协议选择，不能默认使用 `parseFloat` 或原生算术。

## Exceptions

外部协议明确要求格式化字符串或其他表示时，可在字段级声明并提供双端契约测试；不得依赖隐含约定。

## Verification

- 为安全范围边界 Long、超大 Long、默认/注解时间和高精度小数增加 JSON 契约测试。
- 前端测试覆盖路由参数、Map/table key、表单 codec、提交回显和精确值计算。
- 跨端 PR 同时核对 Controller、VO、API 类型和 UI 消费路径。
