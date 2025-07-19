import com.example.kokoro82m.utils.AudioPlayer
import com.example.kokoro82m.utils.PlayerState
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.media.AudioFormat
class AudioPlayerSimpleTest {
    lateinit var player: AudioPlayer
    @Before
    fun setup() {
        player = AudioPlayer(CoroutineScope(Dispatchers.Unconfined)) {}
        val field = AudioPlayer::class.java.getDeclaredField("audioTrack")
        field.isAccessible = true
        field.set(player, null)
        val logField = Class.forName("com.example.kokoro82m.utils.DebugLogger").getDeclaredField("logFile")
        logField.isAccessible = true
        logField.set(null, java.io.File.createTempFile("log", ".txt"))
        val pcmField = AudioPlayer::class.java.getDeclaredField("pcmData")
        pcmField.isAccessible = true
        pcmField.set(player, ByteArray(0))
    }

    @Test
    fun pauseFromPlaying() {
        val stateField = AudioPlayer::class.java.getDeclaredField("currentState")
        stateField.isAccessible = true
        stateField.set(player, PlayerState.PLAYING)

        player.pause()

        assertEquals(PlayerState.PAUSED, stateField.get(player))
    }

    @Test
    fun playResumesFromPaused() {
        val stateField = AudioPlayer::class.java.getDeclaredField("currentState")
        stateField.isAccessible = true
        stateField.set(player, PlayerState.PAUSED)

        player.play()

        assertEquals(PlayerState.PLAYING, stateField.get(player))
    }

    @Test
    fun stopReleasesResources() {
        val stateField = AudioPlayer::class.java.getDeclaredField("currentState")
        stateField.isAccessible = true
        stateField.set(player, PlayerState.PLAYING)

        player.stop()

        assertEquals(PlayerState.IDLE, stateField.get(player))
    }
}
