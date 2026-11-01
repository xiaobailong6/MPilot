package org.moviepilot.android.data

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.moviepilot.android.core.ServerAddress
import org.moviepilot.android.core.arrayField
import org.moviepilot.android.core.parseJsonObjectOrNull
import org.moviepilot.android.core.stringField
import java.util.UUID

/** 非敏感实例配置；每个实例拥有独立的 WebView 数据容器与凭据命名空间。 */
data class SavedInstance(
    val id: String,
    val address: ServerAddress,
)

/**
 * 在应用私有目录保存非敏感实例配置，对应 iOS 的 InstanceStore。
 * 忘记实例时协调清理其令牌与网页数据。
 */
class InstanceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 读取已经校验的历史地址；损坏配置不作为可连接实例。 */
    fun instances(): List<SavedInstance> {
        val array = load().arrayField("instances") ?: return emptyList()
        val result = ArrayList<SavedInstance>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val id = obj.stringField("id") ?: continue
            val address = obj.stringField("address") ?: continue
            val parsed = try {
                ServerAddress.parse(address)
            } catch (error: Exception) {
                continue
            }
            result.add(SavedInstance(id, parsed))
        }
        return result
    }

    /** 恢复上次选择；已忘记或损坏的实例回到连接页。 */
    fun selected(): SavedInstance? {
        val selectedId = load().stringField("selected") ?: return null
        return instances().firstOrNull { it.id == selectedId }
    }

    /** 相同标准地址复用自身容器，切换地址不能继承另一个实例的浏览器数据。 */
    fun select(address: ServerAddress): SavedInstance {
        val values = instances().toMutableList()
        val existing = values.firstOrNull { it.address == address }
        val instance = existing ?: SavedInstance(UUID.randomUUID().toString(), address)
        if (existing == null) values.add(instance)
        save(values, instance.id)
        return instance
    }

    /** 仅清除当前选择，历史实例容器留到用户明确选择忘记。 */
    fun deselect() {
        save(instances(), null)
    }

    /** 与清除 WebView 数据配合使用，移除指定实例的配置。 */
    fun forget(instance: SavedInstance) {
        val remaining = instances().filter { it.id != instance.id }
        val currentSelected = load().stringField("selected")
        save(remaining, if (currentSelected == instance.id) null else currentSelected)
    }

    private fun load(): JsonObject {
        val raw = prefs.getString(KEY_STATE, null) ?: return JsonObject(emptyMap())
        return parseJsonObjectOrNull(raw) ?: JsonObject(emptyMap())
    }

    private fun save(instances: List<SavedInstance>, selectedId: String?) {
        val payload = buildJsonObject {
            put(
                "instances",
                buildJsonArray {
                    for (instance in instances) {
                        add(
                            buildJsonObject {
                                put("id", instance.id)
                                put("address", instance.address.url)
                            },
                        )
                    }
                },
            )
            put("selected", selectedId)
        }
        prefs.edit().putString(KEY_STATE, payload.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "moviepilot.instances.v1"
        const val KEY_STATE = "state"
    }
}