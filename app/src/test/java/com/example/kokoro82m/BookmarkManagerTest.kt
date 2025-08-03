import android.content.Context
import com.example.kokoro82m.utils.Bookmark
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
        val bookmark = Bookmark(5)
        mockStatic(DatabaseManager::class.java).use { db ->
            db.`when`<Bookmark?> { DatabaseManager.getBookmark(context, uri) }.thenReturn(bookmark)
            BookmarkManager.save(context, uri, 5)
            db.verify { DatabaseManager.setBookmark(context, uri, 5) }
            assertEquals(bookmark, BookmarkManager.load(context, uri))

            db.`when`<Bookmark?> { DatabaseManager.getBookmark(context, uri) }.thenReturn(null)
            BookmarkManager.clear(context, uri)
            db.verify { DatabaseManager.clearBookmark(context, uri) }
            assertEquals(null, BookmarkManager.load(context, uri))
        }
    }
}
