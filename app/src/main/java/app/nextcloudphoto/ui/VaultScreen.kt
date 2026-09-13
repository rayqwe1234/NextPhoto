@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nextcloudphoto.vault.*
import kotlinx.coroutines.*
import javax.crypto.AEADBadTagException

private tailrec fun Context.activity(): Activity? = when(this) { is Activity -> this;is ContextWrapper -> baseContext.activity();else -> null }

@Composable fun VaultScreen(vm: PhotoViewModel, exit: ()->Unit) {
    val context=LocalContext.current
    val activity=context.activity()
    val owner=LocalLifecycleOwner.current
    val store=remember { VaultStore(context,vm.repo.client()) }
    val scope=rememberCoroutineScope()
    var ids by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<VaultSession?>(null) }
    var title by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<Job?>(null) }
    var selectedVideo by remember { mutableStateOf<VaultVideo?>(null) }
    var closeVideo by remember { mutableStateOf<()->Unit>({}) }
    var selectedPhoto by remember { mutableStateOf<Pair<String,Bitmap>?>(null) }
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pickingCloud by remember { mutableStateOf(false) }
    var bioEnabled by remember { mutableStateOf(false) }
    var bioReason by remember { mutableStateOf("") }
    fun refreshBiometrics() {
        val biometrics=chosen?.let { VaultBiometrics(context,vm.repo.client().account!!.key,it) }
        bioEnabled=biometrics?.enabled() ?: false
        bioReason=biometrics?.unavailableReason().orEmpty()
    }
    fun lock() {
        work?.cancel();work=null;busy=false
        store.cancelRequests()
        closeVideo()
        pickingCloud=false;selectedVideo=null;selectedPhoto=null;entries=emptyList();title="";password="";confirmation="";name="";message=""
        session?.close();session=null
    }
    fun leave() { lock();pending=emptyList();exit() }
    fun task(block: suspend ()->Unit) {
        if(busy) return
        busy=true;message=""
        work=scope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: AEADBadTagException) { currentCoroutineContext().ensureActive();message=tr(R.string.msg_191_incorrect_password_or_damaged_encrypted_data_the_album) }
            catch(e: Exception) { currentCoroutineContext().ensureActive();message=e.message ?: tr(R.string.msg_140_operation_failed_please_try_again) }
            finally { if(currentCoroutineContext().isActive) { busy=false;work=null } }
        }
    }
    val latestLock by rememberUpdatedState(::lock)
    val latestRefreshBiometrics by rememberUpdatedState(::refreshBiometrics)
    DisposableEffect(owner,activity) {
        val wasSecure=activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) latestLock() else if(event==Lifecycle.Event.ON_RESUME) latestRefreshBiometrics() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            latestLock();owner.lifecycle.removeObserver(observer)
            if(!wasSecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    LaunchedEffect(Unit) { task { ids=store.list() } }
    LaunchedEffect(chosen) { refreshBiometrics() }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { values ->
        pending=values;message=if(values.isNotEmpty()) tr(R.string.msg_192_1_s_items_selected_unlock_then_tap_encrypt_and_upload, values.size) else ""
    }
    fun back() {
        if(pickingCloud) pickingCloud=false
        else if(selectedVideo!=null) { closeVideo();selectedVideo=null }
        else if(selectedPhoto!=null) { selectedPhoto?.second?.let { session?.release(it) };selectedPhoto=null }
        else if(chosen!=null || creating) { lock();chosen=null;creating=false;pending=emptyList() }
        else leave()
    }
    BackHandler(onBack=::back)
    Scaffold(topBar={TopAppBar(title={Text(if(session!=null) title else tr(R.string.msg_116_encrypted_albums))},navigationIcon={IconButton(onClick=::back) { Icon(Icons.Outlined.ArrowBack,tr(R.string.msg_193_back_and_lock)) }},actions={if(session!=null) TextButton(onClick={lock()}) { Text(tr(R.string.msg_194_lock)) }})}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(message.isNotEmpty()) Text(message,color=MaterialTheme.colorScheme.primary)
            val active=session
            val photo=selectedPhoto
            val video=selectedVideo
            when {
                active!=null && video!=null -> {
                    Text(video.name)
                    VaultVideoPlayer(store,active,video,Modifier.weight(1f).fillMaxWidth()) { closeVideo=it }
                }
                active!=null && pickingCloud -> {
                    Text(tr(R.string.msg_195_select_cloud_photos_or_videos_to_create_encrypted_copie),style=MaterialTheme.typography.bodySmall)
                    val cloud=vm.photos.collectAsLazyPagingItems()
                    LazyColumn(modifier=Modifier.weight(1f)) {
                        items(cloud.itemCount) { index -> cloud[index]?.takeIf { it.mime.startsWith("image/") || it.mime.startsWith("video/") }?.let { media ->
                            TextButton(enabled=!busy,onClick={task {
                                store.importCloudPhoto(active,media) { message=tr(R.string.msg_196_encrypting_and_uploading_1_s, bytes(it)) }
                                entries=store.entries(active);pickingCloud=false;message=tr(R.string.msg_197_encrypted_copy_created_the_cloud_original_is_kept)
                            }}) { Text(media.name) }
                        } }
                    }
                }
                active!=null && photo!=null -> {
                    Text(photo.first)
                    var scale by remember(photo) { mutableFloatStateOf(1f) }
                    var translation by remember(photo) { mutableStateOf(Offset.Zero) }
                    Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface).pointerInput(photo) {
                        detectTransformGestures { _,pan,zoom,_ -> scale=(scale*zoom).coerceIn(1f,5f);translation=if(scale==1f) Offset.Zero else translation+pan }
                    },contentAlignment=Alignment.Center) {
                        Image(photo.second.asImageBitmap(),tr(R.string.msg_198_encrypted_photo),Modifier.fillMaxSize().graphicsLayer { scaleX=scale;scaleY=scale;translationX=translation.x;translationY=translation.y },contentScale=ContentScale.Fit)
                    }
                }
                active!=null -> {
                    Text(tr(R.string.msg_199_locks_on_exit_or_in_the_background_decrypted_content_is),style=MaterialTheme.typography.bodySmall)
                    val biometrics=remember(active) { VaultBiometrics(context,vm.repo.client().account!!.key,active.id) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if(bioEnabled) tr(R.string.msg_200_fingerprint_unlock_is_enabled_for_this_album) else tr(R.string.msg_201_use_fingerprint_next_time),style=MaterialTheme.typography.titleSmall)
                            if(bioReason.isNotEmpty()) Text(bioReason,style=MaterialTheme.typography.bodySmall)
                            Button(enabled=!busy && activity!=null,onClick={task {
                                if(bioEnabled) { biometrics.disable();bioEnabled=false }
                                else {
                                    bioReason=biometrics.unavailableReason()
                                    check(bioReason.isEmpty()) { bioReason }
                                    biometrics.authenticate(activity!!,active.header,active).fill(0);bioEnabled=true
                                    message=tr(R.string.msg_202_fingerprint_unlock_enabled_use_the_fingerprint_button_n)
                                }
                            }}) { Icon(Icons.Outlined.Fingerprint,null);Spacer(Modifier.width(8.dp));Text(if(bioEnabled) tr(R.string.msg_203_disable_fingerprint_unlock) else tr(R.string.msg_204_enable_fingerprint_unlock)) }
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=!busy,onClick={ lock();picker.launch(arrayOf("image/*","video/*")) }) { Text(tr(R.string.msg_205_choose_phone_photos_videos)) }
                        if(pending.isNotEmpty()) Button(enabled=!busy,onClick={task {
                            var uploaded=0
                            while(pending.isNotEmpty()) {
                                store.importPhoto(active,pending.first()) { message=tr(R.string.msg_196_encrypting_and_uploading_1_s, bytes(it)) };currentCoroutineContext().ensureActive()
                                pending=pending.drop(1);uploaded++
                                message=tr(R.string.msg_206_1_s_items_encrypted_and_uploaded, uploaded)
                            }
                            entries=store.entries(active)
                        }}) { Text(tr(R.string.msg_207_encrypt_and_upload_1_s, pending.size)) }
                    }
                    TextButton(enabled=!busy,onClick={pickingCloud=true}) { Text(tr(R.string.msg_208_add_cloud_photos_videos)) }
                    if(entries.isEmpty() && !busy) Text(tr(R.string.msg_209_no_photos_or_videos_yet_import_creates_encrypted_copies))
                    LazyVerticalGrid(GridCells.Adaptive(100.dp),modifier=Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(entries,key={it}) { id -> VaultThumbnail(store,active,id,!busy) { task {
                            if(id.endsWith(".ncv")) {
                                val loaded=store.video(active,id)
                                currentCoroutineContext().ensureActive();check(!active.closed);selectedVideo=loaded
                            } else {
                                val loaded=store.photo(active,id)
                                currentCoroutineContext().ensureActive();check(!active.closed);selectedPhoto=loaded
                            }
                        } } }
                    }
                }
                creating || chosen!=null -> {
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.weight(1f)) {
                        item { Text(if(creating) tr(R.string.msg_210_create_an_encrypted_cloud_album) else tr(R.string.msg_211_album_locked_1_s, chosen!!.take(8)),style=MaterialTheme.typography.titleLarge) }
                        if(!creating) item { OutlinedButton(modifier=Modifier.fillMaxWidth(),enabled=!busy && activity!=null,onClick={task {
                            refreshBiometrics()
                            check(bioReason.isEmpty()) { bioReason }
                            if(!bioEnabled) {
                                message=tr(R.string.msg_212_unlock_with_your_album_password_first_then_tap_enable_f)
                                return@task
                            }
                            val id=chosen!!;val header=store.header(id)
                            val key=VaultBiometrics(context,vm.repo.client().account!!.key,id).authenticate(activity!!,header)
                            var candidate: VaultSession?=null
                            try {
                                require(key.size==32);candidate=VaultSession(id,header,key)
                                val albumTitle=candidate.title();val items=store.entries(candidate)
                                currentCoroutineContext().ensureActive();session=candidate;title=albumTitle;entries=items;candidate=null
                            } finally { candidate?.close();if(session==null) key.fill(0) }
                        }}) { Icon(Icons.Outlined.Fingerprint,null);Spacer(Modifier.width(8.dp));Text(tr(R.string.msg_213_fingerprint_unlock)) }
                            Text(if(bioEnabled) tr(R.string.msg_214_enabled_for_this_album_you_can_unlock_with_your_fingerp) else tr(R.string.msg_215_first_time_setup_unlock_with_your_album_password_then_t),style=MaterialTheme.typography.bodySmall)
                            if(bioReason.isNotEmpty()) Text(bioReason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                        }
                        if(creating) item { OutlinedTextField(name,{name=it.take(100)},label={Text(tr(R.string.msg_112_album_name))},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth()) }
                        item { OutlinedTextField(password,{password=it.take(256)},label={Text(tr(R.string.msg_216_album_password))},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth()) }
                        if(creating) item {
                            OutlinedTextField(confirmation,{confirmation=it.take(256)},label={Text(tr(R.string.msg_217_confirm_password))},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                            Text(tr(R.string.msg_218_at_least_12_characters_keep_your_password_safe_photos_c),style=MaterialTheme.typography.bodySmall)
                        }
                        item { Button(enabled=!busy && if(creating) name.isNotBlank() && password.length>=12 && password==confirmation else password.isNotEmpty(),onClick={
                            val secret=password.toCharArray();password="";confirmation=""
                            task {
                                try {
                                    if(creating) { chosen=store.create(name,secret);creating=false;name="";ids=store.list();message=tr(R.string.msg_219_encrypted_album_created_enter_your_password_to_unlock) }
                                    else {
                                        val id=chosen!!;val header=store.header(id)
                                        var candidate: VaultSession?=null
                                        try {
                                            withContext(Dispatchers.Default) { candidate=VaultSession(id,header,VaultCrypto.unlock(header,id,secret)) }
                                            currentCoroutineContext().ensureActive();val opened=candidate!!
                                            val albumTitle=opened.title();val items=store.entries(opened)
                                            session=opened;title=albumTitle;entries=items;candidate=null
                                        } finally { candidate?.close() }
                                    }
                                } finally { secret.fill('\u0000') }
                            }
                        }) { Text(if(creating) tr(R.string.msg_220_create_encrypted_album) else tr(R.string.msg_221_unlock_with_password)) } }

                    }
                }
                else -> {
                    Text(tr(R.string.msg_222_photos_videos_filenames_and_album_names_are_encrypted_b),style=MaterialTheme.typography.bodyMedium)
                    Row {
                        Button(enabled=!busy,onClick={creating=true}) { Text(tr(R.string.msg_223_new_encrypted_album)) }
                        TextButton(enabled=!busy,onClick={task { ids=store.list() }}) { Text(tr(R.string.msg_224_refresh)) }
                    }
                    if(ids.isEmpty() && !busy) Text(tr(R.string.msg_225_no_encrypted_albums_yet))
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) { items(ids,key={it}) { id ->
                        OutlinedCard(onClick={chosen=id},modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock,null);Spacer(Modifier.width(16.dp));Text(tr(R.string.msg_226_locked_album_1_s, id.take(8)))
                        } }
                    } }
                }
            }
        }
    }
}

@Composable private fun VaultThumbnail(store: VaultStore, session: VaultSession, id: String, enabled: Boolean, open: ()->Unit) {
    if(id.endsWith(".ncv")) {
        Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled=enabled,onClick=open),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Outlined.PlayCircle,tr(R.string.msg_227_play_encrypted_video),Modifier.size(40.dp));Text(tr(R.string.msg_95_videos)) }
        }
        return
    }
    var bitmap by remember(session,id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(session,id) { mutableStateOf(false) }
    LaunchedEffect(session,id) {
        var loaded: Bitmap?=null
        try { loaded=store.thumbnail(session,id);currentCoroutineContext().ensureActive();bitmap=loaded }
        catch(e: CancellationException) { loaded?.let(session::release);throw e }
        catch(_: Exception) { failed=true }
    }
    DisposableEffect(session,id) { onDispose { bitmap?.let(session::release);bitmap=null } }
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled=enabled,onClick=open),contentAlignment=Alignment.Center) {
        bitmap?.takeUnless { it.isRecycled }?.let { Image(it.asImageBitmap(),tr(R.string.msg_198_encrypted_photo),Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
            ?: Icon(if(failed) Icons.Outlined.BrokenImage else Icons.Outlined.Lock,if(failed) tr(R.string.msg_228_preview_failed_tap_to_try_the_original) else tr(R.string.msg_229_decrypting_preview))
    }
}
