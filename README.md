# B站评论爬取工具 (Android Native App)

本项目是开源项目 [bilibili_comment_scraper_webui](https://github.com/ManiaAmaeOvo/bilibili_comment_scraper_webui) 的**完整 Android 原生客户端重构版本**（100% Kotlin + Modern Android XML + OkHttp + Coroutines）。

本项目在移动端实现了原版 WebUI + Python Backend 的全部特性，包括**突破API 5000条上限的双重排序策略**、**二级楼中楼回复深度爬取**、**内置浏览器快速登录提取 Cookie**、**防风控随机延时**以及**标准 UTF-8 (BOM) CSV 电子表格自动导出**等核心功能。

---

## ✨ 核心特性与功能对照

| 功能模块 | 原版 WebUI (FastAPI + Playwright) | Android 原生 App 升级实现 (100% Kotlin) |
| :--- | :--- | :--- |
| **一键登录抓取 Cookie** | Playwright 打开桌面端浏览器由用户扫码登录获取 Cookie | **内置 WebView 登录模块 (`LoginWebViewActivity`)**，可在应用内直接加载 B 站登录界面，成功登录后一键保存提取 Cookie 注入爬取配置 |
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
    ├── build.gradle                          # 核心依赖 (OkHttp3, Gson, Coroutines, ViewBinding, DocumentFile)
    ├── proguard-rules.pro                    # B站 API 数据类混淆规则
    └── src/
        └── main/
            ├── AndroidManifest.xml           # 权限声明及 Activity/FileProvider 注册
            ├── java/com/biliscraper/android/
            │   ├── MainActivity.kt           # 主控制面板与配置交互
            │   ├── LoginWebViewActivity.kt   # 应用内 B 站登录与 Cookie 抓取
            │   ├── api/
            │   │   ├── BiliVideoApiService.kt # B站视频基础信息、主评论与楼中楼客户端
            │   │   └── CommentModels.kt      # 评论数据模型与 CSV 列转换
            │   ├── utils/
            │   │   ├── CsvWriter.kt          # UTF-8 BOM CSV 数据表写入工具
            │   │   └── FileUtil.kt           # SAF 目录授权与 Intent 分享管理
            │   └── viewmodel/
            │       └── ScraperViewModel.kt   # 双重排序策略、防风控延时与协程任务管理
            └── res/
                ├── drawable/                 # 自定义圆角卡片及深色控制台背景
                ├── layout/                   # 原生主布局及 WebView 登录布局
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
