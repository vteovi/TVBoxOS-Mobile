# TVBoxOS-Mobile 项目长期笔记

## 构建 / 签名 / 安装（Windows + Git Bash）
- 环境：JDK 17 (`C:\Java\jdk-17`)、Android SDK (`C:\Android\Sdk`)、`local.properties` 里 `sdk.dir=C:\\Android\\Sdk`。仓库：`D:/GitHub/TVBoxOS-Mobile`（另有 `D:/WorkBuddy/TVBoxOS-Mobile`，别改错）。
- 构建：export JAVA_HOME/ANDROID_HOME 后 `./gradlew assembleDebug|assembleRelease --console=plain > build_out.log 2>&1`。**必须沙箱外执行(dangerouslyDisableSandbox:true)**，否则 Gradle 缓存读不到报 AAPT 错误（看着像代码错误实为权限拦截）。
- 产物：`app/build/outputs/apk/{debug,release}/TVBox-Mobile-v<versionName>.apk`（同名靠目录分）。release约41MB。
- 签名：根目录 `TVBoxOSC.jks`（alias/key/store口令均 `TVBoxOSC`），debug/release共用；`minifyEnabled false`。**.gitignore含 *.jks 不入库**。缺失会回退调试证书→无法覆盖安装。校验：`apksigner verify --print-certs <apk>`。
- 安装：`adb install -r <apk>`；本改版 applicationId 已改为 `com.github.tvbox.osc.mobile`（区别于官方 TVBox 与 mbox 的 `com.github.tvbox.osc`，可三者并存、互不覆盖）。签名不符先 `adb uninstall com.github.tvbox.osc.mobile`（旧官方/mbox 才是 `com.github.tvbox.osc`）。
- **交付APK前必校验**（TaskStop杀过构建后）：删产物重跑 assembleRelease → `testzip()` 无损坏 + `apksigner verify` 退出码0 + `sha256sum`，防半成品包。
- **构建防卡配置**(gradle.properties)：`-Xmx1536m -Xms384m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC` + `org.gradle.workers.max=2`。**勿用 `kotlin.compiler.execution.strategy=in_process`**（Kotlin只认`IN_PROCESS`枚举，旧daemon缓存小写值报 `Unknown value 'in_process'`）。
- **一键编译脚本**：`build_apk.ps1`（纯 ASCII PowerShell，已重写可用；原 `.ps1` 只是 `.bat` 副本不能用）。功能：自动读 `versionName` 命名、**构建前后均 `gradlew --stop`** 清残留 daemon、产物存在性自检、`$LASTEXITCODE` 退出码检查；成功后把 APK 复制到**项目根目录**命名 `TVBox-Mobile-v<ver>-<debug|release>.apk`，release 还跑 `apksigner verify` 并输出 SHA256。`.bat` 版本仍存在但未复制产物，优先用 `.ps1`。
- ⚠️ 复制到**项目根目录**的 APK（`TVBox-Mobile-v<ver>-<type>.apk`）已被 `.gitignore` 的 `*.apk` 忽略，禁止 `git add -A` 误提交（曾误入库，已用 reset 修正）。

## 架构要点（改代码用）
- `MainActivity`(Kt)：ViewPager2 仅2页 Home(0)/My(1)，`isUserInputEnabled=false`。`HomeFragment`(Kt)内旧ViewPager+DslTabLayout由ViewPager1Delegate绑定=首页分栏。底部导航4项：首页/我的走vp；直播/订阅独立Activity。
- 状态栏/导航栏 ImmersionBar 3.2.2；深色导航图标用 `navigationBarDarkIcon(boolean)`。
- 动态广播 Android14+ 必须 `RECEIVER_EXPORTED/NOT_EXPORTED`；PendingIntent需 `FLAG_IMMUTABLE`。
- **动态dex(csp.jar/js.jar)按SDK分支**：`SDK>=34` 用 `InMemoryDexClassLoader`(读 classes*.dex 进 ByteBuffer)；`SDK<34` 用 `DexClassLoader+setReadOnly()`。`InMemoryDexClassLoader` 是 final 类且无 jar 路径→`getResourceAsStream` 返回null，需 `JarResourceClassLoader` 作 parent 补资源。dex能否加载=订阅是否可用，`Init.init()` 异常降级不误报。
- **spThreadPool 单线程池自锁**：跑在 spThreadPool 的任务内部不要再往 spThreadPool 提交任务（`getHomeRecList` 已改 `homeRecThreadPool`）。
- 底部导航 `include_bottom_navigation.xml`（三处include：MainActivity/LiveActivity/SubscriptionActivity）；高58dp图标24dp，背景 `bg_bottom_navigation=#FFFFFFFF` 纯白（用户要求勿动）。Material2主题，item高度低于~56dp图标文字必重叠。
- **大屏宽屏左侧导航**：`res/layout-sw600dp/`（4文件）把导航移到左侧。`include_bottom_navigation.xml` 用 `NavigationRailView`(`com.google.android.material.navigationrail.NavigationRailView`，竖向左导航，宽88dp)；三页 activity_* 根改 `horizontal`、导航在左内容在右，live_root id保留。⚠️ `BottomNavigationView` 没有 `app:orientation` 属性（链接期报 not found），不能靠它做竖排。**三页 Activity 把 `bottom_nav` 强转类型由 `BottomNavigationView` 改为公共基类 `NavigationBarView`**（手机端仍是 BottomNavigationView，大屏为 NavigationRailView，二者都继承 NavigationBarView）。sw600dp=较短边≥600dp触发，手机不触发。
- adb限制(MIUI/HyperOS)：`input tap` 被拒无法模拟点击；验证用 `am force-stop`+`monkey` 后 `screencap`。debug包配置 `adb shell run-as ... cat shared_prefs/Hawk2.xml`。
- **Edit工具会把CRLF改LF致diff噪音**：用Python bytes级替换并保留行尾 `replace(b'\r\n',b'\n').replace(b'\n',b'\r\n')`。
- **异步回调晚于Activity销毁→NPE**（直播页崩溃根因）：所有「网络/子线程→主线程→改View/播放器」路径先判 `isFinishing()||isDestroyed()` 并判空View；网络请求onDestroy取消。
- 首页数据源排序：`SourceViewModel.getSort(sourceKey)` 经 `ApiConfig.get().getSource(key)` 取 `SourceBean`；取不到须 null 保护（见关键修复清单）。
- **播放器布局与设置弹窗**：VOD 真正使用的控制布局是 `res/layout/player_vod_control_view.xml`（`VodController.getLayoutId()`）；`box_vod_control_view.xml` 是无人引用的遗留文件。播放设置弹窗 `dialog_playing_control.xml` 被 `PlayingControlDialog`(手机底部) 与 `PlayingControlRightDialog`(sw600dp 右侧抽屉) 共用，布局改一次即可，但**两个 Java 类都要各自接监听**。
- **直播页方向策略(2.18.0 定稿, 勿退回锁横屏)**：全程不锁方向。`LiveActivity.initVideoView()` 里 `setEnableOrientation(true)` + `setRotateToFullScreenEnabled(true)`：手机横屏/大屏横屏进入直播页都会自动进全屏，转到竖屏则退出全屏回普通竖屏页。**库的 `BaseVideoController.stopFullScreen()` 硬编码 `setRequestedOrientation(PORTRAIT)`**，平板会一退全屏就翻竖屏，已在 `LiveNewController` 覆写成 `UNSPECIFIED`；`PlayerTitleView` 的返回按钮同理。**dkplayer 的 `onOrientationLandscape/ReverseLandscape` 没有 `mEnableOrientation` 保护**(只有 portrait 分支有)，要禁止自动全屏必须覆写这两个回调，`setEnableOrientation(false)` 拦不住横屏。
- **直播全屏里的画中画入口**：全屏时 `activity_live.xml` 的 `ic_pip` 被播放器容器盖住，所以在 `dkplayer_layout_menu_view.xml` 右侧菜单栏加了 `pip` 按钮(`PlayerMenuView.OnPlayerMenuClickListener.onPip()` → `LiveActivity.enterPip()`)，仅 `Utils.supportsPiPMode()` 时显示。`enterPip()` 开头若处于全屏会先 `mVideoView.stopFullScreen()` + 方向复位 `UNSPECIFIED`，否则全屏容器(decorView)会让画中画残留白边、退出后卡横屏。`dkplayer_layout_menu_view.xml` 只被直播页 `PlayerMenuView` 使用，改它不影响点播。
- 直播页视频区高度运行时自适应 `applyLiveVideoBoxSize()`(布局 id `live_video_box`，`layout/` 与 `layout-sw600dp/` 都加了)：竖屏 `max(240, 屏宽*9/16)`、横屏 `max(240, min(屏高-120, 屏高*0.62))`；`mVideoView` 高度改 `match_parent`(原固定 300dp 在大屏偏小)。全屏/PiP 时跳过，退出全屏(PLAYER_NORMAL)时重算。资源限定符里 smallestWidth 优先于 orientation，`layout-sw600dp` 会盖过 `layout-land`，别指望加 land 布局区分手机横屏。
- **直播页横竖屏两套结构（2.19.0 起，运行时切换，不是 layout-land）**：`AndroidManifest` 给 LiveActivity 声明了 `configChanges="orientation|screenSize|smallestScreenSize|..."`，**旋转不重建 Activity → 新增 `layout-land/` 完全不会生效**，本页两套布局（`layout/` 与 `layout-sw600dp/`）只在首次启动时选择。横屏左右分栏靠 `applyLiveResponsiveLayout()` 改 LayoutParams 实现（由 `applyLiveVideoBoxSize()` 开头调用）：布局里已加 `live_content_row` + `live_video_column` 两层容器，竖屏 row=VERTICAL / 横屏 row=HORIZONTAL + 视频列 weight=3、频道列表 weight=2、EPG 条压到 60dp。改这两个 View 的层级或 LP 前先确认 id 不变。画中画时左栏(weight=3)会被 `applyPipUiMode` 临时改成 MATCH_PARENT(`mSavedColumnLp`)，否则小窗右侧空白。

## 深色模式 / 主题
- 切换：`util/Utils.isDarkTheme()` + `AppCompatDelegate.setDefaultNightMode`。
- 深色覆盖在 `values-night/colors.xml`（无 drawable-night）；改深色配色要在 values-night 新增同名color覆盖，否则沿用浅色（如 `windowBackground` 浅色#fff不覆盖会闪白）。
- 2026-10-01起深色背景统一纯黑 `#000000`（OLED）；webview深色在 `res/raw/style.css`(weui `--weui-BG-*`)。底部导航白色勿动。

## 版本演进与功能增减（2.5.2 → 2.6.1）
- 2.5.4：设置页「数据备份/还原」改 SAF 系统文件选择器，弃用 MANAGE_EXTERNAL_STORAGE 请求（自定义 ActivityResultContract + OpenDocument，zip 自包含备份）。
- 2.5.5：彻底删 MANAGE_EXTERNAL_STORAGE 声明；本地导入(content://)改复制进 App 私有目录存 file://（新增 util/LocalImport.kt；参考 D:\Doubao\TVBoxOS_Portrait 的"复制进私有目录"思路）。ApiConfig.readContentConfig 加 file:// 分支走 FileInputStream。
- 2.5.6：移除「本地视频」功能（入口/权限/Activity/Adapter/资源全删；保留 VideoInfo/VideoFolder model 被订阅收藏复用、SubsciptionDialog.tvLocal 是订阅本地导入按钮）。删 READ_MEDIA_VIDEO 权限。
- 2.5.7：修复 ApiConfig.java 多余右花括号导致类提前关闭（见踩坑教训）。
- 2.5.8：AndroidManifest 删 READ/WRITE_EXTERNAL_STORAGE 声明 + requestLegacyExternalStorage 属性（应用层）。
- 2.5.9：删 `player` 模块存储权限声明 + 应用层 tools:node=remove 兜底，根除存储弹窗（多模块合并）。
- 2.5.10：SourceViewModel.getSort 对 getSource 返回 null 做保护，修复切换数据源后首页 NPE。
- 2.6.0：播放页(详情页)标题栏 tvName 改 weight=1 占满，宽度随屏幕自适应（横屏更宽/竖屏较窄）。
- 2.6.1：内置播放器(VOD)顶部标题 tv_info_name1 改 match_parent 占满自适应（容器 match_parent + marginEnd 预留右侧图标）。

## 关键修复清单（已落地）
- 详情页"外部播放"按钮(`tvExternalPlay`)：直接调系统播放器（`ACTION_VIEW` 选择器，含MX/VLC）。**Android11+包可见性**：manifest `<queries>` 须声明 `video/*` intent + MX(.pro/.ad/.beta)/Reex/Kodi/VLC 包名，否则检测全失败。**任何检测已装App/隐式intent调外部App，都须先声明`<queries>`**。
- 首页顶栏：搜索栏改搜索按钮(放收藏左)、数据源名 `tvName`（2.5.x 曾 maxWidth 110→200dp；2.6.0 起改 weight=1 自适应）。
- 移除设置页与"我的"页切换壁纸入口（`WallpaperManager` 系统保留，已设壁纸仍生效）。
- 内置源：`strings.xml` `api_name` 数组 = 饭太硬(在线)/王二小(在线)/龙门影视(在线)。
- SourceViewModel.getSort：getSource(key)==null → `sortResult.postValue(null)` 返回，不 NPE（HomeFragment observer 已支持 null，复用 sourceKey==null 既有空路径）。
- 存储权限弹窗根因链：MANAGE(2.5.5删) → READ/WRITE_EXTERNAL_STORAGE(:app 2.5.8删但 player 模块 2.5.9才删) → 最终 tools:node=remove 兜底。

## 踩坑教训（高价值）
- ⚠️ **"缓存/守护进程"诊断极易误判**：2.5.6/2.5.7 曾三次判为 Gradle/Kotlin 守护进程缓存，实际是 ApiConfig.java 多一个 `}` 的真 bug。**判定铁律：用户杀 java + 删 app/build + 重构建仍逐字节复现 → 立即排除缓存，是真代码 bug**（缓存清了就变）。真 bug 时删缓存无效，必须看源码、做括号平衡自检。改 Java try-with-resources / 大改嵌套括号后务必扫描括号平衡（写过 Python 扫描器：跟踪字符串/注释/块注释，数 `{` `}` 是否归零）。
- ⚠️ **多模块 manifest 权限**：TVBox 多模块(:app / :player / :crash / :quickjs / :TabLayout / :ViewPager1Delegate)，删权限必须**逐模块**查 AndroidManifest.xml，且最终在 :app 用 `tools:node="remove"` 兜底最稳——库合并会把权限带回最终 APK，只在 :app 删等于没删。
- 存储权限弹窗根因链：MANAGE(2.5.5删) → READ/WRITE_EXTERNAL_STORAGE(:app 2.5.8删但 player 模块 2.5.9才删) → 最终 tools:node=remove 兜底。
- Edit 工具改 CRLF→LF：用 Python bytes 级 `replace(b'\r\n',b'\n').replace(b'\n',b'\r\n')` 保留行尾。

## 用户偏好
- 中文沟通，发崩溃截图/日志逐条报 bug。改完**自动 git add+commit 本地快照（不push）**，汇报"已提交未推送"。
- 助手不构建（构建/出 APK 由用户做）；只写代码+静态校验。汇报时给构建命令。
- 白条(系统导航栏颜色)问题搁置，勿主动改。
- 外部播放直接走系统选择器即可，无需额外二级列表。
- 隐私相关：用户介意"存储权限弹窗/能读所有文件"，已逐步移除所有存储类权限声明。
