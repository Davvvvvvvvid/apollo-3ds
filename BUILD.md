# Apollo 3DS 编译构建指南 (Build Guide)

本文档面向希望在本地编译、调试或二次开发 Apollo 3DS Launcher 的开发者。

---

## 1. 环境依赖 (Prerequisites)

- **JDK**: Java Development Kit 17 (推荐 Eclipse Temurin 17 或 OpenJDK 17)
- **Android SDK**:
  - `compileSdk`: 34 (Android 14)
  - `minSdk`: 24 (Android 7.0 Nougat)
  - `buildToolsVersion`: 34.0.0 或更高版本
- **Gradle**: 8.7+ (项目自带 `gradlew` 包装器)
- **Android Studio**: Android Studio Jellyfish (2023.3.1) / Koala / Ladybug 或更高版本

---

## 2. 命令行快速构建 (Command Line Build)

### 2.1 编译 Debug 版本 (测试与调试)
```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```
产物输出路径：`app/build/outputs/apk/debug/launcher3ds-debug.apk`

### 2.2 编译 Release 版本 (生产发布)
```bash
# Windows
.\gradlew.bat assembleRelease

# macOS / Linux
./gradlew assembleRelease
```
产物输出路径：`app/build/outputs/apk/release/launcher3ds-release.apk`

---

## 3. 签名配置 (Signing Configuration)

开源版本默认采用自动降级机制：
1. **开发者自签（推荐）**：在项目根目录下创建 `keystore.properties` 文件：
   ```properties
   storeFile=path/to/your/my-release-key.jks
   storePassword=your_store_password
   keyAlias=your_key_alias
   keyPassword=your_key_password
   ```
2. **免配置直接构建**：若未配置 `keystore.properties`，Gradle 会自动复用 Debug 签名密钥对 Release 包进行签名，确保任何开发者拉取源码后均可 `1-Click` 编译成功。

---

## 4. 安装到掌机设备 (Install to Device)

确保掌机已开启“开发者选项”和“USB 调试”：

```bash
adb install -r app/build/outputs/apk/release/launcher3ds-release.apk
```

首次安装后，按设备 HOME 键，在系统弹出的桌面选择器中选择 **Apollo 3DS**，并点击“始终”即可将其设为掌机默认系统桌面。
