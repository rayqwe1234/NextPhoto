@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.nextcloudphoto.data.*
import app.nextcloudphoto.network.ShareLink
import coil.compose.SubcomposeAsyncImage
import java.io.File

@Composable fun Viewer(vm: PhotoViewModel, items: LazyPagingItems<Media>, initialIndex: Int, initial: Media, dismiss: ()->Unit, actions: (Media)->Unit, edit: (Media)->Unit) {
    val pager=rememberPagerState(initialPage=initialIndex,pageCount={items.itemCount})
    var zoomed by remember { mutableStateOf(false) }
    val loaded=if(pager.currentPage<items.itemCount) items[pager.currentPage] else null
    val current=loaded ?: initial
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(color=Color.Black,modifier=Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=dismiss) { Icon(Icons.Outlined.ArrowBack,tr(R.string.msg_232_back),tint=Color.White) }
                    Column(Modifier.weight(1f)) { Text(current.name,color=Color.White,maxLines=1); Text(dayLabel(current.taken),color=Color.LightGray,style=MaterialTheme.typography.bodySmall) }
                    IconButton(onClick={actions(current)},enabled=loaded!=null) { Icon(Icons.Outlined.MoreHoriz,tr(R.string.msg_233_more_actions),tint=Color.White) }
                }
                HorizontalPager(pager,modifier=Modifier.weight(1f).testTag("viewer-pager"),userScrollEnabled=!zoomed) { index ->
                    val media=items[index]
                    if(media==null) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }; return@HorizontalPager }
                    if(media.mime.startsWith("video/")) {
                        if(index==pager.currentPage) Video(vm,media) else Box(Modifier.fillMaxSize())
                    } else {
                        var scale by remember(media.id) { mutableFloatStateOf(1f) }
                        var pan by remember(media.id) { mutableStateOf(Offset.Zero) }
                        var original by remember(media.id) { mutableStateOf(false) }
                        LaunchedEffect(pager.currentPage) { scale=1f;pan=Offset.Zero;zoomed=false }
                        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                            val model=if(media.offline.isNotBlank() && File(media.offline).exists()) File(media.offline) else if(original) media.href else vm.repo.client().preview(media.fileId,media.etag,2048)
                            SubcomposeAsyncImage(model=model,imageLoader=LocalPhotosLoader.current,contentDescription=media.name,contentScale=ContentScale.Fit,
                                modifier=Modifier.fillMaxSize().transformable(state=rememberTransformableState { zoom,delta,_ -> scale=(scale*zoom).coerceIn(1f,8f); pan=if(scale>1) pan+delta else Offset.Zero; zoomed=scale>1f },canPan={scale>1f})
                                    .graphicsLayer { scaleX=scale;scaleY=scale;translationX=pan.x.coerceIn(-size.width*(scale-1)/2,size.width*(scale-1)/2);translationY=pan.y.coerceIn(-size.height*(scale-1)/2,size.height*(scale-1)/2) },
                                loading={Box(contentAlignment=Alignment.Center) { CircularProgressIndicator() }},
                                error={Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Text(tr(R.string.msg_234_cannot_display_this_format_or_preview),color=Color.White); TextButton(onClick={original=true}) { Text(tr(R.string.msg_235_try_the_original)) }; TextButton(onClick={vm.export(media)}) { Text(tr(R.string.msg_236_download_and_open_in_another_app)) } }})
                            if(!original && media.offline.isBlank()) TextButton(onClick={original=true},modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(tr(R.string.msg_237_load_original_1_s, bytes(media.size)),color=Color.White) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                    IconButton(onClick={vm.action { vm.repo.favorite(current) }},enabled=loaded!=null && vm.repo.client().inFiles(current.href)) { Icon(if(current.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,tr(R.string.msg_96_favorites),tint=Color.White) }
                    IconButton(onClick={edit(current)},enabled=loaded!=null && current.mime.startsWith("image/") && vm.repo.client().inFiles(current.href)) { Icon(Icons.Outlined.Edit,tr(R.string.msg_57_edit_photo),tint=Color.White) }
                    IconButton(onClick={vm.offline(listOf(current))},enabled=loaded!=null) { Icon(Icons.Outlined.Download,tr(R.string.msg_102_save_offline),tint=Color.White) }
                    IconButton(onClick={actions(current)},enabled=loaded!=null) { Icon(Icons.Outlined.Info,tr(R.string.msg_238_photo_details_and_actions),tint=Color.White) }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun Video(vm: PhotoViewModel, media: Media) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    var failure by remember { mutableStateOf("") }
    val player=remember(media.id) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context,OkHttpDataSource.Factory(vm.repo.client().http)))).build().apply {
            val uri=if(media.offline.isNotBlank() && File(media.offline).exists()) android.net.Uri.fromFile(File(media.offline)).toString() else media.href
            setMediaItem(MediaItem.fromUri(uri)); prepare()
            addListener(object: Player.Listener { override fun onPlayerError(error: PlaybackException) { failure=tr(R.string.msg_239_cannot_play_this_video_the_format_may_be_unsupported_or) } })
        }
    }
    DisposableEffect(player,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.release() }
    }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        AndroidView(factory={PlayerView(it).apply { this.player=player }},modifier=Modifier.fillMaxSize())
        if(failure.isNotEmpty()) Text(failure,color=Color.White,modifier=Modifier.background(Color.Black).padding(20.dp))
    }
}

@Composable fun MediaActions(vm: PhotoViewModel,media: Media,album: Album?,dismiss: ()->Unit,edit: ()->Unit,shareIntent: (Intent)->Unit) {
    val serverInfo by vm.repo.server.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf("") }
    var link by remember { mutableStateOf<ShareLink?>(null) }
    var links by remember { mutableStateOf<List<ShareLink>>(emptyList()) }
    var password by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var exifText by remember { mutableStateOf("") }
    val canWrite=vm.repo.client().inFiles(media.href)
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp)) {
            Text(media.name,style=MaterialTheme.typography.titleLarge)
            Text(tr(R.string.msg_242_1_s_2_s_ncapture_sort_date_3_s_nmodified_4_s_n_5_s, bytes(media.size), media.mime, dateTime(media.taken), dateTime(media.modified), if(media.offline.isNotBlank()) tr(R.string.msg_240_offline_copy_available) else tr(R.string.msg_241_cloud_only)),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=12.dp))
            if(exifText.isNotBlank()) Text(exifText,style=MaterialTheme.typography.bodySmall)
            if(media.mime.startsWith("image/")) ActionLine(tr(R.string.msg_243_read_capture_details)) { vm.action {
                val file=File(vm.app.cacheDir,"metadata/${media.fileId}")
                try {
                    vm.repo.client().download(media.href,file)
                    val exif=androidx.exifinterface.media.ExifInterface(file)
                    val tags=listOf("Make" to tr(R.string.msg_244_make),"Model" to tr(R.string.msg_245_camera),"DateTimeOriginal" to tr(R.string.msg_246_capture_time),"FNumber" to tr(R.string.msg_247_aperture),"ExposureTime" to tr(R.string.msg_248_exposure_time),"PhotographicSensitivity" to "ISO","FocalLength" to tr(R.string.msg_249_focal_length))
                    exifText=tags.mapNotNull { (tag,label) -> exif.getAttribute(tag)?.let { "${label}：${it}" } }.joinToString("\n").ifBlank { tr(R.string.msg_250_no_readable_exif_capture_information_in_the_original) }
                    if(media.taken==media.modified) vm.repo.dao.taken(media.id,app.nextcloudphoto.network.parseTaken(exif.getAttribute("DateTimeOriginal"),media.taken))
                } finally { file.delete() }
            } }
            ActionLine(tr(R.string.msg_251_save_to_phone_downloads)) { vm.export(media);dismiss() }
            ActionLine(tr(R.string.msg_252_share_file_with_another_app)) { vm.shareFile(media,shareIntent);dismiss() }
            if(canWrite) {
                ActionLine(tr(R.string.msg_253_add_to_a_photos_album)) { dialog="album" }
                ActionLine(tr(R.string.msg_254_create_sharing_link)) { dialog="share" }
                ActionLine(tr(R.string.msg_255_manage_sharing_links)) { vm.action { links=vm.repo.client().shares() };dialog="links" }
                ActionLine(tr(R.string.msg_256_rename_move_file)) { dialog="move" }
                if(media.mime.startsWith("image/")) ActionLine(tr(R.string.msg_257_edit_and_save_a_copy),edit)
            }
            if(album!=null) ActionLine(tr(R.string.msg_258_remove_from_this_album)) { dialog="remove" }
            if(media.offline.isNotBlank()) ActionLine(tr(R.string.msg_259_clear_offline_copy)) { vm.action { vm.repo.clearOffline(media) };dismiss() }
            if(canWrite) ActionLine(tr(R.string.msg_260_delete_cloud_original)) { dialog="delete" }
        }
    }
    if(dialog=="album") AlbumPicker(vm,{dialog=""}) { vm.addToAlbum(it,listOf(media));dismiss() }
    if(dialog=="move") {
        val client=vm.repo.client()
        val relative=client.trusted(media.href).pathSegments.drop(client.trusted(client.filesRoot).pathSegments.size).joinToString("/")
        TextEntryDialog(tr(R.string.msg_261_move_or_rename),tr(R.string.msg_262_full_relative_path_including_filename),relative,{dialog=""}) { vm.move(media,it);dismiss() }
    }
    if(dialog=="delete" || dialog=="remove") AlertDialog(onDismissRequest={dialog=""},title={Text(if(dialog=="delete") tr(R.string.msg_263_delete_cloud_original) else tr(R.string.msg_264_remove_from_album))},text={Text(if(dialog=="remove") tr(R.string.msg_265_only_the_album_membership_is_removed_the_original_photo) else tr(R.string.msg_266_this_deletes_the_original_file_from_nextcloud)+if(serverInfo?.trash==true) tr(R.string.msg_267_you_may_restore_it_from_the_web_trash_according_to_serv) else tr(R.string.msg_268_trash_support_was_not_confirmed_by_the_server_recovery))},confirmButton={TextButton(onClick={vm.action { if(dialog=="delete") vm.repo.removeFile(media) else vm.repo.removeMember(album!!,media) };dismiss()}) { Text(tr(R.string.msg_136_ok)) }},dismissButton={TextButton(onClick={dialog=""}) { Text(tr(R.string.msg_71_cancel)) }})
    if(dialog=="share") AlertDialog(onDismissRequest={dialog=""},title={Text(tr(R.string.msg_269_create_a_read_only_sharing_link))},text={Column {
        OutlinedTextField(password,{password=it},label={Text(tr(R.string.msg_270_password_optional))},singleLine=true)
        OutlinedTextField(expiry,{expiry=it},label={Text(tr(R.string.msg_271_expiry_yyyy_mm_dd_optional))},singleLine=true)
        link?.let { androidx.compose.foundation.text.selection.SelectionContainer { Text(it.url,modifier=Modifier.padding(top=12.dp)) } }
    }},confirmButton={TextButton(onClick={vm.action { link=vm.repo.client().share(media.href,password,expiry) }},enabled=link==null) { Text(tr(R.string.msg_272_create)) }},dismissButton={TextButton(onClick={dialog=""}) { Text(tr(R.string.msg_138_close)) }})
    if(dialog=="links") AlertDialog(onDismissRequest={dialog=""},title={Text(tr(R.string.msg_273_public_sharing_links_for_this_account))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        if(links.isEmpty()) Text(tr(R.string.msg_274_no_public_links_or_still_loading))
        links.forEach { value -> Text(value.url,style=MaterialTheme.typography.bodySmall); TextButton(onClick={vm.action { vm.repo.client().revoke(value.id);links=links-value }}) { Text(tr(R.string.msg_275_revoke_this_link)) } }
    }},confirmButton={TextButton(onClick={dialog=""}) { Text(tr(R.string.msg_138_close)) }})
}
@Composable private fun ActionLine(text: String,click: ()->Unit) { TextButton(onClick=click,modifier=Modifier.fillMaxWidth()) { Text(text,modifier=Modifier.fillMaxWidth()) } }
