# R8 混淆配置
# Wear Compose / Compose 自带 consumer rules；本应用无反射调用，保持默认优化即可。
# kotlinx.coroutines 相关告警由库自带规则处理。
-dontwarn kotlinx.coroutines.**
