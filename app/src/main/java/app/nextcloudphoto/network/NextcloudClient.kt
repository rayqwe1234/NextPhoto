package app.nextcloudphoto.network

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import app.nextcloudphoto.account.Account
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudException(val code: Int, message: String) : IOException(message)
data class LoginFlow(val login: String, val poll: String, val token: String)
data class ServerInfo(val version: String, val quotaUsed: Long, val quotaTotal: Long, val trash: Boolean)
data class ShareLink(val id: String, val url: String)

fun normalizeServer(input: String): String {
    val url = input.trim().toHttpUrl()
    require(url.isHttps) { tr(R.string.msg_16_please_use_an_https_nextcloud_url) }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { tr(R.string.msg_17_the_url_must_not_contain_credentials_a_query_or_a_fragm) }
    return url.toString().trimEnd('/')
}
fun validName(value: String): String {
    require(value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.code < 32 }) { tr(R.string.msg_18_names_cannot_be_empty_or_contain_path_separators) }
    return value
}
fun errorMessage(code: Int): String = when(code) {
    401 -> tr(R.string.msg_19_authorization_has_expired_please_sign_in_again)
    403 -> tr(R.string.msg_20_you_do_not_have_permission_for_this_action)
    404 -> tr(R.string.msg_21_the_file_or_service_does_not_exist)
    409 -> tr(R.string.msg_22_the_destination_folder_is_missing_or_an_item_conflicts)
    412 -> tr(R.string.msg_23_a_file_already_exists_or_has_changed_nothing_was_overwr)
    423 -> tr(R.string.msg_24_the_file_is_temporarily_locked_please_try_again_later)
    429 -> tr(R.string.msg_25_the_server_asks_you_to_try_again_later)
    507 -> tr(R.string.msg_26_nextcloud_storage_is_full)
    else -> tr(R.string.msg_27_server_returned_http_1_s, code)
}

class NextcloudClient(val account: Account?, server: String = account!!.server, transport: OkHttpClient? = null) {
    val base = server.trimEnd('/').toHttpUrl()
    val http: OkHttpClient = (transport ?: OkHttpClient()).newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(25, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            require(sameOrigin(request.url) && request.url.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/")) { tr(R.string.msg_28_blocked_sending_credentials_to_another_server) }
            chain.proceed(request.newBuilder().apply {
                if (account != null) header("Authorization", Credentials.basic(account.login, account.password))
                header("OCS-APIRequest", "true")
            }.build())
        }.build()
    private fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port
    fun url(vararg parts: String): String = base.newBuilder().apply { parts.forEach { addPathSegment(it) } }.build().toString()
    val filesRoot: String get() = url("remote.php", "dav", "files", account!!.user)
    val albumsRoot: String get() = url("remote.php", "dav", "photos", account!!.user, "albums")
    fun child(parent: String, name: String): String = trusted(parent).newBuilder().apply {
        if (trusted(parent).encodedPath.endsWith('/')) removePathSegment(trusted(parent).pathSegments.size - 1)
        addPathSegment(validName(name))
    }.build().toString()
    fun trusted(href: String): HttpUrl {
        val target = base.resolve(href) ?: error(tr(R.string.msg_29_invalid_server_path))
        require(sameOrigin(target) && target.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/")) { tr(R.string.msg_30_the_server_returned_a_cross_site_path) }
        return target
    }
    fun canonical(href: String) = trusted(href).toString().trimEnd('/')
    fun inFiles(href: String): Boolean = canonical(href).startsWith("${filesRoot}/")
    fun response(method: String, href: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder().url(trusted(href)).method(method, body).apply { headers.forEach { (k,v) -> header(k,v) } }.build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) { val code = response.code; response.close(); throw CloudException(code, errorMessage(code)) }
        return response
    }
    fun command(method: String, href: String, body: String? = null, headers: Map<String,String> = emptyMap()) {
        response(method, href, body?.toRequestBody("application/xml; charset=utf-8".toMediaType()), headers).use { res ->
            if (res.code == 207) {
                val text = res.body!!.string()
                val statuses = Regex("HTTP/[0-9.]+ ([0-9]{3})").findAll(text).map { it.groupValues[1].toInt() }.toList()
                statuses.firstOrNull { it !in 200..299 }?.let { throw CloudException(it, errorMessage(it)) }
                check(statuses.isNotEmpty()) { tr(R.string.msg_31_the_server_did_not_confirm_the_operation) }
            }
        }
    }
    fun beginLogin(): LoginFlow {
        val json = response("POST", url("index.php", "login", "v2"), ByteArray(0).toRequestBody()).use { JSONObject(it.body!!.string()) }
        val poll = json.getJSONObject("poll")
        val login = trusted(json.getString("login")).toString()
        return LoginFlow(login, trusted(poll.getString("endpoint")).toString(), poll.getString("token"))
    }
    fun poll(flow: LoginFlow): Account? = try {
        val json = response("POST", flow.poll, FormBody.Builder().add("token", flow.token).build()).use { JSONObject(it.body!!.string()) }
        val server = normalizeServer(json.getString("server"))
        require(server == base.toString().trimEnd('/')) { tr(R.string.msg_32_the_authorization_server_differs_from_the_url_you_enter) }
        Account(server, json.getString("loginName"), json.getString("appPassword"))
    } catch(e: CloudException) { if(e.code == 404) null else throw e }
    fun ocs(method: String, path: List<String>, form: FormBody? = null): JSONObject {
        val target = url(*path.toTypedArray()).toHttpUrl().newBuilder().addQueryParameter("format", "json").build().toString()
        val json = response(method, target, form).use { JSONObject(it.body!!.string()).getJSONObject("ocs") }
        val meta = json.getJSONObject("meta")
        if (meta.optString("status") != "ok") throw IOException(meta.optString("message", tr(R.string.msg_33_nextcloud_operation_failed)))
        return json
    }
    fun info(): ServerInfo {
        val cap = ocs("GET", listOf("ocs","v2.php","cloud","capabilities")).getJSONObject("data")
        val user = ocs("GET", listOf("ocs","v2.php","cloud","user")).getJSONObject("data")
        val quota = user.optJSONObject("quota")
        return ServerInfo(cap.optJSONObject("version")?.optString("string").orEmpty(), quota?.optLong("used") ?: 0,
            quota?.optLong("total") ?: -1, cap.optJSONObject("capabilities")?.optJSONObject("files")?.optBoolean("undelete") ?: false)
    }
    fun list(href: String, depth: Int = 1, consume: (DavEntry) -> Unit) {
        response("PROPFIND", href, PROPS.toRequestBody("application/xml".toMediaType()), mapOf("Depth" to "${depth}")).use {
            readDav(it.body!!.charStream(), consume)
        }
    }
    fun stat(href: String): DavEntry = buildList { list(href, 0) { add(it) } }.firstOrNull() ?: error(tr(R.string.msg_34_the_server_did_not_return_file_information))
    fun exists(href: String): Boolean = try { stat(href); true } catch(e: CloudException) { if(e.code == 404) false else throw e }
    fun favorite(href: String, value: Boolean) {
        require(inFiles(href))
        command("PROPPATCH", href, """<d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:set><d:prop><oc:favorite>${if(value) 1 else 0}</oc:favorite></d:prop></d:set></d:propertyupdate>""")
    }
    fun move(source: String, destination: String, etag: String = "") {
        val vault=child(filesRoot,app.nextcloudphoto.vault.VaultStore.ROOT)
        require(canonical(destination)!=vault && !canonical(destination).startsWith("${vault}/")) { tr(R.string.msg_35_import_through_the_encrypted_album_a_regular_move_does) }
        command("MOVE", source, headers = mapOf("Destination" to trusted(destination).toString(), "Overwrite" to "F") + if(etag.isNotEmpty()) mapOf("If-Match" to etag) else emptyMap())
    }
    fun addToAlbum(source: String, album: String, name: String) {
        require(inFiles(source) && canonical(album).startsWith("${albumsRoot}/"))
        command("COPY", source, headers = mapOf("Destination" to child(album, name), "Overwrite" to "F"))
    }
    fun removeFromAlbum(member: String) {
        require(canonical(member).startsWith("${albumsRoot}/") && trusted(member).pathSegments.size >= trusted(albumsRoot).pathSegments.size + 2)
        command("DELETE", member)
    }
    fun deleteFile(href: String, etag: String = "") { require(inFiles(href)); command("DELETE", href,headers=if(etag.isNotEmpty()) mapOf("If-Match" to etag) else emptyMap()) }
    fun preview(fileId: String, etag: String, size: Int = 512): String = url("index.php","core","preview").toHttpUrl().newBuilder()
        .addQueryParameter("fileId", fileId).addQueryParameter("x", "${size}").addQueryParameter("y", "${size}")
        .addQueryParameter("a", "1").addQueryParameter("etag", etag).build().toString()
    fun download(href: String, target: File, progress: (Long) -> Unit = {}) {
        target.parentFile!!.mkdirs()
        response("GET", href).use { res ->
            val body = res.body!!
            var total = 0L
            body.byteStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if(n < 0) break; output.write(buffer,0,n); total += n; progress(total) }
            } }
            if (body.contentLength() >= 0 && total != body.contentLength()) throw IOException(tr(R.string.msg_36_download_incomplete))
        }
    }
    private val sharesPath = listOf("ocs","v2.php","apps","files_sharing","api","v1","shares")
    fun share(href: String, password: String, expiry: String): ShareLink {
        require(inFiles(href))
        val root = trusted(filesRoot).pathSegments.size
        val path = "/" + trusted(href).pathSegments.drop(root).joinToString("/")
        val form = FormBody.Builder().add("path", path).add("shareType", "3").add("permissions", "1").apply {
            if(password.isNotBlank()) add("password", password)
            if(expiry.isNotBlank()) { java.time.LocalDate.parse(expiry); add("expireDate", expiry) }
        }.build()
        val data = ocs("POST", sharesPath, form).getJSONObject("data")
        return ShareLink(data.getString("id"), data.getString("url"))
    }
    fun shares(): List<ShareLink> {
        val array = ocs("GET", sharesPath).getJSONArray("data")
        return (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optInt("share_type") == 3 }.map { ShareLink(it.getString("id"),it.optString("url")) }
    }
    fun revoke(id: String) { ocs("DELETE", sharesPath + id) }
    companion object {
        val PROPS = """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:getetag/><d:getlastmodified/><oc:fileid/><oc:favorite/><oc:permissions/><nc:has-preview/><nc:metadata-photos-original_date_time/><nc:nbItems/><nc:last-photo/><nc:photos-album-file-origin/><nc:photos-collection-file-original-filename/></d:prop></d:propfind>"""
    }
}
