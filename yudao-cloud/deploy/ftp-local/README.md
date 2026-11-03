# 本机 FTP 文件管理测试服务

在本目录运行 `python3 init.py`，启动仅供本机访问的 FTP 服务。首次启动随机生成独立密码，保存在权限为 0600 的 `.env`；重复运行保留账号密码和测试文件。文件保存在本目录的 `data/`，可在宿主机直接查看或修改。`.env` 和 `data/` 不纳入版本控制。

DataHub 数据连接选择 FTP，填写主机 `127.0.0.1`、端口 `2121`、用户名 `datahub_test`、`.env` 中的 `FTP_PASSWORD` 和根目录 `/`。服务发布的被动模式端口是 `21000–21009`，均仅绑定本机回环地址；这些设置适用于运行在同一台电脑上的 DataForge 服务，不适用于远程后端。FTP 凭据和数据以明文传输，本配置只用于本机测试。

`docker compose ps` 查看状态；`docker compose stop` 停止；`docker compose up -d --wait` 恢复。停止或重建容器不会删除 `data/`。Docker Desktop 启动后容器会自动恢复。
