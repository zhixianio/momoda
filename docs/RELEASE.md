# 发布

## 签名密钥

- 发布用的密钥只生成一次，**永远不能丢**：丢了它，已安装的用户就收不到更新，只能卸载重装。
- 原件放在仓库外（例如 `~/.momoda/momoda-release.jks`），文件和密码存进 1Password，并留一份离线备份。
- 本机构建签名包：复制 `keystore.properties.example` 为 `keystore.properties`（已被 git 忽略）并填写。
- GitHub Actions 用仓库 Secrets：`MOMODA_KEYSTORE_BASE64`、`MOMODA_KEYSTORE_PASSWORD`、`MOMODA_KEY_ALIAS`、`MOMODA_KEY_PASSWORD`。Secrets 只能写入不能读出，原件始终在你手里。
- 正式密钥和 debug 密钥签出来的包不能互相覆盖安装；从 debug 版换到正式版需要先卸载一次（`Documents/momoda` 里的文档不受影响，App 内的设置和登录要重新弄）。

## 发一个版本

1. 在 `CHANGELOG.md` 里写好 `## [版本号] - 日期` 这一节。
2. 打 tag 并推送：`git tag v0.9.0-beta.1 && git push origin v0.9.0-beta.1`。
3. `.github/workflows/release.yml` 会：从 tag 推出 versionName 和 versionCode → 下载并校验字体 → 用 Secrets 里的密钥构建 → 确认不是 debug 签名 → 发布 APK、SHA-256 和 R8 mapping 到 GitHub Releases。带 `-` 的版本（如 `-beta.1`）标为预发布。

versionCode = (主×10000 + 次×100 + 修订)×100 + (正式版 99 / 预发布序号)，例如 `v0.9.0-beta.1` → 90001，`v0.9.0` → 90099。

App 内的“检查更新”读的是 `https://api.github.com/repos/zhixianio/momoda/releases/latest`（不含预发布版）。
