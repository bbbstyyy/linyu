# 贡献说明

欢迎提交可复现的问题和小范围 Pull Request。请先说明使用场景、预期与实际行为；界面问题可提供遮盖账号和设备编号后的截图。

开发环境见 [开发说明](docs/DEVELOPMENT.md)。提交前运行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py' -v
bash gradlew :app:verifyMvp :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
python3 scripts/check_public_source.py --git
```

`PUBLIC_FILES.txt` 列出允许公开的每一个文件。添加新文件时，先审查其内容，再同时更新清单并加入 Git 暂存区；检查脚本按 Git 已跟踪文件验证。不要从其他工作区整目录复制材料，不提交账号配置、抓包、安装包、签名密钥或本机路径。

测试只使用模拟账号和设备。修改控制逻辑时应覆盖重复点击、超时、进程重启及所选设备变化；控制请求不得因重试或后台任务自动重放。真实用水验证由现场人员主动操作并确认结果，不作为 CI 的一部分。

提交贡献意味着你有权提交这些内容，并同意以项目的 MIT 许可证发布；第三方内容须保留其许可和来源。
