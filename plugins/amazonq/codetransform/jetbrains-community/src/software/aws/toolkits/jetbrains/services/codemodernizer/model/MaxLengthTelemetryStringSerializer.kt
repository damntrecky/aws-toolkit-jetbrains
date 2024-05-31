package software.aws.toolkits.jetbrains.services.codemodernizer.model

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider

val MAX_SERIALIZABLE_STRING_LENGTH  = 1000
class MaxLengthTelemetryStringSerializer : JsonSerializer<String>() {
    override fun serialize(value: String, gen: JsonGenerator, provider: SerializerProvider) {
        val truncatedValue = if (value.length > MAX_SERIALIZABLE_STRING_LENGTH) {
            value.substring(0, MAX_SERIALIZABLE_STRING_LENGTH)
        } else {
            value
        }
        gen.writeString(truncatedValue)
    }
}
