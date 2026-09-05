# 项目协作约定

## Git 提交
- 每完成一次修改（一个需求/一批改动），验证构建通过后立即提交一次 commit 并推送到 origin/master
- 提交信息使用中文 conventional commit 风格（feat:/fix:/style:/refactor:），正文分条列出改动点
- 提交署名使用本仓库 git config：ArboRain <ArboRain@users.noreply.github.com>

## 设备调试
- 测试设备通过无线 ADB 连接（adb connect），构建后需同时安装到所有在线测试设备
- 本地构建使用 JAVA_HOME=C:/Users/Leaper/.jdks/jbr-21.0.11
