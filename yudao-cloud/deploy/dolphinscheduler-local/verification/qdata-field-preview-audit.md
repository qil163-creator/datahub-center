# qData 右键字段预览（静态源码证据）

原始文件、SHA256、UTF-8 byte offset及摘录见同目录 `qdata-field-preview-evidence.json`。REA完整导出 `/tmp/qdata-component-audit-rea-evidence.json`，核心父证据 ev_68cc39b8b21c88db98718f03df0f5a88d31327e4a7e9cdc084ceb2bbef077d0d，补充父证据 ev_5802b93fcba2aff32ae68101c46749e090f220d0335ef23f38ac19a20d1d7383。只读GET抓取6个静态依赖，无远端保存，无业务行读取。静态源码不能证明每个运行状态，缺依赖关系明确partial。

- 真正挂载的菜单在 `index-5CNfT-KX.js::fe`，不要用已导入但render为空的 `contextMenu-Bddlif46.js` 推断最终显隐。
- `query.info` 为真（详情）时不绑定 node:contextmenu；普通节点菜单总有删除、编辑、显示输出字段；只有taskParams.type!==P.INPUT时增加显示输入字段。edge右键只有删除连接线。未见按engine/字段数量隐藏菜单的条件。
- 显示输入字段直接读node.data.taskParams.inputFields。不是实时重新拉取数据库，也不是直接读取所有父节点对象。空/非数组给“无法找到输入字段”warning，不开弹窗。
- 输出调用opBase导出的W0(node)。普通为taskParams.outputFields；Java outputFields空时回退inputFields；CONDITION_DISTRIBUTE在提供特定目标node参数时选trueOutputFields/falseOutputFields（为空时inputFields）并将source标成当前node名+(True/False组件字段)。右键本节点调用W0(node)不传目标，因此展示通用outputFields。
- 保存输入组件时tableFields复制到inputFields/outputFields，并把source重标为该输入节点name；保存输出组件时outputFields的source重标为输出节点name。一般转换保留已有outputFields里的source；新增split/derive/constant/crypto/Java等字段设置source=创建字段的当前节点name。因此source是混合来源标签，不能统一替换成最远源节点或当前变换节点。
- 节点改名同步替换字段source中等于旧name或旧name+(True/False组件字段)的标签，不重写其他来源。
- 预览弹窗标题“步骤里的字段和其来源”，宽800px，内部步骤名称和输入/输出字段分隔标题，表格高420px、stripe。列为序号、字段名称(columnName)、字段类型(columnType)、字段长度(length)、字段精度(precision)、步骤来源(source)。后四项分别用值||"-"，所以数值0也显示“-”。长度/精度列可排序；长文字show-overflow-tooltip。
- qData字段编辑中的precision标签为“小数精度”/“字段精度”。显示层直接读原始precision；本项目JDBC字段的precision通常为总位数、scale为小数位，必须用真实元数据适配，不能把两种precision盲目同名拷贝。未知长度和小数精度应留空。
- 关联helper fieldDetection提供 addNewOnly/addAll/clearAndAddAll/cancel，展示existing/new字段数量；不同组件自行处理这些action。不是直接覆盖字段。
- fieldMap真实使用左右两个vuedraggable列表、各自全选、按相同行号成对映射；普通DB仅详情态禁用，HDFS目标字段勾选单独禁用。未发现普通字段类型兼容判断；本轮未在该文件确认搜索框或同名自动映射，不把它们记为参考已有特性。

本地已补右键输入/输出字段菜单、只读schema接口、length/sourceName保守传播、未知和部分结果提示；普通DB双列独立拖拽/全选/勾选与缺失映射保护已由交互回归验证。length/precision显示层使用真实length及decimal scale适配；该本地实现状态不替代qData49项完整浏览器/引擎验收。
