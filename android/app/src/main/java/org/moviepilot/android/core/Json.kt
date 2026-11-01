package org.moviepilot.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 协议层统一使用的 JSON 解析器。
 *
 * 刻意不使用 @Serializable 编译插件：
 * 1. 桥接协议的数字字段可能以 `1` 或 `1.0` 两种形式出现，Swift 侧用 Double 承接，这里同样先读 Double 再取整；
 * 2. 未知字段、可选字段必须宽松处理，避免前端小版本差异直接打断协商。
 */
val protocolJson: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/** 解析顶层对象；语法错误一律返回 null，由调用方按“无效消息”处理。 */
fun parseJsonObjectOrNull(text: String): JsonObject? = try {
    protocolJson.parseToJsonElement(text) as? JsonObject
} catch (error: Exception) {
    null
}

/** 读取字符串字段；JSON null 与非字符串值都视为缺省。 */
fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 读取数值字段；字符串形式的数字不算有效数值，与 Swift 的 Codable 行为一致。 */
fun JsonObject.numberField(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    return if (value.isFinite()) value else null
}

/** 读取整数字段；非整数与超出 Int 范围的值都视为缺省。 */
fun JsonObject.intField(key: String): Int? {
    val value = numberField(key) ?: return null
    if (value != Math.floor(value)) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}

/** 读取布尔字段；只接受 true / false 两个字面量。 */
fun JsonObject.booleanField(key: String): Boolean? = when ((this[key] as? JsonPrimitive)?.content) {
    "true" -> true
    "false" -> false
    else -> null
}

/** 读取嵌套对象；缺省或类型不符返回 null。 */
fun JsonObject.objectField(key: String): JsonObject? = this[key] as? JsonObject

/** 读取数组；缺省或类型不符返回 null。 */
fun JsonObject.arrayField(key: String): JsonArray? = this[key] as? JsonArray

/** 读取字符串数组；任一元素不是字符串则整体视为无效。 */
fun JsonObject.stringListField(key: String): List<String>? {
    val array = arrayField(key) ?: return null
    val values = ArrayList<String>(array.size)
    for (element in array) {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        values.add(text)
    }
    return values
}

/** 读取整数数组；用于 web.ready 的 majors 声明。 */
fun JsonObject.intListField(key: String): List<Int>? {
    val array = arrayField(key) ?: return null
    val values = ArrayList<Int>(array.size)
    for (element in array) {
        val text = (element as? JsonPrimitive)?.content ?: return null
        val number = text.toDoubleOrNull() ?: return null
        if (number != Math.floor(number)) return null
        values.add(number.toInt())
    }
    return values
}

/**
 * 把任意文本编码成可安全嵌入 JS 源码的字符串字面量。
 * 出站消息只以参数形式传给页面，绝不做字符串拼接，避免消息正文变成可执行代码。
 */
fun jsStringLiteral(value: String): String = JsonPrimitive(value).toString()