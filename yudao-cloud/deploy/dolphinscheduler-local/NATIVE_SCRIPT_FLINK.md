# Flink 受管 Java 脚本

正式入口为 `cn.iocoder.yudao.framework.etl.script.flink.EtlNativeScriptMain`，使用 Java17 和官方 Flink1.20.3。业务编译器通过 `NativeTaskParameters.flinkManagedProgram` 传入有界的 v2 程序参数；管理员还须安装不可变 launcher，并配置对应任务、工作组、FLINK 身份、证书引用和脚本执行资源。默认没有这些绑定时拒绝执行。

launcher 不加载或执行用户类。用户源码只经受信发布控制面保存在 broker；执行端根据受管引用读取 mTLS 私钥、证书和 CA，broker 再调用固定依赖、`network=none` 的 Docker worker。源码、原始 token、私钥和 SSLContext 不进入程序参数、JobGraph 或检查点。qData `EtlBase` / `JSONObject data.data` 与已有 `EtlJavaScript` 都由隔离 worker 兼容。

## 批次和恢复合同

- 有界任务保留 SQL 实际分区顺序，整个分区一次调用用户代码。`maximumRows` 超限时整批失败；不隐式切分或重分区。
- 持续任务必须显式配置 `COUNT_OR_TIME`，以 `maximumRows` 或 `flushMillis` 先到者刷新。空窗口不执行；每批编码最多 8MiB。正式入口缺少检查点配置时启用 60 秒检查点，直接复用算子的调用方没有检查点则拒绝。
- 标量字段有明确形状、nullable、整数范围、Decimal 精度和时间戳精度。先验证完整响应，再逐行输出；不通过 JSON 字符串中转 Long、Decimal、DATE、TIMESTAMP。
- 检查点保存作用域摘要、实际分区/并行度、运行和会话引用、下一批次序号、窗口数据、原 processing-time 截止时间。检查点不强制刷新窗口；过期的恢复 timer 按实际 processing time 尽快触发。
- 完成检查点后 ACK 对应序号，且空闲阶段的 ACK 会刷新 broker 会话租期。停止空闲流作业不发送永久 CLOSE，以便同会话恢复；真正有界结束发送 CLOSE。执行结果未知、类型错误或运行中取消发送 CANCEL；此时不能假装已有副作用可安全重试，需要重新发布/新运行。
- 已完整确认响应后的下游失败保留结果缓存；相同序号、相同输入可重放缓存。缓存已经被更新检查点确认回收、会话过期或 broker epoch 变化时明确失败，不重新执行未知副作用。并行度改变和错误作用域恢复同样拒绝。
- 输出仍为至少一次语义。结果缓存不能使任意用户副作用或外部 sink 成为事务性的恰好一次输出。

Flink 的 `custom-token` initializer 当前明确拒绝。REST API 消费者仅支持 Spark，因此不能把仅生成 opaque 引用、没有实际消费者的 Flink token 节点标记可执行。

## 构建与真实验证

```sh
python3 deploy/dolphinscheduler-local/prepare_native_script_flink.py \
  --container components-stable-ds \
  --directory /private/tmp/datahub-native-script-flink-artifacts \
  --output /tmp/datahub-native-script-flink-binding.json
python3 deploy/dolphinscheduler-local/native_script_flink_smoke.py
```

构建器只编译可信 launcher 与纯 JDK wire/value/state 代码，不打入源码或执行身份。验收脚本建立独立 CA、客户端 FLINK 身份、18448 broker 和持久清理账本，不复用平台1004的证书，不修改50026或18447。测试源是明确标记的 SQL VALUES / 受控 DataStream，属于组件实测，不宣称管理端任务或物理数据库持续源验收。

证据写入 `verification/components-20261102-native-script-flink.json`。实际检查包括正式 SQL launcher 的精确 typed 输出、count/time 刷新、未满窗口保存点恢复、原会话继续、下游真实失败后缓存重放、运行中取消、行预算/输出类型/错误 mTLS 身份与缩放恢复拒绝。直接读取 broker 仍存活时的 worker 数量，防止最终 broker 清理掩盖算子取消失败。实际程序、JobGraph 和保存点按真实客户端私钥的 UTF8、UTF16LE/BE、PEM 和 DER 哨兵扫描。
