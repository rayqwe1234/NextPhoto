@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.nextcloudphoto.data.*
import app.nextcloudphoto.network.*
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.disk.DiskCache
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

val Blue=Color(0xFF287BEE)
val LocalPhotosLoader=staticCompositionLocalOf<ImageLoader> { error("Image loader is unavailable") }

@Composable fun PhotoApp(vm: PhotoViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    var appearance by remember { mutableStateOf(vm.repo.prefs.getString("theme","system")!!) }
    val dark=when(appearance) { "dark"->true; "light"->false; else->isSystemInDarkTheme() }
    val scheme=if(dark) darkColorScheme(primary=Color(0xFF8BB9FF),secondaryContainer=Color(0xFF263E60),onSecondaryContainer=Color(0xFFD6E6FF),background=Color(0xFF111318),surface=Color(0xFF191C22))
        else lightColorScheme(primary=Blue,secondaryContainer=Color(0xFFE0EDFF),onSecondaryContainer=Color(0xFF20549C),background=Color(0xFFF8F9FC),surface=Color.White,surfaceVariant=Color(0xFFEEF2F8))
    MaterialTheme(colorScheme=scheme,typography=Typography()) {
        Surface(Modifier.fillMaxSize()) {
            if(account==null) LoginScreen(vm)
            else {
                val loader=remember(account!!.key) { ImageLoader.Builder(vm.app).okHttpClient(vm.repo.client().http)
                    .diskCache { DiskCache.Builder().directory(File(vm.app.cacheDir,"thumbnails")).maxSizeBytes(1024L*1024*1024).build() }.crossfade(true).build() }
                DisposableEffect(loader) { onDispose { loader.shutdown() } }
                CompositionLocalProvider(LocalPhotosLoader provides loader) { LibraryScreen(vm,appearance) { appearance=it; vm.repo.prefs.edit().putString("theme",it).apply() } }
            }
        }
    }
}

@Composable private fun LoginScreen(vm: PhotoViewModel) {
    var server by rememberSaveable { mutableStateOf("") }
    val pending by vm.loginPending.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context=LocalContext.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.Center) {
        androidx.compose.foundation.Image(painter=androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher),contentDescription=null,modifier=Modifier.size(88.dp).clip(RoundedCornerShape(30.dp)))
        Spacer(Modifier.height(32.dp))
        Text(tr(R.string.msg_74_your_photos_nyour_cloud),fontSize=36.sp,fontWeight=FontWeight.Bold,lineHeight=46.sp)
        Spacer(Modifier.height(14.dp))
        Text("NextPhoto",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        Text(tr(R.string.msg_75_browse_organize_and_back_up_the_moments_worth_keeping),modifier=Modifier.padding(top=8.dp,bottom=36.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(server,{server=it},label={Text(tr(R.string.msg_76_nextcloud_url))},placeholder={Text("https://cloud.example.com")},singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth(),enabled=!pending)
        Spacer(Modifier.height(18.dp))
        Button(onClick={ vm.message.value=""; vm.login(server) { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it))) } },enabled=!pending && server.isNotBlank(),modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(18.dp)) {
            if(pending) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Text(tr(R.string.msg_77_connect_to_my_nextcloud),fontSize=16.sp)
        }
        if(pending) { Text(tr(R.string.msg_78_finish_authorization_in_your_browser_then_return_here),modifier=Modifier.padding(top=16.dp)); TextButton(onClick=vm::cancelLogin) { Text(tr(R.string.msg_79_cancel_sign_in)) } }
        if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=16.dp))
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Lock,null,Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.msg_80_your_photos_and_credentials_connect_only_to_your_server),style=MaterialTheme.typography.bodySmall) }
        Text(tr(R.string.msg_81_an_independent_client_for_nextcloud),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
    }
}

@Composable private fun LibraryScreen(vm: PhotoViewModel, appearance: String, onAppearance: (String)->Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val album by vm.album.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val sync by vm.repo.sync.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val photos=vm.timeline.collectAsLazyPagingItems()
    var selected by remember { mutableStateOf<Map<String,Media>>(emptyMap()) }
    var density by rememberSaveable { mutableIntStateOf(3) }
    var dialog by remember { mutableStateOf("") }
    var actionMedia by remember { mutableStateOf<Media?>(null) }
    var viewing by remember { mutableStateOf<Media?>(null) }
    var editing by remember { mutableStateOf<Media?>(null) }
    var vault by remember { mutableStateOf(false) }
    val snackbar=remember { SnackbarHostState() }
    val upload=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isNotEmpty()) vm.upload(it) }
    val context=LocalContext.current
    LaunchedEffect(message) { if(message.isNotBlank()) { snackbar.showSnackbar(message); vm.message.value="" } }
    LaunchedEffect(filters.album,filters.folder) { selected=emptyMap() }
    BackHandler(tab==0 && (album!=null || filters.folder.isNotEmpty() || selected.isNotEmpty())) { if(selected.isNotEmpty()) selected=emptyMap() else vm.reset() }
    if(vault) { VaultScreen(vm) { vault=false };return }
    Scaffold(snackbarHost={SnackbarHost(snackbar)},bottomBar={
        NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
            val tabs=listOf(tr(R.string.msg_82_photos) to Icons.Outlined.PhotoLibrary,tr(R.string.msg_83_albums) to Icons.Outlined.CollectionsBookmark,tr(R.string.msg_84_transfers) to Icons.Outlined.CloudUpload,tr(R.string.msg_85_settings) to Icons.Outlined.Tune)
            tabs.forEachIndexed { i,(label,icon) -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={BadgedBox(badge={if(i==2 && pending>0) Badge { Text("${pending}") }}) { Icon(icon,label) }},label={Text(label)}) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,top=20.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when(tab) {0->album?.name ?: if(filters.folder.isNotBlank()) PhotoRepository.name(filters.folder) else tr(R.string.msg_82_photos);1->tr(R.string.msg_83_albums);2->tr(R.string.msg_84_transfers);else->tr(R.string.msg_85_settings)},fontSize=32.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(when(tab) {0->if(sync.running) sync.message else tr(R.string.msg_86_1_s_cloud_moments, count);1->tr(R.string.msg_87_a_place_for_every_memory);2->tr(R.string.msg_88_every_memory_safely_stored);else->tr(R.string.msg_89_your_cloud_your_control)},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(tab==0) {
                    IconButton(onClick={upload.launch(arrayOf("image/*","video/*"))}) { Icon(Icons.Outlined.Add,tr(R.string.msg_90_upload_photos)) }
                    IconButton(onClick={vm.refresh(true)},enabled=!sync.running) { Icon(Icons.Outlined.Refresh,tr(R.string.msg_91_sync_again)) }
                }
                if(tab==1) IconButton(onClick={dialog="newAlbum"}) { Icon(Icons.Outlined.Add,tr(R.string.msg_92_new_album)) }
            }
            if(busy || sync.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            when(tab) {
                0 -> {
                    OutlinedTextField(filters.query,{vm.filters.value=filters.copy(query=it)},placeholder={Text(tr(R.string.msg_93_search_photos_and_albums))},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        FilterChip(filters.kind=="" && !filters.favorite && !filters.offline,onClick={vm.filters.value=filters.copy(kind="",favorite=false,offline=false)},label={Text(tr(R.string.msg_94_all))})
                        FilterChip(filters.kind=="video",onClick={vm.filters.value=filters.copy(kind=if(filters.kind=="video") "" else "video")},label={Text(tr(R.string.msg_95_videos))})
                        FilterChip(filters.favorite,onClick={vm.filters.value=filters.copy(favorite=!filters.favorite)},label={Text(tr(R.string.msg_96_favorites))})
                        FilterChip(filters.offline,onClick={vm.filters.value=filters.copy(offline=!filters.offline)},label={Text(tr(R.string.msg_97_offline))})
                        IconButton(onClick={dialog="date"}) { Icon(Icons.Outlined.CalendarMonth,tr(R.string.msg_98_jump_to_date)) }
                        IconButton(onClick={density=if(density==5) 2 else density+1}) { Icon(Icons.Outlined.GridView,tr(R.string.msg_99_change_grid_density)) }
                    }
                    if(album!=null || filters.folder.isNotEmpty() || filters.before!=Long.MAX_VALUE) Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick=vm::reset) { Text(tr(R.string.msg_100_back_to_all_photos)) }
                        if(album!=null) {
                            TextButton(onClick={dialog="renameAlbum"}) { Text(tr(R.string.msg_101_rename)) }
                            TextButton(onClick={vm.offlineAlbum(album!!)}) { Text(tr(R.string.msg_102_save_offline)) }
                        }
                    }
                    if(selected.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={selected=emptyMap()}) { Text(tr(R.string.msg_103_cancel_1_s, selected.size)) }
                        TextButton(onClick={dialog="addAlbum"}) { Text(tr(R.string.msg_104_add_to_album)) }
                        TextButton(onClick={vm.offline(selected.values.toList()); selected=emptyMap()}) { Text(tr(R.string.msg_97_offline)) }
                    }
                    if(filters.folder.isNotBlank()) {
                        val folders by vm.folders.collectAsStateWithLifecycle()
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(filters.folder != vm.repo.client().filesRoot) AssistChip(onClick={vm.browse(PhotoRepository.parent(filters.folder))},label={Text(tr(R.string.msg_105_parent_folder))})
                            folders.forEach { folder -> AssistChip(onClick={vm.browse(folder.href)},leadingIcon={Icon(Icons.Outlined.Folder,null)},label={Text(folder.name)}) }
                        }
                    }
                    if(photos.itemCount==0 && !sync.running) EmptyState(Icons.Outlined.PhotoLibrary,tr(R.string.msg_106_no_photos_here_yet),if(filters!=Filters()) tr(R.string.msg_107_try_changing_the_filters_or_syncing_again) else tr(R.string.msg_108_upload_your_first_photo_or_select_a_cloud_folder_in_set),Modifier.weight(1f))
                    else LazyVerticalGrid(columns=GridCells.Fixed(density),modifier=Modifier.weight(1f),contentPadding=PaddingValues(bottom=18.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        items(photos.itemCount,span={ i -> if(photos.peek(i) is TimelineItem.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { i ->
                            when(val row=photos[i]) {
                                is TimelineItem.Day -> Text(row.title,modifier=Modifier.padding(start=22.dp,top=18.dp,bottom=10.dp),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                                is TimelineItem.Photo -> {
                                    val media=row.media
                                    Box(Modifier.aspectRatio(1f).combinedClickable(onClick={ if(selected.isNotEmpty()) selected=if(selected.containsKey(media.id)) selected-media.id else selected+(media.id to media) else viewing=media },onLongClick={selected=selected+(media.id to media)})) {
                                        Thumbnail(vm,media,Modifier.fillMaxSize())
                                        if(media.mime.startsWith("video/")) Icon(Icons.Outlined.PlayCircle,tr(R.string.msg_95_videos),tint=Color.White,modifier=Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp))
                                        if(media.favorite) Icon(Icons.Outlined.Favorite,tr(R.string.msg_96_favorites),tint=Color.White,modifier=Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp))
                                        if(selected.containsKey(media.id)) { Box(Modifier.fillMaxSize().background(Blue.copy(alpha=.28f))); Icon(Icons.Outlined.CheckCircle,tr(R.string.msg_109_selected),tint=Color.White,modifier=Modifier.align(Alignment.TopEnd).padding(6.dp)) }
                                        if(media.offline.isNotBlank()) Icon(Icons.Outlined.OfflinePin,tr(R.string.msg_110_available_offline),tint=Color.White,modifier=Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp))
                                    }
                                }
                                null -> Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant))
                            }
                        }
                    }
                }
                1 -> AlbumsScreen(vm,encrypted={vault=true}) { vm.browse(vm.repo.client().filesRoot); tab=0 }
                2 -> TransfersScreen(vm)
                3 -> SettingsScreen(vm,appearance,onAppearance)
            }
        }
    }
    LaunchedEffect(album) { if(album!=null) tab=0 }
    if(dialog=="newAlbum" || dialog=="renameAlbum") TextEntryDialog(if(dialog=="newAlbum") tr(R.string.msg_92_new_album) else tr(R.string.msg_111_rename_album),tr(R.string.msg_112_album_name),if(dialog=="renameAlbum") album?.name.orEmpty() else "",{dialog=""}) { name -> if(dialog=="newAlbum") vm.newAlbum(name) else album?.let { vm.renameAlbum(it,name) }; dialog="" }
    if(dialog=="date") TextEntryDialog(tr(R.string.msg_98_jump_to_date),"YYYY-MM-DD",LocalDate.now().toString(),{dialog=""}) { value ->
        runCatching { vm.filters.value=filters.copy(before=LocalDate.parse(value).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()-1); dialog="" }.onFailure { vm.message.value=tr(R.string.msg_113_enter_a_date_as_yyyy_mm_dd) }
    }
    if(dialog=="addAlbum") AlbumPicker(vm,{dialog=""}) { value -> vm.addToAlbum(value,selected.values.toList()); selected=emptyMap(); dialog="" }
    viewing?.let { current ->
        val fullPhotos=vm.photos.collectAsLazyPagingItems()
        val position by produceState<Int?>(null,current.id) { value=vm.position(current) }
        position?.takeIf { it<fullPhotos.itemCount }?.let { Viewer(vm,fullPhotos,it,current,{viewing=null},{actionMedia=it},{editing=it}) }
    }
    actionMedia?.let { item -> MediaActions(vm,item,album,{actionMedia=null},{editing=item;actionMedia=null},{intent->context.startActivity(Intent.createChooser(intent,tr(R.string.msg_114_share_photo)))}) }
    editing?.let { Editor(vm,it) { editing=null } }
}

@Composable fun Thumbnail(vm: PhotoViewModel, media: Media, modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val model=if(media.offline.isNotBlank() && File(media.offline).exists() && !media.mime.startsWith("video/")) File(media.offline) else vm.repo.client().preview(media.fileId,media.etag)
    SubcomposeAsyncImage(model=ImageRequest.Builder(context).data(model).build(),imageLoader=LocalPhotosLoader.current,contentDescription=media.name,modifier=modifier,contentScale=ContentScale.Crop,
        loading={Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) { Icon(Icons.Outlined.Image,null,tint=MaterialTheme.colorScheme.outline) }},
        error={Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) { Icon(Icons.Outlined.BrokenImage,tr(R.string.msg_115_cannot_load_preview),tint=MaterialTheme.colorScheme.outline) }})
}

@Composable private fun AlbumsScreen(vm: PhotoViewModel, encrypted: ()->Unit, browse: ()->Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val error by vm.repo.albumError.collectAsStateWithLifecycle()
    LazyVerticalGrid(GridCells.Fixed(2),contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) { ElevatedCard(onClick=encrypted,modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Lock,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Column { Text(tr(R.string.msg_116_encrypted_albums),fontWeight=FontWeight.SemiBold);Text(tr(R.string.msg_117_password_or_fingerprint_unlock_locks_on_exit),style=MaterialTheme.typography.bodySmall) } } } }
        item(span={GridItemSpan(maxLineSpan)}) { ElevatedCard(onClick=browse,modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Folder,null,tint=MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Column { Text(tr(R.string.msg_118_cloud_folders),fontWeight=FontWeight.SemiBold); Text(tr(R.string.msg_119_browse_photos_by_their_original_location),style=MaterialTheme.typography.bodySmall) } } } }
        if(error.isNotBlank()) item(span={GridItemSpan(maxLineSpan)}) { Text(error,color=MaterialTheme.colorScheme.error) }
        items(albums,key={it.href}) { album -> Column(Modifier.clickable { vm.openAlbum(album) }) {
            Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().aspectRatio(1f)) {
                if(album.cover.isNotBlank() && album.cover!="0") SubcomposeAsyncImage(model=vm.repo.client().preview(album.cover,""),imageLoader=LocalPhotosLoader.current,contentDescription=album.name,contentScale=ContentScale.Crop,error={Box(contentAlignment=Alignment.Center) { Icon(Icons.Outlined.Collections,null,Modifier.size(40.dp)) }})
                else Box(contentAlignment=Alignment.Center) { Icon(Icons.Outlined.Collections,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary) }
            }
            Text(album.name,modifier=Modifier.padding(top=10.dp),fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(tr(R.string.msg_120_1_s_items, album.count),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if(albums.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { EmptyState(Icons.Outlined.Collections,tr(R.string.msg_121_create_your_first_album),tr(R.string.msg_122_albums_sync_with_nextcloud_photos)) }
    }
}

@Composable private fun TransfersScreen(vm: PhotoViewModel) {
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val historyCount by vm.transferHistoryCount.collectAsStateWithLifecycle()
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primaryContainer) { Text(if(vm.repo.prefs.getBoolean("wifi",true)) tr(R.string.msg_123_transfers_use_wi_fi_or_other_unmetered_networks_backgro) else tr(R.string.msg_124_mobile_data_is_allowed_for_transfers),modifier=Modifier.padding(18.dp),style=MaterialTheme.typography.bodyMedium) } }
        item {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                    TextButton(enabled=historyCount>0,onClick={vm.action { vm.repo.dao.clearTransferHistory() }}) { Text(tr(R.string.msg_125_clear_history)) }
                }
                Text(tr(R.string.msg_126_clears_completed_failed_and_cancelled_records_keeps_que),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(transfers.isEmpty()) item { EmptyState(Icons.Outlined.CloudDone,tr(R.string.msg_127_your_memories_are_safe),tr(R.string.msg_128_upload_and_offline_download_progress_appears_here)) }
        items(transfers,key={it.id}) { item -> Card { Column(Modifier.padding(16.dp)) {
            Text(item.name,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text((if(item.kind=="DOWNLOAD") tr(R.string.msg_129_offline_download) else tr(R.string.msg_130_upload))+when(item.state) {"DONE"->tr(R.string.msg_131_completed);"RUNNING"->tr(R.string.msg_132_transferring);"ERROR"->tr(R.string.msg_133_needs_attention);"CANCELLED"->tr(R.string.msg_40_cancelled);else->tr(R.string.msg_134_waiting_to_start)},style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
            if(item.state=="RUNNING") LinearProgressIndicator(progress={if(item.size>0) (item.progress.toFloat()/item.size).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth())
            if(item.error.isNotBlank()) Text(item.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Row { if(item.state in listOf("ERROR","CANCELLED")) TextButton(onClick={vm.action { vm.queue.retry(item) }}) { Text(tr(R.string.msg_135_retry)) }; if(item.state in listOf("RUNNING","QUEUED")) TextButton(onClick={vm.action { vm.queue.cancel(item) }}) { Text(tr(R.string.msg_71_cancel)) } }
        } } }
    }
}

@Composable fun EmptyState(icon: ImageVector,title: String,body: String,modifier: Modifier=Modifier) {
    Column(modifier.fillMaxWidth().padding(36.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        Icon(icon,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.outline)
        Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=18.dp,bottom=8.dp))
        Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun TextEntryDialog(title: String,label: String,initial: String="",dismiss: ()->Unit,confirm: (String)->Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={OutlinedTextField(value,{value=it},label={Text(label)},singleLine=true)},confirmButton={TextButton(onClick={confirm(value)},enabled=value.isNotBlank()) { Text(tr(R.string.msg_136_ok)) }},dismissButton={TextButton(onClick=dismiss) { Text(tr(R.string.msg_71_cancel)) }})
}
@Composable fun AlbumPicker(vm: PhotoViewModel,dismiss: ()->Unit,selected: (Album)->Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest=dismiss,title={Text(tr(R.string.msg_104_add_to_album))},text={LazyColumn { if(albums.isEmpty()) item { Text(tr(R.string.msg_137_create_an_album_in_the_albums_tab_first)) }; items(albums) { album -> TextButton(onClick={selected(album)}) { Text(album.name) } } }},confirmButton={TextButton(onClick=dismiss) { Text(tr(R.string.msg_138_close)) }})
}
fun bytes(value: Long): String = when { value<0 -> tr(R.string.msg_139_not_available); value>=1024L*1024*1024 -> "%.1f GB".format(value/(1024.0*1024*1024)); value>=1024*1024 -> "%.1f MB".format(value/(1024.0*1024)); else -> "${value/1024} KB" }
