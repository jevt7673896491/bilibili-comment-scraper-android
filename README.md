# B站评论爬取工具 (Android Native App)

本项目是开源项目 [bilibili_comment_scraper_webui](https://github.com/ManiaAmaeOvo/bilibili_comment_scraper_webui) 的**完整 Android 原生客户端重构与深度架构升级版本**（100% Kotlin + Modern Android XML + OkHttp + Coroutines + ZXing 原生二维码 + **多引擎联合集成爬虫架构** + **五级意外中断断点容灾保存机制**）。

本项目解决了移动端 WebView 扫码没反应、长耗时任务意外中断丢失数据等全部痛点，打造了全网集成的多引擎互补抓取体系与**断点容灾持久化保存技术**，最大限度突破 B 站 API 单套路接口 5000 条的分页限制与风控封锁！

---

## ✨ 核心特性与架构对比

| 功能模块 | 原版 WebUI (FastAPI + Playwright) | Android 原生 App 深度升级实现 (100% Kotlin) |
| :--- | :--- | :--- |
| **【容灾升级】五级意外中断自动保存** | 一旦出现网络报错、长耗时异常退出，已采到的所有评论全部丢失 | **全场景容灾持久化体系 (`ScraperViewModel`)**：<br>1. **增量定期快照**：每采集 200 条有效评论自动生成检查点快照；<br>2. **网络断线/报错保存**：当 HTTP 连接超时或接口异常退出时，立即触发灾备钩子，完整保存截至异常前的一刻所有已采集评论；<br>3. **协程取消与后台切出保存**：当系统将进程转入后台或作用域取消时，调用 `CancellationException` 与 `onStop()` 钩子把内存数据无损刷入硬盘；<br>4. **用户主动中止保存**：点击“中止任务”安全平滑收尾；<br>5. **终态安全检查**：确保最终始终能拿到完整的 CSV 文件！ |
| **【全新架构】多引擎联合集成爬取** | 仅使用单套路 `bilibili-api` 封装执行排序请求 | **全网采集方案集成的多引擎协作架构 (`ScraperViewModel`)**：<br>1. **引擎一 (标准 Cursor 游标引擎)**：调用 `/x/v2/reply/main` 接口分别对【时间最新 (`mode=2`)】和【热度推荐 (`mode=3`)】执行双维度抓取；<br>2. **引擎二 (WBI 签名加密引擎)**：自研官方完整 WBI 签名算法 (`WbiSigner.kt`)，调用带签名校验的现代主站接口 `/x/v2/reply/wbi/main`，突破无鉴权状态下的 `-403`/`-352` 封锁与限流，将引擎一被拦截或漏抓的评论全部抓回！<br>3. **深度楼中楼引擎**：对前两套引擎捕获的全部主评论中 `rcount > 0` 的项进行递归展开，联合采集并本地 `HashSet<Long>` 一键去重！ |
| **【独家功能】原生官方扫码登录** | Playwright 打开桌面浏览器扫码，依赖笨重的桌面内核 | **基于官方登录 API 的免 WebView 原生扫码 (`QrCodeLoginActivity`)**：直接调取官方 `/qrcode/generate` 接口利用 **ZXing** 在手机屏幕原生绘制 `600×600` 二维码图标，并后台轮询 `/qrcode/poll` 状态；一旦成功 (`code=0`) 直接从 `Set-Cookie` 响应头解析提取 `SESSDATA`、`bili_jct` 一键保存；并保留网页验证码登录作为备用通道 |
| **防风控与真人行为模拟** | 集成随机停顿与分块请求 | 所有多引擎分页查询和楼中楼网络请求均内置 `Random.nextLong()` 随机化延时，有效打破机器人固定频率特征 |
| **CSV 导出与兼容性** | Python `csv.DictWriter` 输出并支持前端网页直接下载 | **自研 CSV 引擎 (`CsvWriter.kt`)**，自动写入 **UTF-8 BOM (`\uFEFF`)** 标识头，使导出的 CSV 在 Excel / WPS 中完美无乱码展开；同时支持 SAF 目录选择及调用系统 API 分享文件 |

---

## 💻 项目结构说明

```text
BiliCommentScraperAndroid/
├── build.gradle                              # 根构建脚本
├── settings.gradle                           # 依赖仓库设置
├── gradle.properties                         # Android 编译优化参数
├── gradlew & gradlew.bat                     # Gradle 包装执行脚本
├── README.md                                 # 项目文档说明
├── .github/workflows/build.yml               # GitHub Actions 自动化 APK 编译发布脚本
└── app/
    ├── build.gradle                          # 核心依赖 (OkHttp3, Gson, Coroutines, ViewBinding, ZXing)
    ├── proguard-rules.pro                    # B站 API 数据类混淆规则
    └── src/
        └── main/
            ├── AndroidManifest.xml           # 权限声明及 Activity/FileProvider 注册
            ├── java/com/biliscraper/android/
            │   ├── MainActivity.kt           # 主控制面板与后台切出保存钩子 (onStop)
            │   ├── QrCodeLoginActivity.kt    # 官方接口无 WebView 原生二维码扫码登录
            │   ├── LoginWebViewActivity.kt   # 备用网页验证码/账密登录通道
            │   ├── api/
            │   │   ├── WbiSigner.kt          # 自研全套 Bilibili WBI 签名算法 (w_rid + wts 计算)
            │   │   ├── BiliLoginApiService.kt # 官方二维码申请 /qrcode/generate 与轮询 /qrcode/poll
            │   │   ├── BiliVideoApiService.kt # 包含标准 Cursor、WBI 签名、楼中楼的三重 API 客户端
            │   │   └── CommentModels.kt      # 评论数据模型与 CSV 列转换
            │   ├── utils/
            │   │   ├── CsvWriter.kt          # UTF-8 BOM CSV 数据表写入工具
            │   │   └── FileUtil.kt           # SAF 目录授权与 Intent 分享管理
            │   └── viewmodel/
            │       └── ScraperViewModel.kt   # 【核心架构】多引擎采集、五级断点容灾与异常自动持久化调度器
            └── res/
                ├── drawable/                 # 自定义圆角卡片及深色控制台背景
                ├── layout/                   # 原生主布局、原生扫码布局及网页登录布局
                ├── mipmap-*/ic_launcher.png  # B站专属粉底应用图标
                └── values/                   # 颜色方案 (粉/底)、文本及主题字串
```

---

## 🚀 编译与调试指南

1. **直接导入 Android Studio**：
   打开 **Android Studio** -> 选取 `/home/user/BiliCommentScraperAndroid` 即可进行开发与真机调试。
2. **命令行自动化打包**：
   执行以下命令将生成 Debug 调试安装包：
   ```bash
   ./gradlew assembleDebug
   ```
   安装包生成在 `app/build/outputs/apk/debug/app-debug.apk`。
3. **GitHub Actions 云端自动构建**：
   向仓库提交或创建 PR 时，工作流会迅速启动自动编译，通过 Github Actions Artifacts 页面可直接下载 `BiliCommentScraper-Debug-APK`。
