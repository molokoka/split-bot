package split.storage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun encodeStrings(values: List<String>): String =
    Json.encodeToString(
        ListSerializer(String.serializer()),
        values,
    )

internal fun decodeStrings(text: String): List<String> =
    Json.decodeFromString(
        ListSerializer(String.serializer()),
        text,
    )

internal fun encodeLongs(values: List<Long>): String = Json.encodeToString(ListSerializer(Long.serializer()), values)

internal fun decodeLongs(text: String): List<Long> = Json.decodeFromString(ListSerializer(Long.serializer()), text)

internal fun encodeAmountsByMemberId(values: Map<String, Long>): String =
    Json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), values)

internal fun decodeAmountsByMemberId(text: String): Map<String, Long> =
    Json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), text)
