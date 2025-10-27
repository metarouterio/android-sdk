package com.metarouter.analytics.types

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CodableValueTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun `fromAny converts null to NullValue`() {
        val result = CodableValue.fromAny(null)
        assertTrue(result is CodableValue.NullValue)
        assertTrue(result.isNull)
    }

    @Test
    fun `fromAny converts String to StringValue`() {
        val result = CodableValue.fromAny("test")
        assertTrue(result is CodableValue.StringValue)
        assertEquals("test", result.stringOrNull)
    }

    @Test
    fun `fromAny converts Int to IntValue`() {
        val result = CodableValue.fromAny(42)
        assertTrue(result is CodableValue.IntValue)
        assertEquals(42L, result.longOrNull)
        assertEquals(42, result.intOrNull)
    }

    @Test
    fun `fromAny converts Long to IntValue`() {
        val result = CodableValue.fromAny(42L)
        assertTrue(result is CodableValue.IntValue)
        assertEquals(42L, result.longOrNull)
    }

    @Test
    fun `fromAny converts Float to DoubleValue`() {
        val result = CodableValue.fromAny(3.14f)
        assertTrue(result is CodableValue.DoubleValue)
        assertEquals(3.14, result.doubleOrNull!!, 0.01)
    }

    @Test
    fun `fromAny converts Double to DoubleValue`() {
        val result = CodableValue.fromAny(3.14159)
        assertTrue(result is CodableValue.DoubleValue)
        assertEquals(3.14159, result.doubleOrNull!!, 0.00001)
    }

    @Test
    fun `fromAny converts Boolean to BoolValue`() {
        val trueResult = CodableValue.fromAny(true)
        val falseResult = CodableValue.fromAny(false)

        assertTrue(trueResult is CodableValue.BoolValue)
        assertEquals(true, trueResult.boolOrNull)

        assertTrue(falseResult is CodableValue.BoolValue)
        assertEquals(false, falseResult.boolOrNull)
    }

    @Test
    fun `fromAny converts List to ArrayValue`() {
        val list = listOf(1, "test", true)
        val result = CodableValue.fromAny(list)

        assertTrue(result is CodableValue.ArrayValue)
        val array = result.arrayOrNull!!
        assertEquals(3, array.size)
        assertEquals(1L, array[0].longOrNull)
        assertEquals("test", array[1].stringOrNull)
        assertEquals(true, array[2].boolOrNull)
    }

    @Test
    fun `fromAny converts Map to ObjectValue`() {
        val map = mapOf("name" to "Alice", "age" to 30)
        val result = CodableValue.fromAny(map)

        assertTrue(result is CodableValue.ObjectValue)
        val obj = result.objectOrNull!!
        assertEquals("Alice", obj["name"]?.stringOrNull)
        assertEquals(30L, obj["age"]?.longOrNull)
    }

    @Test
    fun `fromAny converts nested structures`() {
        val nested = mapOf(
            "user" to mapOf("name" to "Bob", "age" to 25),
            "scores" to listOf(90, 85, 95)
        )
        val result = CodableValue.fromAny(nested)

        assertTrue(result is CodableValue.ObjectValue)
        val obj = result.objectOrNull!!

        val user = obj["user"]?.objectOrNull!!
        assertEquals("Bob", user["name"]?.stringOrNull)
        assertEquals(25L, user["age"]?.longOrNull)

        val scores = obj["scores"]?.arrayOrNull!!
        assertEquals(3, scores.size)
        assertEquals(90L, scores[0].longOrNull)
    }

    @Test
    fun `serialization roundtrip for String`() {
        val original: CodableValue = CodableValue.StringValue("test")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<CodableValue>(serialized)

        assertEquals("\"test\"", serialized)
        assertTrue(deserialized is CodableValue.StringValue)
        assertEquals("test", deserialized.stringOrNull)
    }

    @Test
    fun `serialization roundtrip for Int`() {
        val original: CodableValue = CodableValue.IntValue(42)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<CodableValue>(serialized)

        assertEquals("42", serialized)
        assertTrue(deserialized is CodableValue.IntValue)
        assertEquals(42L, deserialized.longOrNull)
    }

    @Test
    fun `serialization roundtrip for Double`() {
        val original: CodableValue = CodableValue.DoubleValue(3.14)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<CodableValue>(serialized)

        assertEquals("3.14", serialized)
        assertTrue(deserialized is CodableValue.DoubleValue)
        assertEquals(3.14, deserialized.doubleOrNull!!, 0.01)
    }

    @Test
    fun `serialization roundtrip for Boolean`() {
        val original: CodableValue = CodableValue.BoolValue(true)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<CodableValue>(serialized)

        assertEquals("true", serialized)
        assertTrue(deserialized is CodableValue.BoolValue)
        assertEquals(true, deserialized.boolOrNull)
    }

    @Test
    fun `serialization roundtrip for Array`() {
        val original: CodableValue = CodableValue.ArrayValue(
            listOf(
                CodableValue.IntValue(1),
                CodableValue.StringValue("two"),
                CodableValue.BoolValue(true)
            )
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<CodableValue>(serialized)

        assertEquals("[1,\"two\",true]", serialized)
        assertTrue(deserialized is CodableValue.ArrayValue)
        val array = deserialized.arrayOrNull!!
        assertEquals(3, array.size)
    }

    @Test
    fun `serialization roundtrip for Object`() {
        val original: CodableValue = CodableValue.ObjectValue(
            mapOf(
                "name" to CodableValue.StringValue("Alice"),
                "age" to CodableValue.IntValue(30)
            )
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<CodableValue>(serialized)

        assertTrue(deserialized is CodableValue.ObjectValue)
        val obj = deserialized.objectOrNull!!
        assertEquals("Alice", obj["name"]?.stringOrNull)
        assertEquals(30L, obj["age"]?.longOrNull)
    }

    @Test
    fun `serialization roundtrip for Null`() {
        val original: CodableValue = CodableValue.NullValue
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<CodableValue>(serialized)

        assertEquals("null", serialized)
        assertTrue(deserialized is CodableValue.NullValue)
        assertTrue(deserialized.isNull)
    }

    @Test
    fun `toString produces readable output`() {
        assertEquals("\"test\"", CodableValue.StringValue("test").toString())
        assertEquals("42", CodableValue.IntValue(42).toString())
        assertEquals("3.14", CodableValue.DoubleValue(3.14).toString())
        assertEquals("true", CodableValue.BoolValue(true).toString())
        assertEquals("null", CodableValue.NullValue.toString())
    }

    @Test
    fun `type extraction returns null for wrong types`() {
        val stringValue = CodableValue.StringValue("test")

        assertNull(stringValue.longOrNull)
        assertNull(stringValue.intOrNull)
        assertNull(stringValue.doubleOrNull)
        assertNull(stringValue.boolOrNull)
        assertNull(stringValue.arrayOrNull)
        assertNull(stringValue.objectOrNull)
        assertFalse(stringValue.isNull)
    }

    @Test
    fun `fromMap converts entire map`() {
        val map = mapOf(
            "string" to "value",
            "int" to 42,
            "bool" to true,
            "null" to null
        )

        val result = CodableValue.fromMap(map)

        assertEquals(4, result.size)
        assertEquals("value", result["string"]?.stringOrNull)
        assertEquals(42L, result["int"]?.longOrNull)
        assertEquals(true, result["bool"]?.boolOrNull)
        assertTrue(result["null"]?.isNull == true)
    }
}
