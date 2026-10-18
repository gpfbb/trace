# UI 现状与设计系统说明

> **这份文档原本是"重做前的交接计划"。UI 重做已经做完，现在改成记录当前状态。**
> 分支 `feat/ui-redesign`，起点标签 `ui-baseline`（重做前的快照）。
> 架构与关键陷阱仍看 `CLAUDE.md`。

---

## 一、设计方向（用户定的）

- **视觉**：仿 iOS 分组列表 + 大圆角，无渐变、无发光、无玻璃拟态
- **主题**：深浅色跟随系统 + 8 个强调色可选（原来的 13 个暗色主题已废弃）
- **适配**：暂不做横屏/平板，保持锁竖屏
- **图标**：统一 Material 线框/实心语义（见 `AppIcons`），不引入 SVG

---

## 二、新代码在哪

```
ui/design/                  ← 设计系统，唯一真源
├─ Tokens.kt                  颜色/间距/圆角/字号
├─ Theme.kt                   LocalPalette + ThemeController + Material 映射
├─ Typography.kt              AppType → Material Typography
├─ Components.kt              GroupedSection / SettingsRow / Divider / Button …
├─ Surfaces.kt                SurfaceCard / AppDialog
├─ AppIcons.kt                图标语义表
└─ ThemePicker.kt             主题设置弹窗

ui/map/                     ← 地图页拆分后的产物
├─ MapEditorState.kt          绘制/编辑三态状态机
├─ MapToolsPopup.kt           批量工具
└─ FenceDialogs.kt            新建/列表/编辑弹窗

settings/                   ← 设置页拆分后的产物
├─ FenceRelationshipRecalculator.kt   父子关系重算算法
└─ PermissionSection.kt                权限/追踪/定位模式
```

### 使用规则

- 颜色一律 `currentPalette.xxx`。**UI 层已无任何 `Color(0x…)` 硬编码**，
  只有 `Tokens.kt` 里允许出现色值。
- 间距 `Space.xxx`、圆角 `Radius.xxx`、字号 `AppType.xxx`。
- 分组结构：`GroupedSection { InfoRow(); Divider(); InfoRow() }`。
- 主题控制器是**单例**，由 `TrackMasterDesignTheme` 创建并通过
  `LocalThemeController` 下发。**不要在自己的页面里 remember 一个**——
  会创建第二份状态，改它不触发重组（踩过这个坑）。

---

## 三、屏幕现状

| 文件 | 行数 | 状态 |
|---|---:|---|
| `StatisticsScreen.kt` | 1057 | 已接入设计系统，但仍是单文件；可继续拆 |
| `SettingsScreen.kt` | 875 | 已拆出算法与权限分区；主函数仍较大 |
| `ui/screens/MapScreen.kt` | 864 | 已拆出状态机/弹窗/工具栏 |
| `DataManagementScreen.kt` | 745 | 已接入设计系统 |
| `ui/screens/OnboardingScreen.kt` | 562 | 已重做，5 页 |
| `CategoryManagerScreen.kt` | 502 | 已从对话框改成正常页面 |
| `StatsExportImportScreen.kt` | 348 | 已接入设计系统 |
| `ui/screens/HistoryScreen.kt` | 332 | 已重做 |
| `ui/screens/HomeScreen.kt` | 257 | 已重做 |

已删除：`GlassComponents.kt`(796)、`Color.kt`(154)、`Theme.kt`(旧)、
`Type.kt`(旧)、`TrackMasterTheme.kt`、`CategorizedFenceListScreen.kt`(379)、
`VacuumPeriodInfo.kt`(82)。`ui/theme/` 整个包已不存在。

---

## 四、验证方式

```bash
JAVA_HOME=/d/javajdk ./gradlew compileDebugKotlin   # ~45s
JAVA_HOME=/d/javajdk ./gradlew testDebugUnitTest    # 93 个用例
JAVA_HOME=/d/javajdk ./gradlew assembleDebug
```

**模拟器可用**（AEHD 驱动已装）。注意 `Pixel_8_Pro` 指向不存在的
android-36 镜像，用 `TrackMaster_Test`（android-35）：

```bash
export ANDROID_SDK_ROOT="E:\\Androidsdk" ANDROID_HOME="E:\\Androidsdk"
E:/Androidsdk/emulator/emulator.exe -avd TrackMaster_Test -no-snapshot-load -no-audio &
```

截图与取色：

```bash
adb exec-out screencap -p > shot.png
adb shell "uiautomator dump //sdcard/ui.xml"   # 路径必须用 // 前缀
adb exec-out screencap > raw.bin               # 12 字节头(w,h,fmt) + RGBA
# 取 (x,y) 像素：offset = 12 + (y*W + x)*4
```

**点击坐标一律从 uiautomator 的 bounds 取，不要从截图换算**——换算常差
几十像素，会点到按钮之间的缝隙，误判成"点了没反应"。

---

## 五、测试覆盖的"不能退回"点

- `DesignSystemTest`：8 个强调色 × 深浅两种模式的 WCAG 对比度。
  改调色板必须先跑它。
- `FenceAreaTest`：多边形面积必须带 cos(纬度) 校正（曾经的实现
  对轴对齐矩形恒返回 0）。
- `FenceTreeFlattenerTest`：父子树成环不能栈溢出、孤儿节点不能消失。
- `MapEditorStateTest`：绘制/编辑互斥、空闲态点击无副作用、撤销边界。
- `FenceRelationshipGeometryTest`：包含关系 vs 相交关系的区分。

---

## 六、已知未做的事

| 项 | 说明 |
|---|---|
| 旋转/进程死亡丢状态 | 仍只有部分屏幕用 `rememberSaveable` |
| `StatisticsScreen` 图表 | 仍是手搓进度条，没引入图表库 |
| 主题名不随语言切换 | 已随 13 主题一起删除，现在只有"跟随系统/浅色/深色"三档 |
| `MainActivity` 锁竖屏 | 按用户决定保持 |

---

## 七、用户偏好

- 沟通说人话，讲"会有什么后果"而不是"用了什么模式"
- 重视可验证的结果：能编译、测试要过、说清怎么确认真的好了
- 小步提交，每批可回退
- 涉及范围变更、删除文件、合并分支等，先问再做
