# Apollo 3DS Launcher

<p align="center">
  <strong>Retro 3DS Dual-Screen Home Menu & Retro Gaming Launcher for Android</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0%20%7C%20MIT-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-brightgreen.svg" alt="Android Version">
  <img src="https://img.shields.io/badge/Target%20SDK-34-orange.svg" alt="Target SDK">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin">
</p>

---

[中文说明](#中文说明) | [English](#english)

---

## 中文说明

**Apollo 3DS Launcher** 是一款专为双屏安卓掌机（如 AYANEO Flip DS、Nova 等双屏设备）及折叠屏/大屏安卓设备打造的高拟真 3DS 风格游戏启动器。

它完整重现了经典 3DS 系统的双屏交互精髓：
- **上屏（主屏 15:9）**：大魄力 3D 悬浮卡带展示、游戏专属顶部横幅（Banner）、实机游戏演示视频画中画播放、以及复古 CRT/Scanline/Pixel 实时后处理着色器；
- **下屏（副屏 4:3）**：触控网格菜单、Pegasus 游戏元数据沉浸式介绍卡片、战术伴侣面板、游戏分类平滑切换与模拟器一键直达。

---

### ✨ 核心特性

1. **真实 3DS 双屏沉浸式架构**
   - 自动识别双屏硬件布局，上屏呈现高精度视频与 3D 旋转卡盒，下屏提供灵动触控与游戏选择。
   - 支持单屏/折叠屏上下分屏适配。

2. **全能游戏库与多平台模拟器直通**
   - 支持 3DS、NDS、GBA、PSP、PS1、GBC、SNES、NES、Sega 等全系模拟器平台。
   - 深度集成 **DraStic**（自研双屏精准启动通道与 Direct-ROM 校验）、**Citra / Lime3DS**、**RetroArch**、**PPSSPP** 等，彻底消除文件权限与白屏阻碍。

3. **Pegasus 与天马 G 深度媒体支持**
   - 自动解析 `metadata.pegasus.txt`，读取游戏中文简介、发行年、开发商、分类标签等信息。
   - 智能挂载外置 TF 卡/存储媒体，封面与宣传视频即插即扫，无需把庞大资源打包进 APK。

4. **3D 卡带与复古画质滤镜**
   - 采用纯手写超轻量 3D 矩阵变换与透视投影，游戏卡盒支持随摇杆陀螺仪实时倾角转动。
   - 内置 CRT 扫描线、LCD 液晶点阵、GB 怀旧绿调等多种硬件级 Shader 滤镜实时切换。

5. **全物理手柄映射与操作习惯**
   - 摇杆/十字键：丝滑流畅平移切游；
   - L1 / R1：横向快速切换游戏平台与分类；
   - L2 / R2：副屏顶部小工具快速切换；
   - A / B / X / Y：确认启动、收藏切换、元数据长按面板。

---

### 📦 快速编译指南

详见 [BUILD.md](BUILD.md)。

```bash
# 克隆仓库
git clone https://github.com/your-username/apollo-3ds.git
cd apollo-3ds

# 编译独立 Release APK
./gradlew assembleRelease
```

编译生成文件位于：`app/build/outputs/apk/release/launcher3ds-release.apk`。

---

## English

**Apollo 3DS Launcher** is a specialized, retro-styled dual-screen front-end launcher developed for Android dual-screen handheld gaming devices (such as AYANEO Flip DS and Nova dual-screen consoles) and foldable/tablet devices.

### ✨ Highlights

- **Authentic Dual-Screen Architecture**: 15:9 upper widescreen with 3D rotating box-art, banners, and video playback; 4:3 lower touch screen with smooth applet navigation and category filtering.
- **Universal Emulator Launch Pipeline**: High-compatibility dispatching for DraStic (custom double-screen SAF bypass), Citra/Lime3DS, RetroArch, PPSSPP, and more.
- **Pegasus Frontend Compatibility**: Reads `metadata.pegasus.txt` dynamically from external SD cards/USB storage.
- **Real-Time Visual Shaders**: CRT Scanlines, Retro Dot Matrix, GameBoy Amber, LCD grids.
- **Gamepad-First UX**: Native hardware controller mapping for directional navigation, shoulder bumpers, and tactile audio feedback.

---

### 📜 开源许可协议 (License)

本项目采用 **Apache-2.0 / MIT 双重许可协议**（Dual-licensed under Apache 2.0 and MIT License）。
详见 [LICENSE](LICENSE) 文件。

### ⚠️ 免责声明 (Disclaimer)

1. 本项目为开源社区自主开发的第三方前端桌面启动器，与任天堂（Nintendo Co., Ltd.）不存在任何商业合作、从属或背书关系。
2. 本软件代码中不包含任何受版权保护的商业游戏 ROM、BIOS 固件或商业商标素材。
3. 文档或测试示例中所提及的商标与知识产权均归其合法所有者所有。
