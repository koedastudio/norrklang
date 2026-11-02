package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class MetadataDurationMediaSourceFactoryTest {

    @Test
    fun `radio can play HLS streams`() {
        val factory = MetadataDurationMediaSourceFactory(
            dataSourceFactory = DataSource.Factory { error("not loading in this test") },
            extractorsFactory = DefaultExtractorsFactory(),
            liveExtractorsFactory = DefaultExtractorsFactory(),
            loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
        )

        assertTrue(C.CONTENT_TYPE_HLS in factory.getSupportedTypes())
    }
}
