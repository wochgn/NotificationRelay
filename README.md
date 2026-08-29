# 通知流转（正式版）

在两台 Android 手机之间，通过 BLE 把「A 收到的通知」流转到「B 弹出」，反向亦然。
UI 遵循 Material Design 3（含动态取色）。

## 功能

- **BLE 双向流转**：两端自动协商中心/外设；新设备需确认配对，已保存设备可自动重连
- **通知标题带远端设备名**：`<远端设备名> | <应用名> | <通知标题>`，设备名默认取系统设备名、可在设置中修改
- **按应用选择是否转发**：应用页列出已安装应用，可勾选；配合「仅转发选中」开关实现白名单
- **连接测试**：在设置页通过当前 BLE 链路向远端发送测试通知
- **常驻后台 + 常驻通知**：前台服务（`connectedDevice` 类型）保活，状态栏常驻通知实时显示连接状态与远端电量
- **分通道管理**：普通通知 / 常驻通知 / 后台状态三个独立通知通道，可在系统设置中分别管理

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 / UI | Kotlin + ViewBinding + Material 3（动态取色） |
| BLE | 系统 `android.bluetooth.le`，无三方库 |
| 通知读取 | `NotificationListenerService` |
| 保活 | 前台服务（`foregroundServiceType=connectedDevice`） |
| 持久化 | `SharedPreferences`（同步读写，监听线程与 UI 通用） |
| SDK | `minSdk 31` / `targetSdk 35` / `compileSdk 35` |

## 环境要求

- Android Studio（自带 JDK 17+）
- 两台测试机：**Android 12 (ColorOS)** 与 **Android 16 (HyperOS)**
- 首次同步 Gradle 需联网

## 编译安装

1. Android Studio 打开本目录（`File → Open`，选 `NotifRelayPro`）
2. 等 Gradle Sync 完成
3. 两台手机连电脑，分别 `Run`（或各打一个 debug APK 安装）

> 命令行：`./gradlew assembleDebug`，APK 在 `app/build/outputs/apk/debug/`

## 使用步骤（两台手机各自操作）

| 页面 | 操作 |
|---|---|
| 设备 | 两端打开设备页 → 在附近设备中点击对方 → 确认「配对并连接」 |
| 应用 | 可选：开启「仅转发选中」，勾选要转发的应用 |
| 设置 | 可选：改设备名、开「常驻后台」，或发送测试通知检查连接 |

连接成功后，任意一台收到通知，另一台会弹出 `<设备名> | <应用> | <标题>` 的通知。

> 配对确认是应用内的信任确认，不会创建系统蓝牙绑定。首次配对后会记住设备，后续发现时自动协商角色并重连。

## 权限清单

| 权限 | 用途 |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` | BLE 扫描/连接/广播（运行时申请） |
| `POST_NOTIFICATIONS`（Android 13+） | 弹出流转通知 |
| `QUERY_ALL_PACKAGES` | 解析通知来源应用名 |
| `FOREGROUND_SERVICE(_CONNECTED_DEVICE)` | 前台保活 |

## 厂商后台白名单（重要）

ColorOS / HyperOS 会对后台应用做激进回收。开启「常驻后台」后，仍建议把本 App 加入厂商的白名单：

- **ColorOS（Android 12）**：设置 → 电池 → 应用耗电管理 → 找到本 App → 允许「后台运行 / 自启动」
- **HyperOS（Android 16）**：设置 → 应用设置 → 本 App → 电池 → 无限制；并允许「自启动」与「后台弹出界面」

## 通知通道

| 通道 | 内容 | 重要级别 |
|---|---|---|
| 流转的通知 | 普通流转通知 | 高（有声音） |
| 流转的常驻通知 | 不可清除类通知（如场景调度） | 低（静默） |
| 常驻后台状态 | 前台服务状态（连接状态 + 电量） | 低（静默） |

## 已知限制

- **OTP 脱敏（Android 15+）**：HyperOS/Android 16 上验证码类敏感通知内容会被系统脱敏，普通通知不受影响；需 `CompanionDeviceManager` 信任通道豁免，属二期
- **不同步「通知被清除」**：只流转「新通知」
- **不传图片**：仅标题 + 正文（截断 2000 字符）
- **未加密**：BLE 链路未 bonding，明文传输
- **电量为自定义 status 消息**：非标准 Battery Service

## 代码结构

```
app/src/main/java/com/notifrelay/
├── MainActivity.kt             单 Activity + 底部导航（三页）
├── ui/
│   ├── DevicesFragment.kt      设备页（发现/配对/连接状态）
│   ├── AppsFragment.kt         应用页（白名单 + 搜索 + 开关）
│   └── SettingsFragment.kt     设置页（设备名 / 常驻后台）
├── BleRelayManager.kt          BLE 双角色 + 分片 + hello/status 消息 + 状态监听
├── RelayListenerService.kt     通知读取 + 过滤
├── RelayForegroundService.kt   前台保活 + 常驻状态通知
├── NotificationCodec.kt        通知 → JSON
├── SettingsRepository.kt       设置持久化（设备名/白名单/常驻开关）
├── DeviceInfo.kt               系统设备名 / 电量
├── Constants.kt                UUID / 通道常量
└── EventLog.kt                 日志桥（BLE 线程 → UI 线程）
```
