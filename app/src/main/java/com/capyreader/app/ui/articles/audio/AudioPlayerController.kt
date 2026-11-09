package com.capyreader.app.ui.articles.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.capyreader.app.common.AudioEnclosure
import com.google.common.util.concurrent.ListenableFuture
import com.jocmp.capy.logging.CapyLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioPlayerController(
    private val context: Context,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentAudio = MutableStateFlow<AudioEnclosure?>(null)
    val currentAudio: StateFlow<AudioEnclosure?> = _currentAudio.asStateFlow()

    private val _isExpanded = MutableStateFlow(false)

    /** Whether the full player sheet is asked for; the sheet itself is drawn once, by the app. */
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    fun expand() {
        _isExpanded.value = true
    }

    fun collapse() {
        _isExpanded.value = false
    }

    /**
     * Shows the full player for this episode, for links that point at the episode itself (the article title,
     * "open externally"). A different episode starts playing; the loaded one only opens the sheet.
     */
    fun open(audio: AudioEnclosure) {
        _isExpanded.value = true
        if (startsOnOpen(_currentAudio.value?.url, audio.url, mediaController?.isConnected == true)) {
            play(audio)
        }
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        mediaController?.let {
            if (it.isConnected) {
                onReady(it)
                return
            }
        }

        val sessionToken = SessionToken(
            context,
            ComponentName(context, MediaPlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                mediaController = controller
                _player.value = controller
                controller?.let {
                    setupPlayerListener(it)
                    onReady(it)
                }
            } catch (e: Exception) {
                _isBuffering.value = false
                CapyLog.error("audio_player", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                _isPlaying.value = player.isPlaying
                _isBuffering.value = player.playWhenReady &&
                        player.playbackState == Player.STATE_BUFFERING
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    controller.pause()
                }
            }
        })
    }

    @OptIn(UnstableApi::class)
    fun play(audio: AudioEnclosure) {
        mainScope.launch {
            val currentUrl = _currentAudio.value?.url

            if (currentUrl == audio.url && mediaController?.isConnected == true) {
                mediaController?.play()
                return@launch
            }

            if (opensPlayerOnPlay(currentUrl = currentUrl, newUrl = audio.url)) {
                _isExpanded.value = true
            }

            _currentAudio.value = audio
            _isBuffering.value = true

            ensureController { controller ->
                val mediaItem = MediaItem.Builder()
                    .setUri(audio.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(audio.title)
                            .setArtist(audio.feedName)
                            .setArtworkUri(audio.artworkUrl?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()

                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.playWhenReady = true
            }
        }
    }

    fun pause() {
        mainScope.launch {
            mediaController?.pause()
        }
    }

    fun dismiss() {
        mainScope.launch {
            mediaController?.let { controller ->
                controller.stop()
                controller.clearMediaItems()
            }
            _currentAudio.value = null
            _isExpanded.value = false
            _isPlaying.value = false
            _isBuffering.value = false
        }
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
        _player.value = null
        controllerFuture = null
    }

    companion object {
        /**
         * The full player opens by itself when a different episode starts, not when the same one is
         * played again after a pause: closing the sheet then stays closed. After a stop there is no
         * current episode, so playing it again counts as new.
         */
        internal fun opensPlayerOnPlay(currentUrl: String?, newUrl: String): Boolean {
            return currentUrl != newUrl
        }

        /**
         * Opening the player from a link starts the episode unless it is the one already loaded in a live
         * controller; then the sheet only shows it, paused or playing as it was.
         */
        internal fun startsOnOpen(currentUrl: String?, newUrl: String, connected: Boolean): Boolean {
            return currentUrl != newUrl || !connected
        }
    }
}
