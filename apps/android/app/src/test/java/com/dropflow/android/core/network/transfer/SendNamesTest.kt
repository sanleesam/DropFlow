package com.dropflow.android.core.network.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class SendNamesTest {
    @Test
    fun sameNamedFilesGetDistinctNames() {
        assertEquals(
            listOf("IMG_1.jpg", "IMG_1 (2).jpg", "img_1 (3).JPG", "notes", "notes (2)", ".env", ".env (2)", "IMG_1 (2) (2).jpg"),
            SendNames.unique(listOf("IMG_1.jpg", "IMG_1.jpg", "img_1.JPG", "notes", "notes", ".env", ".env", "IMG_1 (2).jpg"))
        )
    }
}
