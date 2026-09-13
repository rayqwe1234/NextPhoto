package app.nextcloudphoto.data

import androidx.room.*
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index("parent"), Index(value = ["taken", "id"]), Index("name")])
data class Media(
    @PrimaryKey val id: String,
    val fileId: String,
    val href: String,
    val name: String,
    val parent: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val taken: Long,
    val etag: String,
    val favorite: Boolean = false,
    val permissions: String = "",
    val preview: Boolean = true,
    val offline: String = "",
    val offlineEtag: String = "",
    val seen: Long = 0
)
@Entity data class Folder(@PrimaryKey val href: String, val name: String, val parent: String, val etag: String)
@Entity data class Album(@PrimaryKey val href: String, val name: String, val count: Int, val cover: String)
@Entity(primaryKeys = ["album", "media"], indices = [Index("media")])
data class Membership(val album: String, val media: String, val href: String, val seen: Long = 0)
@Entity(indices = [Index(value = ["sourceKey"], unique = true)])
data class Transfer(
    @PrimaryKey val id: String,
    val source: String,
    val sourceKey: String,
    val destination: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val state: String = "QUEUED",
    val progress: Long = 0,
    val error: String = "",
    val kind: String = "UPLOAD",
    val mediaId: String = "",
    val checksum: String = "",
    val created: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false
)

@Dao interface PhotoDao {
    @Query("""SELECT * FROM Media WHERE
        (:q = '' OR instr(lower(name), lower(:q)) > 0 OR id IN
          (SELECT media FROM Membership INNER JOIN Album ON Album.href=Membership.album WHERE instr(lower(Album.name),lower(:q)) > 0))
        AND (:kind = '' OR mime LIKE :kind || '/%') AND (:favorite = 0 OR favorite=1)
        AND (:offline = 0 OR offline != '') AND (:folder = '' OR parent=:folder)
        AND (:album = '' OR id IN (SELECT media FROM Membership WHERE album=:album))
        AND (:scope = '' OR instr(href, :scope || '/') = 1 OR :album != '')
        AND taken <= :before ORDER BY taken DESC, id DESC""")
    fun page(q: String, kind: String, favorite: Boolean, offline: Boolean, folder: String, album: String, before: Long, scope: String): PagingSource<Int, Media>
    @Query("""SELECT COUNT(*) FROM Media WHERE
        (:q = '' OR instr(lower(name), lower(:q)) > 0 OR id IN
          (SELECT media FROM Membership INNER JOIN Album ON Album.href=Membership.album WHERE instr(lower(Album.name),lower(:q)) > 0))
        AND (:kind = '' OR mime LIKE :kind || '/%') AND (:favorite = 0 OR favorite=1)
        AND (:offline = 0 OR offline != '') AND (:folder = '' OR parent=:folder)
        AND (:album = '' OR id IN (SELECT media FROM Membership WHERE album=:album))
        AND (:scope = '' OR instr(href, :scope || '/') = 1 OR :album != '') AND taken <= :before
        AND (taken > :taken OR (taken=:taken AND id > :id))""")
    suspend fun position(q: String, kind: String, favorite: Boolean, offline: Boolean, folder: String, album: String, before: Long, scope: String, taken: Long, id: String): Int
    @Query("SELECT COUNT(*) FROM Media") fun count(): Flow<Int>
    @Query("SELECT * FROM Media WHERE id=:id") suspend fun media(id: String): Media?
    @Upsert suspend fun saveMedia(items: List<Media>)
    @Query("UPDATE Media SET offline=:path, offlineEtag=:etag WHERE id=:id") suspend fun offline(id: String, path: String, etag: String)
    @Query("UPDATE Media SET favorite=:value WHERE id=:id") suspend fun favorite(id: String, value: Boolean)
    @Query("UPDATE Media SET taken=:taken WHERE id=:id") suspend fun taken(id: String, taken: Long)
    @Query("DELETE FROM Media WHERE id=:id") suspend fun delete(id: String)
    @Query("SELECT * FROM Media WHERE parent=:parent AND seen != :seen") suspend fun missing(parent: String, seen: Long): List<Media>
    @Query("SELECT * FROM Folder WHERE href=:href") suspend fun folder(href: String): Folder?
    @Query("SELECT * FROM Folder WHERE parent=:parent ORDER BY name") fun folders(parent: String): Flow<List<Folder>>
    @Query("SELECT * FROM Folder WHERE parent=:parent") suspend fun childFolders(parent: String): List<Folder>
    @Query("DELETE FROM Folder") suspend fun clearFolders()
    @Query("DELETE FROM Media WHERE instr(href,:folder || '/')=1") suspend fun removeFolderMedia(folder: String)
    @Query("DELETE FROM Folder WHERE href=:folder OR instr(href,:folder || '/')=1") suspend fun removeFolder(folder: String)
    @Upsert suspend fun folder(folder: Folder)
    @Query("SELECT * FROM Album ORDER BY name") fun albums(): Flow<List<Album>>
    @Query("SELECT * FROM Album ORDER BY name") suspend fun allAlbums(): List<Album>
    @Upsert suspend fun albums(albums: List<Album>)
    @Query("DELETE FROM Album") suspend fun clearAlbums()
    @Query("DELETE FROM Membership WHERE album=:album") suspend fun clearMembers(album: String)
    @Query("DELETE FROM Membership WHERE album=:album AND seen != :seen") suspend fun pruneMembers(album: String, seen: Long)
    @Query("SELECT * FROM Membership WHERE album=:album AND media=:media") suspend fun member(album: String, media: String): Membership?
    @Upsert suspend fun members(members: List<Membership>)
    @Query("DELETE FROM Membership WHERE media=:id") suspend fun removeMemberships(id: String)
    @Query("SELECT Media.* FROM Media INNER JOIN Membership ON Media.id=Membership.media WHERE album=:album LIMIT :limit OFFSET :offset") suspend fun albumBatch(album: String, limit: Int, offset: Int): List<Media>
    @Query("SELECT * FROM Transfer WHERE hidden=0 ORDER BY created DESC LIMIT 200") fun transfers(): Flow<List<Transfer>>
    @Query("UPDATE Transfer SET hidden=1 WHERE state IN ('DONE','ERROR','CANCELLED') AND hidden=0")
    suspend fun clearTransferHistory()
    @Query("SELECT COUNT(*) FROM Transfer WHERE hidden=0 AND state IN ('DONE','ERROR','CANCELLED')")
    fun transferHistoryCount(): Flow<Int>
    @Query("SELECT * FROM Transfer WHERE id=:id") suspend fun transfer(id: String): Transfer?
    @Query("SELECT * FROM Transfer WHERE sourceKey=:key") suspend fun transferForSource(key: String): Transfer?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTransfer(item: Transfer): Long
    @Update suspend fun transfer(item: Transfer)
    @Query("SELECT * FROM Transfer WHERE state IN ('QUEUED','RUNNING') ORDER BY created LIMIT 1") suspend fun nextTransfer(): Transfer?
    @Query("UPDATE Transfer SET state=:state, progress=:progress, error=:error WHERE id=:id AND state != 'CANCELLED'") suspend fun status(id: String, state: String, progress: Long, error: String = "")
    @Query("SELECT COUNT(*) FROM Transfer WHERE state IN ('QUEUED','RUNNING')") fun pending(): Flow<Int>
    @Query("SELECT * FROM Media WHERE offline != ''") suspend fun offlineItems(): List<Media>
}

@Database(entities = [Media::class, Folder::class, Album::class, Membership::class, Transfer::class], version = 2, exportSchema = true)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun dao(): PhotoDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Transfer ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
