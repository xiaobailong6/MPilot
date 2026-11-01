# 协议字段全部通过 JSON 字符串收发，Kotlin 侧没有反射序列化，当前不需要额外 keep 规则。
# 若后续启用 @Serializable，需要保留生成的 serializer：
# -keepclassmembers class **$$serializer { *; }
# -keep,includedescriptorclasses class org.moviepilot.android.** { *; }