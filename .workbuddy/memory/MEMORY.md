# TVBoxOS-Mobile 项目笔记

> 2026-11-16 精简。日常细节见 `.workbuddy/memory/YYYY-MM-DD.md`。

## 分支与发布
- `dev`=日常开发唯一入口；`master`=稳定版+GitHub 默认分支（旧 `main` 已删，更新检测走 `/master/update.json`）；`2.34.2`=存档分支（2.34.0~2.34.2 旧功能）。
- 版本：feat 升 MINOR、fix 升 PATCH，versionCode 每次 +1 不复用（**当前 2.34.9 / 385**）；发版前先写 `更新记录.md` 的 `### 版本 — 日期` 段（CRLF）。
- 发布（用户说「发布」才做）：dev 推完 → master `pull --rebase` + `merge --no-ff dev` → 推 master → `tag vX.Y.Z && push tag`（CI 只听 tag）→ 回 dev `merge origin/master`；github.com 被 TLS 阻断时用 `scripts/api_push_branches.py`。
- 推前 `unset *_proxy`（否则 502）；全局 `gh-proxy` insteadOf 会让 push 报 `No anonymous write access`，`--unset-all` 推完再 `--add`。

## 构建
- 仓库 `D:/WorkBuddy/TVBoxOS-Mobile`；`applicationId=com.github.tvbox.osc.mobile`。
- `export JAVA_HOME=/c/Java/jdk-17 && export PATH="/c/Java/jdk-17/bin:$PATH" && export ANDROID_HOME=/c/Android/Sdk`（shell 不继承用户环境变量）。
- `./gradlew assembleArm64v8Release --console=plain` → `app/build/outputs/apk/arm64v8/release/TVBox-Mobile-v<ver>-arm64v8.apk`；改 .kt 必单独跑 `compileArm64v8DebugKotlin`。
- 签名 `TVBoxOSC.jks`（全 `TVBoxOSC`），别 `git add -A`（根目录 APK 已 ignore）。
- 源码与 md 均为 **CRLF**：`Edit` 长段落常因 LF 不匹配失败 → 大段改用 Python `newline=''` 按行替换；出包后 `cp` 到项目根目录。

## 布局/架构
- 首页两套：`layout/fragment_home.xml`（手机：顶栏数据源胶囊按钮 + 右下浮动搜索 FAB `btn_search`）、`layout-sw600dp/fragment_home.xml`（平板：顶栏 + 右上 280dp 搜索框 `search`，无 FAB）→ `mBinding.btnSearch?.` / `mBinding.root.findViewById<View>(R.id.search)?`（Fragment 无 findViewById）。
- ViewBinding 以默认布局定类型：sw600dp 独有控件用 `findViewById`+判空；两套布局同名 id 必须同类型。
- 底部导航 58dp / 图标 24dp / **纯白背景勿动**；sw600dp 用 `NavigationRailView`，代码统一 `NavigationBarView`；菜单 `menu/` 与 `menu-sw600dp/` 是两份文件，改菜单要同步。
- `MainActivity`=ViewPager2(Home/My)；首页分栏 `HomeFragment`（旧 ViewPager + `DslTabLayout`）；直播/订阅独立 Activity；搜索页 `FastSearchActivity`（已并入底部导航，`EXTRA_START_DESTINATION` 回跳）。
- `DetailActivity`：手机点标题弹 `VideoDetailDialog`，`Utils.isTablet()` 时不弹、信息在右栏 → 大屏要单独入口（`ext_copy_link` 共用 `copyCurrentVodUrl()`）。

## 高频坑
- 主题色别用 `ColorUtils.getColor()`，统一 `Utils.getThemeColor(ctx,res)`；深色覆盖只在 `values-night/colors.xml`，新增颜色必须补 night。
- **Hawk 2.0.1 的 SP 文件是 `Hawk2`**，`getSharedPreferences("Hawk")` 静默失效；备份走 `Hawk.get/put` 真实值。
- **LoadSir 1.3.8**：`setupSuccessLayout()` 设 INVISIBLE，必须真走 `showSuccess()`；外层别再包状态缓存。
- `GONE`→`VISIBLE` 的 RecyclerView 常被量成 0 尺寸且 `requestLayout()` 无效 → 用 `INVISIBLE` + 强制重排（v2.33.3 选集空白）。
- `DslTabLayout.currentItemIndex` 重建期是 -1 → 取当前 tab 兜底读 `ViewPager.currentItem`，`onBackPressed` 用 `getOrNull()`（v2.32.9）。
- `FragmentStatePagerAdapter` 回来复用实例、`init()` 不重跑 → 观察者要在 `onCreate` 重挂。
- 首页后台刷新只写缓存不碰 adapter（整表替换会闪+跳位）；大屏多列一屏 >20 条时 LoadMore 不触发，靠 `GridFragment.autoFillViewport()` 补页。
- 异步回调判 `isFinishing()||isDestroyed()`；Android14+ 动态广播需 EXPORTED，PendingIntent 需 IMMUTABLE。
- 搜索热词：腾讯 `node.video.qq.com/x/api/hot_search` **已下线** → 内置 `arrays.xml` 兜底 + 豆瓣 `j/search_subjects` 增强。

## 用户偏好
- 中文沟通；改完自动本地 `git add + commit` 到 dev（不自动 push），汇报 commit id。
- 「发布」才推 master + tag；构建自行完成，产物 `cp` 到项目根目录供其装机验证。
- 介意「读取所有文件」类权限；导航栏白条搁置。
