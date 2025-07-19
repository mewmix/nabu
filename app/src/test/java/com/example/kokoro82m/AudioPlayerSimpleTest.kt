import com.example.kokoro82m.utils.AudioPlayer
import com.example.kokoro82m.utils.PlayerState
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import android.media.AudioFormat
class AudioPlayerSimpleTest {
    private lateinit var player: AudioPlayer
    private lateinit var audioTrack: AudioTrack

    @Before
    fun setup() {
        audioTrack = mock(AudioTrack::class.java)
        player = AudioPlayer(CoroutineScope(Dispatchers.Unconfined)) {}
        val field = AudioPlayer::class.java.getDeclaredField("audioTrack")
        field.isAccessible = true
        field.set(player, audioTrack)
        val logField = Class.forName("com.example.kokoro82m.utils.DebugLogger").getDeclaredField("logFile")
        logField.isAccessible = true
        logField.set(null, java.io.File.createTempFile("log", ".txt"))
        val pcmField = AudioPlayer::class.java.getDeclaredField("pcmData")
        pcmField.isAccessible = true
        pcmField.set(player, ByteArray(100))
    }

    @Test
    fun pauseFromPlaying() {
        val stateField = AudioPlayer::class.java.getDeclaredField("currentState")
        stateField.isAccessible = true
        stateField.set(player, PlayerState.PLAYING)
        `when`(audioTrack.playbackHeadPosition).thenReturn(50)

        player.pause()

        assertEquals(PlayerState.PAUSED, player.getState())
        assertEquals(50, player.getPosition())
    }

    @Test
    fun playResumesFromPaused() {
        val stateField = AudioPlayer::class.java.getDeclaredField("currentState")
        stateField.isAccessible = true
        stateField.set(player, PlayerState.PAUSED)

        player.play()

        assertEquals(PlayerState.PLAYING, player.getState())
    }

    @Test
    fun stopReleasesResources() {
        val stateField = AudioPlayer::class.java.getDeclaredField("currentState")
        stateField.isAccessible = true
        stateField.set(player, PlayerState.PLAYING)

        player.stop()

        assertEquals(PlayerState.IDLE, player.getState())
        assertEquals(0, player.getPosition())
    }
}
