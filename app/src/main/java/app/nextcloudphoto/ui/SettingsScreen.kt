package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*
import app.nextcloudphoto.transfer.BackupWorker
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.sync.withLock

@Composable fun SettingsScreen(vm: PhotoViewModel, appearance: String, onAppearance: (String)->Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val info by vm.repo.server.collectAsStateWithLifecycle()
    val sync by vm.repo.sync.collectAsStateWithLifecycle()
    val prefs=vm.repo.prefs
    var backup by remember { mutableStateOf(prefs.getBoolean("backup",false)) }
    var tree by remember { mutableStateOf(prefs.getString("backupTree","")!!) }
    var destination by remember { mutableStateOf(prefs.getString("uploadRoot","Photos/手機備份")!!) }
    var root by remember { mutableStateOf(prefs.getString("scanRoot","")!!) }
    var wifi by remember { mutableStateOf(prefs.getBoolean("wifi",true)) }
    var logout by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf(tr(R.string.msg_152_calculating)) }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickTree=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) {
            runCatching { vm.app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                tree=uri.toString(); prefs.edit().putString("backupTree",tree).apply()
            }.onFailure { vm.message.value=tr(R.string.msg_153_cannot_retain_folder_access_please_select_it_again) }
        }
    }
    LaunchedEffect(Unit) { vm.action {
        fun size(file: File)=if(file.exists()) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        storage=tr(R.string.msg_154_thumbnails_1_s_offline_2_s, bytes(size(File(vm.app.cacheDir,"thumbnails"))), bytes(size(File(vm.app.filesDir,"offline"))))
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SettingsCard(tr(R.string.msg_155_my_nextcloud)) {
            Text(account?.server.orEmpty(),style=MaterialTheme.typography.bodyMedium)
            Text("${account?.user} · Nextcloud ${info?.version ?: tr(R.string.msg_156_waiting_for_connection)}",style=MaterialTheme.typography.bodySmall)
            Text(tr(R.string.msg_157_used_1_s_2_s, bytes(info?.quotaUsed ?: 0), bytes(info?.quotaTotal ?: -1)))
            if((info?.quotaTotal ?: 0)>0) LinearProgressIndicator(progress={(info!!.quotaUsed.toFloat()/info!!.quotaTotal).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            Text(sync.message,style=MaterialTheme.typography.bodySmall)
            Text(tr(R.string.msg_158_last_sync_1_s, dateTime(sync.last)),style=MaterialTheme.typography.bodySmall)
        }
        SettingsCard(tr(R.string.msg_159_photo_library)) {
            OutlinedTextField(root,{root=it},label={Text(tr(R.string.msg_160_cloud_folder_to_scan_blank_for_all))},placeholder={Text("Photos")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Button(onClick={vm.action {
                root.split('/').filter { it.isNotBlank() }.forEach { app.nextcloudphoto.network.validName(it) }
                vm.repo.lock.withLock {
                    prefs.edit().putString("scanRoot",root.trim('/')).commit()
                    vm.repo.dao.clearFolders()
                }
                vm.reset()
                vm.repo.refresh(true)
            }}) { Text(tr(R.string.msg_161_apply_and_reindex)) }
            Text(tr(R.string.msg_162_changing_the_scan_scope_rebuilds_the_local_index_cloud),style=MaterialTheme.typography.bodySmall)
        }
        SettingsCard(tr(R.string.msg_163_uploads_and_auto_backup)) {
            OutlinedTextField(destination,{destination=it},label={Text(tr(R.string.msg_164_cloud_upload_destination_folder))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            TextButton(onClick={vm.action { destination.split('/').filter { it.isNotBlank() }.forEach { app.nextcloudphoto.network.validName(it) }; prefs.edit().putString("uploadRoot",destination.trim('/')).commit(); vm.message.value=tr(R.string.msg_165_upload_location_saved) }}) { Text(tr(R.string.msg_166_save_upload_location)) }
            Text(tr(R.string.msg_168_backup_source_1_s, if(tree.isEmpty()) tr(R.string.msg_167_not_selected) else android.net.Uri.decode(tree.substringAfterLast('/'))),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={pickTree.launch(null)}) { Text(tr(R.string.msg_169_choose_a_phone_backup_folder)) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(tr(R.string.msg_170_auto_backup),Modifier.weight(1f)); Switch(backup,onCheckedChange={enabled->
                if(enabled && tree.isEmpty()) vm.message.value=tr(R.string.msg_171_select_a_source_folder_on_your_phone_first)
                else { backup=enabled; prefs.edit().putBoolean("backup",enabled).apply(); vm.queue.backupSchedule(enabled); if(enabled && Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(tr(R.string.msg_172_wi_fi_unmetered_networks_only),Modifier.weight(1f)); Switch(wifi,onCheckedChange={wifi=it; prefs.edit().putBoolean("wifi",it).apply(); vm.queue.backupSchedule(backup); vm.queue.schedule(true)}) }
            Text(tr(R.string.msg_173_one_way_upload_organized_by_year_and_month_deleting_on),style=MaterialTheme.typography.bodySmall)
            Text(tr(R.string.msg_174_last_backup_scan_1_s, dateTime(prefs.getLong("lastBackup",0))),style=MaterialTheme.typography.bodySmall)
            prefs.getString("backupError","")?.takeIf { it.isNotBlank() }?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick={WorkManager.getInstance(vm.app).enqueueUniqueWork("backup-now",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<BackupWorker>().build()); vm.message.value=tr(R.string.msg_175_backup_scan_scheduled)},enabled=backup) { Text(tr(R.string.msg_176_scan_for_backups_now)) }
        }
        SettingsCard(tr(R.string.msg_177_appearance_and_storage)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("system" to tr(R.string.msg_178_system),"light" to tr(R.string.msg_179_light),"dark" to tr(R.string.msg_180_dark)).forEach { (key,label)->FilterChip(appearance==key,onClick={onAppearance(key)},label={Text(label)}) } }
            Text(storage,style=MaterialTheme.typography.bodySmall)
            Text(tr(R.string.msg_181_thumbnail_cache_limit_1_gb_offline_originals_are_counte),style=MaterialTheme.typography.bodySmall)
            val loader=LocalPhotosLoader.current
            TextButton(onClick={vm.action { loader.memoryCache?.clear(); loader.diskCache?.clear(); vm.message.value=tr(R.string.msg_182_thumbnail_cache_cleared) }}) { Text(tr(R.string.msg_183_clear_thumbnail_cache)) }
        }
        SettingsCard(tr(R.string.msg_184_about)) {
            Text("NextPhoto ${app.nextcloudphoto.BuildConfig.VERSION_NAME}")
            Text(tr(R.string.msg_185_no_ads_paid_features_or_third_party_analytics_nan_indep),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={logout=true}) { Text(tr(R.string.msg_186_sign_out_and_clear_local_data),color=MaterialTheme.colorScheme.error) }
        }
    }
    if(logout) AlertDialog(onDismissRequest={logout=false},title={Text(tr(R.string.msg_187_sign_out_of_nextcloud))},text={Text(tr(R.string.msg_188_stops_transfers_and_clears_credentials_the_local_index))},confirmButton={TextButton(onClick={logout=false;vm.logout()}) { Text(tr(R.string.msg_189_sign_out)) }},dismissButton={TextButton(onClick={logout=false}) { Text(tr(R.string.msg_71_cancel)) }})
}
@Composable private fun SettingsCard(title: String,content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text(title,style=MaterialTheme.typography.titleMedium); content() } }
}
fun dateTime(value: Long): String = if(value<=0) tr(R.string.msg_190_not_run_yet) else DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
