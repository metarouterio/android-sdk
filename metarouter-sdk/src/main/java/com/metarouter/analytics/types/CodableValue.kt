package com.metarouter.analytics.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Type-safe wrapper for JSON-compatible values.
 * Supports serialization to/from JSON and provides safe type extraction.
 *
 * Supports the following types:
 * - String
 * - Int/Long
 * - Double/Float
 * - Boolean
 * - Array (List<CodableValue>)
 * - Object (Map<String, CodableValue>)
 * - Null
 */
@Serializable(with = CodableValueSerializer::class)
sealed class CodableValue {
    data class StringValue(val value: String) : CodableValue() {
        override fun toString() = "\"$value\""
    }
    data class IntValue(val value: Long) : CodableValue() {
        override fun toString() = value.toString()
    }
    data class DoubleValue(val value: Double) : CodableValue() {
        override fun toString() = value.toString()
    }
    data class BoolValue(val value: Boolean) : CodableValue() {
        override fun toString() = value.toString()
    }
    data class ArrayValue(val value: List<CodableValue>) : CodableValue() {
        override fun toString() = value.toString()
    }
    data class ObjectValue(val value: Map<String, CodableValue>) : CodableValue() {
        override fun toString() = value.toString()
    }
    object NullValue : CodableValue() {
        override fun toString() = "null"
    }

    /**
     * Safely extract String value, returns null if not a string.
     */
    val stringOrNull: String?
        get() = (this as? StringValue)?.value

    /**
     * Safely extract Long value, returns null if not an integer.
     */
    val longOrNull: Long?
        get() = (this as? IntValue)?.value

    /**
     * Safely extract Int value, returns null if not an integer or out of Int range.
     */
    val intOrNull: Int?
        get() = longOrNull?.toInt()

    /**
     * Safely extract Double value, returns null if not a double.
     */
    val doubleOrNull: Double?
        get() = (this as? DoubleValue)?.value

    /**
     * Safely extract Boolean value, returns null if not a boolean.
     */
    val boolOrNull: Boolean?
        get() = (this as? BoolValue)?.value

    /**
     * Safely extract Array value, returns null if not an array.
     */
    val arrayOrNull: List<CodableValue>?
        get() = (this as? ArrayValue)?.value

    /**
     * Safely extract Object value, returns null if not an object.
     */
    val objectOrNull: Map<String, CodableValue>?
        get() = (this as? ObjectValue)?.value

    /**
     * Check if this value is null.
     */
    val isNull: Boolean
        get() = this is NullValue

    companion object {
        /**
         * Convert a Map<String, Any?> to Map<String, CodableValue>.
         * Used to convert user input to type-safe values.
         */
        fun fromMap(map: Map<String, Any?>): Map<String, CodableValue> {
            return map.mapValues { (_, value) -> fromAny(value) }
        }

        /**
         * Convert Any? to CodableValue.
         * Handles nested maps and lists recursively.
         */
        fun fromAny(value: Any?): CodableValue = when (value) {
            null -> NullValue
            is String -> StringValue(value)
            is Int -> IntValue(value.toLong())
            is Long -> IntValue(value)
            is Float -> DoubleValue(value.toDouble())
            is Double -> DoubleValue(value)
            is Boolean -> BoolValue(value)
            is List<*> -> ArrayValue(value.map { fromAny(it) })
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val stringMap = value as? Map<String, Any?> ?: emptyMap()
                ObjectValue(fromMap(stringMap))
            }
            else -> StringValue(value.toString())
        }
    }
}

/**
 * Custom serializer for CodableValue that handles JSON serialization/deserialization.
 */
object CodableValueSerializer : KSerializer<CodableValue> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CodableValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CodableValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("CodableValue can only be serialized to JSON")

        val element = when (value) {
            is CodableValue.StringValue -> JsonPrimitive(value.value)
            is CodableValue.IntValue -> JsonPrimitive(value.value)
            is CodableValue.DoubleValue -> JsonPrimitive(value.value)
            is CodableValue.BoolValue -> JsonPrimitive(value.value)
            is CodableValue.ArrayValue -> JsonArray(value.value.map { serializeToElement(it) })
            is CodableValue.ObjectValue -> JsonObject(value.value.mapValues { serializeToElement(it.value) })
            is CodableValue.NullValue -> JsonNull
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): CodableValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("CodableValue can only be deserialized from JSON")

        return deserializeFromElement(jsonDecoder.decodeJsonElement())
    }

    private fun serializeToElement(value: CodableValue): JsonElement = when (value) {
        is CodableValue.StringValue -> JsonPrimitive(value.value)
        is CodableValue.IntValue -> JsonPrimitive(value.value)
        is CodableValue.DoubleValue -> JsonPrimitive(value.value)
        is CodableValue.BoolValue -> JsonPrimitive(value.value)
        is CodableValue.ArrayValue -> JsonArray(value.value.map { serializeToElement(it) })
        is CodableValue.ObjectValue -> JsonObject(value.value.mapValues { serializeToElement(it.value) })
        is CodableValue.NullValue -> JsonNull
    }

    private fun deserializeFromElement(element: JsonElement): CodableValue = when (element) {
        is JsonNull -> CodableValue.NullValue
        is JsonPrimitive -> {
            when {
                element.isString -> CodableValue.StringValue(element.content)
                element.booleanOrNull != null -> CodableValue.BoolValue(element.boolean)
                element.longOrNull != null -> CodableValue.IntValue(element.long)
                element.doubleOrNull != null -> CodableValue.DoubleValue(element.double)
                else -> CodableValue.StringValue(element.content)
            }
        }
        is JsonArray -> CodableValue.ArrayValue(element.map { deserializeFromElement(it) })
        is JsonObject -> CodableValue.ObjectValue(element.mapValues { deserializeFromElement(it.value) })
    }
}
