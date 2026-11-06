# 模拟炒股 · Wear OS 版 📈⌚

用**真实行情**、**虚拟资金**的炒股模拟器，专为 Wear OS 圆形手表设计，采用 Google 官方设计语言。

> 纯原生应用：**Jetpack Compose for Wear OS** 官方组件库（`androidx.wear.compose`），零 WebView、零第三方 UI 依赖。

## ✨ 功能特性

### 全市场支持
| 市场 | 规则模拟 |
|---|---|
| 🇨🇳 A股 | T+1 · 100股整数倍 · 佣金万2.5(最低5元) · 印花税卖出0.05% · 过户费 · 涨跌停 |
| 📊 ETF/LOF | T+1 · 100份整数倍 · 免印花税 |
| 🇭🇰 港股 | T+0 · **每手股数按真实值**（腾讯100/手、汇丰400/手，实时读取）· 佣金0.03% · 印花税双边0.1% |
| 🇺🇸 美股 | T+0 · 任意股数 · 佣金0.05%最低$1 · 无涨跌停 |

- **分币种资金池**：初始 ¥100万 + HK$10万 + $1万，总资产按**实时汇率**折算
- **货币互通（换汇）**：¥/HK$/$ 互相兑换，0.2% 点差模拟真实换汇成本，汇率来自新浪外汇（盘中实时）
- **游玩天数**：自选页显示「模拟第 N 天」，从 0 开始按自然日递增
- **堆叠卡片列表（v2.3 更新）**：Wear Compose **M3E**（`androidx.wear.compose.material3` 1.5.0）的 `ScreenScaffold` + `TransformingLazyColumn` + `transformedHeight/SurfaceTransformation` 官方堆叠变换；卡片统一为**常规圆角矩形**（14dp，v2.3 去掉跑道形胶囊），滚动堆叠阴影保留
- **GitHub 应用内更新（v2.3 新增）**：管理页「检查更新」一键查询 GitHub Releases → 比对版本号 → 应用内下载 APK（进度条）→ FileProvider 授权调起系统安装器；无 WebView、不跳浏览器
- **主题种子色**：13 种 Material 种子色（Lab 色彩空间生成整套 Wear M3 ColorScheme，与 AppOpsPro 同款算法），管理页可即时切换
- 工具链与 AppOpsPro 对齐：Kotlin 2.0.21 + Compose 插件 + BOM 2025.06 + compileSdk 35
- **R8**：开启 minify + shrinkResources 混淆压缩，APK 体积 7MB → 912KB
- **搜索全市场**：中文名 / 代码 / 拼音（如"腾讯"、"00700"、"AAPL"、"纳指"），覆盖沪深北 + 港股 + 美股 1万+ 标的
- **限价委托**：后台自动撮合引擎，可撤单，资金/股份冻结解冻
- **完整交易闭环**：持仓盈亏（持仓/当日）、T+1 可卖计算、成交记录、实现盈亏

### 内置离线教程 📚
12 章《零基础炒股教程》，完全离线，从零到入门：
股票是什么 → 开户准备 → A股规则 → 交易成本 → 看懂行情 → K线分时 → 均线量价 → 基本面(PE/PB/ROE) → 港美股差异 → ETF定投 → 风险管理(止损/仓位) → 避坑指南

### Wear OS 原生体验
- `ScalingLazyColumn` 官方弧形列表 / `TimeText` / `Chip` / `ToggleChip` / `SwipeDismissableNavHost` 右滑返回
- Material You 深色配色，涨红跌绿（A股习惯）
- 触感震动反馈、圆形确认动效

## 📲 安装

从 [Releases](../../releases) 下载 APK 安装到 Wear OS 手表或手机：

```bash
adb install 模拟炒股-WearOS-v2.0-全市场.apk
```

需 Android 7.1.2 (API 25) 及以上。

## 🛠 构建

```bash
git clone https://github.com/lliiaannggyu/stock-sim-wearos.git
cd stock-sim-wearos
# 在 local.properties 中配置你的 Android SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug   # 或使用已安装的 gradle: gradle assembleDebug
```

技术栈：Kotlin · Jetpack Compose for Wear OS 1.3 · AGP 8.4 · minSdk 25 / targetSdk 34

## 📡 数据来源

| 数据 | 来源 |
|---|---|
| 实时行情（全市场） | 腾讯财经 `qt.gtimg.cn` |
| 日K/分时曲线 | 腾讯财经 `web.ifzq.gtimg.cn` |
| 股票搜索 | 腾讯 smartbox |

> ⚠️ 本应用仅供学习交流，不构成任何投资建议。行情数据来自公开免费接口，可能有延迟；账户数据仅保存在设备本地。
