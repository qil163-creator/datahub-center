# 资产市场文件交付

提供方在资产目录发布文件资产后，使用“维护文件交付”给该目录版本绑定文件。单份文件最大 20 MiB。使用方从资产市场申请，审批通过后在“我的资产”下载。申请冻结目录版本、文件名、大小和 SHA-256，目录换版后需为新版本绑定文件并重新申请。

## 本地运行

已有业务库先在资产业务 schema 下执行 `sql/postgresql/migrations/0296_asset_market-file-delivery.sql`。新库直接使用包含 0296 的全量基线。Infra 需要配置可用的主文件存储。交付文件应保存在私有目录或私有桶，浏览器通过 Asset 的下载接口领取。

本机开发可在 `yudao-cloud` 下运行以下命令，生成 Asset 和 Infra 使用的专用证书。证书与私钥保存到当前用户目录。

```sh
python3 deploy/prepare_local_rpc_tls.py \
  --directory "$HOME/.local/share/datahub/asset-file-delivery-tls" \
  --client asset-delivery --server infra --port 55480 \
  --context managedFileApi --bundle-property yudao.rpc.client.ssl-bundle \
  --peer-property yudao.rpc.peers.asset-server
```

随后重启承载 Infra 的进程和 Asset 服务，使 `local` 配置生效。开发环境可由 `yudao-server` 承载 Infra，也可单独运行 Infra。端口 55480 的双向 TLS 监听仅绑定本机回环地址。其他环境使用对应的服务证书、Infra HTTPS 地址和授权的 Asset 证书指纹，服务间配置键与上例相同。
