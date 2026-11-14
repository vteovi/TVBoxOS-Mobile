# TVBoxOS-Mobile 项目长期笔记

> 2026-11-14 精简整理。细节按日期存 `.workbuddy/memory/YYYY-MM-DD.md`，这里只留跨会话仍会用到的规则与坑。

## 分支与发布
- **`dev` = 日常开发，所有改动都在这里；`main` = 稳定版，只接受从 dev 合并**（外加 CI 发版后 bot 的元数据提交）。别直接在 main 写代码。
- 发布（用户说「发布」才做）：推 dev → `git switch main && git pull --rebase origin main` → `merge --no-ff dev` → 推 main → `git tag -a vX.Y.Z && git push origin vX.Y.Z` → 等 CI → 校验 Release/update.json/README → pull bot 提交 → 回 dev `merge origin/main` → 推 dev。
- 推 main/dev **不触发 CI**（workflow 只听 `push: tags:["v*"]` + `workflow_dispatch`）；`publish-github-release` 负责构建、apksigner 校验、发 Release、回写 update.json/README，密钥 secret `TVBOX_KEYSTORE_BASE64`。
- **发版前必须先写 `更新记录.md` 的 `### <版本> — 日期` 段落**（`scripts/sync_release_metadata.sh` 靠它抽 changelog，条目以 `- ` 开头）。
- 版本号在 dev 就改：feat→MINOR、fix→PATCH，versionCode 每次 +1 绝不复用。
- 推 GitHub：① `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy`（否则 502）；② `git -c http.version=HTTP/1.1 push`，失败隔 8s 重试；③ 查 CI 用匿名 `curl api.github.com/repos/vteovi/TVBoxOS-Mobile/actions/runs`。
- ⚠️ 全局 gitconfig 有 `url."https://gh-proxy.com/https://github.com/".insteadOf`（为加速 fetch）：**push 会报 `No anonymous write access`**。推前 `--unset-all`、推后 `--add` 恢复；别让带 unset 的命令被 timeout 杀掉。fetch 走代理正常。
- `github.com:443` 被 TLS 阻断时（api 正常）用 `scripts/push_via_api.py`（Git Data API 重放 blob→tree→commit→移 ref）；代价是时间归一化成 UTC、**commit sha 必变**，推完 `git fetch` + `reset --hard origin/<br>` + `fetch --tags --force` 对齐。
- 读远端文件别用 raw.githubusercontent.com（代理 502），用 `GET /repos/.../contents/<file>?ref=main` + `Accept: application/vnd.github.raw`。

## 环境 / 构建 / 签名
- 仓库 `D:/WorkBuddy/TVBoxOS-Mobile`（另有 `D:/GitHub/...`，别改错）；`applicationId=com.github.tvbox.osc.mobile`；`local.properties` 的 `sdk.dir=C:\Android\Sdk`。
- JDK `C:\Java\jdk-17`、SDK `C:\Android\Sdk`（platform-34、build-tools 34.0.0）。⚠️ **助手 shell 不继承用户环境变量**，命令自带：`export JAVA_HOME=/c/Java/jdk-17 && export PATH="/c/Java/jdk-17/bin:$PATH" && export ANDROID_HOME=/c/Android/Sdk`。
- 构建 `./gradlew assembleArm64v8Debug|assembleArm64v8Release --console=plain`，产物 `app/build/outputs/apk/arm64v8/<type>/TVBox-Mobile-v<ver>-arm64v8.apk`（约 41MB）；增量几十秒，首次十几分钟。
- 签名：根 `TVBoxOSC.jks`（alias/key/store 均 `TVBoxOSC`），debug/release 共用，`minifyEnabled false`；`.gitignore` 忽略 `*.jks` 与根 `*.apk`，**别 `git add -A` 误提交 APK**。校验 `build-tools/34.0.0/apksigner.bat verify --print-certs`（应 `CN=TVBoxOSC`）。
- gradle.properties 防卡：`-Xmx1536m -Xms384m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC`、`org.gradle.workers.max=2`；小写 `kotlin.compiler.execution.strategy=in_process` 无效（只认 `IN_PROCESS`）。
- 括号平衡自检脚本要**先替换字符串再删 `//` 注释**，否则 `"file://"` 里的 `//` 被当注释造成假报警。改完大段 Java 必做自检。
- 改了 .kt 必须单独跑 `compileArm64v8DebugKotlin`（Java 编译任务不覆盖 Kotlin）。
- CI 坑（已修）：Ubuntu 26 让 `setup-android@v3` 必挂 → 自建 `.github/actions/setup-android-sdk` + `runs-on: ubuntu-24.04`；脚本要 `git update-index --chmod=+x` 且工作流用 `bash x.sh`；加速站用 gh-proxy.com / ghfast.top（gh.xxooo.cf 已死）。

## 架构速查
- `MainActivity`(Kt) 是 ViewPager2 两页 Home/My；`HomeFragment` 内旧 ViewPager+DslTabLayout = 首页分栏（`FragmentStatePagerAdapter`，offscreen 1）。直播/订阅是独立 Activity。
- 底部导航 `include_bottom_navigation.xml`：高 58dp、图标 24dp、**背景纯白 #FFFFFF 勿动**；item 高度 <~56dp 图标文字必重叠。sw600dp 用 `NavigationRailView`(88dp)，代码里 `bottom_nav` 统一强转公共基类 `NavigationBarView`。sw600dp 触发 = 较短边 ≥600dp。
- 动态 dex(csp.jar/js.jar)：SDK≥34 用 `InMemoryDexClassLoader`（需 `JarResourceClassLoader` 作 parent 才能读资源），SDK<34 用 `DexClassLoader+setReadOnly()`。dex 失败 = 订阅不可用。
- `spThreadPool` 是单线程池，任务里别再提交它自己（自锁）。`SourceViewModel.getSort(key)` 经 `ApiConfig.getSource(key)` 取 `SourceBean` **必须判空**。
- 异步回调晚于 Activity 销毁 → NPE：子线程回主线程改 View/播放器前判 `isFinishing()||isDestroyed()`，onDestroy 取消请求。Android14+ 动态广播需 `RECEIVER_EXPORTED/NOT_EXPORTED`，PendingIntent 需 `FLAG_IMMUTABLE`。
- 播放器：VOD 用 `player_vod_control_view.xml`（`box_vod_control_view.xml` 是死文件）；`dialog_playing_control.xml` 被 `PlayingControlDialog` 与 `PlayingControlRightDialog`(sw600dp) 共用，改布局两个类都要接监听。
- **详情/播放页 `DetailActivity`**：手机点标题弹 `VideoDetailDialog`（显示当前地址 + 复制），`Utils.isTablet()` 为真时禁用该弹窗、右侧 `ll_info_column` 直接展示信息 → 大屏功能要单独给入口（2.33.0 加 `ext_copy_link`，与手机共用 `copyCurrentVodUrl()`）。sw600dp 布局的 id 必须与默认布局同名同类型（ViewBinding 以默认布局定类型），只在大屏存在的控件用 `findViewById` + 判空。
- `FragmentStatePagerAdapter` 滑远会销毁、回来复用同一实例且 `init()` 不再执行 → `observe(this)` 丢失。在 `init()` 注册的观察者要能在 `onCreate` 重挂；LiveData 重放旧值要用「是否真在等回调」过滤。

## Hawk / Room / 备份同步
- ⚠️ **Hawk 2.0.1 的 SP 文件名是 `Hawk2`**（`STORAGE_TAG_DO_NOT_CHANGE`），用 `getSharedPreferences("Hawk")` 静默失效。值是 `type#value` 包装串，判断内容必须 `Hawk.get`；`Hawk.put` 内部 `commit()` 同步落盘（要 relaunch 时别用 apply）。
- 备份/还原一律走 `Hawk.get`/`Hawk.put` 真实值，别搬序列化串。复杂对象导出登记 `Class.getName()`，导入端 `Gson.fromJson(json, Class.forName(名))` 再 put（否则退化成 LinkedTreeMap），并兼容 `isJsonArray/isJsonPrimitive`。
- 本地导入的订阅/配置 URL 是 `file:///data/data/<pkg>/files/...`（仅本机私有）→ 同步/备份要把文件内容一起打包（`localFiles` Base64），接收端重建并重写 URL。`Subscription` 的 `url`/`name` 包级私有，跨包只能用 `getUrl()/setUrl()`。
- 局域网同步：`DeviceSyncDialog` + `LanSyncManager`（UDP 广播 45127 + /24 单播 `GET /ping`，需 MulticastLock，端口 8099~8103 / `GET /config`），复用 `BackupManager`，导入后 `relaunchApp(true)`；明文 HTTP 依赖 `usesCleartextTraffic=true`。
- `BackupManager.ImportMode{REPLACE,MERGE}`：设备同步默认 MERGE、文件还原 REPLACE，普通设置始终覆盖。收藏/历史按 `(sourceKey, vodId)` 去重、`updateTime` 大者胜，再按 `HISTORY_NUM` 用 `reserver()` 裁剪；两表**无唯一约束**（主键 autoGenerate），合并要先建 Map 索引再 `@Update`。订阅并集去重：在线按归一化 URL、本地按**内容 MD5**（设备私有路径不同）；MERGE 时 `API_URL`/`HOME_API`/`LIVE_URL` 本机有值就保留。并集语义下「删除」不跨设备同步。
- `AppResetHelper`：`Hawk.deleteAll()` + `clearAllTables()`（**只能子线程**）+ filesDir/cacheDir，外置备份不动，清完必须重启。`App.java` 在 `API_URL` 缺失时自愈到内置源。

## 直播页
- 不锁方向。`mEnteredPortrait` + `setRotateToFullScreenEnabled()` 作闸门：竖屏进入才允许「转横屏自动全屏」；横屏/大屏进入停在左右分栏。改方向策略必须 grep 全部 `setRequestedOrientation`（dkplayer 退全屏有多条路径）。
- 横竖屏靠**运行时改 LayoutParams**（`configChanges` 让 `layout-land/` 失效）：`applyLiveResponsiveLayout()` 切 `live_content_row`/`live_video_column` 方向，`applyLiveVideoBoxSize()` 算 `live_video_box` 高度；全屏/PiP 跳过，回 NORMAL 重算。
- 全屏画中画入口在 `dkplayer_layout_menu_view.xml`（`ic_pip` 会被播放器盖住），进前先 `stopFullScreen()`+方向复位。全屏频道抽屉必须 `.popupWidth(max(dp2px(260), 屏宽*0.42f))`，否则 wrap_content 让 weight 失效。RelativeLayout 改定位要清**全部**同类锚点。

## 主题 / 颜色
- ⚠️ 取主题色别用 `ColorUtils.getColor()`（走 Application 的 Resources，AppCompat 深浅覆盖只写在 Activity 上 → 「系统深色+软件浅色」白底白字，2.29.1）。统一 `Utils.getThemeColor(context, resId)`；`Utils.isDarkTheme()` 先看 `AppCompatDelegate.getDefaultNightMode()`，仅「跟随系统」时读 uiMode。
- 深色覆盖在 `values-night/colors.xml`（无 drawable-night），背景纯黑 #000000；`text_foreground` 浅 #050505/深 #FFFFFF，`text_sub_foreground` 浅 #787878/深 #AFAFAF。底部导航白色勿动。
- 弹窗状态栏图标色：根因 `CustomDialogStyle` 的 `windowFullscreen=true` + `FLAG_DIM_BEHIND`，运行时改 flag 无效。解法 = 样式删 `windowFullscreen` + `BaseDialog.dimAmountBehind=0f` + show 后把宿主 Activity 同值双通道写一遍；改窗口参数要在 `super.show()` 前。

## LoadSir / 首页 / 数量统计
- **LoadSir 1.3.8 别猜行为**：`setupSuccessLayout()` 把内容视图设 `INVISIBLE`、**必须真走一次 `showSuccess()` 才显示**；`showCallbackView()` 首行按 `preCallback` 去重。外层再包状态缓存会拦掉首次调用 → `UserFragment` 整栏空白（v2.32.5 的坑）。jar 在 `~/.gradle/caches/transforms-4/*/jetified-loadsir-1.3.8-api.jar`，`javap -p -c` 可查。
- 首页后台刷新整表替换会「闪+跳位」→ 现方案是**后台刷新只写缓存、不碰 adapter**，需切分类/换源/改筛选/重启才更新（用户认可）。
- `HomeFragment.sortResult` 只在分类**集合**变化时重建 ViewPager；分栏切回记 `findFirstVisibleItemPosition`+`getDecoratedTop` 还原；点击取数据用 `getChildAdapterPosition(view)`。
- `DslTabLayout.currentItemIndex` 来自 `dslSelectIndex`，`removeAllViews()`+`install()` 重建期间是 **-1** → 取当前 tab 一律兜底（改读 `ViewPager.currentItem`），`MainActivity.onBackPressed()` 用 `getOrNull()`（v2.32.9）。
- 收藏页数量靠 `RecordListFragment.setOnCountChangedListener` 回传，ViewPager2 懒创建 → 没滑到的分栏没数字，用 `CollectActivity.preloadCounts()` 预查；口径必须与列表一致（历史用 `getAllVodRecord(HISTORY_LIMIT).size`，不是 DAO count）。
- `DetailActivity.onDestroy()` → `registerActionReceiver(false)` 曾直接 `playFragment.getPlayer().isPlaying()`，而 `PlayFragment.onDestroyView()` 会 release 并置 null → 销毁 NPE（v2.32.8 全判空 + 广播注销 try/catch）。

## 其它高频坑
- 「Gradle 缓存/守护进程」诊断极易误判（曾三次，实际是 Java 多一个 `}`）：杀 java + 删 app/build 重建仍逐字节复现才是真 bug。
- 多模块 manifest 权限：`:app/:player/:crash/:quickjs/:TabLayout/:ViewPager1Delegate` 逐模块查，`:app` 用 `tools:node="remove"` 兜底；只在 :app 删等于没删。
- 大屏多列一屏 ≈20 条 > 一页 20 条 → `canScrollVertically()==false` 让 LoadMore 永不触发，`GridFragment.autoFillViewport()` 补页。
- Picasso 在 RecyclerView 别用 `.fit()`，用 `resize+onlyScaleDown+centerCrop+noFade`；`PosterImageView` 抑制 setImage 触发的整屏 requestLayout，`PosterCardLayout` 用 `setClipToOutline` 做圆角；解码走独立低优先级池（OkGoHelper）。
- `Edit` 工具会把 CRLF 改 LF 造成 diff 噪音；`更新记录.md` 是 CRLF，改它要用 Python 带 `newline=''`（或按字节）并核实行尾。
- 详情页「外部播放」用系统 `ACTION_VIEW`；Android11+ 检测已装 App 必须在 manifest `<queries>` 声明 `video/*` 与包名。
- 搜索页有卡片/列表两套（`SEARCH_CARD_MODE`），列表缩略图跟随 `LANDSCAPE_CARD`；`FastSearchAdapter` 用 `getDefItemViewType`+`onCreateDefViewHolder`，**别**覆写 `getItemViewType`；`RoundTransformation` 缓存 key 必须带尺寸。

## 版本速查
- 2.29.0 内置重置；2.30.0 收藏页数量；2.31.0 设备同步合并收藏/历史；**2.32.0 = 订阅源也合并的内容基线**；2.32.7 收藏页数量补齐；2.32.8 详情页销毁 NPE；2.32.9 返回键 -1 越界；**2.33.0/2.33.1 播放页补「复制视频链接」（大屏图标 + 手机图标，同一 id/同一逻辑）；2.33.2 修退出全屏后选集区空白**。
- **versionCode 已到 374，必须一直递增，绝不能再退回 362 区间。**

## 用户偏好
- 中文沟通；改完自动 `git add + commit` 到 **dev**（本地快照，不自动 push），汇报 commit id；「发布」时才推 main + 打 tag。
- 介意「存储权限/能读所有文件」类权限，能删就删；系统导航栏白条搁置勿主动改；外部播放无需二级列表。
- 助手可自行构建校验（产物 `app/build/outputs/apk/arm64v8/<type>/`），用户仍习惯自己装包验证。
