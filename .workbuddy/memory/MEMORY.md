# TVBoxOS-Mobile 项目长期笔记

## 构建 / 签名 / 安装（Windows + Git Bash）
- 环境：JDK 17 (`C:\Java\jdk-17`)、Android SDK (`C:\Android\Sdk`)、`local.properties` 里 `sdk.dir=C:\\Android\\Sdk`。仓库：`D:/GitHub/TVBoxOS-Mobile`（另有 `D:/WorkBuddy/TVBoxOS-Mobile`，别改错）。
- 构建：export JAVA_HOME/ANDROID_HOME 后 `./gradlew assembleDebug|assembleRelease --console=plain > build_out.log 2>&1`。首次(含NDK)约25分钟，debug增量1–3分钟。**必须沙箱外执行(dangerouslyDisableSandbox:true)**，否则Gradle缓存读不到报 AAPT 错误（看着像代码错误实为权限拦截）。
- 产物：`app/build/outputs/apk/{debug,release}/TVBox-Mobile-v<versionName>.apk`（同名靠目录分）。release约41MB。
- 签名：根目录 `TVBoxOSC.jks`（alias/key/store口令均 `TVBoxOSC`），debug/release共用；`minifyEnabled false`。**.gitignore含 *.jks 不入库**。缺失会回退调试证书→无法覆盖安装。校验：`apksigner verify --print-certs <apk>`。
- 安装：`adb install -r <apk>`；签名不符先 `adb uninstall com.github.tvbox.osc`。
- **交付APK前必校验**（TaskStop杀过构建后）：删产物重跑 assembleRelease → `testzip()` 无损坏 + `apksigner verify` 退出码0 + `sha256sum`，防半成品包。
- **构建防卡配置**(gradle.properties)：`-Xmx1536m -Xms384m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC` + `org.gradle.workers.max=2`；`build_apk.bat` 开头 `call gradlew.bat --stop` 清残留daemon。**勿用 `kotlin.compiler.execution.strategy=in_process`**（Kotlin只认`IN_PROCESS`枚举，旧daemon缓存小写值报 `Unknown value 'in_process'`）。

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

## 深色模式 / 主题
- 切换：`util/Utils.isDarkTheme()` + `AppCompatDelegate.setDefaultNightMode`。
- 深色覆盖在 `values-night/colors.xml`（无 drawable-night）；改深色配色要在 values-night 新增同名color覆盖，否则沿用浅色（如 `windowBackground` 浅色#fff不覆盖会闪白）。
- 2026-10-01起深色背景统一纯黑 `#000000`（OLED）；webview深色在 `res/raw/style.css`(weui `--weui-BG-*`)。底部导航白色勿动。

## 关键修复清单（已落地）
- 详情页"外部播放"按钮(`tvExternalPlay`)：直接调系统播放器（`ACTION_VIEW` 选择器，含MX/VLC）。**Android11+包可见性**：manifest `<queries>` 须声明 `video/*` intent + MX(.pro/.ad/.beta)/Reex/Kodi/VLC 包名，否则检测全失败。**任何检测已装App/隐式intent调外部App，都须先声明`<queries>`**。
- 首页顶栏：搜索栏改搜索按钮(放收藏左)、数据源名 `tvName` `maxWidth` 110→200dp。
- 移除设置页与"我的"页切换壁纸入口（`WallpaperManager` 系统保留，已设壁纸仍生效）。
- 内置源：`strings.xml` `api_name` 数组 = 饭太硬(在线)/王二小(在线)/龙门影视(在线)。

## 用户偏好
- 中文沟通，发崩溃截图/日志逐条报bug。改完**自动 git add+commit 本地快照（不push）**。
- 白条(系统导航栏颜色)问题搁置，勿主动改。
- 外部播放直接走系统选择器即可，无需额外二级列表。
