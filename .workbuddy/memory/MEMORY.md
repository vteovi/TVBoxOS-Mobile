# TVBoxOS-Mobile 项目长期笔记

## 构建 / 签名 / 安装（Windows + Git Bash）
- 环境：JDK 17 (`C:\Java\jdk-17`)、Android SDK (`C:\Android\Sdk`)、`local.properties` 里 `sdk.dir=C:\\Android\\Sdk`。
- 仓库位置：当前工作区是 **`D:/GitHub/TVBoxOS-Mobile`**（另有一份 `D:/WorkBuddy/TVBoxOS-Mobile`，注意别改错）。
- 构建：`cd /d/GitHub/TVBoxOS-Mobile && export JAVA_HOME="C:\\Java\\jdk-17" && export ANDROID_HOME="C:\\Android\\Sdk" && ./gradlew assembleDebug --console=plain > build_out.log 2>&1`
  - 用 `--console=plain` 并把输出重定向到文件，否则失败时真正的错误会被截断。首次（含 NDK）构建约 25 分钟，之后增量约 1–3 分钟。
  - **必须在沙箱外执行**（Bash 工具 `dangerouslyDisableSandbox: true`）：Gradle 缓存 `C:\Users\VTE\.gradle\...` 在工作区之外，沙箱会拦截读取，报 `AAPT: error: failed to open file` / `processDebugResources` 失败，看着像代码错误其实是权限拦截。
- 产物路径（两个 variant **同名**，靠目录区分）：`app/build/outputs/apk/{debug,release}/TVBox-Mobile-v<versionName>.apk`（release **41MB** / debug **44MB**）。
- **release 构建**：`./gradlew assembleRelease`。`minifyEnabled false` → 代码逻辑与 debug 完全一致（仅非 debuggable），`System.out` 诊断日志照样能在 logcat 看到。
  - ⏱️ **切 build type 要付一次全量代价**：`assembleRelease` 实测 **20m56s**（debug 增量才 1–3 分钟）。因为 `player`/`TabLayout`/`ViewPager1Delegate` 三个子模块要重建 release AAR，且 CMake native 输出在 `build/intermediates/cmake/release/`，与 debug 目录不共享。
  - 读 APK 元信息：`"C:/Android/Sdk/build-tools/35.0.0/aapt2.exe" dump badging <apk> | grep -E "^package|native-code"`。
  - **交付 APK 前必须校验**（尤其 TaskStop 杀过构建之后）：删产物重跑 assembleRelease → 校验 `zipfile.testzip()` 无损坏 + `apksigner verify` 退出码 0 + `sha256sum` 告知用户，防止拿到写了一半的包（2026-09-28 用户装到半成品包报"用不了"）。
- **签名**：`app/build.gradle` 期望根目录 `TVBoxOSC.jks`（alias/key/store 口令默认均为 `TVBoxOSC`）；文件缺失时会回退到 Android 调试证书（`CN=Android Debug`），导致与官方 release 包签名不一致、无法覆盖安装。本仓库已生成 `TVBoxOSC.jks`（PKCS12，SHA-256 `4a568ad4...`），debug/release 共用一个身份。
  - 注意：`.gitignore` 含 `*.jks`，该密钥不会入库（本地文件，不要提交）。
- 安装：`adb install -r <apk>`；签名不一致时先 `adb uninstall com.github.tvbox.osc`（会清数据）。Git Bash 下 adb 路径含 `$`/反斜杠需注意，截图拉取要 `export MSYS_NO_PATHCONV=1`。
- 校验签名：`apksigner verify --print-certs <apk>`（build-tools 35.0.0）。
- **构建内存/防卡（2026-09-30 起）**：项目根 `gradle.properties` 已限制 `org.gradle.jvmargs=-Xmx1536m -Xms384m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC`（7.9G 内存机器）+ `org.gradle.workers.max=2`；`build_apk.bat` 的 `:run_build` 开头已加 `call gradlew.bat --stop` 清残留 daemon。
  - **不要再用 `kotlin.compiler.execution.strategy=in_process`**：Kotlin 插件只认大写枚举 `IN_PROCESS`，且旧 daemon 缓存小写值会报 `Unknown value 'in_process'`（改了文件仍复现）。已移除，改用 `kotlin.daemon.jvmargs=-Xmx1024m` 限制独立 Kotlin 守护进程内存（与 Gradle 主 JVM 双上限）。

## 架构要点（改代码会用到）
- `MainActivity`(Kotlin)：外层 `androidx.viewpager2.widget.ViewPager2`(`mBinding.vp`)，仅 2 页 = HomeFragment(0)/MyFragment(1)；已设 `isUserInputEnabled=false` 关闭整页手势滑动。
- `HomeFragment`(Kotlin)：内层旧版 `ViewPager`(`mBinding.mViewPager`) + `DslTabLayout`，由 `ViewPager1Delegate.install(...)` 绑定，是首页分栏。
- 底部导航 4 项：首页/我的走 vp 切页；直播/订阅是独立 Activity。
- 状态栏/导航栏用 ImmersionBar 3.2.2；导航栏深色图标方法是 `navigationBarDarkIcon(boolean)`（不是 `navigationBarDarkFont`）。
- 崩溃处理用 `customactivityoncrash`(Caoc)，在 `App.initCrashConfig()` 里。
- 动态广播注册在 Android 14+ 必须声明 `RECEIVER_EXPORTED`/`NOT_EXPORTED`（已统一 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`）；`PendingIntent` 需 `FLAG_IMMUTABLE`。
- **动态 dex 加载（csp.jar / js.jar）**：Android 14+ 不允许从「可写目录」用 `DexClassLoader` 加载 dex，会抛
  `SecurityException: Writable dex file '...' is not allowed`（`targetSdk 34` 起生效）。
  - **正确做法是按 SDK 分支，不要一刀切**：`SDK >= 34` 才用 `InMemoryDexClassLoader`（把 jar 里 `classes*.dex` 读进直接内存 `ByteBuffer`）；**`SDK < 34` 必须沿用 `DexClassLoader` + `setReadOnly()`**。分支在 `JarLoader.createClassLoader`（`JsLoader` 复用同一方法）。
  - **踩坑（2026-09-27「首页分栏又消失」的真因）**：曾把 Android 8.0+ 一律换成 `InMemoryDexClassLoader`，结果在 **Android 12** 上首页分类全没了。原因链：`InMemoryDexClassLoader` 是 **final 类**，且从内存加载 dex 时 DexPathList 里没有 jar 路径 ⇒ **`getResourceAsStream` 永远返回 null**；而 csp.jar 里的 `com.github.catvod.spider.DexNative.<clinit>` 正是靠 `Init.classLoader().getResourceAsStream("assets/ftyguard_v8.so")` 把自带 native 库读出来 `System.load()`（`Init.classLoader()` 返回的就是我们创建的类加载器：`Init` 构造器里 `getClass().getClassLoader()`）。取不到 so ⇒ `DexNative` 类初始化 NPE（它的 catch 块还有 `e.getCause().getMessage()` 的二次 NPE）⇒ `Init.init()` 抛 InvocationTargetException ⇒ 所有继承 `BaseSpiderGuard` 的源（`DouDouGuard` 等）变 `SpiderNull` ⇒ **首页只剩"主页"**。
  - Android 14+ 的补齐办法：`crawler/JarResourceClassLoader`（继承 `ClassLoader`，`findClass` 一律抛 CNFE，只重写 `getResource`/`getResourceAsStream` 从 jar 的 zip 条目读）作为 `InMemoryDexClassLoader` 的 **parent** —— ClassLoader 的资源查找是"先自身、再逐级问父加载器"，于是 jar 内资源重新可用。
  - 关键日志标志：正常只有 `自定义爬虫代码加载成功!`；异常时额外出现 `自定义爬虫 Init 初始化失败(...): java.lang.reflect.InvocationTargetException`，cause 链为 `ExceptionInInitializerError → NPE at DexNative.<clinit>`。
  - 排查 jar 内部问题的有效手段：`adb exec-out run-as com.github.tvbox.osc cat files/<x>.jar > /tmp/x.jar` 拉出来，`unzip` 取 `classes.dex`，再用 `C:\Android\Sdk\build-tools\35.0.0\dexdump.exe -d` 反汇编看目标方法（本次就是这样定位到 `DexNative.<clinit>` 的真实逻辑的）。
- 该问题的用户现象：**"订阅失败"toast + 首页只剩"主页"一栏**（spider 加载失败 → 分类为空）。诊断关键日志：`Attempt to load writable dex file` / `Writable dex file ... is not allowed`，以及成功标志 `自定义爬虫代码加载成功!`。
- **"更新订阅失败" toasts 来源唯一**：`HomeFragment.loadJar()` 的 `callback.error()`（第 204 行）。上游只有两处会触发：`ApiConfig.loadJar` 下载失败 / `JarLoader.load()` 返回 false。
- **成败判定原则（易踩坑）**：`JarLoader.loadClassLoader()` 里 **dex 能否加载 = 订阅是否可用**，`Init.init()`、`Proxy` 都只是"可选能力"。
  - 原实现把 `Init.init()` 的成败也算进 `success`，而 jar 里的 `Init` 会运行时下载 GoProxy 等原生库 → 一个坏 so 就会把**整份订阅**判死（所有源变 SpiderNull / 首页只剩"主页"）。
  - 现已改为：`loadClass("com.github.catvod.spider.Init")` 成功即 `success=true` 并注册类加载器，`Init.init()` 异常降级为打印日志，不再误报订阅失败。
  - 注意：app 自身有 `com.github.catvod.Init`（**不是** `...catvod.spider.Init`），不会与探测类名冲突（父类加载器不会先命中）。
- `ApiConfig.loadJar` 缓存策略：`files/csp.jar` 存在且（`useCache` 或 md5 命中）时优先用缓存；**现已在缓存加载失败时 `cache.delete()` 并回退到重新下载**，避免坏缓存把"更新订阅失败"卡成永久状态。
- 新增诊断日志：`csp.jar 加载失败: <path> 大小=<> 来源=<>` 与 `csp.jar 下载失败: <url> -> <msg>`（"大小"很小/内容不是 `PK`/`dex\n` 开头 = 下到的是错误页）。
- **首页分栏"一个都没有"的元凶：`SourceViewModel.spThreadPool` 单线程池自锁死**
  - `spThreadPool = Executors.newSingleThreadExecutor()` 是静态单线程池，被 getSort/getList/getDetail/getPlay/getSearch 共用。
  - `getSort(type=3)` 自己就跑在 spThreadPool 上，内部在 `HOME_REC==1` 且 homeContent 无视频列表时调 `getHomeRecList()`，后者又 `spThreadPool.execute(...)` → 新任务排在当前任务后永远不执行，而当前任务在等它的回调 ⇒ 永不回调。`initViewPager` 不执行 ⇒ 连"主页"栏都没有（adjustSort 的 withMy 分支根本没跑到）；且池被永久占死，后续列表/详情/播放全部不响应。
  - 修复：`getHomeRecList` 改用独立的 `homeRecThreadPool`。**硬规则：任何跑在 spThreadPool 上的任务，内部都不要再往 spThreadPool 提交任务。**
  - `HomeFragment.sortTimeoutTask`：20s 无结果且无分栏时 `postValue(null)` 强制渲染"主页"栏兜底。
- **底部导航栏**：`res/layout/include_bottom_navigation.xml`（被 activity_main/live/subscription 三处 include，改一处全生效）。当前规格：高 **58dp**、图标 **24dp**、文字 `@style/BottomNavTextAppearance`(10sp)、文字色 `drawable/bottom_navigation_text_color.xml`(统一黑)、背景 `@color/bg_bottom_navigation`=**#FFFFFFFF 纯白不透明**（原 #B3FFFFFF 半透明）。图标色仍走 `bottom_navigation_item_selector`（选中 colorPrimary/未选 disable_gray）。
  - **尺寸底线**：主题是 Material2（`Theme.MaterialComponents.Light.NoActionBar` + material 1.12.0），`labelVisibilityMode="labeled"` 时 item 需求高度 = iconSize + label + 默认上下 padding，**低于 ~56dp 图标必与文字重叠**。改小图标/高度时要同步留足高度；不要给 `itemPaddingTop/itemPaddingBottom` 加值（会反向加重重叠）。
- **adb 操作限制（MIUI/HyperOS）**：`adb shell input tap` 会被拒（`SecurityException: Injecting to another application requires INJECT_EVENTS permission`），**无法模拟点击**；验证只能 `am force-stop` + `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` 重启后 `screencap` 截图。
- **播放页控制层**：`res/layout/player_vod_control_view.xml`（`VodController` 与 `LocalVideoController` 共用；`box_vod_control_view.xml` 无引用=死文件）。`bottom_container` 自上而下 = `parse_root` → **进度条单独一行**（`curr_time` + `seekBar` 高 34dp/weight=1 + `total_time`）→ **按钮行**（`play_status`/`play_pre`/`play_next` + `View(weight=1)` 占位 + `choose_series`/`iv_fullscreen`）→ `container_playing_setting`。
- **拉起点播播放页做真机验证**：播放页是 Fragment 无法直接启动，且 MIUI 禁 `input`。可临时把 `LocalPlayActivity` 的 `exported` 改 true + `bottom_container` 设 `visible`，用 `am start ... LocalPlayActivity --es videoList '[{...}]' --ei position 0` 拉起（先 monkey 预热 ~18s，且需 2s 内截图，否则被系统收回退回首页），**验证后务必还原并重新构建**。
- **编辑文件注意行尾**：Edit 工具会把整文件 CRLF 改成 LF，造成 git diff 全是噪音；用 Python `replace(b'\r\n',b'\n').replace(b'\n',b'\r\n')` 转回 CRLF。
  - 截图拉取：`export MSYS_NO_PATHCONV=1` 时目标路径必须写成 `D:/...` 形式（`/d/...` 会失败）。
  - 应用是 debug 包，可用 `adb shell run-as com.github.tvbox.osc cat shared_prefs/Hawk2.xml` 读配置（Hawk 的 prefs 名叫 **Hawk2**）。
  - 无法模拟点击时仍能驱动 Activity 生命周期做验证：`am start -n <pkg>/<全限定Activity名>` 直接拉起页面，再用 `am start -n <pkg>/.ui.activity.MainActivity --activity-clear-top` 让栈上该页面被 finish（等价于"返回"），即可在 adb 下走完 onCreate→onDestroy。注意 `adb shell input tap/keyevent` 在该机型一律被拒。
  - 复现「销毁后回调」这类竞争问题时：若请求本身只需几十毫秒（如本地 `127.0.0.1` 代理），`am start` 的间隔（≥100ms）永远撞不进窗口，外部难以稳定复现 —— 此时以崩溃堆栈 + 代码路径为准，别硬凑。
- **异步回调晚于 Activity 销毁 → 空指针（直播页崩溃根因）**：`LiveActivity.onDestroy()` 会把 `mVideoView` release 并置 null，而 `loadProxyLives()` 的 OkGo 回调（以及 `content://` 分支里子线程的 `runOnUiThread`）可能在销毁之后才回来 → `parseProxyLiveContent → initLiveState → livePlayerManager.init(null) → PlayerHelper.updateCfg` 第 90 行 `setPlayerFactory` NPE。用户现场日志：崩溃时间戳与 `LiveActivity destroyed` **同一秒**。
  - 已做三层防护：① `PlayerHelper.updateCfg(VideoView[, JSONObject])` 开头 `if (videoView == null) return;`；② `LivePlayerManager.getDefaultLiveChannelPlayer/getLiveChannelPlayer` 判空（后者另判 `currentPlayerConfig == null`）；③ `LiveActivity` 的 `parseProxyLiveContent/initLiveState/showNoLiveChannels` 开头 `if (isFinishing() || isDestroyed()) return;`（`initLiveState` 另加 `mVideoView == null`），并给 `loadProxyLives` 的请求加 `.tag(this)`、在 `onDestroy` 里 `OkGo.getInstance().cancelTag(this)`。
  - **规则：Activity 中任何「网络/子线程 → 主线程 → 改 View 或播放器」的回来路径，都要先判 `isFinishing() || isDestroyed()` 并判空 View；网络请求在 onDestroy 里取消。**（minSdk 24，`isDestroyed()` 可用。）

## 深色模式 / 主题（改配色会用到）
- 切换：`util/Utils.java` 的 `isDarkTheme()` + `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES/NO/FOLLOW_SYSTEM)`。
- 深色覆盖资源：`app/src/main/res/values-night/colors.xml`（**无** `drawable-night`）。深色只覆盖少量 color（`bg_gray`/`bg_popup`/`windowBackground`/`text_*`），其余沿用 `values/colors.xml`——**改深色配色要在 values-night 里新增同名 color 覆盖**，否则深色下会沿用浅色值（如 `windowBackground` 浅色是 #fff，深色不覆盖就闪白）。
- 2026-10-01 起深色背景统一纯黑 `#000000`（OLED 不发光）；webview 深色主题在 `res/raw/style.css`（weui `--weui-BG-*` 变量）。
- **底部导航 `bg_bottom_navigation`（白色）用户要求勿动**。

## 用户偏好
- 用中文沟通，会发手机崩溃截图/日志，逐条报 bug。改完需**编出 APK 并尽量 adb 装上**再让其测试。
- 明确表示"白条（系统导航栏颜色）问题搁置，不要再主动改导航栏颜色"。
