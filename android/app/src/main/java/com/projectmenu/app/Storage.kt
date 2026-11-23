package com.projectmenu.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device equivalent of server.py. Same file layout and semantics:
 *   filesDir/projects.json, filesDir/backups/, filesDir/media/, filesDir/media/.trash/
 */
class Storage(private val root: File) {
    val dataFile = File(root, "projects.json")
    val backupDir = File(root, "backups")
    val mediaDir = File(root, "media")
    val trashDir = File(mediaDir, ".trash")

    private val lock = Any()
    private val rng = SecureRandom()

    private class Upload(val file: File, val name: String, val out: FileOutputStream)
    private val uploads = ConcurrentHashMap<String, Upload>()

    /** Response envelope understood by pm-android.js. */
    private fun env(status: Int, body: String, type: String = "application/json"): String =
        JSONObject().put("status", status).put("body", body).put("type", type).toString()

    private fun json(status: Int, obj: JSONObject) = env(status, obj.toString())
    private fun err(status: Int, msg: String) = json(status, JSONObject().put("error", msg))

    fun getData(): String = synchronized(lock) {
        if (!dataFile.isFile) env(404, "{}") else env(200, dataFile.readText(Charsets.UTF_8))
    }

    fun putData(text: String): String {
        try {
            val o = JSONObject(text)
            if (o.opt("nodes") !is JSONArray || o.opt("tags") !is JSONArray) throw IllegalArgumentException()
        } catch (e: Exception) {
            return err(400, "invalid data")
        }
        return try {
            synchronized(lock) {
                if (dataFile.exists()) {
                    backupDir.mkdirs()
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val bk = File(backupDir, "projects-$day.json")
                    if (!bk.exists()) dataFile.copyTo(bk)
                }
                val tmp = File(root, "projects.json.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmp.renameTo(dataFile)) {
                    // renameTo replaces atomically on Android/Linux; fall back just in case
                    tmp.copyTo(dataFile, overwrite = true)
                    tmp.delete()
                }
            }
            json(200, JSONObject().put("ok", true))
        } catch (e: Exception) {
            err(500, e.message ?: "write failed")
        }
    }

    fun uploadBegin(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        var safe = base.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_')
        if (safe.length > 80) safe = safe.takeLast(80)
        if (safe.isEmpty()) safe = "file"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val hex = ByteArray(3).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        mediaDir.mkdirs()
        val f = File(mediaDir, "$stamp-$hex-$safe")
        val id = UUID.randomUUID().toString()
        uploads[id] = Upload(f, name, FileOutputStream(f))
        return id
    }

    fun uploadChunk(id: String, b64: String): Boolean {
        val u = uploads[id] ?: return false
        return try {
            u.out.write(Base64.decode(b64, Base64.DEFAULT))
            true
        } catch (e: Exception) {
            uploadAbort(id)
            false
        }
    }

    fun uploadEnd(id: String): String {
        val u = uploads.remove(id) ?: return err(500, "unknown upload")
        return try {
            u.out.close()
            json(200, JSONObject().put("url", "media/" + u.file.name).put("name", u.name))
        } catch (e: Exception) {
            u.file.delete()
            err(500, e.message ?: "upload failed")
        }
    }

    fun uploadAbort(id: String) {
        val u = uploads.remove(id) ?: return
        try { u.out.close() } catch (_: Exception) {}
        u.file.delete()
    }

    fun deleteMedia(name: String): String {
        val fname = File(name).name
        val src = File(mediaDir, fname)
        if (fname.isEmpty() || fname == ".trash" || !src.isFile) return err(404, "not found")
        trashDir.mkdirs()
        val dst = File(trashDir, fname)
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        return json(200, JSONObject().put("ok", true))
    }

    /** Resolve a /media/<name> request to a file, or null. */
    fun mediaFile(name: String): File? {
        val fname = File(name).name
        if (fname.isEmpty() || fname == ".trash") return null
        val f = File(mediaDir, fname)
        return if (f.isFile) f else null
    }
}
