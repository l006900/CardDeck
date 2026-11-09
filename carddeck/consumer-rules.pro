# 预防性规则：仅当「消费方 app」的 release 开启 minifyEnabled(R8) 时才生效。
# 本仓库 demo 工程未开启混淆，构建运行不会走到这条规则。
#
# C++ 侧用的是 JNI 静态注册（FrameTiming 的帧时钟、GpuTimer 的 disjoint timer query），
# native 符号名形式为 Java_com_arcana_gfx_FrameTiming_nativeCreate /
# Java_com_arcana_gfx_GpuTimer_nativeBegin 等，同时含完整类名和方法名。
# 两者任一被 R8 改名，运行时都会按原名找不到符号并抛 UnsatisfiedLinkError。
# 该文件会随 AAR 打成 proguard.txt 并合并进消费方配置；手工集成 AAR 时需自行添加。
-keep class com.arcana.gfx.FrameTiming { *; }
-keep class com.arcana.gfx.GpuTimer { *; }

