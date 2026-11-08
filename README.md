# Apollo 3DS Launcher

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="96" height="96" alt="Apollo 3DS Logo" />
</p>

<p align="center">
  <strong>专为安卓双屏掌机打造的高拟真 3DS 风格系统桌面与复古游戏启动器</strong><br>
  <em>Retro 3DS Dual-Screen Home Menu & Retro Gaming Launcher for Android</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0%20%7C%20MIT-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-brightgreen.svg" alt="Android Version">
  <img src="https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-orange.svg" alt="Target SDK">
  <img src="https://img.shields.io/badge/Architecture-Kotlin%20%7C%20Dual--Screen%20Presentation-purple.svg" alt="Architecture">
  <img src="https://img.shields.io/badge/Build-Gradle%208.7%2B-blueviolet.svg" alt="Gradle">
</p>

---

## 📦 安装包下载 / Download APK

| 平台 / Platform | 下载渠道 / Download Source | 说明 / Note |
| :--- | :--- | :--- |
| **Gitee (国内高速)** | [📥 Gitee 直接下载 (Launcher3DS.apk)](https://gitee.com/apollo-handheld/apollo-3ds/raw/main/releases/Launcher3DS.apk) | 国内直连免代理，支持手机/掌机浏览器高速下载 |
| **GitHub Releases** | [📥 GitHub Release 下载](https://github.com/Davvvvvvvvid/apollo-3ds/releases/latest) | 官方最新发行版安装包 |
| **GitHub 直链** | [📥 GitHub Raw 直接下载](https://github.com/Davvvvvvvvid/apollo-3ds/raw/main/releases/Launcher3DS.apk) | 仓库直接克隆或下载 |

---

[中文详细说明](#中文详细说明) | [English Documentation](#english-documentation)

---

## 中文详细说明

**Apollo 3DS Launcher** 是一款深度致敬 Nintendo 3DS 经典双屏交互系统的现代化 Android 前端桌面启动器。

专为 **AYANEO Flip DS、Nova 等物理双屏安卓掌机** 以及折叠屏/大屏设备深度定制。项目采用纯原生 Kotlin + Canvas/OpenGL 轻量渲染管线构建，在提供极高拟真度的 3D 悬浮卡带、实机演示画中画与复古像素滤镜的同时，保证毫秒级冷启动与极低整机功耗。

---

### 🌟 详细功能全景

#### 1. 拟真 3DS 物理双屏渲染架构
* **主屏（上屏 15:9 影院比例）**：
  * **动态顶部横幅（Animated Banner）**：选中游戏时自动展开专属动态像素横幅与微动效。
  * **实机游戏演示视频画中画**：自动无缝循环播放游戏宣传片/实机演示视频，内置防高频切换闪烁缓冲与智能静音控制。
  * **沉浸式顶部状态栏**：时间、电量、Wi-Fi 状态、当前平台标签与 3DS 原版 StreetPass 提示呼吸灯。
* **副屏（下屏 4:3 触控交互主阵地）**：
  * **经典网格桌面**：支持图标网格流畅拖拽、触控点选，提供流畅的行/列分页滑动动效。
  * **顶部折叠状态栏与小组件**：集成屏幕亮度、系统设置、滤镜选择、快速抽卡随机挑游小工具。
  * **独立副屏战术面板**：支持独立开启/关闭副屏伴侣抽屉，纯粹显示游戏或辅助信息，不破坏原生双屏模式。
* **多形态硬件自适应**：
  * **物理双屏设备**：通过 Android `Presentation` API 自动接管第二物理显示通道，上下屏各司其职，无卡顿无黑屏；
  * **单屏/折叠屏设备**：自动平滑降级为上下比例自适应分屏视口。

---

#### 2. 纯手写超轻量 3D 卡带透视引擎
* **零重型引擎依赖**：摒弃 Unity/Unreal 等高发热、高功耗的重量级 3D 引擎，采用手写三维矩阵旋转投影算法与透视贴图映射。
* **动态手柄与触控响应**：
  * 推动右摇杆或在屏幕滑动时，游戏卡带支持随手指角度进行 360° 实时立体俯仰转动；
  * 模拟 3DS 卡盒侧边卡榫、芯片金手指光泽反光与拟真环境漫反射阴影。

---

#### 3. 全平台全能模拟器精准直调通道 (Launch Pipeline)
* **DraStic 独家深度优化（独创双通道）**：
  * 彻底解决 Android 11+ 作用域存储（Scoped Storage）导致的白屏、以及**“无法打开快捷方式”**系统错误弹窗；
  * 针对 DraStic 深度定制 Direct-ROM 校验与 `content://` SAF 授权协议，确保 NDS 游戏 100% 毫秒级直达拉起。
* **3DS 平台全兼容**：
  * 完美支持 Citra (MMJ / Enhanced / Canary / Nightly)、Lime3DS、Mandarine 3DS 等主流分支，自动解析游戏代码与存档映射。
* **复古全机种矩阵一键直达**：
  * 覆盖 GBA、GBC、PSP、PS1、SNES、NES、Sega MD、MAME 等平台；
  * 无缝对接 RetroArch（各核心自动路由）、PPSSPP、AetherSX2 / NetherSX2、Dolphin、DuckStation 等。

---

#### 4. Pegasus 与天马 G 媒体库即插即用挂载
* **原生解析 `metadata.pegasus.txt`**：
  * 自动读取并结构化提取游戏中文名、原版英文名、发行时间、开发商、发行商、游戏类型及长篇中文剧情简介；
  * 副屏一键呼出沉浸式“游戏说明书”图文介绍卡片。
* **外置存储智能扫盘**：
  * 插入 TF 卡或连接外置存储卡，启动器自动检索主流前端（天马 G、Pegasus、EmulationStation）的媒体资产目录；
  * 封面盒照（Boxart）、标题画面（Title）、快照截屏与演示视频无需导入手机内部存储，即插即玩。
* **长按手动媒体重定向**：
  * 长按任意游戏卡位，可自由指定本地图片或视频作为替代封面。

---

#### 5. 实时硬件级复古着色器 (Post-Processing Shaders)
* **CRT 显像管扫描线（Scanlines）**：还原复古大头管电视的横向微光栅与边缘微畸变；
* **Dot Matrix 像素点阵**：还原经典 GameBoy / NDS 下屏的液晶点阵网格质感；
* **色彩滤镜矩阵**：
  * GB 原版“初号机”原谅绿调；
  * 怀旧暖调琥珀金（Amber）；
  * 高对比度暗室黑白模式；
* 支持实时热切换，设定自动记忆。

---

#### 6. 全物理手柄人体工学映射

| 按键 | 功能说明 |
| :--- | :--- |
| **左摇杆 / 十字键 (D-Pad)** | 在下屏游戏网格中顺滑平移选择游戏 |
| **右摇杆 (R-Stick)** | 上屏 3D 卡带实时立体旋转查看 |
| **L1 / R1** | 快速切换游戏分类与平台（3DS、NDS、GBA、PSP、PS1 等） |
| **L2 / R2** | 快速循环切换副屏顶部功能栏与工具小组件 |
| **A 键** | 确认 / 快速启动游戏 |
| **B 键** | 返回上级目录 / 关闭当前面板 / 取消操作 |
| **X 键** | 随机快速挑游（Random Select） |
| **Y 键** | 展开当前选定游戏的 Pegasus 中文详细说明书与元数据卡片 |
| **Select 键** | 切换或关闭复古画质滤镜（CRT / 点阵 / 怀旧绿） |

---

#### 7. 高保真 3DS 交互音效系统
* 内置采集自原版系统的经典触感音效：
  * 光标移动音（`se_cursor`）
  * 确认启动音（`se_launch`）
  * 文件夹进出与视图缩放音（`se_zoom_in` / `se_zoom_out`）
  * 取消与返回音（`se_cancel`）
* 支持音效声量随手柄操作无延迟播放，支持系统静音与独立音量平衡调节。

---

#### 8. 原版 3DS 触控备忘录（Game Notes）
* 继承 3DS 原版特色的手写笔触控便签；
* 支持在游玩过程中随时切出，用触控笔/手指手绘记录迷宫地图、剧情解谜密码或金手指代码，笔记随游戏自动关联保存。

---

### 🛠️ 编译与开发构建

完整编译与配置说明请参阅 **[BUILD.md](BUILD.md)**。

```bash
# 1. 克隆开源仓库
git clone https://gitee.com/apollo-handheld/apollo-3ds.git
cd apollo-3ds

# 2. 编译发布版 APK (自动使用内置自签密钥，开箱即用)
./gradlew assembleRelease
```
产物输出路径：`app/build/outputs/apk/release/app-release.apk`。

---

## English Documentation

**Apollo 3DS Launcher** is an authentic, retro-styled dual-screen front-end launcher developed specifically for Android dual-screen handhelds (such as **AYANEO Flip DS** and **Nova DS**) as well as foldables and large-screen Android devices.

### 🌟 Key Architectural Highlights

* **Authentic 3DS Dual-Display Architecture**:
  * **Top Screen (15:9)**: Dynamic banner animations, background preview video playback, and real-time interactive 3D box-art.
  * **Bottom Screen (4:3)**: Smooth touch-enabled game grid, collapsible tools bar, and detailed Pegasus game encyclopedia view.
  * **Multi-Hardware Adaptability**: Native `Presentation` dual-display integration with automatic single-screen split fallback.
* **Ultra-Lightweight 3D Box-Art Renderer**:
  * Hand-crafted 3D transformation matrices running at 60 FPS with minimal CPU/GPU overhead.
  * Interactive 3D tilt and specular lighting responsive to thumbstick inputs.
* **Universal Emulator Launch Pipeline**:
  * Custom direct-ROM dispatching for **DraStic** bypassing Android 11+ Scoped Storage restrictions and shortcut bugs.
  * Out-of-the-box routing for **Citra / Lime3DS**, **RetroArch**, **PPSSPP**, **AetherSX2**, and more.
* **Plug & Play Pegasus Metadata**:
  * Reads `metadata.pegasus.txt` directly from external TF cards and USB drives.
* **Hardware Retro Shaders**:
  * Real-time CRT scanlines, LCD dot-matrix grids, and vintage GameBoy palettes.
* **Gamepad-First Navigation**:
  * Complete physical controller layout with shoulder triggers for rapid platform and tab cycling.

---

### 📜 开源许可协议 (License)

本项目采用 **Apache-2.0 / MIT 双重许可协议**（Dual-licensed under Apache 2.0 and MIT License）。  
你可以自由修改、分发、二次开发或集成至个人与商业项目中，详情请参阅 [LICENSE](LICENSE) 文件。

### ⚠️ 法律免责声明 (Disclaimer)

1. **Apollo 3DS Launcher** 是一个完全独立的开源前端桌面软件，与任天堂（Nintendo Co., Ltd.）不存在任何商业合作、从属或背书关系。
2. 本开源仓库内**不包含**任何商业游戏 ROM、BIOS 固件或未经授权的版权素材。
3. 文档与演示中所出现的全部商标、游戏名称与知识产权均归其合法所有者所有。
