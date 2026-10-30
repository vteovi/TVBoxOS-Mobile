<div align="center">

<img src="https://github.com/vteovi/TVBoxOS-Mobile/blob/main/website/tvbox/images/logo.png?raw=true" width="140px" />

<h1>TVBoxOS-Mobile</h1>

<p>一个开源、免费、无广告的 TVBox 手机 / 平板客户端</p>

[![GitHub stars](https://img.shields.io/github/stars/vteovi/TVBoxOS-Mobile?logo=Undertale)](https://github.com/vteovi/TVBoxOS-Mobile/stargazers)
![forks](https://img.shields.io/github/forks/vteovi/TVBoxOS-Mobile.svg)
![tag](https://img.shields.io/github/tag/vteovi/TVBoxOS-Mobile.svg)
![release](https://img.shields.io/github/release/vteovi/TVBoxOS-Mobile.svg)
![license](https://img.shields.io/github/license/vteovi/TVBoxOS-Mobile.svg)
[![GitHub Pull Requests](https://img.shields.io/github/issues-pr/vteovi/TVBoxOS-Mobile)](https://github.com/vteovi/TVBoxOS-Mobile/pulls)

[![Typing SVG](https://readme-typing-svg.demolab.com?font=Fira+Code&weight=900&size=22&duration=4000&pause=1000&color=140900&center=true&vCenter=true&width=550&height=30&lines=%E2%AD%90%E4%BC%98%E8%B4%A8%E6%8E%A5%E5%8F%A3%C2%B7%E7%9B%B4%E6%92%AD%E6%BA%90%C2%B7%E7%9B%B8%E5%85%B3%E8%BD%AF%E4%BB%B6%E5%92%8C%E7%BD%91%E7%AB%99%E7%9A%84%E6%90%9C%E9%9B%86%E5%88%86%E4%BA%AB%E2%AD%90)](https://git.io/typing-svg)

您是第 <img src="https://access-counter.vercel.app/api/counter?name=kukuqi666" /> 位访问者

</div>

## 📖 项目简介

TVBoxOS-Mobile 是基于 CatVod（TVBox）开源方案的 Android 客户端改版，针对**手机与平板**做了完整适配：点播、直播、搜索、订阅、备份还原、应用内更新全流程可用，界面无广告、无捆绑。

**项目只交付客户端本身**，播放内容来自你自己导入的接口（订阅）。仓库不存储、不转存任何影视资源。

| 项目 | 说明 |
| --- | --- |
| 应用包名 | `com.github.tvbox.osc.mobile`（可与官方 TVBox、影视仓等版本共存安装） |
| 系统要求 | Android 7.0 及以上（minSdk 24），目标 Android 14（targetSdk 34） |
| CPU 架构 | `arm64-v8a` / `armeabi-v7a` 分包，按设备选一个安装 |
| 当前版本 | 2.26.1（以 [Releases](https://github.com/vteovi/TVBoxOS-Mobile/releases) 为准） |

## ✨ 功能特性

| 模块 | 说明 |
| --- | --- |
| 📺 点播 | 多数据源切换、线路选择、剧集网格选集、断点续播、字幕与倍速 |
| 📡 直播 | 频道分组 + EPG 节目单、全屏频道抽屉、画中画、密码频道 |
| 🔍 搜索 | 快速搜索（多源并发）、搜索建议、搜索结果按源聚合 |
| ⭐ 收藏 / 历史 | 收藏夹与观看历史，支持按数据源分组与批量清理 |
| 🗂 订阅管理 | 在线链接导入、本地配置导入、内置源、去重与失效清理 |
| 💾 备份还原 | SAF 导出 / 导入整套配置与本地订阅文件，换机不用重设 |
| 📶 设备同步 | 同一局域网内两台设备互传配置（UDP 发现 + 子网探测，HTTP 传输） |
| 🎨 界面 | 深色模式（OLED 纯黑）、自定义壁纸、四入口底部导航 |
| 📱 平板适配 | 大屏自动切换侧边导航栏、列表多列、详情页内联展示影片信息 |
| 🔄 应用内更新 | 按设备架构选择安装包、多线路加速回退、下载进度、包名与版本校验 |
| 📤 外部播放 | 一键调用 MX / VLC / Reex / Kodi 等已安装播放器 |
| 🖥 投屏 | DLNA 投屏到电视 / 投影仪 |

## 📲 下载安装

<!-- release-download: 发版时 CI 会自动把下面的链接更新到最新版本 -->

| 设备 | 安装包 | 适用 |
| --- | --- | --- |
| 64 位设备（推荐） | [TVBox-Mobile-v2.26.1-arm64v8.apk](https://gh-proxy.com/https://github.com/vteovi/TVBoxOS-Mobile/releases/download/v2.26.1/TVBox-Mobile-v2.26.1-arm64v8.apk) | 绝大多数 2018 年之后的手机 / 平板 |
| 32 位设备 | [TVBox-Mobile-v2.26.1-armv7a.apk](https://gh-proxy.com/https://github.com/vteovi/TVBoxOS-Mobile/releases/download/v2.26.1/TVBox-Mobile-v2.26.1-armv7a.apk) | 老机型、部分电视盒子 |

- 全部版本：<https://github.com/vteovi/TVBoxOS-Mobile/releases>
- 装好后进入「我的 → 关于 → 检查更新」即可在线升级，程序会自动匹配你的设备架构；下载完会校验包名与版本号再安装，避免装错包。
- 安装包统一使用固定签名，后续版本可直接覆盖安装。

## 🧩 项目结构

```
TVBoxOS-Mobile/
├── app/                    主应用：界面、数据层、播放控制、设置与工具
│   └── src/main/
│       ├── java/…/osc/     activity / ui / api / bean / data / util …
│       ├── res/            布局与资源，layout-sw600dp 为平板专用
│       └── assets/         内置订阅、EPG、JS 脚本等
├── player/                 播放器内核封装（ijk / exo，自带 .so）
├── quickjs/                QuickJS 引擎，运行接口里的 js
├── TabLayout/              首页分栏 Tab 控件
├── ViewPager1Delegate/     ViewPager 与 TabLayout 的绑定代理
├── crash/                  崩溃捕获与本地记录
├── website/                项目主页与静态资源（logo、图片）
├── scripts/                发版脚本：生成 update.json、回写 README
├── .github/workflows/      CI：在线构建、签名、发布 Release
├── update.json             应用内更新清单（版本 / 下载地址 / 更新说明）
├── 更新记录.md              版本更新历史（发版时同步维护）
└── gradle/ gradlew …       构建脚本
```

| 模块 | 职责 |
| --- | --- |
| `app` | 唯一入口模块，所有界面与业务逻辑；按平板宽度使用 `layout-sw600dp` 变体 |
| `player` | 播放器控件与内核封装，提供 `arm64-v8a` / `armeabi-v7a` 原生库 |
| `quickjs` | 接口脚本执行环境，缺少对应架构的 `.so` 会在启动阶段闪退 |
| `TabLayout` | 首页顶部分栏控件 |
| `ViewPager1Delegate` | 把 TabLayout 与 ViewPager 绑定成首页分栏 |
| `crash` | 全局异常捕获，便于反馈问题 |

## 🏗️ 技术栈

| 项 | 版本 / 选型 |
| --- | --- |
| 语言 | Java + Kotlin |
| 构建 | Gradle 8.7 / AGP 8.5.2 / JDK 17 |
| SDK | compileSdk 34，minSdk 24，targetSdk 34 |
| 架构 | 按 ABI 分包（`arm64v8` / `armv7a`），`applicationId` 与官方版隔离 |
| 网络 | OkGo / OkHttp3、nanohttpd（局域网同步）、conscrypt |
| 数据 | Gson、Room、Hawk（配置持久化） |
| 界面 | AndroidX、ViewBinding、ViewPager2、BaseRecyclerViewAdapterHelper、XPopup、ImmersionBar、AndroidAutoSize |
| 媒体 | ijkplayer / exoplayer、Glide、Picasso |

## 🛠️ 自行构建

### 方式一：GitHub Actions（推荐，无需本地环境）

1. Fork 本仓库，`Actions` → `Build APK` → `Run workflow`；或直接 push 到 `main`。
2. 运行结束后在该次运行的 `Artifacts` 里下载，一次产出两个架构：
   `TVBox-Mobile-v<版本>-arm64v8.apk`、`TVBox-Mobile-v<版本>-armv7a.apk`。
3. 发布正式版：改好 `app/build.gradle` 里的 `versionName` / `versionCode` 并提交，然后打同名标签推送：

   ```bash
   git tag v2.27.0 && git push origin v2.27.0
   ```

   工作流会校验标签与 `versionName` 是否一致，随后自动签名打包、创建 Release，并回写 `update.json` 与本文档的下载链接。

**签名密钥（只需配置一次）**

在仓库 `Settings` → `Secrets and variables` → `Actions` 新增 `TVBOX_KEYSTORE_BASE64`，值为 `TVBoxOSC.jks` 的 Base64 单行文本：

```bash
base64 -w 0 TVBoxOSC.jks
```

> 未配置时分支构建会退化为 debug 包（可安装测试，但无法覆盖安装正式版）；打标签发布会直接失败。
> 密钥别名 / 口令默认均为 `TVBoxOSC`，如不同再补充 `TVBOX_KEY_ALIAS`、`TVBOX_KEY_PASSWORD`、`TVBOX_STORE_PASSWORD`。

### 方式二：本地构建

需要 JDK 17 与 Android SDK（`local.properties` 里配置 `sdk.dir`），然后：

```bash
# 一次产出两个架构的 release 包
./gradlew :app:assembleRelease

# 只出某一个架构
./gradlew :app:assembleArm64v8Release
./gradlew :app:assembleArmv7aRelease
```

产物位于 `app/build/outputs/apk/<flavor>/<buildType>/TVBox-Mobile-v<版本>-<flavor>.apk`。

Windows 下也可以直接运行 `build_apk.ps1`（默认 release，`.\build_apk.ps1 debug` 出 debug 包）：脚本会构建各架构、把安装包复制到仓库根目录，并打印大小、SHA256 与安装命令。

> 暂不提供 x86 / x86_64 包：`player`、`quickjs` 中没有对应架构的原生库，强行打包会在启动时 `QuickJSLoader.init()` 抛 `UnsatisfiedLinkError`。

## 📦 版本与发布约定

- 版本号遵循语义化版本 `MAJOR.MINOR.PATCH`：加功能升 MINOR，修 bug 升 PATCH，不兼容改动升 MAJOR。
- `versionCode` 每次进入 APK 的提交都要 +1，用于覆盖安装与应用内更新判断。
- 每次发版同步三处：`app/build.gradle` 的 `versionName`/`versionCode`、`update.json`、`更新记录.md`。
- 安装包名固定为 `TVBox-Mobile-v<版本>-<flavor>.apk`。

## 📝 更新记录

>* **2026/10/30 TVBox Mobile v2.26.1：** 修复「检查更新」首选加速站失效（`gh.xxooo.cf` 已无法连接）：主加速改为 `gh-proxy.com`、备用 `ghfast.top`，加速前缀统一到 `ACCEL_PREFIXES` 维护，旧前缀仍兼容剥离；README 与 `update.json` 下载链接同步改用新线路。
>
>* **2026/10/30 TVBox Mobile v2.26.0：** 完善应用内检查更新（按架构选包、多线路回退、下载进度与包校验）；「关于」项目地址更正并移除联系方式；打包改为 GitHub Actions 在线构建。
>
>* **2026/10/28 TVBox Mobile v2.25.0：** 按 CPU 架构分包，同时提供 arm64v8 与 armv7a 安装包。
>
>* **2026/10/28 TVBox Mobile v2.24.0：** 详情页选集改为网格 + 整页上下滑动，列数随屏幕宽度自适应。

> 更早的版本与逐条改动见 [更新记录.md](./更新记录.md)。

## 🔗 相关资源

视频源 / 接口（由第三方维护，时效性不保证）：

- <https://github.com/gaotianliuyun/gao>
- <https://github.com/yoursmile66/TVBox>
- <https://github.com/xyq254245/xyqonlinerule>
- <https://github.com/UndCover/PyramidStore>

GitHub 访问加速：<https://gh-proxy.com/>

## ⚠️ 免责声明

- 本项目仅供学习交流与技术研究，**不得用于任何商业用途**。
- 本项目不存储、不制作、不转存任何音视频内容，也不提供任何接口；所有内容均来自用户自行导入的第三方接口。
- 接口与直播源由第三方维护，其可用性、合法性、内容安全性均与本项目无关；因使用第三方接口产生的一切后果由使用者自行承担。
- 请于下载后 24 小时内删除，如果你喜欢某部作品，请支持正版。
- 本项目基于 AGPL-3.0 协议开源，Fork 或引用请遵循协议。

## 🙏 致谢

- [CatVod / TVBox](https://github.com/CatVodTVOfficial/TVBoxOSC) 及其衍生项目提供的客户端方案
- [mlabalabala/TVBoxOS-Mobile](https://github.com/mlabalabala/TVBoxOS-Mobile) 上游仓库
- 所有开源依赖的作者，以及维护接口与直播源的朋友们
