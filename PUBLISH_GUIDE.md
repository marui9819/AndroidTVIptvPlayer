# 发布指南（GitHub Pages 与手动步骤）

本文件记录一次发布会用到的手动操作。若自动化流程已完成提交与推送，只需按第 1 节开启 Pages。

## 1. 开启 GitHub Pages（一次性）

1. 打开仓库页 `https://github.com/marui9819/AndroidTVIptvPlayer`；
2. 进入 **Settings → Pages**；
3. Source 选择 **Deploy from a branch**；
4. Branch 选择 **main**，目录选择 **/docs**，点击 Save；
5. 等待 1–3 分钟，站点地址预期为：

   ```
   https://marui9819.github.io/AndroidTVIptvPlayer/
   ```

6. 打开地址确认：页面可访问、截图正常显示、无 404。

## 2. 可选：自定义域名

1. Settings → Pages → Custom domain，填入你的域名（如 `5xing.tv`）；
2. 在域名服务商处添加 CNAME 记录指向 `marui9819.github.io`；
3. 勾选 **Enforce HTTPS**（域名 DNS 生效后可用）。

## 3. 发布新版本 APK

1. 本地构建：`./gradlew assembleDebug`；
2. 仓库页 → **Releases → Draft a new release**；
3. Tag 如 `v2.9.124`，标题同 Tag；
4. 上传 `app/build/outputs/apk/debug/app-debug.apk`；
5. 发布后 README 与站点中的 Releases 链接自动指向最新版本。

## 4. 若 git push 失败时手动提交

当前仓库 origin 指向 gitclone.com 镜像（国内加速用），镜像 504 时改用直连地址推送（注意用 `github.com:443` 写法可绕过全局镜像改写规则）：

```bash
cd /path/to/AndroidTVIptvPlayer-main
git push https://github.com:443/marui9819/AndroidTVIptvPlayer.git main
# 或交互输入凭据的常规写法（会触发镜像改写，仅镜像可用时使用）：
# git push origin main
```

推送需要 GitHub 凭据，三种方式任选：
1. **SSH key**（推荐，本机已有 ~/.ssh/id_ed25519）：把公钥内容 `cat ~/.ssh/id_ed25519.pub` 粘贴到 GitHub → Settings → SSH and GPG keys → New SSH key，之后用 `git push git@github.com:marui9819/AndroidTVIptvPlayer.git main`；
2. **gh CLI**：`brew install gh && gh auth login`（浏览器授权），之后 `gh auth setup-git` 再 push；
3. **PAT**：GitHub → Settings → Developer settings → Personal access tokens → 生成仅本仓库 Contents 读写权限的 token，push 时作用户名 marui9819 + 密码填 token。

## 5. 渠道物料人工事项

- 各平台（公众号 / 小红书 / 抖音）发文前，请逐篇阅读 `marketing/` 下对应文案并定稿；
- 小红书封面图已生成在 `marketing/xiaohongshu/covers/`，发布前检查文字无错别字；
- 所有物料已通过红线词扫描，但人工再读一遍更稳妥；
- 请勿在任何渠道宣称"内置直播源""保证可用""永久免费"等。
