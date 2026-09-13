package app.nextcloudphoto

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nextcloudphoto.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DatabaseTest {
    private fun media(index: Int)=Media("account:$index","$index","https://cloud/remote.php/dav/files/ray/Photos/$index.jpg","photo-$index.jpg","https://cloud/remote.php/dav/files/ray/Photos","image/jpeg",100,100,index.toLong(),"etag")
    @Test fun `one hundred thousand media stay paged and ordered`() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhotoDatabase::class.java).build()
        try {
            val start=System.nanoTime()
            repeat(100) { batch -> db.dao().saveMedia((batch*1000 until (batch+1)*1000).map(::media)) }
            val source=db.dao().page("","",false,false,"","",Long.MAX_VALUE,"")
            val result=source.load(PagingSource.LoadParams.Refresh(null,90,true)) as PagingSource.LoadResult.Page
            assertEquals(90,result.data.size)
            assertEquals("account:99999",result.data.first().id)
            assertEquals(99910,result.itemsAfter)
            val second=source.load(PagingSource.LoadParams.Append(result.nextKey!!,90,true)) as PagingSource.LoadResult.Page
            assertEquals("account:99909",second.data.first().id)
            println("100000 records and two pages: ${(System.nanoTime()-start)/1000000} ms")
        } finally { db.close() }
    }
    @Test fun `renaming preserves identity and album removal preserves original`() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhotoDatabase::class.java).build()
        try {
            val item=media(1);db.dao().saveMedia(listOf(item))
            db.dao().members(listOf(Membership("album",item.id,"album/member")))
            db.dao().saveMedia(listOf(item.copy(name="renamed.jpg",href="new-path")))
            db.dao().clearMembers("album")
            assertNotNull(db.dao().media(item.id));assertEquals("renamed.jpg",db.dao().media(item.id)!!.name)
        } finally {db.close()}
    }
    @Test fun `duplicate backup scheduling creates exactly one transfer`() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhotoDatabase::class.java).build()
        try {
            val item=Transfer("one","content://photo/1","same-source","dest","photo.jpg","image/jpeg",123,100)
            assertTrue(db.dao().insertTransfer(item)!=-1L)
            assertEquals(-1L,db.dao().insertTransfer(item.copy(id="two")))
            db.dao().transfer(item.copy(state="CANCELLED"))
            db.dao().status(item.id,"DONE",123)
            assertEquals("CANCELLED",db.dao().transfer(item.id)!!.state)
        } finally {db.close()}
    }
    @Test fun `clearing history preserves active jobs and backup deduplication`() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),PhotoDatabase::class.java).build()
        try {
            val dao=db.dao()
            listOf("DONE","ERROR","CANCELLED","QUEUED","RUNNING").forEach { state ->
                dao.insertTransfer(Transfer(state,"source",state,"dest",state,"image/jpeg",123,100,state=state))
            }
            dao.clearTransferHistory()
            assertEquals(setOf("QUEUED","RUNNING"),dao.transfers().first().map { it.state }.toSet())
            assertEquals(0,dao.transferHistoryCount().first())
            assertEquals(2,dao.pending().first())
            val done=dao.transferForSource("DONE")!!
            assertTrue(done.hidden)
            assertEquals(-1L,dao.insertTransfer(done.copy(id="duplicate",hidden=false)))
            dao.status("RUNNING","DONE",123)
            assertEquals(1,dao.transferHistoryCount().first())
            dao.clearTransferHistory()
            assertEquals(listOf("QUEUED"),dao.transfers().first().map { it.state })
        } finally { db.close() }
    }
    @Test fun `version one database upgrades without losing transfer history`() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-history.db"
        context.deleteDatabase(name)
        val schema=org.json.JSONObject(java.io.File("schemas/app.nextcloudphoto.data.PhotoDatabase/1.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,0,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}",entity.getString("tableName")))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",entity.getString("tableName")))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO Transfer VALUES ('old','source','key','dest','photo.jpg','image/jpeg',123,100,'DONE',123,'','UPLOAD','','',100)")
            old.version=1
        }
        val db=Room.databaseBuilder(context,PhotoDatabase::class.java,name).addMigrations(PhotoDatabase.MIGRATION_1_2).build()
        try {
            assertEquals("old",db.dao().transfers().first().single().id)
            assertFalse(db.dao().transfer("old")!!.hidden)
            db.dao().clearTransferHistory()
            assertTrue(db.dao().transfers().first().isEmpty())
            assertNotNull(db.dao().transferForSource("key"))
        } finally { db.close();context.deleteDatabase(name) }
    }
}
