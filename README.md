# TrackMaster - 轨迹大师

一个功能完整的 Android 定位轨迹记录应用，使用高德地图 SDK 实现。

## 应用信息

- **应用包名**: com.trackmaster.app
- **Debug 版签名 SHA1**: `E4:81:A4:7D:4C:6C:83:4A:6A:2D:22:44:FA:9B:01:4C:98:B4:73:65`
- **Release 版签名 SHA1**: `01:A7:56:A7:3A:23:E1:AC:88:40:9E:8E:16:F9:EE:9F:18:E4:2C:BB`
- **高德地图 Key 申请地址**: https://console.amap.com/dev/key/app

## 功能特性

### 核心功能
- 高德地图定位与轨迹记录
- 运动状态自动检测（静止、步行、骑行、驾车）
- 智能定位频率管理
- 地理围栏功能（手绘多边形、射线法检测）
- 后台保活与前台服务
- 三种省电模式（高性能、均衡、极致省电）

### UI 设计
- Material3 设计风格
- 亮色/暗色主题切换
- 渐变背景效果
- 流畅的动画过渡

### 数据管理
- Room 本地数据库存储
- 轨迹会话管理
- 历史轨迹回放
- 数据统计功能

## 高德 Key 替换步骤

1. 访问 [高德开放平台](https://console.amap.com/dev/key/app) 申请 Key
2. 在 `app/build.gradle.kts` 中找到以下配置：
   ```kotlin
   debug {
       buildConfigField("String", "AMAP_API_KEY", "\"YOUR_AMAP_DEBUG_KEY\"")
   }
   release {
       buildConfigField("String", "AMAP_API_KEY", "\"YOUR_AMAP_RELEASE_KEY\"")
   }
   ```
3. 将 `YOUR_AMAP_DEBUG_KEY` 和 `YOUR_AMAP_RELEASE_KEY` 替换为您申请的 Key
4. 同步 Gradle 并重新编译

## 技术栈

- **语言**: Kotlin
- **架构**: MVVM + Repository
- **异步**: Coroutines + Flow
- **数据库**: Room
- **UI**: Jetpack Compose + Material3
- **依赖注入**: Hilt
- **地图**: 高德地图 SDK (AMap)

## 系统要求

- 最低版本：Android 8.0 (API 26)
- 目标版本：Android 14 (API 34)

## 编译运行

1. 打开 Android Studio Hedgehog 及以上版本
2. 导入项目
3. 替换高德 Key（见上方步骤）
4. 同步 Gradle
5. 连接设备或启动模拟器
6. 点击运行按钮

## 权限说明

应用需要以下权限：
- 前台定位权限 (ACCESS_FINE_LOCATION)
- 后台定位权限 (ACCESS_BACKGROUND_LOCATION) - Android 10+
- 前台服务权限 (FOREGROUND_SERVICE)
- 通知权限 (POST_NOTIFICATIONS) - Android 13+
- 网络权限 (INTERNET)
- 唤醒锁权限 (WAKE_LOCK)

## 签名文件

项目根目录包含：
- `trackmaster_debug.jks` - Debug 签名文件
- `trackmaster.jks` - Release 签名文件

签名密码：trackmaster2024

## 注意事项

1. 首次使用请授予所有必要权限
2. 建议关闭电池优化以确保后台记录稳定
3. 国产 ROM 需要开启自启动和后台运行权限

## 版本信息

- 版本号：1.0.0
- 版本名称：1.0.0
