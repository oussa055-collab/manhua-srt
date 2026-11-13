package app.manhua

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.app.AlertDialog
import android.widget.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class Cue(var start: Double, var end: Double, var zh: String, var ar: String = "")

class Prov(val name: String, val batch: Int, val pause: Long, val call: (List<Cue>, List<Cue>) -> List<String>)

class ApiErr(val code: Int, body: String = "") : Exception("API $code " + body.replace(Regex("\\s+"), " ").take(160))

fun ensureChannel(c: Context) {
    c.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel("work", "Analyse en cours", NotificationManager.IMPORTANCE_LOW))
}

fun buildNotif(c: Context, text: String): Notification {
    val pi = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE
    )
    return Notification.Builder(c, "work").setContentTitle("Manhua SRT").setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setOngoing(true).build()
}

class KeepAliveService : Service() {
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        ensureChannel(this)
        startForeground(1, buildNotif(this, "Analyse en cours…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wl == null) {
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "manhua:work")
            wl?.acquire(15 * 60 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        try { wl?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}

class MainActivity : Activity() {
    private val STEP = 1.0
    private var uri: Uri? = null
    private var base = "video"
    private var durMs = 0L
    private val items = mutableListOf<Pair<Double, String>>()
    private val memo = HashMap<String, String>()
    private val glossary = LinkedHashMap<String, String>()
    private val userGl = LinkedHashMap<String, String>()
    private var cues = listOf<Cue>()
    private var done = 0.0
    private var savedTop = 80
    private var savedBot = 98
    private var savedStart = 0.0
    @Volatile private var fellBack = false
    private var rEnd = 0.0
    @Volatile private var stop = false
    @Volatile private var running = false
    private var preview: Bitmap? = null
    private var mode = 0
    private val h = Handler(Looper.getMainLooper())
    private lateinit var st: TextView
    private lateinit var img: ImageView
    private lateinit var sTop: SeekBar
    private lateinit var sBot: SeekBar
    private lateinit var key: EditText
    private lateinit var key2: EditText
    private lateinit var key3: EditText
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var treeTv: TextView
    private lateinit var goB: Button
    private lateinit var playB: Button
    private lateinit var box: FrameLayout
    private lateinit var vv: VideoView
    private lateinit var ov: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadGl()
        val d = resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        fun bar(mx: Int, v: Int) = SeekBar(this).apply {
            max = mx; progress = v
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { drawPreview() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(24)) }
        col.addView(TextView(this).apply { text = "Manhua · sous-titres chinois → arabe"; textSize = 20f })
        col.addView(btn("1. Choisir la vidéo") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
        })
        img = ImageView(this).apply { adjustViewBounds = true }
        col.addView(img)
        col.addView(TextView(this).apply { text = "Zone rouge = sous-titres incrustés (haut puis bas, en %)" })
        sTop = bar(95, 80); sBot = bar(100, 98)
        col.addView(sTop); col.addView(sBot)
        key = EditText(this).apply {
            hint = "Clé Gemini (AQ… ou AIza…) ou Claude (sk-ant…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("p", 0).getString("k", ""))
        }
        col.addView(key)
        key2 = EditText(this).apply {
            hint = "Clé Groq (gsk_…) : serveur de secours 1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("p", 0).getString("kg", ""))
        }
        key3 = EditText(this).apply {
            hint = "Clé OpenRouter (sk-or-…) : serveur de secours 2"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("p", 0).getString("ko", ""))
        }
        col.addView(key2); col.addView(key3)
        etFrom = EditText(this).apply { hint = "Début de l’analyse (minutes)"; setText("0"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        etTo = EditText(this).apply { hint = "Fin (minutes, 0 = toute la vidéo)"; setText("5"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        col.addView(etFrom); col.addView(etTo)
        col.addView(btn("Choisir le dossier de sortie (celui de la vidéo)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 2)
        })
        treeTv = TextView(this).apply { text = treeLabel() }
        col.addView(treeTv)
        goB = btn("2. Analyser et traduire") { start() }.also { it.isEnabled = false }
        col.addView(goB)
        col.addView(btn("Arrêter (la progression est gardée)") { stop = true })
        playB = btn("3. Lire avec sous-titres") { play() }.also { it.isEnabled = false }
        col.addView(playB)
        col.addView(btn("Mon glossaire (noms et termes)") { editGlossary() })
        col.addView(btn("Retraduire avec le glossaire (garde l’analyse)") {
            if (!running && uri != null) {
                memo.clear(); saveState(); playB.isEnabled = false
                st.text = "Traductions effacées. Appuie sur « 2 » pour retraduire avec ton glossaire."
            }
        })
        col.addView(btn("Effacer la progression") {
            if (!running) {
                items.clear(); memo.clear(); done = 0.0; cues = listOf()
                uri?.let { sf().delete() }
                playB.isEnabled = false; st.text = "Progression effacée."
            }
        })
        st = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(st)

        vv = VideoView(this)
        ov = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt()); textSize = 20f
            gravity = Gravity.CENTER; setOnClickListener { mode = (mode + 1) % 3 }
        }
        box = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(ov, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(70) })
            addView(btn("✕") { closePlayer() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }
        setContentView(FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply { addView(col) })
            addView(box)
        })
    }

    private fun loadGl() {
        try {
            val f = File(filesDir, "glossaire.json"); if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.optJSONObject("user")?.let { m -> m.keys().forEach { userGl[it] = m.getString(it) } }
            o.optJSONObject("learned")?.let { m -> m.keys().forEach { glossary[it] = m.getString(it) } }
        } catch (e: Exception) {}
    }

    @Synchronized private fun saveGl() {
        try {
            File(filesDir, "glossaire.json").writeText(
                JSONObject().put("user", JSONObject(userGl.toMap())).put("learned", JSONObject(glossary.toMap())).toString())
        } catch (e: Exception) {}
    }

    private fun editGlossary() {
        val all = LinkedHashMap<String, String>(); all.putAll(glossary); all.putAll(userGl)
        val et = EditText(this).apply {
            setText(all.entries.joinToString("\n") { "${it.key} = ${it.value}" })
            minLines = 8; gravity = Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle("Mon glossaire : chinois = arabe")
            .setMessage("Une ligne par terme. Ces traductions passent avant tout le reste, en ligne et hors ligne.")
            .setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("Enregistrer") { _, _ ->
                userGl.clear(); glossary.clear()
                et.text.toString().lines().forEach { l ->
                    val i = l.indexOf('=')
                    if (i > 0) {
                        val k = l.substring(0, i).trim(); val v = l.substring(i + 1).trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) userGl[k] = v
                    }
                }
                saveGl()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun offlineLine(tr: Translator, zh: String): String {
        userGl[zh]?.let { return it }
        val terms = (userGl.keys + glossary.keys).filter { it.length >= 2 && zh.contains(it) }.sortedByDescending { it.length }
        if (terms.isEmpty()) return Tasks.await(tr.translate(zh))
        var parts = listOf(false to zh)
        for (t in terms) {
            val np = mutableListOf<Pair<Boolean, String>>()
            for ((isTerm, txt) in parts) {
                if (isTerm || !txt.contains(t)) { np.add(isTerm to txt); continue }
                txt.split(t).forEachIndexed { i, piece ->
                    if (i > 0) np.add(true to t)
                    if (piece.isNotBlank()) np.add(false to piece)
                }
            }
            parts = np
        }
        return parts.joinToString(" ") { (isTerm, txt) ->
            if (isTerm) (userGl[txt] ?: glossary[txt] ?: txt) else Tasks.await(tr.translate(txt))
        }.trim()
    }

    private fun notifyProgress(t: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotif(this, t))
    }

    private fun askBackground() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        }
    }

    private fun treeLabel(): String =
        getSharedPreferences("p", 0).getString("tree", null)?.let { "Dossier de sortie : " + DocumentsContract.getTreeDocumentId(Uri.parse(it)) }
            ?: "Dossier de sortie : Téléchargements (par défaut)"

    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rq == 2) {
            val tu = d?.data
            if (rc == RESULT_OK && tu != null) {
                try { contentResolver.takePersistableUriPermission(tu, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
                getSharedPreferences("p", 0).edit().putString("tree", tu.toString()).apply()
                treeTv.text = treeLabel()
            }
            return
        }
        val u = d?.data ?: return
        if (rc != RESULT_OK) return
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        uri = u
        base = try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0).substringBeforeLast('.') else null
            } ?: "video"
        } catch (e: Exception) { "video" }
        Thread {
            try {
                val r = retr()
                durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                preview = r.getFrameAtTime(minOf(60000L, durMs / 2) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                r.release()
                try { loadState() } catch (e: Exception) { items.clear(); memo.clear(); done = 0.0 }
                runOnUiThread {
                    if (items.isNotEmpty()) { sTop.progress = savedTop; sBot.progress = savedBot; etFrom.setText((savedStart / 60).toString()) }
                    drawPreview(); goB.isEnabled = true
                    playB.isEnabled = cues.isNotEmpty() && cues.all { it.ar.isNotEmpty() }
                    st.text = "Durée ${hms(durMs / 1000.0)}." + if (items.isNotEmpty()) " Progression retrouvée : ${hms(done)}." else " Ajuste la zone rouge puis lance l’analyse."
                }
            } catch (e: Exception) { ui("Vidéo illisible : $e") }
        }.start()
    }

    private fun ui(s: String) { runOnUiThread { st.text = s } }
    private fun retr() = MediaMetadataRetriever().also { it.setDataSource(this, uri) }
    private fun hms(t: Double): String { val s = t.toLong(); return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }
    private fun sf() = File(filesDir, "s${uri.toString().hashCode()}.json")

    private fun drawPreview() {
        val p = preview ?: return
        val s = Bitmap.createScaledBitmap(p, 900, (900f * p.height / p.width).toInt(), true).copy(Bitmap.Config.ARGB_8888, true)
        val pt = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
        val top = s.height * sTop.progress / 100f
        val bot = maxOf(s.height * sBot.progress / 100f, top + 8f)
        Canvas(s).drawRect(0f, top, s.width.toFloat(), bot, pt)
        img.setImageBitmap(s)
    }

    @Synchronized private fun saveState() {
        val o = JSONObject().put("done", done).put("tp", savedTop).put("bt", savedBot).put("rs", savedStart)
        o.put("items", JSONArray().also { a -> items.forEach { a.put(JSONArray().put(it.first).put(it.second)) } })
        o.put("memo", JSONObject(memo.toMap())).put("g", JSONObject(glossary.toMap()))
        sf().writeText(o.toString())
        saveGl()
    }

    private fun loadState() {
        items.clear(); memo.clear(); done = 0.0; cues = listOf(); savedStart = 0.0
        val f = sf(); if (!f.exists()) return
        val o = JSONObject(f.readText())
        done = o.optDouble("done", 0.0); savedTop = o.optInt("tp", 80); savedBot = o.optInt("bt", 98); savedStart = o.optDouble("rs", 0.0)
        val a = o.getJSONArray("items")
        for (i in 0 until a.length()) { val x = a.getJSONArray(i); items.add(x.getDouble(0) to x.getString(1)) }
        val m = o.getJSONObject("memo"); m.keys().forEach { memo[it] = m.getString(it) }
        if (items.isNotEmpty()) { cues = buildCues(); cues.forEach { it.ar = memo[it.zh] ?: "" } }
    }

    private fun norm(s: String) = Regex("[\\s\\p{P}，。！？、…·～]").replace(s, "")

    private fun sim(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val x = (0 until a.length - 1).map { a.substring(it, it + 2) }
        val y = (0 until b.length - 1).map { b.substring(it, it + 2) }.toMutableList()
        var c = 0
        for (g in x) if (y.remove(g)) c++
        return 2.0 * c / (x.size + b.length - 1)
    }

    private fun band(f: Bitmap, tp: Int, bt: Int): Bitmap {
        val t = (f.height * tp / 100f).toInt().coerceIn(0, f.height - 9)
        val b = maxOf((f.height * bt / 100f).toInt(), t + 8).coerceAtMost(f.height)
        return Bitmap.createBitmap(f, 0, t, f.width, b - t)
    }

    private fun sig(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 48, 12, true)
        val p = IntArray(576); s.getPixels(p, 0, 48, 0, 0, 48, 12)
        return IntArray(576) { (Color.red(p[it]) + Color.green(p[it]) + Color.blue(p[it])) / 3 }
    }

    private fun diff(a: IntArray, b: IntArray): Double { var s = 0; for (i in a.indices) s += abs(a[i] - b[i]); return s / a.size.toDouble() }

    private fun ocr(rec: TextRecognizer, b: Bitmap): String {
        val r = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)))
        val s = r.textBlocks.flatMap { it.lines }.joinToString(" ") { it.text }.trim()
        return if (Regex("[\\u4e00-\\u9fff]").containsMatchIn(s)) s else ""
    }

    private fun buildCues(): List<Cue> {
        val out = mutableListOf<Cue>(); val hh = STEP / 2; val dur = if (rEnd > 0) rEnd else durMs / 1000.0
        for (k in items.indices) {
            val (t, zh) = items[k]; if (zh.isEmpty()) continue
            val s0 = maxOf(0.0, t - hh)
            val e0 = maxOf((if (k + 1 < items.size) items[k + 1].first else minOf(dur, t + 3)) - hh, s0 + 0.5)
            val l = out.lastOrNull()
            if (l != null && abs(l.end - s0) < 0.01 && sim(norm(l.zh), norm(zh)) > 0.85) {
                l.end = e0; if (zh.length > l.zh.length) l.zh = zh
            } else out.add(Cue(s0, e0, zh))
        }
        return out
    }

    private fun start() {
        if (running || uri == null) return
        val k = key.text.toString().trim()
        val k2 = key2.text.toString().trim()
        val k3 = key3.text.toString().trim()
        getSharedPreferences("p", 0).edit().putString("k", k).putString("kg", k2).putString("ko", k3).apply()
        val ct = sTop.progress; val cb = sBot.progress
        val full = durMs / 1000.0
        val rs = ((etFrom.text.toString().toDoubleOrNull() ?: 0.0) * 60).coerceAtLeast(0.0)
        val reRaw = (etTo.text.toString().toDoubleOrNull() ?: 0.0) * 60
        val re = if (reRaw <= 0.0 || reRaw > full) full else reRaw
        if (re <= rs) { st.text = "La fin doit être après le début."; return }
        if (savedTop != ct || savedBot != cb || savedStart != rs) { items.clear(); done = 0.0 }
        done = maxOf(done, rs)
        savedTop = ct; savedBot = cb; savedStart = rs; rEnd = re
        running = true; stop = false; goB.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ensureChannel(this)
        startForegroundService(Intent(this, KeepAliveService::class.java))
        askBackground()
        Thread {
            try {
                val dur = re
                if (done < dur) {
                    val r = retr()
                    val rec = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    var last = items.lastOrNull()?.second ?: ""
                    var prev: IntArray? = null
                    var t = done
                    var ls = System.currentTimeMillis()
                    while (t < dur && !stop) {
                        val f = r.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                        if (f != null) {
                            val b = band(f, ct, cb); val s = sig(b); val p = prev
                            if (p == null || diff(p, s) > 6) {
                                prev = s
                                val txt = ocr(rec, b)
                                if (norm(txt) != norm(last)) { items.add(t to txt); last = txt }
                            }
                            if (b !== f) b.recycle()
                            f.recycle()
                        }
                        t += STEP; done = t
                        if (System.currentTimeMillis() - ls > 20000) { saveState(); ls = System.currentTimeMillis(); notifyProgress("Analyse ${hms(t)} / ${hms(dur)}") }
                        ui("Analyse ${hms(t)} / ${hms(dur)} · ${items.size} changements")
                    }
                    r.release(); saveState()
                }
                if (stop) { ui("Arrêté. Relance l’analyse pour reprendre."); return@Thread }
                cues = buildCues()
                if (cues.isEmpty()) { ui("Aucun sous-titre détecté : vérifie la zone rouge."); return@Thread }
                ui("${cues.size} sous-titres. Traduction…")
                translateAll(k, k2, k3)
                if (stop) { saveState(); ui("Arrêté pendant la traduction. Relance pour reprendre."); return@Thread }
                cues.forEach { it.ar = memo[it.zh] ?: "" }
                saveState()
                val inTree = exportAll()
                val where = if (inTree) "dans le dossier choisi : $base.srt (arabe), $base.zh.srt, $base.bilingue.srt" else "dans Téléchargements : $base.ar.srt, $base.zh.srt, $base.bilingue.srt"
                val testNote = if (re < full) " Test terminé : vérifie avec « 3. Lire », puis mets Fin = 0 et relance pour toute la vidéo." else ""
                ui("Terminé ✓ ${cues.size} sous-titres, $where.$testNote" + if (fellBack) " Une partie a été traduite hors ligne (quota gratuit atteint)." else "")
                runOnUiThread { playB.isEnabled = true }
            } catch (e: Exception) {
                try { saveState() } catch (x: Exception) {}
                ui("Erreur : ${e.message ?: e}. La progression est gardée : relance pour reprendre.")
            } finally {
                running = false
                stopService(Intent(this, KeepAliveService::class.java))
                runOnUiThread { goB.isEnabled = true; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }.start()
    }

    private fun offlineTranslate(list: List<Cue>) {
        val tr = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.ARABIC).build())
        ui("Téléchargement du modèle hors ligne (une seule fois)…")
        Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        var n = 0
        for (c in list) {
            if (stop) return
            if (memo[c.zh] != null) continue
            memo[c.zh] = offlineLine(tr, c.zh)
            if (++n % 25 == 0) { ui("Traduction hors ligne : $n"); notifyProgress("Traduction hors ligne : $n"); saveState() }
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun prepareOffline() {
        try {
            val tr = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.ARABIC).build())
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            tr.close()
        } catch (e: Exception) {}
    }

    private fun translateAll(k1: String, k2: String, k3: String) {
        fellBack = false
        cues.forEach { c -> userGl[c.zh]?.let { memo[c.zh] = it } }
        fun todo() = cues.filter { memo[it.zh] == null }
        val chain = mutableListOf<Prov>()
        val nKeys = listOf(k1, k2, k3).count { it.isNotEmpty() }
        if (k1.startsWith("sk-ant")) chain.add(Prov("Claude", 25, 0L) { c, b -> claude(k1, c, b) })
        else if (k1.isNotEmpty()) chain.add(Prov("Gemini", 40, 0L) { c, b -> gemini(k1, c, b, if (nKeys > 1) 4 else 12) })
        if (k2.isNotEmpty()) chain.add(Prov("Groq", 25, 2500L) { c, b ->
            oai("https://api.groq.com/openai/v1", k2, listOf("llama-3.3-70b", "llama-4", "gpt-oss-120b", "qwen"), false, c, b)
        })
        if (k3.isNotEmpty()) chain.add(Prov("OpenRouter", 20, 4000L) { c, b ->
            oai("https://openrouter.ai/api/v1", k3, listOf("qwen", "llama-3.3-70b", "gpt-oss-120b", "gemma"), true, c, b)
        })
        if (chain.isEmpty()) { offlineTranslate(todo()); return }
        if (!online()) {
            fellBack = true
            ui("Pas d’internet : traduction hors ligne directe…")
            offlineTranslate(todo()); return
        }
        ui("Préparation du mode hors ligne (une seule fois)…")
        prepareOffline()
        val dead = HashSet<String>()
        while (!stop) {
            if (!online()) dead.addAll(chain.map { it.name })
            val prov = chain.firstOrNull { it.name !in dead }
            if (prov == null) {
                fellBack = true
                ui("Serveurs indisponibles : passage à la traduction hors ligne…")
                offlineTranslate(todo()); return
            }
            val b = todo().take(prov.batch); if (b.isEmpty()) break
            val i = cues.indexOf(b[0])
            val ctx = cues.subList(maxOf(0, i - 8), i).filter { memo[it.zh] != null }
            try {
                val res = prov.call(ctx, b)
                b.forEachIndexed { j, c -> memo[c.zh] = res[j] }
                val msg = "Traduction ${cues.count { memo[it.zh] != null }} / ${cues.size} (${prov.name})"
                ui(msg); notifyProgress(msg); saveState()
                if (prov.pause > 0) Thread.sleep(prov.pause)
            } catch (e: Exception) {
                if (stop) return
                dead.add(prov.name)
                ui(prov.name + " indisponible (" + (if (e is ApiErr) e.code.toString() else "erreur") + ") : serveur suivant…")
                Thread.sleep(1500)
            }
        }
    }

    private val modelCache = HashMap<String, String>()
    private val badModels = HashSet<String>()

    private fun pickModel(base: String, key: String, prefs: List<String>, freeOnly: Boolean): String {
        modelCache[base]?.let { return it }
        val c = URL("$base/models").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $key"); c.connectTimeout = 20000; c.readTimeout = 30000
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) throw ApiErr(code, txt)
        val arr = JSONObject(txt).getJSONArray("data")
        val skip = listOf("whisper", "embed", "guard", "tts", "orpheus", "image", "moderation", "vision", "safeguard", "compound")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            .filter { (!freeOnly || it.endsWith(":free")) && it !in badModels && skip.none { s -> it.contains(s, true) } }
        val m = prefs.firstNotNullOfOrNull { p -> ids.firstOrNull { it.contains(p, true) } } ?: ids.firstOrNull()
            ?: throw Exception("aucun modèle disponible")
        modelCache[base] = m
        return m
    }

    private fun oai(base: String, key: String, prefs: List<String>, freeOnly: Boolean, ctx: List<Cue>, b: List<Cue>): List<String> {
        var err: Exception? = null
        for (a in 1..3) {
            var model = ""
            try {
                model = pickModel(base, key, prefs, freeOnly)
                val body = JSONObject().put("model", model).put("temperature", 0.3)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt(ctx, b))))
                val txt = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
                val out = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                return parse(out, b.size)
            } catch (e: Exception) {
                if (e is ApiErr && (e.code == 401 || e.code == 403)) throw e
                err = e
                if (e is ApiErr && (e.code == 400 || e.code == 404) && model.isNotEmpty()) { badModels.add(model); modelCache.remove(base) }
                if (e !is ApiErr && !online()) throw e
                if (e is ApiErr && e.code == 429 && a >= 2) break
                val wait = if (e is ApiErr && (e.code == 429 || e.code >= 500)) 20L * a else 3L * a
                ui("Serveur occupé : nouvel essai dans $wait s…")
                Thread.sleep(wait * 1000)
            }
        }
        throw err!!
    }

    private fun prompt(ctx: List<Cue>, b: List<Cue>): String {
        val src = (ctx.map { it.zh } + b.map { it.zh }).joinToString("\n")
        val rel = LinkedHashMap<String, String>()
        for ((k, v) in glossary) if (src.contains(k)) rel[k] = v
        for ((k, v) in userGl) if (src.contains(k)) rel[k] = v
        val p = StringBuilder()
        p.append("Tu traduis les sous-titres d’un manhua raconté (chinois) en arabe standard moderne, naturel et fidèle. ")
        p.append("Garde la logique et la continuité du discours : résous les sujets et pronoms omis d’après le contexte, ")
        p.append("garde les mêmes termes et la même transcription des noms propres (glossaire), le même ton et le même registre. ")
        p.append("Ne résume pas, ne commente pas.\n\nGlossaire à respecter exactement : ").append(JSONObject(rel.toMap())).append("\n\n")
        if (ctx.isNotEmpty()) {
            p.append("Contexte déjà traduit (ne pas retraduire) :\n")
            ctx.forEach { p.append(it.zh).append(" => ").append(memo[it.zh]).append("\n") }
            p.append("\n")
        }
        p.append("Lignes à traduire (tableau JSON) :\n").append(JSONArray(b.map { it.zh })).append("\n\n")
        p.append("Réponds uniquement par un objet JSON {\"t\":[${b.size} chaînes arabes dans le même ordre],\"g\":{\"nom chinois\":\"nom arabe\"}} ; ")
        p.append("g ne contient que les NOUVEAUX noms propres ou termes clés.")
        return p.toString()
    }

    private fun parse(raw: String, n: Int): List<String> {
        val out = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        val j = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
        val arr = j.getJSONArray("t")
        if (arr.length() != n) throw Exception("nombre de lignes différent")
        j.optJSONObject("g")?.let { g -> g.keys().forEach { if (!glossary.containsKey(it) && !userGl.containsKey(it)) glossary[it] = g.getString(it) } }
        while (glossary.size > 5000) glossary.remove(glossary.keys.first())
        return List(arr.length()) { arr.getString(it) }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 30000; c.readTimeout = 120000
        c.setRequestProperty("content-type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) throw ApiErr(code, txt)
        return txt
    }

    private fun claude(key: String, ctx: List<Cue>, b: List<Cue>): List<String> {
        val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt(ctx, b))))
        var err: Exception? = null
        for (a in 1..3) {
            try {
                val txt = post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
                return parse(JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text"), b.size)
            } catch (e: Exception) { err = e; Thread.sleep(4000L * a) }
        }
        throw err!!
    }

    private fun gemini(key: String, ctx: List<Cue>, b: List<Cue>, tries: Int = 12): List<String> {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(ctx, b))))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
        var err: Exception? = null
        for (a in 1..tries) {
            try {
                val model = if (a % 2 == 1) "gemini-flash-latest" else "gemini-flash-lite-latest"
                val txt = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
                val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val r = parse(out, b.size)
                Thread.sleep(5000)
                return r
            } catch (e: Exception) {
                if (e is ApiErr && (e.code == 401 || e.code == 403)) throw e
                err = e
                if (e is ApiErr && e.code == 429 && a >= minOf(6, tries)) break
                if (e !is ApiErr && !online()) throw e
                val wait = if (e is ApiErr && (e.code == 429 || e.code >= 500)) minOf(300L, 20L * a) else 4L * a
                ui("Gemini occupé (" + (if (e is ApiErr) e.code.toString() else "réseau") + ") : nouvel essai dans " + wait + " s…")
                Thread.sleep(wait * 1000)
            }
        }
        throw err!!
    }

    private fun ts(t: Double): String {
        var ms = (maxOf(0.0, t) * 1000).toLong()
        val hh = ms / 3600000; ms %= 3600000
        val m = ms / 60000; ms %= 60000
        val s = ms / 1000; ms %= 1000
        return "%02d:%02d:%02d,%03d".format(hh, m, s, ms)
    }

    private fun srt(sel: (Cue) -> String) =
        cues.withIndex().joinToString("\n") { (i, c) -> "${i + 1}\n${ts(c.start)} --> ${ts(c.end)}\n${sel(c)}\n" }

    private fun putTree(tree: Uri, name: String, text: String) {
        val tid = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, tid)
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, tid)
        contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) {
                try { DocumentsContract.deleteDocument(contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) } catch (e: Exception) {}
            }
        }
        val u = DocumentsContract.createDocument(contentResolver, parent, "application/x-subrip", name) ?: throw Exception("création impossible")
        contentResolver.openOutputStream(u)!!.use { it.write(("\uFEFF" + text).toByteArray()) }
    }

    private fun exportAll(): Boolean {
        val tree = getSharedPreferences("p", 0).getString("tree", null)?.let { Uri.parse(it) }
        if (tree != null) {
            try {
                putTree(tree, "$base.srt", srt { it.ar })
                putTree(tree, "$base.zh.srt", srt { it.zh })
                putTree(tree, "$base.bilingue.srt", srt { it.zh + "\n" + it.ar })
                return true
            } catch (e: Exception) {}
        }
        fun put(name: String, text: String) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(u)!!.use { it.write(("\uFEFF" + text).toByteArray()) }
        }
        put("$base.ar.srt", srt { it.ar })
        put("$base.zh.srt", srt { it.zh })
        put("$base.bilingue.srt", srt { it.zh + "\n" + it.ar })
        return false
    }

    private fun play() {
        val u = uri ?: return
        box.visibility = View.VISIBLE
        vv.setVideoURI(u)
        vv.setMediaController(MediaController(this).also { it.setAnchorView(vv) })
        vv.start()
        h.post(object : Runnable {
            override fun run() {
                if (box.visibility != View.VISIBLE) return
                val t = vv.currentPosition / 1000.0
                val c = cues.firstOrNull { t >= it.start && t < it.end }
                ov.text = when { c == null -> ""; mode == 0 -> c.ar; mode == 2 -> c.zh; else -> c.zh + "\n" + c.ar }
                ov.visibility = if (c == null) View.INVISIBLE else View.VISIBLE
                h.postDelayed(this, 250)
            }
        })
    }

    private fun closePlayer() { vv.stopPlayback(); box.visibility = View.GONE }

    override fun onBackPressed() { if (box.visibility == View.VISIBLE) closePlayer() else super.onBackPressed() }
}
