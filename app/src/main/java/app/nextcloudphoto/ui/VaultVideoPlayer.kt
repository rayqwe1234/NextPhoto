package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import app.nextcloudphoto.vault.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun VaultVideoPlayer(store: VaultStore, session: VaultSession, video: VaultVideo, modifier: Modifier=Modifier, onCloseReady: (()->Unit)->Unit = {}) {
    val context=LocalContext.current
    var error by remember(video) { mutableStateOf("") }
    var view by remember(video) { mutableStateOf<PlayerView?>(null) }
    val player=remember(session,video) {
        ExoPlayer.Builder(context).setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(3000,10000,1000,2000).setTargetBufferBytes(8*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
    }
    DisposableEffect(player,session) {
        var released=false
        fun release() {
            if(released) return
            released=true;store.cancelRequests();view?.player=null
            player.clearVideoSurface();player.release()
        }
        val registration=session.onLock { release() }
        onCloseReady { release() }
        val listener=object: Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error=tr(R.string.msg_230_cannot_play_this_video_check_your_connection_file_integ) }
        }
        if(!session.closed) {
            player.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VaultVideoDataSource(store,session,video) }
                .createMediaSource(MediaItem.Builder().setUri("vault://${session.id}/${video.id}").setMimeType(video.mime).build())
            player.setMediaSource(source);player.prepare();player.playWhenReady=true
        }
        onDispose { registration.close();release();onCloseReady { } }
    }
    Column(modifier) {
        if(error.isNotEmpty()) {
            Text(error,color=MaterialTheme.colorScheme.error)
            TextButton(onClick={error="";player.prepare();player.play()},enabled=!session.closed) { Text(tr(R.string.msg_231_retry_playback)) }
        }
        AndroidView(factory={PlayerView(it).apply { this.player=player;keepScreenOn=true;view=this }},
            onRelease={it.player=null;it.keepScreenOn=false},modifier=Modifier.fillMaxWidth().weight(1f))
    }
}
