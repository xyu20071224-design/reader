# LinguaReader 同步服务端（自托管）

REST + JSON 的极简同步后端，**Python 3 标准库实现，零第三方依赖**。
协议、数据模型与安全设计见 [阶段2-同步协议与部署-提案.md](../全平台与同步(新目标)/阶段2-同步协议与部署-提案.md)。

## 能力

- 账号：注册（默认关闭）/ 登录换令牌 / 登出吊销；口令 PBKDF2-HMAC-SHA256（210k 迭代，16 字节盐）。
- 增量同步：每用户单调 serverSeq 变更流，游标拉取；推送冲突时返回服务端版本（字段级合并在客户端）。
- 书籍正文：内容寻址 blob（bookId = 源文件 SHA-256 前 20 hex），支持分片上传、断点续传、Range 下载、按用户配额。
- 存储：单文件 SQLite（WAL）+ blobs 目录。

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| LR_SYNC_DB | sync.db | SQLite 路径 |
| LR_SYNC_BLOB_DIR | blobs | 书籍正文目录 |
| LR_SYNC_HOST | 0.0.0.0 | 监听地址 |
| LR_SYNC_PORT | 8787 | 监听端口 |
| LR_SYNC_BLOB_QUOTA_MB | 2048 | 每用户书籍配额，超限 413 |
| LR_SYNC_TOKEN_TTL_DAYS | 30 | 令牌有效期 |
| LR_SYNC_ALLOW_REGISTER | 0 | 1 = 开放注册（默认关闭） |
| LR_SYNC_TLS_CERT | 空 | 证书路径；与 KEY 同时给出才启用 HTTPS |
| LR_SYNC_TLS_KEY | 空 | 私钥路径 |

## 本机快速开始

    python3 server.py create-user alice --password 'a-long-password'
    LR_SYNC_DB=/tmp/lr/sync.db LR_SYNC_BLOB_DIR=/tmp/lr/blobs python3 server.py serve
    curl -s http://127.0.0.1:8787/api/v1/health

跑测试：

    python3 -m unittest discover -s sync-server -p 'test_*.py'

## 部署到腾讯云 / 阿里云（D3：有 IPv4、无域名）

1. 买一台 1C2G 的 CVM / ECS，系统 Ubuntu 22.04。
2. 安全组只放行 **443**；22 限制来源 IP。
3. 上传本目录到 /opt/linguareader-sync/。
4. 生成自签证书（客户端首次配置时录入下面的指纹）：

       openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout /etc/linguareader-sync/server.key -out /etc/linguareader-sync/server.crt -subj "/CN=linguareader-sync"
       openssl x509 -in /etc/linguareader-sync/server.crt -noout -fingerprint -sha256

5. 建号与启动：

       export LR_SYNC_DB=/var/lib/linguareader-sync/sync.db
       export LR_SYNC_BLOB_DIR=/var/lib/linguareader-sync/blobs
       python3 /opt/linguareader-sync/server.py create-user alice
       systemctl enable --now linguareader-sync

6. 防火墙放行后用 curl -k https://你的IP:443/api/v1/health 自检。

systemd 单元见本目录 [linguareader-sync.service](linguareader-sync.service)。

## 备份

推荐直接用仓库里的 deploy/（systemd timer + 脚本，保留最近 7 份；不依赖 sqlite3 CLI）：

    sudo cp deploy/backup.sh /home/ubuntu/linguareader-sync/backup.sh
    sudo cp deploy/linguareader-sync-backup.service deploy/linguareader-sync-backup.timer /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable --now linguareader-sync-backup.timer
    systemctl list-timers linguareader-sync-backup.timer

脚本做两件事：用 Python 的 sqlite3.Connection.backup 做一致性快照，再把 blobs 目录打包；
文件名带秒级时间戳（sync-<YYYY-MM-DD-HHMMSS>.db / blobs-<...>.tar.gz），超出 7 份自动删旧。

若偏好 cron：

    sqlite3 /var/lib/linguareader-sync/sync.db ".backup '/var/backups/lr-sync-DATE.db'"

## 安全须知

- 服务端会持有用户书籍正文（用户已确认纳入同步），**必须启用 HTTPS**，不要裸 HTTP 暴露公网。
- 令牌在服务端只存 SHA-256 哈希；数据库与 blobs 目录权限设为 0600 / 0700。
- 默认关闭注册；新用户走管理员 CLI create-user。
