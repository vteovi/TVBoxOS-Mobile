# TVBoxOS-Mobile 项目长期笔记

## 构建 / 签名 / 安装（Windows）
- 环境：JDK 17 `C:\Java\jdk-17`、SDK `C:\Android\Sdk`（`local.properties` 的 `sdk.dir`）、`applicationId=com.github.tvbox.osc.mobile`（可与官方/mbox 并存）。仓库实际在 `D:/WorkBuddy/TVBoxOS-Mobile`（另有 `D:/GitHub/...`，别改错）。
- 构建：`./gradlew assembleDebug|assembleRelease --console=plain > build_out.log 2>&1`，**必须沙箱外执行**，否则 Gradle 缓存被拦、报假的 AAPT 错误。产物 `app/build/outputs/apk/<type>/TVBox-Mobile-v<versionName>.apk`。
- 签名：根 `TVBoxOSC.jks`（alias/key/store 均 `TVBoxOSC`），debug/release 共用，`minifyEnabled false`；`.gitignore` 忽略 `*.jks` 与根目录 `*.apk`（**别 `git add -A` 误提交 APK**）。校验 `apksigner verify --print-certs`。
- **主用 CI 在线出包**（2.26.0 起，用户不再本机构建）：`.github/workflows/build.yml`；push main / workflow_dispatch → 两架构 APK 走 Artifacts；推 `v*` tag → `assembleRelease` + apksigner 校验 + 发 Release + 回写 `update.json`/README。密钥来自 secret `TVBOX_KEYSTORE_BASE64`（`base64 -w 0 TVBoxOSC.jks`），缺失时分支构建退化为 debug 包。`build_apk.bat` 已删，`build_apk.ps1` 保留作离线兜底（按 `apk/<flavor>/<buildType>/` 枚举产物）。
- 交付前必校验（尤其 TaskStop 杀过构建）：删产物重跑 → zip 完好 + apksigner 退出码 0 + sha256，防半成品。
- gradle.properties 防卡：`-Xmx1536m -Xms384m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC`、`org.gradle.workers.max=2`。**勿设 `kotlin.compiler.execution.strategy=in_process`**（只认大写 `IN_PROCESS`，小写报 Unknown value）。

## 架构要点
- `MainActivity`(Kt)：ViewPager2 两页 Home/My，`isUserInputEnabled=false`；`HomeFragment` 内旧 ViewPager+DslTabLayout 由 `ViewPager1Delegate` 绑定=首页分栏。底部导航 4 项（首页/我的走 vp，直播/订阅独立 Activity）。
- 底部导航 `include_bottom_navigation.xml`（MainActivity/LiveActivity/SubscriptionActivity 三处 include）：高 58dp、图标 24dp、背景纯白 `#FFFFFFFF`（用户要求勿动）；item 高度 <~56dp 图标文字必重叠。
- **大屏左侧导航**：`res/layout-sw600dp/` 用 `NavigationRailView`（宽 88dp），三页 activity_* 根改 `horizontal`、导航在左；`bottom_nav` 强转类型改公共基类 `NavigationBarView`（手机 BottomNavigationView / 大屏 Rail 都继承它）。`BottomNavigationView` 无 `app:orientation`，不能靠它竖排。sw600dp 触发条件=较短边≥600dp。
- 动态广播 Android14+ 需 `RECEIVER_EXPORTED/NOT_EXPORTED`；PendingIntent 需 `FLAG_IMMUTABLE`。
- **动态 dex(csp.jar/js.jar)**：SDK≥34 用 `InMemoryDexClassLoader`（classes*.dex 进 ByteBuffer），SDK<34 用 `DexClassLoader+setReadOnly()`；InMemory 版无 jar 路径→资源读不到，需 `JarResourceClassLoader` 作 parent。dex 能否加载=订阅是否可用。
- `spThreadPool` 单线程池自锁：跑在其中的任务别内嵌再提交到 spThreadPool（首页推荐已改 `homeRecThreadPool`）。
- 异步回调晚于 Activity 销毁→NPE（直播页崩溃根因）：所有「子线程→主线程→改 View/播放器」先判 `isFinishing()||isDestroyed()` 并判空，onDestroy 取消请求。
- `SourceViewModel.getSort(key)` 经 `ApiConfig.getSource(key)` 取 `SourceBean`，**必须 null 保护**（否则切源后首页 NPE）。
- 播放器：VOD 真正用的是 `player_vod_control_view.xml`（`box_vod_control_view.xml` 是死文件）；`dialog_playing_control.xml` 被 `PlayingControlDialog`(手机底部) 与 `PlayingControlRightDialog`(sw600dp 抽屉) 共用，布局改一次但**两个类都要接监听**。

## 直播页方向 / 布局（2.18~2.21 定稿）
- 全程不锁方向。`LiveActivity.mEnteredPortrait` + `LiveNewController.setRotateToFullScreenEnabled()` 作闸门：竖屏进入才保留「转横屏自动全屏」；**横屏进入（手机横屏/大屏）直接停在左右分栏普通页，不自动全屏**（用户明确要求）。出现过竖屏即在 `onConfigurationChanged` 解锁。
- dkplayer 退出全屏多条路径：`stopFullScreen()`、`ControlWrapper.toggleFullScreen()`(双击,硬 PORTRAIT)、`toggleFullScreenByVideoSize()`、`PlayerTitleView` 返回——改方向策略必须 grep `setRequestedOrientation` 全查。`LiveNewController` 已覆写 `stopFullScreen/toggleFullScreen` 为 UNSPECIFIED；`onOrientationLandscape/ReverseLandscape` 无 `mEnableOrientation` 保护，禁自动全屏只能覆写它们。
- **横竖屏两套结构靠运行时改 LayoutParams，不是 layout-land**：LiveActivity 声明了 `configChanges` 旋转不重建 → `layout-land/` 不生效，只在启动时选 `layout/` 或 `layout-sw600dp/`。`applyLiveResponsiveLayout()`（由 `applyLiveVideoBoxSize()` 开头调用）用 `live_content_row`+`live_video_column` 两层容器切换：竖屏 VERTICAL / 横屏 HORIZONTAL（视频 weight=3、频道 weight=2、EPG 压 60dp）。
- `applyLiveVideoBoxSize()`（id `live_video_box`，两种布局都有）：竖屏 `max(240, 屏宽*9/16)`、横屏 `max(240, min(屏高-120, 屏高*0.62))`；全屏/PiP 跳过，回 PLAYER_NORMAL 重算。smallestWidth 优先于 orientation，`layout-sw600dp` 会盖过 `layout-land`。
- 全屏画中画入口：全屏时 `ic_pip` 被播放器容器盖住 → 在 `dkplayer_layout_menu_view.xml`（仅直播页用）右侧菜单加 pip 按钮 → `LiveActivity.enterPip()`；进入前先 `stopFullScreen()`+方向复位，否则白边/卡横屏。
- 全屏频道抽屉（XPopup）列宽忽宽忽窄：必须显式 `.popupWidth(max(dp2px(260), 屏宽*0.42f))`，否则 wrap_content 测量让 `0dp+weight` 失效。
- RelativeLayout 改定位要清**全部**同类锚点（只清 ALIGN_PARENT_BOTTOM 漏了 alignParentRight 就会重叠）。

## Hawk / 设置持久化（重要）
- ⚠️ **Hawk 2.0.1 的 SharedPreferences 文件名是 `Hawk2`**（`STORAGE_TAG_DO_NOT_CHANGE="Hawk2"`）。绕开 Hawk API 用 `getSharedPreferences("Hawk")` 读写会**静默失效**（曾导致同步后 API_URL/HOME_API 等全部不生效，只有走 `Hawk.put` 的订阅正常）。备份类代码要么用 `"Hawk2"`，要么全走 Hawk API。
- Hawk 内部存 `type#value` 包装串（`INFO_DELIMITER="#"`），sp 原始值不是纯值 → 判断内容必须 `Hawk.get` 取真实值。
- **备份/还原设置一律走 `Hawk.get`/`Hawk.put` 真实值**，不要搬运序列化串（实测 `editor.putString(原串)` 后 HOME_REC/LANDSCAPE_CARD 不生效）。复杂对象导出登记 `val.getClass().getName()`，导入端 `Gson.fromJson(json, Class.forName(名))` 再 `Hawk.put`，避免退化成 LinkedTreeMap。
- `Hawk.put` 内部 `editor.commit()` 同步落盘；自己写 sp 且随后要 `relaunchApp` 时**必须 commit 不能 apply**。
- 备份 JSON 里复杂对象用「字符串 primitive」存最稳，导入端先判 `isJsonArray/isJsonPrimitive` 两种形态，别让异常静默吞掉。
- 本地导入的订阅/配置 URL 是 `file:///data/data/<pkg>/files/...`（仅本机私有目录）→ 同步/备份必须把文件内容一起打包（`localFiles` Base64），接收端重建并重写 URL，否则报"无法读取本地配置文件"。
- `Subscription` 的 `url`/`name` 是**包级私有**，跨包只能用 `getUrl()/setUrl()`。

## 局域网设备同步（2.22.x）
- 设置页「设备同步（局域网）」→ `DeviceSyncDialog`；核心 `util/LanSyncManager.java`（纯 JDK）。
- 发现：UDP 广播 45127（`TVBOX_LAN_SYNC|设备名|端口`，接收端**必须拿 MulticastLock**）；Wi-Fi 常拦广播，故并行「子网 /24 逐 IP 单播 `GET /ping` 探测」（80 线程、300ms 超时），"ok" 即对方。手动连接端口默认 8099。
- 传输：HTTP 8099~8103，`GET /config` / `GET /ping`；数据复用 `BackupManager.exportBackup/importBackup`，导入后 `AppUtils.relaunchApp(true)`。明文 HTTP 依赖 manifest `usesCleartextTraffic=true` + network_security_config base-config 放行。

## 深色模式 / 主题
- `util/Utils.isDarkTheme()` + `AppCompatDelegate.setDefaultNightMode`；深色覆盖在 `values-night/colors.xml`（无 drawable-night），不加同名 color 会沿用浅色（`windowBackground` 不覆盖会闪白）。
- 2026-10-01 起深色背景纯黑 `#000000`（OLED）；webview 深色在 `res/raw/style.css`。底部导航白色勿动。

## 已落地功能 / 修复（速查）
- 2.5.4 SAF 备份还原（弃 MANAGE_EXTERNAL_STORAGE）；2.5.5 本地导入复制进私有目录（`util/LocalImport.kt`，`ApiConfig.readContentConfig` 加 file:// 分支）；2.5.6 删「本地视频」；2.5.8/2.5.9 逐模块删存储权限 + `tools:node="remove"` 兜底；2.5.10/2.6.1 标题栏自适应。
- 详情页"外部播放"走系统 `ACTION_VIEW` 选择器（MX/VLC/Reex/Kodi）；**Android11+ 检测已装 App 必须在 manifest `<queries>` 声明 `video/*` 与包名**，否则全失败。
- 内置源：`strings.xml` 的 `api_name` = 饭太硬/王二小/龙门影视。
- 首页顶栏：`tvName`（数据源名，可点切换/长按刷新）+ 搜索框；2.22.8 已**移除右上角历史记录图标入口**（历史改从「我的 → 观看历史」进）。

## 踩坑教训
- ⚠️ **"缓存/守护进程"诊断极易误判**：曾三次误判 Gradle 缓存，实际是 `ApiConfig.java` 多一个 `}`。**铁律：用户杀 java+删 app/build+重建仍逐字节复现 → 就是真 bug**。改完 Java 大段嵌套/括号务必做括号平衡自检。
- ⚠️ **多模块 manifest 权限**：:app / :player / :crash / :quickjs / :TabLayout / :ViewPager1Delegate 需逐模块查，且 :app 用 `tools:node="remove"` 兜底，只在 :app 删等于没删。
- Edit 工具会把 CRLF 改 LF 造成 diff 噪音：批量替换用 Python bytes 级替换并保留行尾。

## 用户偏好
- 中文沟通；改完**自动 git add+commit 本地快照（不 push）**，汇报"已提交未推送"。
- 助手不构建（构建/出 APK 由用户做），只写代码+静态校验，并给出构建命令。
- 介意"存储权限/能读所有文件"类权限，能删就删。系统导航栏白条问题搁置，勿主动改；外部播放无需二级列表。
