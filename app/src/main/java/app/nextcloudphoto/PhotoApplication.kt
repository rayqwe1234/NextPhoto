package app.nextcloudphoto

import android.app.Application
import androidx.room.Room
import app.nextcloudphoto.account.AccountStore
import app.nextcloudphoto.account.CredentialStore
import app.nextcloudphoto.data.PhotoDatabase
import app.nextcloudphoto.data.PhotoRepository
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import java.io.File

open class PhotoApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        app.nextcloudphoto.i18n.Localization.initialize(this)
    }
    open val accounts: CredentialStore by lazy { AccountStore(this) }
    val database by lazy { Room.databaseBuilder(this, PhotoDatabase::class.java, "photos.db").addMigrations(PhotoDatabase.MIGRATION_1_2).build() }
    val repository by lazy { PhotoRepository(this, database, accounts) }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache { DiskCache.Builder().directory(File(cacheDir,"thumbnails")).maxSizeBytes(1024L*1024*1024).build() }
        .crossfade(true).build()
}
