# B站评论爬取工具 (Android Native App)

本项目是开源项目 [bilibili_comment_scraper_webui](https://github.com/ManiaAmaeOvo/bilibili_comment_scraper_webui) 的**完整 Android 原生客户端重构版本**（100% Kotlin + Modern Android XML + OkHttp + Coroutines + ZXing 原生二维码）。

本项目在移动端实现了原版 WebUI + Python Backend 的全部特性，同时针对移动端 WebView 扫码兼容性痛点，完成了**官方登录接口无 WebView 原生扫码授权**的深度重构与升级！

---

## ✨ 核心特性与功能对照

| 功能模块 | 原版 WebUI (FastAPI + Playwright) | Android 原生 App 升级实现 (100% Kotlin) |
| :--- | :--- | :--- |
| **【全新升级】原生官方扫码登录** | Playwright 打开桌面浏览器扫码，需占用系统浏览器并扫描 | **基于官方登录 API 的免 WebView 原生扫码 (`QrCodeLoginActivity`)**：直接调用官方 `qrcode/generate` 接口通过 Google **ZXing** 实时生成登录二维码 Bitmap，并轮询 `/qrcode/poll`，成功状态 (`code=0`) 下直接解析 `Set-Cookie` 响应头提取 `SESSDATA`、`bili_jct`；彻底解决移动端 WebView 扫码没反应、Cookie 拦截问题；并保留网页验证码登录作为备用通道 |
| **突破 API 5000 条上限** | 采用双重排序策略（先按【时间】，再按【热度】），并在本地内存去重 | **支持双重排序爬取（时间排序 mode=2 + 热度排序 mode=3）**，利用 `HashSet<Long>` 自动去重评论 ID，轻松抓取突破 5000 条甚至万级数据 |
| **楼中楼二级回复抓取** | 根据 `rcount > 0` 对主评论发起并发查询请求 | 自动筛选主评论中的楼中楼回复数量，递归分页抽取全部二级回复，关联子评论的父级 ID，保证会话层级明确 |
| **防风控与真人行为模拟** | 集成随机停顿与分块请求 | 所有分页查询和楼中楼网络请求均内置 `Random.nextLong()` 随机化延时，有效减缓高频率并发带来的风控封禁风险 |
| **任务中断与状态收集** | 基于 `asyncio.Event` 触发 SSE 实时日志与停止指令 | **基于 `ViewModel` 状态收集与协程响应**，支持实时滚动显示深色终端控制台日志；可在任务途中随时点“中止任务”安全收尾保存已抓取的评论 |
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
            │   ├── MainActivity.kt           # 主控制面板与配置交互
            │   ├── QrCodeLoginActivity.kt    # 【核心升级】官方接口无 WebView 原生二维码扫码登录
            │   ├── LoginWebViewActivity.kt   # 备用网页验证码/账密登录通道
            │   ├── api/
            │   │   ├── BiliLoginApiService.kt # 官方二维码申请 /qrcode/generate 与轮询 /qrcode/poll
            │   │   ├── BiliVideoApiService.kt # B站视频基础信息、主评论与楼中楼客户端
            │   │   └── CommentModels.kt      # 评论数据模型与 CSV 列转换
            │   ├── utils/
            │   │   ├── CsvWriter.kt          # UTF-8 BOM CSV 数据表写入工具
            │   │   └── FileUtil.kt           # SAF 目录授权与 Intent 分享管理
            │   └── viewmodel/
            │       └── ScraperViewModel.kt   # 双重排序策略、防风控延时与协程任务管理
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
