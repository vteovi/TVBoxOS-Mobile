# TVBoxOS-Mobile 项目长期笔记

> 精简整理于 2026-11-07。细节按日期存 `.workbuddy/memory/YYYY-MM-DD.md`，这里只留跨会话仍会用到的规则与坑。

## 分支与发布（用户 2026-11-04 明确要求）
- **`dev` = 日常开发/测试，所有代码改动都在这里**；**`main` = 稳定版，只接受从 dev 合并**（外加 CI 发版后 bot 的元数据提交）。不要直接在 main 写代码。
- 发布流程（用户说「发布」才执行）：推 dev → `git switch main && git pull --rebase origin main` → `git merge --no-ff dev` → 推 main → `git tag -a vX.Y.Z && git push origin vX.Y.Z` → 等 CI → 校验 Release/update.json/README → pull bot 提交 → 切回 dev `merge origin/main`（通常 fast-forward）→ 推 dev。
- 推 main/dev **不触发 CI**（workflow 只监听 `push: tags:["v*"]` + `workflow_dispatch`）。`publish-github-release` 自带构建、apksigner 校验、发 Release、回写 `update.json`/README，密钥来自 secret `TVBOX_KEYSTORE_BASE64`。
- **发版前必须先写好 `更新记录.md` 的 `### <版本> — 日期` 段落**：CI 的 `scripts/sync_release_metadata.sh` 从中抽 changelog，漏写会让 update.json 没有 changelog（2.28.8 返工过）。
- 版本号在 dev 上就改好：feat→MINOR、fix→PATCH，versionCode 每次 +1，绝不复用。
- **推 GitHub 网络三连**：① 先 `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy`（否则 502）；② `git -c http.version=HTTP/1.1 push`，失败间隔 8s 重试（github.com:443 常抖，`api.github.com` 正常）；③ 查 CI 用匿名 `curl api.github.com/repos/vteovi/TVBoxOS-Mobile/actions/runs`，无需登录。

## 环境 / 构建 / 签名
- 仓库 `D:/WorkBuddy/TVBoxOS-Mobile`（另有 `D:/GitHub/...`，别改错）；`applicationId=com.github.tvbox.osc.mobile`；`local.properties` 的 `sdk.dir=C:\\Android\\Sdk`。
- 构建：`./gradlew assembleDebug|assembleRelease --console=plain > build_out.log 2>&1`（用户自己构建），产物 `app/build/outputs/apk/<type>/TVBox-Mobile-v<versionName>.apk`。gradle.properties 防卡：`-Xmx1536m -Xms384m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC`、`org.gradle.workers.max=2`；**勿设小写 `kotlin.compiler.execution.strategy=in_process`**（只认 `IN_PROCESS`）。
- 签名：根 `TVBoxOSC.jks`（alias/key/store 均 `TVBoxOSC`），debug/release 共用，`minifyEnabled false`；`.gitignore` 忽略 `*.jks` 与根 `*.apk`，**别 `git add -A` 误提交 APK**。交付前校验 zip 完好 + `apksigner verify --print-certs` + sha256。
- ⚠️ **2026-11-07 起本机编译链不可见**：`C:\Java\jdk-17`、`C:\Android\Sdk`、`~/.gradle` 全都不存在，全盘找不到 `java.exe`（沙箱内外都一样）→ 目前**跑不了 gradle 编译检查**，只能做静态校验（括号平衡、符号引用、XML 结构）。下次开工先确认 JDK/SDK 新路径。
- 静态校验注意：剥离 Java 注释做括号自检时**必须先替换字符串再删 `//` 行注释**，否则 `"file://"` 里的 `//` 会被当成注释造成假报警。
- CI 历史坑（已修）：`ubuntu-latest` 切 Ubuntu 26 会让 `setup-android@v3` 必挂 → 用自建 `.github/actions/setup-android-sdk` + `runs-on: ubuntu-24.04`；git 里脚本要 `git update-index --chmod=+x` 且工作流用 `bash x.sh`；加速站用 `gh-proxy.com`/`ghfast.top`（`gh.xxooo.cf` 已死）。

## 架构速查
- `MainActivity`(Kt)：ViewPager2 两页 Home/My；`HomeFragment` 内旧 ViewPager+DslTabLayout = 首页分栏（`FragmentStatePagerAdapter`，offscreen 默认 1）。底部导航 4 项，直播/订阅是独立 Activity。
- 底部导航 `include_bottom_navigation.xml`：高 58dp、图标 24dp、**背景纯白 `#FFFFFFFF`（用户要求勿动）**；item 高度 <~56dp 图标文字必重叠。大屏 `layout-sw600dp/` 用 `NavigationRailView`(88dp)，代码里 `bottom_nav` 统一强转为公共基类 `NavigationBarView`。sw600dp 触发条件 = 较短边 ≥600dp。
- 动态 dex(csp.jar/js.jar)：SDK≥34 用 `InMemoryDexClassLoader`，需 `JarResourceClassLoader` 作 parent 才能读资源；SDK<34 用 `DexClassLoader+setReadOnly()`。dex 加载失败 = 订阅不可用。
- `spThreadPool` 是单线程池，其中的任务别再内嵌提交它自己（会自锁）。`SourceViewModel.getSort(key)` 经 `ApiConfig.getSource(key)` 取 `SourceBean` **必须判空**。
- 异步回调晚于 Activity 销毁 → NPE：所有「子线程→主线程改 View/播放器」先判 `isFinishing()||isDestroyed()`，onDestroy 取消请求。Android14+ 动态广播需 `RECEIVER_EXPORTED/NOT_EXPORTED`，PendingIntent 需 `FLAG_IMMUTABLE`。
- 播放器：VOD 用 `player_vod_control_view.xml`（`box_vod_control_view.xml` 是死文件）；`dialog_playing_control.xml` 被 `PlayingControlDialog` 与 `PlayingControlRightDialog`(sw600dp) 共用，改布局两个类都要接监听。
- **首页分栏复用 Fragment 实例的坑**（2.28.8）：`FragmentStatePagerAdapter` 滑远会销毁 Fragment、回来复用同一实例，而 `init()` 不再执行 → `observe(this)` 丢失。凡在 `init()` 里注册的观察者都要能在 `onCreate` 重挂；LiveData 重放旧值要用「是否真的在等回调」过滤。已在 `GridFragment` 用 `mWaitingList`+20s 超时兜底解决。

## Hawk / Room / 备份同步
- ⚠️ **Hawk 2.0.1 的 SP 文件名是 `Hawk2`**（`STORAGE_TAG_DO_NOT_CHANGE`）。用 `getSharedPreferences("Hawk")` 会静默失效。Hawk 存 `type#value` 包装串，判断内容必须 `Hawk.get` 取真实值；`Hawk.put` 内部 `editor.commit()` 同步落盘（要 `relaunchApp` 时别用 apply）。
- **备份/还原设置一律走 `Hawk.get`/`Hawk.put` 真实值**，别搬运序列化串。复杂对象导出登记 `val.getClass().getName()`，导入端 `Gson.fromJson(json, Class.forName(名))` 再 `Hawk.put`，避免退化成 LinkedTreeMap；导入端兼容 `isJsonArray/isJsonPrimitive` 两种形态。
- 本地导入的订阅/配置 URL 是 `file:///data/data/<pkg>/files/...`（仅本机私有目录）→ 同步/备份必须把文件内容一起打包（`localFiles` Base64），接收端重建并重写 URL。`Subscription` 的 `url`/`name` 包级私有，跨包只能用 `getUrl()/setUrl()`。
- **局域网同步**：`DeviceSyncDialog` + `LanSyncManager`（UDP 广播 45127 + 子网 /24 单播 `GET /ping`，MulticastLock 必需，端口 8099~8103 / `GET /config`），数据复用 `BackupManager`，导入后 `relaunchApp(true)`。明文 HTTP 依赖 `usesCleartextTraffic=true`。
- **2.31.0 起收藏/历史合并，2.32.0 起订阅源也合并**：`BackupManager.ImportMode{REPLACE,MERGE}`，设备同步默认 MERGE，文件备份还原仍 REPLACE；只有播放器/主题等普通设置始终覆盖。收藏/历史按 `(sourceKey, vodId)` 去重、`updateTime` 较大者胜，之后按 `HISTORY_NUM` 上限 `reserver()` 裁剪；两个表**没有唯一约束**（主键 autoGenerate），合并必须先建 Map 索引再 `@Update`。订阅列表并集且全表去重：在线按归一化 URL、本地文件按**内容 MD5**（两台设备私有路径不同，只比地址会重复）；MERGE 时 `API_URL`/`HOME_API`/`LIVE_URL` 本机有值就保留，避免「列表选中 A、实际加载 B」；REPLACE 路径也走 `dedupSubscriptions()`。并集语义下「删除」不会跨设备同步，需要完全一致时取消勾选走覆盖。
- `AppResetHelper`（2.29.0 软件内重置）：清 `Hawk.deleteAll()` + `clearAllTables()`（**只能在子线程**）+ filesDir/cacheDir，外置备份不动，清完必须重启。`App.java` 会在 `API_URL` 缺失时自愈到内置源。

## 直播页
- 不锁方向。`mEnteredPortrait` + `setRotateToFullScreenEnabled()` 作闸门：竖屏进入才允许「转横屏自动全屏」；**横屏/大屏进入直接停在左右分栏**。改方向策略必须 grep 全部 `setRequestedOrientation`（dkplayer 退出全屏有多条路径）。
- 横竖屏结构是**运行时改 LayoutParams**（`configChanges` 使 `layout-land/` 不生效）：`applyLiveResponsiveLayout()` 切 `live_content_row`/`live_video_column` 的 VERTICAL/HORIZONTAL；`applyLiveVideoBoxSize()` 算 `live_video_box` 高度。全屏/PiP 跳过，回 NORMAL 重算。
- 全屏画中画入口在 `dkplayer_layout_menu_view.xml`（`ic_pip` 会被播放器盖住），进入前先 `stopFullScreen()`+方向复位。全屏频道抽屉必须显式 `.popupWidth(max(dp2px(260), 屏宽*0.42f))`，否则 wrap_content 让 weight 失效。RelativeLayout 改定位要清**全部**同类锚点。

## 主题 / 颜色
- ⚠️ **取主题色千万别用 `ColorUtils.getColor()`**（androidutilcode）：内部是 `ContextCompat.getColor(Utils.getApp(), id)`，走 **Application** 的 Resources；AppCompat 的浅/深覆盖只写在 **Activity** 上 → 「系统深色 + 软件浅色」会白底白字（2.29.1）。统一用 `Utils.getThemeColor(context, resId)`；`Utils.isDarkTheme()` 优先看 `AppCompatDelegate.getDefaultNightMode()`，只在「跟随系统」时读系统 uiMode。
- 深色覆盖在 `values-night/colors.xml`（无 drawable-night），背景纯黑 `#000000`；`text_foreground` 浅 #050505 / 深 #FFFFFF，`text_sub_foreground` 浅 #787878 / 深 #AFAFAF。底部导航白色勿动。
- 弹窗改状态栏图标颜色：根因是 `CustomDialogStyle` 的 `windowFullscreen=true` + `FLAG_DIM_BEHIND`，运行时改 flag 无效。解法 = 样式删 `windowFullscreen` + `BaseDialog.dimAmountBehind=0f` + show 后把宿主 Activity 同值双通道写一遍；判断深浅读宿主窗口底色，改窗口参数要在 `super.show()` 前。

## 其它高频坑
- **「Gradle 缓存/守护进程」诊断极易误判**：曾三次误判，实际是 Java 多一个 `}`。铁律：杀 java + 删 app/build + 重建仍逐字节复现 → 就是真 bug。改完大段 Java 必做括号平衡自检。
- **多模块 manifest 权限**：`:app/:player/:crash/:quickjs/:TabLayout/:ViewPager1Delegate` 逐模块查，`:app` 用 `tools:node="remove"` 兜底；只在 :app 删等于没删。
- 大屏多列一屏 ≈20 条 > 一页 20 条 → `canScrollVertically()==false` 让 LoadMore 永不触发，`GridFragment.autoFillViewport()` 自动补页解决。
- Picasso 在 RecyclerView 里别用 `.fit()`；用 `resize+onlyScaleDown+centerCrop+noFade`，`PosterImageView` 抑制 setImage 触发的整屏 requestLayout，`PosterCardLayout` 用 `setClipToOutline` 做 GPU 圆角。解码走独立低优先级线程池（OkGoHelper）。
- `Edit` 工具会把 CRLF 改 LF 造成 diff 噪音；`更新记录.md` 是 CRLF，批量替换用 Python 保留行尾。改了 .kt 必须单独跑 `compileArm64v8DebugKotlin`（Java 编译任务不覆盖 Kotlin）。
- 详情页「外部播放」用系统 `ACTION_VIEW`；Android11+ 检测已装 App 必须在 manifest `<queries>` 声明 `video/*` 与包名。

## 版本 / 功能速查
- 2.27.x 订阅源缓存自愈 + spider jar 按源分份缓存；2.28.x 大屏滑动流畅度（多轮迭代）；2.28.8 切回分栏后不加载；2.28.9 切回在线订阅后首页数据源被换掉。
- 2.29.0 软件内「重置软件」；2.29.1 主题取色修复；2.30.0 收藏页两个 Tab 显示条目数量（数量取**列表实际条数**，不是 SQL count —— 历史有上限裁剪和失效源过滤）；2.31.0 设备同步合并收藏/历史。
- 2.32.0 设备同步订阅源合并+去重；**2.32.2 分栏切回保位置 / 2.32.3 首页刷新改差量更新**：缓存先铺 → 网络第一页回来时用 `DiffUtil` 差量更新（不再 `setNewData` 整表替换，否则所有可见卡片重新 bind + 海报闪 + 新片插前面导致整片换位），套用后按**影片 id** 把正在看的那条 `scrollToPositionWithOffset` 回原位；列表 `scrollState != IDLE` 时先暂存、停下来再套；点击取数据用 `getChildAdapterPosition(view)`（不用回调里的 position）；**2.32.4 大屏仍「闪+往下挪」的三个真凶**：① `applyFirstPage` 里 `atTop` 不锚定（平板一页≈一屏，`canScrollVertically(-1)` 常 false → 新片插前面把整屏推下去，改成**始终按 id 锚定**）；② `target.clear()+addAll(第一页)` 会截断 autoFill/loadMore 已加载的后续页（改成 id 去重合并保留）；③ `Hawk.put(cacheKey(), GsonUtils.toJson(list))` 主线程序列化+SP commit 掉帧（改 `HeavyTaskUtil` 异步，主线程只拷快照）。`HomeFragment.sortResult` 只在分类**集合**变化时重建 ViewPager（顺序变不重建），已有分栏时不再 `showLoading()`（LoadSir 会把 contentLayout 摘出树）；分栏切走时 `onFragmentPause` 记 `findFirstVisibleItemPosition`+`getDecoratedTop`，切回 `onFragmentResume` 同步+post 各还原一次，`initData()` 主动回顶部时清记忆。**2.32.5 起彻底换思路：首页后台刷新不再改动正在显示的列表**——`shownFromCache` 时第一页只写缓存、不碰 adapter（上面前三版的 DiffUtil/锚定整套逻辑已删除：平板一屏 20+ 条、首行贴顶边，补偿误差会被放大成「闪+下窜」，且用户正按着卡片会点到别的片子）；要新内容靠切分类/换源/改筛选/重启。另：`BaseLazyFragment` 记录 LoadSir 当前状态，重复 `showSuccess()/showLoading()` 直接忽略（LoadSir 每次 showCallback 都会把目标视图摘下来再挂回，而 GridFragment 是把 LoadSir 直接注册在 RecyclerView 上的 → 等于 detach/attach 一次 = 闪一下）；分类集合变化重建 ViewPager 后按分类 id 停回原栏；`UserFragment.refreshHomeRec()` 不再由 sortResult 触发，豆瓣热门回调也只在列表为空时才 `setNewData`。
- 搜索页有卡片/列表两套（`SEARCH_CARD_MODE`），列表缩略图跟随 `LANDSCAPE_CARD`；`FastSearchAdapter` 用 `getDefItemViewType`+`onCreateDefViewHolder`，**不要**覆写 `getItemViewType`；`RoundTransformation` 缓存 key 必须带尺寸。

## 用户偏好
- 中文沟通；改完自动 `git add + commit` 到 **dev**（本地快照，不自动 push），汇报 commit id；发布时才按流程推 main + 打 tag。
- **助手不负责出 APK**（用户本机构建），只写代码 + 静态校验并给出构建命令；当前环境连编译检查也跑不了，需如实说明。
- 介意「存储权限/能读所有文件」类权限，能删就删；系统导航栏白条搁置勿主动改；外部播放无需二级列表。
