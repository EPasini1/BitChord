package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopUpdateNotesFormatTest {

    @Test
    fun markdownHeadingsLinksAndImagesAreReadableInDialog() {
        val notes = """
            ![BitChord banner](https://example.com/banner.png)
            ## Top Features
            - Read [release notes](https://example.com/release)
        """.trimIndent()

        assertEquals(
            """
                BitChord banner (https://example.com/banner.png)
                Top Features
                - Read release notes (https://example.com/release)
            """.trimIndent(),
            formatUpdateNotes(notes),
        )
    }
}
