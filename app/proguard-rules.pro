# 保留崩溃堆栈行号，便于排查 R8 混淆后的线上问题
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 反射目标均为平台类（BluetoothGatt.refresh、Notification.ProgressStyle 等），
# R8 不会重命名 android.jar 中的平台类，无需额外 keep 规则；
# Compose / miuix 等 Android 库自带 consumer rules。
