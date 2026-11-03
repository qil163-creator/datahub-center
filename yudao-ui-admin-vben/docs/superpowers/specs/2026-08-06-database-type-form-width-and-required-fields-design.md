# 数据库类型定义表单宽度与必填校验设计

## 目标

改善“新增/编辑数据库类型定义”弹窗偏窄的问题，并确保截图标出的业务字段在提交前完成必填校验。

## 界面调整

- 将数据库类型定义弹窗设置为固定 `1024px` 宽度，沿用项目现有的 Modal 宽度类写法。
- 保持当前单列表单、滚动区域、按钮和字段顺序不变。
- 不调整列表页、接口类型或后端校验。

## 必填字段

为以下字段增加表单 `required` 规则：

- 数据源编码 `typeCode`
- 数据源名称 `typeName`
- 数据库分类 `categoryCode`
- 连接协议类型 `protocolType`
- 数据源图标路径 `iconPath`
- 数据源 URL `urlTemplates`
- 默认端口 `portTemplates`
- JDBC 驱动类 `driverClass`
- 默认连接测试 SQL `validationQuery`

现有的排序 `sort` 和状态 `status` 继续保持必填；备注 `remark` 保持可选。

## 验证

- 先增加针对表单 schema 和 Modal 宽度的回归检查，并确认其在代码修改前失败。
- 修改后运行针对性测试，确认 9 个字段均为必填，原有必填规则未丢失，备注仍为可选，Modal 使用 `w-[1024px]`。
- 运行 `@vben/web-antdv-next` 类型检查，并审查 SVN 差异，避免覆盖工作副本中的其他改动。
