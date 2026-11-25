# 独立资源包分发

本目录将资源分发与 APK Release 分开。源码工具已在本地测试；只有服务器执行、外网下载和应用安装均验证后才能记录上线。交接 ZIP 的旧验证记录不替代这些步骤。

目标地址以操作时的参数为准。当前确认地址为 `62.234.28.97`；发布器默认源站集中定义在 `scripts/publish_packs.mjs` 的 `DEFAULT_BASE_URL`。安装器必须显式给 `--host`，PowerShell 远程部署必须显式给 `-Deploy -Server`。

## 发布和打包（Node 18+、JDK 17+）

```sh
# 设置 JAVA_HOME 为实际 JDK；不需要重新制作已有的、已验证的 .lrpack。
node scripts/publish_packs.mjs dictionary --version 1.0.0 --out artifacts/ecdict-1.0.0.lrpack
node scripts/publish_packs.mjs publish --root artifacts/packs-site \
  --base-url https://62.234.28.97:25447 --pack artifacts/ecdict-1.0.0.lrpack
node deploy/packs/package.mjs --site artifacts/packs-site \
  --base-url https://62.234.28.97:25447 --out artifacts/linguareader-packs-server.tar.gz
```

已有包可重复 `--pack` 批量发布；每批同一 packId 只能出现一次。词典、音色、音频、集合包均检查 ZIP 路径/条目/解压量、CRC、清单大小/哈希、类型载荷引用；音频还检查当前管线与章节树，集合成员逐包核对身份。结构校验不替代 SQLite 词表模式、真实音频听感及应用端安装验收。

`packs/index.json` 保持 `formatVersion:1` 和 `packs` 契约，追加客户端可忽略的 `publishedVersions`。该历史与当前目录一次原子提交；即使 v1 已升级为 v2，也不能把不同内容重新用作 v1。历史不可手动删掉以绕过校验。旧下载对象始终保留，未更新的目录项及其元数据保留。

发布先对输入建立快照并全部验证，再发布不可变对象，最后 fsync+rename 一份目录。提交前失败保留旧目录；对象可有未被引用的完整孤儿，后续重试可复用。不在正在服务的对象上原地覆盖。不自动删除旧包。目录 rename 是提交点；提交后的磁盘同步/进程中断错误不能当作“未改动”，应读取实际 index 判断再重试。Windows 缺少目录 fsync，不能宣称与 Linux 相同的断电持久性。

所有发布入口共用 `packs/.publish.lock` 的独占创建锁。发现锁立即失败，永不按超时自动删除。异常退出后，先读取锁内 host/pid/startedAt，检查该主机进程、部署作业和当前 index；确认原进程已结束且无发布者后，人工备份并移除锁，再重试。锁为空或主机不可确认时也需人工检查。

## 服务器安装：保留现有服务

当前只读检查发现 Ubuntu 24.04、现有 Nginx、80 上已有 ACME 站点映射 `/var/www/acme`；这些是安装前观察，不是安装成功记录。现有同步等服务必须保留。

安装器要求现有 Nginx、Python 3.8+、curl、OpenSSL、ss、systemd；不执行 apt、不安装/升级 Nginx、不增改 80/443 站点、不停止其他服务、不修改 UFW/云安全组。必须使用已经从公网提供 HTTP-01 challenge 的 webroot；没有此映射时先由操作者配置，不能接管现有站点。25447 被其他服务占用、目标配置名不属于本工具、或已有部署目标不同，都会停止。

本地先生成可审阅配置（不应用）：

```sh
python3 deploy/packs/install_config.py --host 62.234.28.97 --render-only artifacts/packs-config-review
```

将生成归档上传到新的私有临时目录，**在解压和执行脚本前**用本地打包输出的 SHA-256 校验整份归档。解压后再校验包含 index、脚本及全部对象的 `SHA256SUMS`。示例中的路径是阶段目录，不应与已有目录混用：

```sh
sha256sum --check upload.tar.gz.sha256
mkdir stage
# 只有整包哈希与本地一致后才解压/执行。
tar -xzf upload.tar.gz -C stage
python3 stage/activate.py verify --stage stage
sudo bash stage/install-ubuntu.sh --host 62.234.28.97 \
  --existing-webroot /var/www/acme --owner ubuntu --check
sudo bash stage/install-ubuntu.sh --host 62.234.28.97 \
  --existing-webroot /var/www/acme --owner ubuntu --bootstrap-certbot
```

安装只管理本工具命名的 Nginx 25447 站点、续期 unit/timer 和独立 `/opt/linguareader-packs`。 私有 venv 的 bootstrap 仅从 `https://pypi.org/simple` 安装，使用 `--isolated --no-cache-dir`，并仅为该子进程设置 `PIP_CONFIG_FILE=/dev/null` 以禁用继承的全局/用户/环境配置；不修改系统 pip 配置。配置与命令行优先级依据 [pip 官方说明](https://pip.pypa.io/en/stable/topics/configuration/)。Certbot 环境和账号/证书配置也在该目录，避免使用或改动已有站点的 `/etc/letsencrypt` 账号和证书。若系统缺少 Python venv/ensurepip，需操作者先安装该依赖再重试；不会顺便安装其他服务。

脚本会写入一个随机 challenge 并读取公网 HTTP-01 路径，结束删除该文件；不会覆盖别人的 challenge。Certbot 5.4+ 才支持 IP 的 webroot 模式，使用 `--preferred-profile shortlived --ip-address`，续期每 12 小时检查并在证书变更后校验/重载 Nginx。官方资料核对于 2026-11-25：[Let's Encrypt 的 Certbot IP 使用说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)、[Certbot CLI](https://eff-certbot.readthedocs.io/en/stable/man/certbot.html)、[短期/IP 证书正式可用说明](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)。证书约 160 小时有效，仍需在实际服务器执行续期演练并监控 timer，不能据配置文件生成成功声称续期正常。

Nginx reload 后只对本机连接尚未就绪（curl 7）在 10 秒总截止内重试，HTTP/TLS/证书错误立即失败。回滚先恢复本次更改的 timer 启用/运行状态，再还原 unit 文件，避免悬挂的 wants 链接。配置写入有归属检查和失败回滚；证书签发先于 TLS 配置切换。`nginx -t`、unit 校验、重载或本机 TLS health 失败会恢复原配置。若回滚本身遇到磁盘错误，会明确报告需检查；不会继续显示成功。孤立的 Certbot 环境/已签证书可保留以便恢复，不删除其他服务证书。

## 激活与真实校验（服务器仅需 Python 3）

```sh
python3 stage/activate.py activate --stage stage \
  --root /srv/linguareader-packs/public --base-url https://62.234.28.97:25447
python3 stage/activate.py check-http --root /srv/linguareader-packs/public \
  --base-url https://62.234.28.97:25447 --full
```

激活合并服务器现有目录，不用上传的旧目录覆盖服务器新增的其他包；版本历史、既有文件、包目录身份和全部上传文件都先核验。相同包重试不修改 index；失败阶段保留用于诊断。`check-http` 验证 TLS 信任、实际服务的目录字节、206/Content-Range 和首 100 字节；`--full` 另外逐包完整下载核对 SHA-256，可能消耗较多带宽。HTTP 校验在提交后执行，若失败，目录可能已激活，需检查实际状态，不能据退出码推断回滚。

PowerShell 默认只在本地打包，不进行远程连接；显式部署入口复用相同工具，不接受或保存密码：

```powershell
.\scripts\deploy_packs.ps1 -PackageOnly -JavaHome C:\实际JDK -Pack C:\实际包.lrpack -BaseUrl https://62.234.28.97:25447
.\scripts\deploy_packs.ps1 -Deploy -Server ubuntu@62.234.28.97 -ExistingWebroot /var/www/acme -BootstrapCertbot -Pack C:\实际包.lrpack -JavaHome C:\实际JDK
```

OpenSSH 和 sudo 仍由终端交互认证。上传校验失败不执行安装；安装/激活失败后保留独立远端 stage，不笼统声称“服务器未改”。远程脚本的本机 HTTP 通过也不替代独立外网 25447、手机获取/下载/中断续传/安装/重启离线使用验证。安全组或防火墙放行由操作者根据实际环境单独决定。

## 可独立运行的本地测试

```sh
node --test scripts/publish_packs.test.mjs deploy/packs/package.test.mjs
python3 deploy/packs/test_activate.py
bash -n deploy/packs/install-ubuntu.sh
```

Node 测试需要 JAVA_HOME 指向 JDK 17。这些用例覆盖本地发布、跨进程竞争、上传损坏、激活幂等、目录事务、历史不可变、配置写失败/校验失败/重载失败回滚及 HTTP 响应验证；不把模拟 HTTP 或本地文件系统结果当作真实 Nginx/证书/手机验收。PowerShell 应另外在有 pwsh 的环境执行解析和 PackageOnly，当前 macOS 验证环境未提供 pwsh。
