package studio.koeda.norrklang.data.artwork

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Artwork URIs as plain strings on disk. Decoding re-registers cover ids with
 * [KnownCoverIds], which a restored list would otherwise bypass and leave
 * its covers undownloadable.
 */
object CoverUriSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CoverUri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String =
        decoder.decodeString().also(ArtworkContract::registerCoverUri)
}
