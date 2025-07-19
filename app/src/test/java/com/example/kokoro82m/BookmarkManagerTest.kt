import android.content.Context
import com.example.kokoro82m.utils.BookmarkManager
import com.example.kokoro82m.utils.DatabaseManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class BookmarkManagerTest {
    private val context = mock(Context::class.java)

    @Test
    fun saveLoadAndClearBookmark() {
        val uri = "sample"
        mockStatic(DatabaseManager::class.java).use { db ->
            db.`when`<Int?> { DatabaseManager.getBookmark(context, uri) }.thenReturn(5)
            BookmarkManager.save(context, uri, 5)
            db.verify { DatabaseManager.setBookmark(context, uri, 5) }
            assertEquals(5, BookmarkManager.load(context, uri))

            db.`when`<Int?> { DatabaseManager.getBookmark(context, uri) }.thenReturn(null)
            BookmarkManager.clear(context, uri)
            db.verify { DatabaseManager.clearBookmark(context, uri) }
            assertEquals(-1, BookmarkManager.load(context, uri))
        }
    }
}
