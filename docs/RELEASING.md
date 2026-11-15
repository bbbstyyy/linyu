# GitHub Actions 发布指南

## 创建仓库

把经过检查的独立 APP 目录作为仓库根目录，根目录应直接包含 `app/`、`gradlew` 和 `version.properties`。使用全新的 Git 历史，不复制原工作区的 `.git` 或其他历史对象。`PUBLIC_FILES.txt` 是逐文件公开清单。

在 GitHub 创建空仓库后，从干净 APP 目录执行，将 `OWNER/REPO` 替换为自己的仓库：

```bash
git init -b main
git add .
PYTHONDONTWRITEBYTECODE=1 python3 scripts/check_public_source.py --git
git diff --cached --stat
git commit -m 'Prepare Shower Helper open source release'
git remote add origin https://github.com/OWNER/REPO.git
git push -u origin main
```

首次推送后确认 **Android CI** 成功。建议开启主分支与 `v*` 标签保护，要求变更经过审查；在 Security 设置中开启私密漏洞报告。

## 正式签名

正式版本需要长期使用同一把签名密钥。请在仓库外生成并离线备份密钥和密码，不要提交。以下命令交互式询问密码；证书信息会随 APK 公开，可使用项目名称，避免填写个人联系方式：

```bash
keytool -genkeypair -keystore /secure/location/shower-release.jks \
  -storetype JKS -alias shower-release -keyalg RSA -keysize 3072 \
  -validity 10000 -dname 'CN=Shower Helper'

# 查看证书 SHA256 指纹。
keytool -list -v -keystore /secure/location/shower-release.jks -alias shower-release
```

`/secure/location/` 需替换成实际安全目录。不要使用 Android Debug 证书。新正式签名与旧开发签名不同，用户须先确认停水、卸载旧版后重新安装登录；后续版本保持密钥不变并增加 `versionCode`。

在仓库 **Settings → Environments** 创建 `release`，限制仅 `v*` 标签可部署，在此环境配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_BASE64` | keystore 文件的单行 Base64 编码 |
| Secret | `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| Secret | `ANDROID_KEY_ALIAS` | 例如 `shower-release` |
| Secret | `ANDROID_KEY_PASSWORD` | 签名条目密码 |
| Variable | `ANDROID_SIGNING_CERT_SHA256` | 正式证书 SHA-256 指纹，允许冒号分隔 |

如已安装并登录 GitHub CLI，可通过管道上传密钥，不把编码结果写进项目文件或打印到终端：

```bash
base64 -w 0 /secure/location/shower-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env release
gh secret set ANDROID_KEYSTORE_PASSWORD --env release
gh secret set ANDROID_KEY_ALIAS --env release
gh secret set ANDROID_KEY_PASSWORD --env release
gh variable set ANDROID_SIGNING_CERT_SHA256 --env release
```

也可在 GitHub 网页中配置。缺少配置、证书指纹不匹配或使用标准调试证书都会停止发布。环境审批人是可选设置；未设置时，通过检查的标签会自动发布。

## 发布版本

1. 修改 `version.properties`：递增 `versionCode`，填写 `versionName`。同一版本不重复发布，不倒退 `versionCode`。
2. 更新 CHANGELOG，合并到主分支并确认 Android CI 成功。
3. 推送与版本精确一致的标签。例如当前基线 `0.2.8-mvp` 对应：

```bash
git tag -a v0.2.8-mvp -m 'Shower Helper 0.2.8-mvp'
git push origin v0.2.8-mvp
```

带后缀的版本自动标记为 prerelease；无后缀版本按正式 Release 发布。不要直接上传已有开发 APK。

工作流先在不接触密钥的任务中检查公开范围、测试和 Lint，构建非调试 APK；再由独立发布任务校验包名和版本，签名并检查证书，创建 Release，附带 APK、`SHA256SUMS` 和 `SIGNING_CERT.txt`。签名密钥只在临时目录中短暂出现，退出或失败时清除，不进入缓存。

工作流仅使用内置 `GITHUB_TOKEN`。构建任务只有读取权限，发布任务有 `contents: write`。普通提交和 PR 不读取发布 Secrets，也不使用 `pull_request_target` 执行外部贡献代码。

## 失败与重跑

构建、检查或签名失败不会执行创建 Release 的步骤。修复环境配置后，可在 Actions 中重跑失败任务；源码发生变化时用新版本和新标签，不移动已发布标签。

脚本不覆盖同名 Release。创建或上传阶段若遇网络故障，GitHub 可能已创建 Release 或只上传部分资产；先检查内容，再决定补齐或撤下不完整发布，不要反复重跑创建命令。

## 本地复核

```bash
bash gradlew :app:assembleRelease
python3 scripts/check_release.py --tag v0.2.8-mvp \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt"
```

本地签名时，设置表格中的密码、别名、证书指纹对应环境变量，并额外设置 `ANDROID_KEYSTORE_PATH`，执行：

```bash
bash scripts/sign_release.sh app/build/outputs/apk/release/app-release-unsigned.apk dist
```

自动化组件固定到已核实的官方提交哈希，并在注释中保留主版本；由 Dependabot 提醒更新。实际联网运行和服务侧权限须在首次 GitHub CI / Release 中确认；本地检查不等于 GitHub 已成功发布。
