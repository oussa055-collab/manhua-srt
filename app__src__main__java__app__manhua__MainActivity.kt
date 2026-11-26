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
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import android.view.inputmethod.InputMethodManager
import android.app.AlertDialog
import android.widget.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
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

class Cue(var start: Double, var end: Double, var zh: String, var ar: String = "") { var hint: String = "" }

class Prov(val name: String, val batch: Int, val pause: Long, val call: (List<Cue>, List<Cue>) -> List<String>)

class ApiErr(val code: Int, body: String = "") : Exception("API $code " + body.replace(Regex("\\s+"), " ").take(160)) { var resetMs: Long = -1L }

class ParseErr(m: String) : Exception(m)

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
    private val phrases = LinkedHashMap<String, String>()
    @Volatile private var phDirty = false
    @Volatile private var libBusy = false
    @Volatile private var offDone = 0
    private lateinit var addZh: EditText
    private lateinit var addAr: EditText
    private val userGl = LinkedHashMap<String, String>()
    private var cues = listOf<Cue>()
    private var done = 0.0
    private var savedTop = 80
    private var savedBot = 98
    private var savedStart = 0.0
    @Volatile private var fellBack = false
    @Volatile private var failNotes = ""
    private val offlineKeys = HashSet<String>()
    private val srcMap = HashMap<String, String>()
    private val pstate = LinkedHashMap<String, String>()
    private val pquota = LinkedHashMap<String, String>()
    private val pmodel = LinkedHashMap<String, String>()
    private val perr = LinkedHashMap<String, String>()
    private val retraSet = HashSet<String>()
    private val pReset = HashMap<String, Long>()
    @Volatile private var autoGlN = 0
    private lateinit var key4: EditText
    @Volatile private var k4 = ""
    @Volatile private var geminiName = "Gemini"
    @Volatile private var transStart = 0L
    @Volatile private var transBase = 0
    private lateinit var pbar: ProgressBar
    private lateinit var ptxt: TextView
    private val asr = ArrayList<Seg>()
    private var asrDone = 0.0
    private val zhFix = HashMap<String, String>()
    @Volatile private var useAsr = false
    @Volatile private var asrNote = ""
    @Volatile private var lastFix = HashMap<Int, String>()
    private lateinit var cbAsr: CheckBox
    @Volatile private var phase = ""
    @Volatile private var phaseInfo = ""
    @Volatile private var lastOk = false
    @Volatile private var activeName = ""
    @Volatile private var firstName = "Gemini"
    @Volatile private var srtMode = false
    private var srtKey = ""
    @Volatile private var srcLang = "chinois"
    @Volatile private var srcIsEn = false
    private lateinit var panel: TextView
    private lateinit var gbox: LinearLayout
    private lateinit var gedit: EditText
    private var gOriginal = ""
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
        loadPhrases()
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
        key4 = EditText(this).apply {
            hint = "2e clé Gemini (autre projet Google) : optionnel"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("p", 0).getString("k4", ""))
        }
        col.addView(key4)
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
        etFrom = EditText(this).apply { hint = "Début : minutes, ou h:mm (ex. 4:00). Avec « - » = depuis la fin (-2:00 = 2 dernières heures)"; setText("0"); inputType = InputType.TYPE_CLASS_TEXT }
        etTo = EditText(this).apply { hint = "Fin : minutes ou h:mm (0 = jusqu’à la fin de la vidéo)"; setText("5"); inputType = InputType.TYPE_CLASS_TEXT }
        col.addView(etFrom); col.addView(etTo)
        cbAsr = CheckBox(this).apply {
            text = "Comparer avec l’audio chinois (reconnaissance vocale hors ligne, modèle ≈ 40 Mo, plus lent)"
            isChecked = getSharedPreferences("p", 0).getBoolean("asr", false)
        }
        col.addView(cbAsr)
        col.addView(btn("Choisir le dossier de sortie (celui de la vidéo)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 2)
        })
        treeTv = TextView(this).apply { text = treeLabel() }
        col.addView(treeTv)
        goB = btn("2. Analyser et traduire") { start() }.also { it.isEnabled = false }
        col.addView(goB)
        col.addView(btn("Arrêter (la progression est gardée)") { stop = true })
        ptxt = TextView(this).apply { setTextColor(0xFF1B7F3A.toInt()); textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(12), 0, dp(2)); text = "Progression de la traduction : 0 %" }
        col.addView(ptxt)
        pbar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        col.addView(pbar, LinearLayout.LayoutParams(-1, dp(14)))
        panel = TextView(this).apply { setTextColor(0xFF555555.toInt()); textSize = 14f; setPadding(0, dp(10), 0, dp(10)) }
        col.addView(panel)
        col.addView(btn("Vérifier mes quotas (Gemini, Groq, OpenRouter)") { checkQuotas() })
        playB = btn("3. Lire avec sous-titres") { play() }.also { it.isEnabled = false }
        col.addView(playB)
        col.addView(btn("Traduire un fichier .srt (chinois ou anglais → arabe)") { if (!running) pickSrt() })
        col.addView(btn("Mon glossaire (noms et termes)") { editGlossary() })
        col.addView(btn("Bibliothèque hors ligne (télécharger / mettre à jour)") { openLibrary() })
        col.addView(btn("Retraduire avec le glossaire (garde l’analyse)") {
            if (!running && uri != null) confirmDanger(
                "Retraduire toute la vidéo ?",
                "Les ${memo.size} traductions actuelles seront effacées (l’analyse est gardée). Une copie de secours est conservée : « Restaurer » les remet.",
                null
            ) {
                backupState()
                memo.clear(); offlineKeys.clear(); srcMap.clear(); retraSet.clear(); zhFix.clear(); saveState(); playB.isEnabled = false
                st.text = "Traductions effacées. Appuie sur « 2 » pour retraduire avec ton glossaire."
            }
        })
        col.addView(btn("Retraduire seulement la partie hors ligne") {
            if (!running && uri != null) confirmDanger(
                "Retraduire la partie hors ligne ?",
                "${offlineKeys.size} lignes traduites hors ligne seront effacées pour être retraduites par tes serveurs. Les autres sont gardées.",
                null
            ) {
                backupState()
                val n = offlineKeys.size
                retraSet.clear(); retraSet.addAll(offlineKeys)
                offlineKeys.forEach { memo.remove(it); srcMap.remove(it) }; offlineKeys.clear(); saveState(); playB.isEnabled = false
                st.text = "$n lignes hors ligne effacées. Appuie sur « 2 » pour les retraduire avec tes serveurs."
                renderPanel()
            }
        })
        col.addView(btn("Restaurer la dernière progression effacée") {
            if (!running && uri != null) confirmDanger(
                "Restaurer la sauvegarde ?",
                "La progression actuelle sera remplacée par la dernière copie de secours.",
                null
            ) { restoreState() }
        })
        col.addView(btn("⚠ Effacer la progression") {
            if (!running && uri != null) confirmDanger(
                "Effacer TOUTE la progression ?",
                "Tu vas perdre l’analyse jusqu’à ${hms(done)} et ${memo.size} lignes traduites pour cette vidéo. Une copie de secours est gardée : « Restaurer » la remet.",
                "EFFACER"
            ) {
                backupState()
                items.clear(); memo.clear(); offlineKeys.clear(); srcMap.clear(); retraSet.clear(); done = 0.0; cues = listOf()
                asr.clear(); asrDone = 0.0; zhFix.clear()
                sf().delete()
                playB.isEnabled = false
                st.text = "Progression effacée. Une copie de secours existe : « Restaurer »."
            }
        }.also { it.setTextColor(Color.RED) })
        st = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(st)

        vv = VideoView(this)
        ov = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt()); textSize = 20f
            gravity = Gravity.CENTER; setOnClickListener { mode = (mode + 1) % 3 }
        }
        box = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true
            addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(ov, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(70) })
            addView(btn("✕") { closePlayer() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }
        addZh = EditText(this).apply { hint = "Mot chinois (ou mot à chercher)"; setSingleLine(true) }
        addAr = EditText(this).apply { hint = "Arabe (vide = auto)"; setSingleLine(true) }
        gedit = EditText(this).apply { minLines = 8; gravity = Gravity.TOP; setHorizontallyScrolling(false) }
        gbox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); visibility = View.GONE; isClickable = true
            setPadding(dp(12), dp(36), dp(12), dp(12))
            addView(TextView(this@MainActivity).apply { text = "Mon glossaire : chinois = arabe\nUne ligne par terme, avec « = » entre les deux. Pour ajouter vite : remplis les deux cases puis « Ajouter » (arabe vide = traduction automatique)."; textSize = 16f })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btn("Enregistrer") { saveGlossary(true) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(btn("Fermer") { closeGlossary() }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(addZh, LinearLayout.LayoutParams(0, -2, 1f))
                addView(addAr, LinearLayout.LayoutParams(0, -2, 1f))
                addView(btn("Ajouter") { addTermFromFields() }, LinearLayout.LayoutParams(-2, -2))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btn("Chercher") { searchGlossary() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(btn("Mots de la vidéo") { openDetected() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(btn("Importer phrases") { pickPhrases() }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(ScrollView(this@MainActivity).apply { addView(gedit) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply { addView(col) })
            addView(box)
            addView(gbox, FrameLayout.LayoutParams(-1, -1))
        })
        renderPanel()
    }

    private fun bak() = File(filesDir, "bak_" + sf().name)

    private fun backupState() {
        try { if (sf().exists()) sf().copyTo(bak(), overwrite = true) } catch (e: Exception) {}
    }

    private fun restoreState() {
        val b = bak()
        if (!b.exists()) { st.text = "Aucune sauvegarde à restaurer."; return }
        try {
            b.copyTo(sf(), overwrite = true)
            loadState()
            if (items.isNotEmpty()) { sTop.progress = savedTop; sBot.progress = savedBot; etFrom.setText(hms(savedStart)) }
            playB.isEnabled = cues.isNotEmpty() && cues.all { it.ar.isNotEmpty() }
            st.text = "Progression restaurée : analyse ${hms(done)}, ${memo.size} lignes traduites."
        } catch (e: Exception) { st.text = "Restauration impossible : ${e.message}" }
    }

    private fun confirmDanger(title: String, msg: String, word: String?, action: () -> Unit) {
        val et = EditText(this).apply { hint = if (word != null) "Tape $word pour confirmer" else "" }
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        if (word != null) b.setView(et)
        b.setNegativeButton("Annuler", null)
        b.setPositiveButton("Confirmer") { _, _ ->
            if (word == null || et.text.toString().trim().equals(word, true)) action()
            else st.text = "Mot incorrect : rien n’a été effacé."
        }
        b.show()
    }

    private fun loadGl() {
        try {
            val f = File(filesDir, "glossaire.json"); if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.optJSONObject("user")?.let { m -> m.keys().forEach { userGl[it] = m.getString(it) } }
            o.optJSONObject("learned")?.let { m -> m.keys().forEach { glossary[it] = m.getString(it) } }
        } catch (e: Exception) {}
    }

    @Synchronized private fun saveGl(): Boolean {
        savePhrases()
        return try {
            File(filesDir, "glossaire.json").writeText(
                JSONObject().put("user", JSONObject(userGl.toMap())).put("learned", JSONObject(glossary.toMap())).toString())
            true
        } catch (e: Exception) { false }
    }

    private fun editGlossary() {
        val all = LinkedHashMap<String, String>(); all.putAll(glossary); all.putAll(userGl)
        gedit.setText(all.entries.joinToString("\n") { "${it.key} = ${it.value}" })
        gOriginal = gedit.text.toString()
        gbox.visibility = View.VISIBLE
    }

    private fun saveGlossary(close: Boolean) {
        val nu = LinkedHashMap<String, String>(); var ignored = 0
        val sep = Regex("\\s*(.+?)\\s*(?:=|＝|→|->|：|:|\\t)\\s*(.+)")
        for (l in gedit.text.toString().lines()) {
            if (l.isBlank()) continue
            val m = sep.matchEntire(l.trim())
            if (m == null) { ignored++; continue }
            nu[m.groupValues[1].trim()] = m.groupValues[2].trim()
        }
        userGl.clear(); userGl.putAll(nu); glossary.clear()
        val ok = saveGl()
        st.text = (if (ok) "Glossaire enregistré : ${nu.size} termes" else "ÉCHEC de l’enregistrement du glossaire") +
            (if (ignored > 0) " ($ignored lignes ignorées : écris « chinois = arabe »)." else ".")
        gOriginal = gedit.text.toString()
        if (close) closeGlossaryNow()
    }

    private fun closeGlossaryNow() {
        try { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(gedit.windowToken, 0) } catch (e: Exception) {}
        gbox.visibility = View.GONE
    }

    private fun closeGlossary() {
        if (gedit.text.toString() == gOriginal) { closeGlossaryNow(); return }
        AlertDialog.Builder(this).setTitle("Modifications non enregistrées")
            .setMessage("Les enregistrer avant de fermer ?")
            .setPositiveButton("Enregistrer") { _, _ -> saveGlossary(true) }
            .setNeutralButton("Perdre") { _, _ -> closeGlossaryNow() }
            .setNegativeButton("Continuer à modifier", null).show()
    }

    private fun offlineLine(tr: Translator, zh: String): String {
        userGl[zh]?.let { return it }
        if (!srcIsEn) synchronized(phrases) { phrases[norm(zh)] }?.let { return it }
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
        if (rq == 4) {
            val su = d?.data
            if (rc == RESULT_OK && su != null) translateSrtFile(su)
            return
        }
        if (rq == 5) {
            val pu = d?.data
            if (rc == RESULT_OK && pu != null) importPhrases(pu)
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
                    if (items.isNotEmpty()) { sTop.progress = savedTop; sBot.progress = savedBot; etFrom.setText(hms(savedStart)) }
                    drawPreview(); renderPanel(); goB.isEnabled = true
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
        if (srtMode) {
            try {
                File(filesDir, "srt_$srtKey.json").writeText(JSONObject().put("memo", JSONObject(memo.toMap()))
                    .put("src", JSONObject(srcMap.toMap())).put("off", JSONArray(offlineKeys.toList())).toString())
            } catch (e: Exception) {}
            saveGl(); return
        }
        val o = JSONObject().put("done", done).put("tp", savedTop).put("bt", savedBot).put("rs", savedStart)
        o.put("items", JSONArray().also { a -> items.forEach { a.put(JSONArray().put(it.first).put(it.second)) } })
        o.put("memo", JSONObject(memo.toMap())).put("g", JSONObject(glossary.toMap())).put("off", JSONArray(offlineKeys.toList())).put("src", JSONObject(srcMap.toMap())).put("ret", JSONArray(retraSet.toList()))
        o.put("ad", asrDone).put("zf", JSONObject(zhFix.toMap()))
        o.put("asr", JSONArray().also { a -> synchronized(asr) { asr.forEach { sg -> a.put(JSONArray().put(sg.start).put(sg.end).put(sg.text)) } } })
        sf().writeText(o.toString())
        saveGl()
    }

    private fun loadState() {
        items.clear(); memo.clear(); offlineKeys.clear(); srcMap.clear(); retraSet.clear(); done = 0.0; cues = listOf(); savedStart = 0.0
        asr.clear(); asrDone = 0.0; zhFix.clear()
        val f = sf(); if (!f.exists()) return
        val o = JSONObject(f.readText())
        done = o.optDouble("done", 0.0); savedTop = o.optInt("tp", 80); savedBot = o.optInt("bt", 98); savedStart = o.optDouble("rs", 0.0)
        val a = o.getJSONArray("items")
        for (i in 0 until a.length()) { val x = a.getJSONArray(i); items.add(x.getDouble(0) to x.getString(1)) }
        val m = o.getJSONObject("memo"); m.keys().forEach { memo[it] = m.getString(it) }
        offlineKeys.clear(); o.optJSONArray("off")?.let { a -> for (i in 0 until a.length()) offlineKeys.add(a.getString(i)) }
        o.optJSONObject("src")?.let { s2 -> s2.keys().forEach { srcMap[it] = s2.getString(it) } }
        o.optJSONArray("ret")?.let { a -> for (i in 0 until a.length()) retraSet.add(a.getString(i)) }
        asrDone = o.optDouble("ad", 0.0)
        o.optJSONObject("zf")?.let { z -> z.keys().forEach { zhFix[it] = z.getString(it) } }
        o.optJSONArray("asr")?.let { a -> for (i in 0 until a.length()) { val x = a.getJSONArray(i); asr.add(Seg(x.getDouble(0), x.getDouble(1), x.getString(2))) } }
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
        val s = clean(r.textBlocks.flatMap { it.lines }.joinToString(" ") { it.text })
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

    // Accepte : 90 (minutes) · 1:30 (h:mm) · 1:30:15 (h:mm:ss) · -2:00 ou -120 (depuis la fin de la vidéo)
    private fun parseTime(raw: String, full: Double): Double? {
        var t = raw.trim().replace(',', '.').replace('：', ':')
        if (t.isEmpty()) return null
        val neg = t.startsWith("-")
        if (neg) t = t.substring(1).trim()
        val p = t.split(":")
        val v: Double = try {
            when (p.size) {
                1 -> p[0].toDouble() * 60
                2 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60
                3 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble()
                else -> return null
            }
        } catch (e: Exception) { return null }
        return if (neg) (full - v).coerceAtLeast(0.0) else v
    }

    private fun start() {
        if (running || uri == null) return
        val k = key.text.toString().trim()
        val k2 = key2.text.toString().trim()
        val k3 = key3.text.toString().trim()
        getSharedPreferences("p", 0).edit().putString("k", k).putString("kg", k2).putString("ko", k3).apply(); k4 = key4.text.toString().trim(); getSharedPreferences("p", 0).edit().putString("k4", k4).apply()
        val ct = sTop.progress; val cb = sBot.progress
        val full = durMs / 1000.0
        val rs = (parseTime(etFrom.text.toString(), full) ?: 0.0).coerceIn(0.0, full)
        val reRaw = parseTime(etTo.text.toString(), full) ?: 0.0
        val re = if (reRaw <= 0.0 || reRaw > full) full else reRaw
        if (re <= rs) { st.text = "La fin doit être après le début."; return }
        useAsr = cbAsr.isChecked
        getSharedPreferences("p", 0).edit().putBoolean("asr", useAsr).apply()
        if (savedStart != rs) { asr.clear(); asrDone = 0.0 }
        if (savedTop != ct || savedBot != cb || savedStart != rs) { items.clear(); done = 0.0 }
        done = maxOf(done, rs)
        asrDone = maxOf(asrDone, rs)
        savedTop = ct; savedBot = cb; savedStart = rs; rEnd = re
        running = true; stop = false; goB.isEnabled = false; lastOk = false; phase = ""
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
                        phaseInfo = "Analyse ${hms(t)} / ${hms(dur)} · ${items.size} changements"; phase = "analyse"
                        ui(phaseInfo); runOnUiThread { renderPanel() }
                    }
                    r.release(); saveState()
                }
                if (useAsr && !stop) runAsr(re)
                if (stop) { ui("Arrêté. Relance l’analyse pour reprendre."); return@Thread }
                cues = buildCues()
                attachHints(cues)
                if (cues.isEmpty()) { ui("Aucun sous-titre détecté : vérifie la zone rouge."); return@Thread }
                ui("${cues.size} sous-titres. Traduction…")
                autoGlossary(k, k2, k3)
                translateAll(k, k2, k3)
                if (stop) { saveState(); ui("Arrêté pendant la traduction. Relance pour reprendre."); return@Thread }
                cues.forEach { it.ar = memo[it.zh] ?: "" }
                saveState()
                val inTree = exportAll()
                val where = if (inTree) "dans le dossier choisi : $base.srt (arabe), $base.zh.srt, $base.bilingue.srt" else "dans Téléchargements : $base.ar.srt, $base.zh.srt, $base.bilingue.srt"
                val fixNote = if (useAsr) (" Audio : ${asr.size} phrases, ${cues.count { zhFix.containsKey(it.zh) }} lignes de chinois corrigées." + (if (asr.isNotEmpty()) " Fichier $base.audio.srt = audio brut." else "") + asrNote) else ""
                val glNote = if (autoGlN > 0) " Glossaire : $autoGlN mots ajoutés automatiquement (à vérifier dans « Mon glossaire »)." else ""
                val testNote = if (re < full) " Test terminé : vérifie avec « 3. Lire », puis mets Fin = 0 et relance pour toute la vidéo." else ""
                ui("Terminé ✓ ${cues.size} sous-titres, $where.$testNote$fixNote$glNote" + if (fellBack) " Une partie a été traduite hors ligne. Serveurs en échec : " + failNotes.trim() + "." else "")
                lastOk = true
                runOnUiThread { playB.isEnabled = true }
            } catch (e: Exception) {
                try { saveState() } catch (x: Exception) {}
                ui("Erreur : ${e.message ?: e}. La progression est gardée : relance pour reprendre.")
            } finally {
                running = false
                phase = if (stop) "arrêté" else if (lastOk) "terminé" else "erreur"
                stopService(Intent(this, KeepAliveService::class.java))
                runOnUiThread { goB.isEnabled = true; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); renderPanel() }
            }
        }.start()
    }

    private fun zhOf(c: Cue): String = zhFix[c.zh] ?: c.zh

    private fun okFix(orig: String, z: String): Boolean {
        val a = norm(orig); val b = norm(z)
        if (b.isEmpty() || a == b) return false
        if (!Regex("[\\u4e00-\\u9fff]").containsMatchIn(b)) return false
        return b.length >= a.length * 0.5 && b.length <= a.length * 1.6 + 2
    }

    private fun cover(a: String, b: String): Double {
        if (a.length < 2) return if (a.isNotEmpty() && b.contains(a)) 1.0 else 0.0
        var c = 0
        for (i in 0 until a.length - 1) if (b.contains(a.substring(i, i + 2))) c++
        return c.toDouble() / (a.length - 1)
    }

    private fun runAsr(to: Double) {
        val u = uri ?: return
        asrNote = ""
        if (asrDone >= to) return
        try {
            phase = "analyse"
            val md = Asr.ensureModel(this, { m -> phaseInfo = m; ui(m); runOnUiThread { renderPanel() } }, { stop })
            if (stop) return
            var ls = System.currentTimeMillis()
            Asr.transcribe(this, u, md, asrDone, to,
                { sg -> synchronized(asr) { asr.add(sg) } },
                { t ->
                    asrDone = maxOf(asrDone, t)
                    phaseInfo = "Audio ${hms(t)} / ${hms(to)} · ${asr.size} phrases"; phase = "analyse"
                    ui(phaseInfo); runOnUiThread { renderPanel() }
                    if (System.currentTimeMillis() - ls > 20000) { saveState(); ls = System.currentTimeMillis(); notifyProgress("Audio ${hms(t)} / ${hms(to)}") }
                },
                { stop })
            if (!stop) asrDone = to
            saveState()
        } catch (e: Exception) {
            asrNote = " Audio ignoré (" + (e.message ?: e.toString()).replace(Regex("\\s+"), " ").take(90) + ")."
            try { saveState() } catch (x: Exception) {}
        }
    }

    private fun attachHints(list: List<Cue>) {
        val segs = synchronized(asr) { asr.sortedBy { it.start } }
        var lo = 0
        for (c in list) {
            c.hint = ""
            if (!useAsr || segs.isEmpty()) continue
            val a = c.start - 1.0; val b = c.end + 1.0
            while (lo < segs.size && segs[lo].end < a) lo++
            val sb = StringBuilder()
            var i = lo
            while (i < segs.size && segs[i].start <= b) { if (segs[i].end >= a) sb.append(segs[i].text); i++ }
            val h = sb.toString()
            if (h.isEmpty()) continue
            if (cover(norm(c.zh), norm(h)) >= 0.7) continue
            c.hint = h.take(120)
        }
    }

    private fun audioSrt(): String {
        val segs = synchronized(asr) { asr.sortedBy { it.start } }
        return segs.withIndex().joinToString("\n") { (i, sg) ->
            "${i + 1}\n${ts(sg.start)} --> ${ts(maxOf(sg.end, sg.start + 0.5))}\n${sg.text}\n"
        }
    }

    private fun offlineTranslate(list: List<Cue>) {
        val tr = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(if (srcIsEn) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.ARABIC).build())
        activeName = "Hors ligne"; pst("Hors ligne", "ACTIF — préparation du modèle local…")
        val haveModel = try {
            Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(
                TranslateRemoteModel.Builder(if (srcIsEn) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE).build()))
        } catch (e: Exception) { false }
        if (!haveModel) ui("Téléchargement du modèle hors ligne (une seule fois)…")
        Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        pst("Hors ligne", "ACTIF — traduction locale")
        var n = 0
        for (c in list) {
            if (stop) return
            if (memo[c.zh] != null) continue
            memo[c.zh] = offlineLine(tr, c.zh); offlineKeys.add(c.zh); srcMap[c.zh] = "H"
            offDone++
            if (++n % 25 == 0) {
                ui("Traduction hors ligne : $offDone"); notifyProgress("Traduction hors ligne : $offDone"); saveState()
                pst("Hors ligne", "ACTIF — $offDone lignes au total")
            }
        }
        try { tr.close() } catch (e: Exception) {}
        pst("Hors ligne", "terminé — $offDone lignes au total"); activeName = ""
    }

    private fun online(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { true }

    private fun prepareOffline() {
        try {
            val tr = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(if (srcIsEn) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.ARABIC).build())
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            tr.close()
        } catch (e: Exception) {}
    }

    private fun codeOf(n: String) = when (n) { "Gemini" -> "G"; "Gemini 2" -> "g"; "Groq" -> "Q"; "OpenRouter" -> "O"; "Claude" -> "C"; else -> "H" }

    private fun pst(n: String, t: String) {
        synchronized(pstate) { pstate[n] = t }
        runOnUiThread { renderPanel() }
    }

    private fun quotaLine(n: String): String {
        if (n == "Hors ligne") return "illimité (sur ton téléphone)"
        val q = synchronized(pquota) { pquota[n] }
        val m = synchronized(pmodel) { pmodel[n] }
        val e = synchronized(perr) { perr[n] }
        return "demandes envoyées aujourd’hui : " + usedToday(n) +
            (if (q != null) " · $q" else if (n.startsWith("Gemini")) " · reste exact : AI Studio > Usage (non fourni par l’API)" else "") +
            (synchronized(pReset) { pReset[n] }?.let { ra -> if (ra > System.currentTimeMillis()) "\n     ⏳ limite atteinte : reprise dans " + fmtDur(ra - System.currentTimeMillis()) + " (vers " + hhmm(ra) + ")" else null } ?: "") +
            (if (m != null) "\n     modèle : $m" else "") +
            (if (e != null) "\n     dernière erreur : $e" else "")
    }

    private fun renderPanel() {
        val green = 0xFF1B7F3A.toInt()
        val names = ArrayList<String>().apply { add(firstName); if (k4.isNotEmpty()) add("Gemini 2"); add("Groq"); add("OpenRouter"); add("Hors ligne") }
        val total = cues.count { memo[it.zh] != null }
        val head = StringBuilder()
        when {
            phase == "analyse" -> head.append("▶ EN MARCHE : ").append(phaseInfo)
            activeName.isNotEmpty() -> {
                val cnt = cues.count { srcMap[it.zh] == codeOf(activeName) }
                val s = synchronized(pstate) { pstate[activeName] ?: "" }
                head.append("▶ SERVEUR ACTIF : ").append(activeName).append(" — en marche\n   ").append(s)
                    .append("\n   Traduit par ").append(activeName).append(" : ").append(cnt).append(" lignes")
                    .append("\n   ").append(quotaLine(activeName))
            }
            phase == "traduction" -> head.append("▶ Traduction : choix du serveur…")
            phase == "terminé" -> head.append("✓ Travail terminé")
            phase == "arrêté" -> head.append("■ Arrêté (progression gardée)")
            phase == "erreur" -> head.append("✗ Arrêt sur erreur (progression gardée)")
            else -> head.append("Serveur actif : aucun")
        }
        head.append("\nTotal traduit : ").append(total).append(" / ").append(cues.size).append(" · reste : ").append(cues.size - total)
        if (retraSet.isNotEmpty()) {
            val rem = retraSet.count { memo[it] == null || srcMap[it] == "H" }
            head.append("\nRetraduction de la partie hors ligne : reste ").append(rem).append(" / ").append(retraSet.size)
            if (rem == 0) head.append(" ✓ terminée")
        }
        val rest = StringBuilder("\n\nInternet : ").append(if (online()) "oui" else "non").append("\nAutres serveurs :\n")
        var known = 0
        for (n in names) {
            val cnt = cues.count { srcMap[it.zh] == codeOf(n) }
            known += cnt
            if (n == activeName) continue
            val s = synchronized(pstate) { pstate[n] ?: "en attente" }
            rest.append("• ").append(n).append(" — ").append(s).append(" — ").append(cnt).append(" lignes\n     ")
                .append(quotaLine(n)).append("\n")
        }
        if (total > known) rest.append("• Avant cette version : ").append(total - known).append(" lignes\n")
        run {
            val tot = cues.size
            val pm = if (tot == 0) 0 else (total * 1000L / tot).toInt()
            pbar.progress = pm
            val sb = StringBuilder("Progression de la traduction : ").append(pm / 10).append(",").append(pm % 10).append(" %  (").append(total).append(" / ").append(tot).append(" lignes)")
            val dn = total - transBase
            val el = System.currentTimeMillis() - transStart
            if ((phase == "traduction" || activeName.isNotEmpty()) && tot > total && dn >= 10 && el > 30000L && transStart > 0L)
                sb.append("\nTemps restant estimé : ").append(fmtDur((tot - total) * el / dn))
            if (tot in 1..total) sb.append(" ✓")
            ptxt.text = sb.toString()
        }
        val sp = SpannableStringBuilder(head.toString())
        sp.setSpan(ForegroundColorSpan(green), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.append(rest.toString().trimEnd())
        panel.text = sp
    }

    private fun translateAll(k1: String, k2: String, k3: String) {
        fellBack = false
        failNotes = ""
        activeName = ""
        phase = "traduction"
        firstName = if (k1.startsWith("sk-ant")) "Claude" else "Gemini"
        synchronized(pstate) {
            pstate.clear()
            pstate[firstName] = if (k1.isEmpty()) "pas de clé" else "en attente"
            if (k4.isNotEmpty()) pstate["Gemini 2"] = "en attente"
            pstate["Groq"] = if (k2.isEmpty()) "pas de clé" else "en attente"
            pstate["OpenRouter"] = if (k3.isEmpty()) "pas de clé" else "en attente"
            pstate["Hors ligne"] = "prêt"
        }
        synchronized(perr) { perr.clear() }
        runOnUiThread { renderPanel() }
        offDone = 0
        cues.forEach { c -> userGl[c.zh]?.let { memo[c.zh] = it } }
        fun todo() = cues.filter { memo[it.zh] == null }
        val chain = mutableListOf<Prov>()
        val nKeys = listOf(k1, k2, k3, k4).count { it.isNotEmpty() }
        if (k1.startsWith("sk-ant")) chain.add(Prov("Claude", 25, 0L) { c, b -> claude(k1, c, b) })
        else if (k1.isNotEmpty()) chain.add(Prov("Gemini", 40, 0L) { c, b -> geminiName = "Gemini"; gemini(k1, c, b, if (nKeys > 1) 3 else 12, "Gemini") })
        if (k4.isNotEmpty()) chain.add(Prov("Gemini 2", 40, 0L) { c, b -> geminiName = "Gemini 2"; gemini(k4, c, b, if (nKeys > 1) 3 else 12, "Gemini 2") })
        if (k2.isNotEmpty()) chain.add(Prov("Groq", 15, 2500L) { c, b ->
            oai("Groq", "https://api.groq.com/openai/v1", k2, listOf("llama-3.3-70b", "llama-4", "qwen", "gpt-oss-120b"), false, c, b)
        })
        if (k3.isNotEmpty()) chain.add(Prov("OpenRouter", 12, 4000L) { c, b ->
            oai("OpenRouter", "https://openrouter.ai/api/v1", k3, listOf("qwen", "llama-3.3-70b", "gpt-oss-120b", "gemma"), true, c, b)
        })
        if (chain.isEmpty()) { offlineTranslate(todo()); return }
        if (online()) {
            ui("Préparation du mode hors ligne (une seule fois)…")
            prepareOffline()
            if (k3.isNotEmpty()) refreshOrQuota(k3)
        }
        val until = HashMap<String, Long>()
        val bsz = HashMap<String, Int>()
        val pf = HashMap<String, Int>()
        val netFail = HashMap<String, Int>()
        val down = HashSet<String>()
        val probeAt = HashMap<String, Long>()
        val resetKnown = HashSet<String>()
        val back = HashSet<String>()
        val far = Long.MAX_VALUE / 4
        var cur = ""
        synchronized(pReset) { pReset.clear() }
        transStart = System.currentTimeMillis(); transBase = cues.count { memo[it.zh] != null }
        chain.forEach { bsz[it.name] = it.batch }
        fun probe(name: String): Boolean = try {
            val u: String
            val h = HashMap<String, String>()
            when (name) {
                "Gemini" -> { u = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1"; h["x-goog-api-key"] = k1 }
                "Gemini 2" -> { u = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1"; h["x-goog-api-key"] = k4 }
                "Claude" -> { u = "https://api.anthropic.com/v1/models?limit=1"; h["x-api-key"] = k1; h["anthropic-version"] = "2023-06-01" }
                "Groq" -> { u = "https://api.groq.com/openai/v1/models"; h["Authorization"] = "Bearer $k2" }
                else -> { u = "https://openrouter.ai/api/v1/key"; h["Authorization"] = "Bearer $k3" }
            }
            val pc = URL(u).openConnection() as HttpURLConnection
            for ((hk, hv) in h) pc.setRequestProperty(hk, hv)
            pc.connectTimeout = 10000; pc.readTimeout = 10000
            val pcode = pc.responseCode
            pc.disconnect()
            pcode < 500 && pcode != 408
        } catch (e: Exception) { false }
        while (!stop) {
            val now = System.currentTimeMillis()
            val onl = online()
            // 1) serveurs qui ne répondaient pas : test toutes les minutes
            if (onl) for (p in chain) {
                if (down.contains(p.name) && now >= (probeAt[p.name] ?: 0L)) {
                    if (probe(p.name)) {
                        down.remove(p.name); until[p.name] = 0L; back.add(p.name); netFail[p.name] = 0
                        synchronized(perr) { perr.remove(p.name) }
                        pst(p.name, "de nouveau en ligne ✓ — on y retourne")
                    } else {
                        probeAt[p.name] = System.currentTimeMillis() + 60_000L
                        pst(p.name, "toujours hors ligne — nouveau test dans 1 min")
                    }
                }
            }
            // 2) limites dont l'heure de remise à zéro est passée : retour automatique
            for (p in chain) if (resetKnown.contains(p.name) && (until[p.name] ?: 0L) <= now) {
                resetKnown.remove(p.name); back.add(p.name)
                synchronized(pReset) { pReset.remove(p.name) }
                pst(p.name, "limite remise à zéro ✓ — on y retourne")
            }
            val avail = chain.filter { (until[it.name] ?: 0L) <= now }
            val curP = chain.firstOrNull { it.name == cur && (until[it.name] ?: 0L) <= now }
            val sel: Prov? = if (!onl) null
                else if (curP != null) (avail.firstOrNull { chain.indexOf(it) < chain.indexOf(curP) && back.contains(it.name) } ?: curP)
                else avail.firstOrNull()
            if (sel == null) {
                val chunk = todo().take(150); if (chunk.isEmpty()) break
                fellBack = true
                if (!onl && !failNotes.contains("internet")) failNotes += "internet coupé "
                ui(if (onl) "Serveurs en pause : traduction hors ligne d’un bloc de ${chunk.size} lignes…" else "Pas d’internet : traduction hors ligne…")
                offlineTranslate(chunk)
                continue
            }
            val prov: Prov = sel
            back.remove(prov.name)
            if (prov.name != cur) { cur = prov.name; ui("Serveur : " + prov.name) }
            val b = todo().take(minOf(bsz[prov.name] ?: prov.batch, prov.batch)); if (b.isEmpty()) break
            val i = cues.indexOf(b[0])
            val ctx = cues.subList(maxOf(0, i - 8), i).filter { memo[it.zh] != null }
            activeName = prov.name
            pst(prov.name, "ACTIF — traduit un lot de ${b.size} lignes…")
            try {
                val res = prov.call(ctx, b)
                val fx = lastFix
                var got = 0
                b.forEachIndexed { j, c ->
                    val r = if (j < res.size) res[j] else ""
                    if (r.isNotBlank()) { memo[c.zh] = r; offlineKeys.remove(c.zh); srcMap[c.zh] = codeOf(prov.name); got++; learnPhrase(c.zh, r); fx[j]?.let { z -> if (okFix(c.zh, z)) zhFix[c.zh] = z } }
                }
                if (got == 0) throw Exception("aucune ligne exploitable dans la réponse")
                if (got < b.size) bsz[prov.name] = maxOf(5, (bsz[prov.name] ?: prov.batch) * 2 / 3)
                synchronized(perr) { perr.remove(prov.name) }
                pf[prov.name] = 0
                netFail[prov.name] = 0
                val msg = "Traduction ${cues.count { memo[it.zh] != null }} / ${cues.size} (${prov.name})"
                ui(msg); notifyProgress(msg); saveState()
                pst(prov.name, "ACTIF ✓ dernier lot : $got/${b.size} lignes")
                if (prov.name == "OpenRouter") refreshOrQuota(k3)
                if (prov.pause > 0) Thread.sleep(prov.pause)
            } catch (e: Exception) {
                if (stop) return
                if (e is ParseErr && (pf[prov.name] ?: 0) < 3) {
                    pf[prov.name] = (pf[prov.name] ?: 0) + 1
                    val cur2 = bsz[prov.name] ?: prov.batch
                    synchronized(perr) { perr[prov.name] = "réponse illisible (ignorée, on continue)" }
                    if (b.size <= 3) {
                        pst(prov.name, "réponse illisible : ${b.size} ligne(s) traduite(s) hors ligne, on continue")
                        activeName = ""
                        try { offlineTranslate(b) } catch (x: Exception) {}
                        bsz[prov.name] = minOf(prov.batch, 8)
                    } else {
                        bsz[prov.name] = maxOf(3, cur2 / 2)
                        pst(prov.name, "réponse illisible : nouvel essai avec un lot plus petit")
                    }
                    Thread.sleep(500)
                    continue
                }
                synchronized(perr) { perr[prov.name] = (e.message ?: e.toString()).replace(Regex("\\s+"), " ").take(160) }
                val ae = e as? ApiErr
                val isLimit = ae != null && ae.code == 429
                val isKey = ae != null && (ae.code == 401 || ae.code == 403)
                val isDown = e is java.io.IOException || (ae != null && (ae.code >= 500 || ae.code == 408))
                if (isDown && !online()) { activeName = ""; continue }
                if (isDown) {
                    // le serveur ne répond pas : 2e essai après 20 s, puis serveur suivant
                    val nf = (netFail[prov.name] ?: 0) + 1
                    netFail[prov.name] = nf
                    if (nf < 2) {
                        activeName = ""
                        for (sec in 20 downTo 1) {
                            if (stop) return
                            pst(prov.name, "ne répond pas — nouvel essai dans $sec s")
                            Thread.sleep(1000)
                        }
                        continue
                    }
                    netFail[prov.name] = 0
                    down.add(prov.name); probeAt[prov.name] = System.currentTimeMillis() + 60_000L; until[prov.name] = far
                    failNotes += prov.name + " (hors ligne) "
                    pst(prov.name, "hors ligne — test automatique chaque minute")
                    activeName = ""
                    ui(prov.name + " ne répond pas : serveur suivant…")
                    Thread.sleep(500)
                    continue
                }
                val nowE = System.currentTimeMillis()
                if (isLimit) {
                    val r = ae!!.resetMs
                    if (r > nowE && r - nowE < 36L * 3600 * 1000) {
                        until[prov.name] = r + 2000L; resetKnown.add(prov.name)
                        synchronized(pReset) { pReset[prov.name] = r + 2000L }
                        pst(prov.name, "limite atteinte — reprise automatique dans ${fmtDur(r - nowE)} (vers ${hhmm(r)})")
                    } else {
                        until[prov.name] = nowE + 30 * 60 * 1000L
                        pst(prov.name, "limite atteinte — heure de remise à zéro non fournie : on reste sur le serveur actuel")
                    }
                } else if (isKey) {
                    until[prov.name] = nowE + 6 * 3600 * 1000L
                    pst(prov.name, "clé refusée (${ae!!.code}) — serveur ignoré")
                } else {
                    until[prov.name] = nowE + 3 * 60 * 1000L
                    pst(prov.name, "en pause — nouvel essai dans 3 min")
                }
                if (e !is ApiErr) bsz[prov.name] = maxOf(5, (bsz[prov.name] ?: prov.batch) / 2)
                failNotes += prov.name + " (" + (if (ae != null) ae.code.toString() else "erreur") + ") "
                activeName = ""
                ui(prov.name + " : serveur suivant…")
                Thread.sleep(1000)
            }
        }
        activeName = ""
        chain.forEach { if ((until[it.name] ?: 0L) <= System.currentTimeMillis()) pst(it.name, "terminé") }
    }

    private fun fmtDur(ms: Long): String {
        val t = maxOf(0L, ms) / 1000
        return when {
            t >= 3600 -> "%d h %02d min".format(t / 3600, t % 3600 / 60)
            t >= 60 -> "%d min %02d s".format(t / 60, t % 60)
            else -> "$t s"
        }
    }

    private fun hhmm(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    private fun nextPacificMidnight(now: Long): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun parseDur(s: String): Long? {
        val m = Regex("(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:([0-9.]+)s)?").matchEntire(s.trim()) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val mi = m.groupValues[2].toLongOrNull() ?: 0L
        val se = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return ((h * 3600 + mi * 60) * 1000 + se * 1000).toLong()
    }

    /** Heure (en ms) à laquelle la limite sera remise à zéro, si le serveur la fournit ; -1 sinon. */
    private fun resetFrom(url: String, c: HttpURLConnection, body: String): Long {
        val now = System.currentTimeMillis()
        try {
            if (provOf(url).startsWith("Gemini")) {
                if (body.contains("PerDay", true) || body.contains("per day", true)) return nextPacificMidnight(now)
                Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(body)?.let { return now + (it.groupValues[1].toDouble() * 1000).toLong() }
            }
            c.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.let { return now + (it * 1000).toLong() }
            c.getHeaderField("x-ratelimit-reset-requests")?.let { r -> parseDur(r)?.let { d -> return now + d } }
            val h = c.getHeaderField("X-RateLimit-Reset")
                ?: Regex("(?i)x-ratelimit-reset\"?\\s*[:=]\\s*\"?(\\d{10,13})").find(body)?.groupValues?.get(1)
            h?.toLongOrNull()?.let { v -> val ms = if (v < 100000000000L) v * 1000 else v; if (ms > now) return ms }
        } catch (e: Exception) {}
        return -1L
    }

    private fun provOf(url: String) = when {
        url.contains("generativelanguage") -> geminiName
        url.contains("groq") -> "Groq"
        url.contains("openrouter") -> "OpenRouter"
        else -> "Claude"
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun usedToday(n: String): Int {
        val sp = getSharedPreferences("p", 0)
        return if (sp.getString("d_$n", "") == today()) sp.getInt("n_$n", 0) else 0
    }

    private fun countReq(n: String) {
        val sp = getSharedPreferences("p", 0)
        sp.edit().putString("d_$n", today()).putInt("n_$n", usedToday(n) + 1).apply()
    }

    private fun quotaFrom(url: String, c: HttpURLConnection) {
        val n = provOf(url)
        countReq(n)
        if (n == "Groq") {
            val rr = c.getHeaderField("x-ratelimit-remaining-requests"); val rl = c.getHeaderField("x-ratelimit-limit-requests")
            val tr = c.getHeaderField("x-ratelimit-remaining-tokens"); val tl = c.getHeaderField("x-ratelimit-limit-tokens")
            val rs = c.getHeaderField("x-ratelimit-reset-requests")
            if (rr != null) synchronized(pquota) {
                pquota["Groq"] = "reste : $rr / $rl demandes aujourd’hui (renouvelé dans $rs) · jetons cette minute : $tr / $tl"
            }
        }
        runOnUiThread { renderPanel() }
    }

    private fun refreshOrQuota(key: String) {
        try {
            val c = URL("https://openrouter.ai/api/v1/key").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $key"); c.connectTimeout = 15000; c.readTimeout = 20000
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            val t: String
            if (code >= 400) t = "clé refusée ($code)"
            else {
                val d = JSONObject(txt).getJSONObject("data")
                val parts = mutableListOf<String>()
                val f = d.opt("free_model_daily_requests")
                if (f != null && f != JSONObject.NULL) parts.add("gratuit par jour : " + f.toString().replace("\"", "").take(80))
                if (d.has("is_free_tier")) parts.add(if (d.optBoolean("is_free_tier")) "offre gratuite (limite indicative : 50 demandes/jour)" else "compte avec crédits")
                t = if (parts.isEmpty()) "clé valide" else parts.joinToString(" · ")
            }
            synchronized(pquota) { pquota["OpenRouter"] = t }
            runOnUiThread { renderPanel() }
        } catch (e: Exception) {}
    }

    private fun checkQuotas() {
        val k1 = key.text.toString().trim(); val k2 = key2.text.toString().trim(); val k3 = key3.text.toString().trim()
        ui("Vérification des quotas…")
        Thread {
            try {
                if (k3.isNotEmpty()) refreshOrQuota(k3)
                if (k2.isNotEmpty()) {
                    val base = "https://api.groq.com/openai/v1"
                    val model = pickModels(base, k2, listOf("llama-3.3-70b", "llama-4", "qwen", "gpt-oss-120b"), false).first()
                    val body = JSONObject().put("model", model).put("max_tokens", 1)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ok")))
                    try { post("$base/chat/completions", mapOf("Authorization" to "Bearer $k2"), body) } catch (e: ApiErr) {}
                }
                ui("Quotas mis à jour (voir le panneau vert).")
            } catch (e: Exception) { ui("Vérification impossible : ${e.message ?: e}") }
            runOnUiThread { renderPanel() }
        }.start()
    }

    private val modelList = HashMap<String, List<String>>()

    private fun pickModels(base: String, key: String, prefs: List<String>, freeOnly: Boolean): List<String> {
        modelList[base]?.let { return it }
        val c = URL("$base/models").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $key"); c.connectTimeout = 20000; c.readTimeout = 30000
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) throw ApiErr(code, txt)
        val arr = JSONObject(txt).getJSONArray("data")
        val skip = listOf("whisper", "embed", "guard", "tts", "orpheus", "image", "moderation", "vision", "safeguard", "compound", "allam")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            .filter { (!freeOnly || it.endsWith(":free")) && skip.none { s -> it.contains(s, true) } }
        val ordered = LinkedHashSet<String>()
        for (p in prefs) ids.filter { it.contains(p, true) }.forEach { ordered.add(it) }
        ids.forEach { ordered.add(it) }
        val l = ordered.take(6).toList()
        if (l.isEmpty()) throw Exception("aucun modèle disponible")
        modelList[base] = l
        return l
    }

    private fun oai(name: String, base: String, key: String, prefs: List<String>, freeOnly: Boolean, ctx: List<Cue>, b: List<Cue>): List<String> {
        var err: Exception? = null
        val models = pickModels(base, key, prefs, freeOnly)
        for (a in 1..4) {
            val model = models[(a - 1) % models.size]
            synchronized(pmodel) { pmodel[name] = model }
            try {
                val body = JSONObject().put("model", model).put("temperature", 0.3)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt(ctx, b))))
                val txt = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
                val msg = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val out = msg.optString("content", "")
                if (out.isBlank() || out == "null") throw ParseErr("réponse vide ($model)")
                return parse(out, b.size)
            } catch (e: Exception) {
                if (e is ApiErr && (e.code == 401 || e.code == 403)) throw e
                err = e
                if (e is ParseErr && a >= 2) throw e
                if (e is java.io.IOException) throw e
                if (e !is ApiErr && !online()) throw e
                val wait = if (e is ApiErr && (e.code == 429 || e.code >= 500)) 2L else 2L
                val c = if (e is ApiErr) e.code.toString() else "erreur"
                pst(name, "essai $a/4 échoué ($c) : autre modèle dans $wait s")
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
        p.append("Tu traduis les sous-titres d’un manhua raconté ($srcLang) en arabe standard moderne, naturel et fidèle. ")
        p.append("Garde la logique et la continuité du discours : résous les sujets et pronoms omis d’après le contexte, ")
        p.append("garde les mêmes termes et la même transcription des noms propres (glossaire), le même ton et le même registre. ")
        p.append("Ne résume pas, ne commente pas.\n\nGlossaire à respecter exactement : ").append(JSONObject(rel.toMap())).append("\n\n")
        if (ctx.isNotEmpty()) {
            p.append("Contexte déjà traduit (ne pas retraduire) :\n")
            ctx.forEach { p.append(zhOf(it)).append(" => ").append(memo[it.zh]).append("\n") }
            p.append("\n")
        }
        val numbered = JSONObject(); b.forEachIndexed { i, c -> numbered.put((i + 1).toString(), clean(c.zh)) }
        p.append("Lignes à traduire (objet JSON numéroté) :\n").append(numbered).append("\n\n")
        val hints = JSONObject(); b.forEachIndexed { i, c -> if (c.hint.isNotEmpty()) hints.put((i + 1).toString(), c.hint) }
        val withAudio = hints.length() > 0
        if (withAudio) {
            p.append("Transcription AUDIO automatique de certaines lignes (mêmes numéros) : ").append(hints).append("\n")
            p.append("Le texte à l’écran (OCR) peut contenir des caractères faux (formes proches). L’audio vient d’une reconnaissance vocale sans ponctuation : il peut contenir des mots faux (même prononciation) ou des paroles absentes de l’écran. ")
            p.append("Compare : si l’OCR est incohérent ou douteux et que l’audio donne une phrase plausible de prononciation ou de sens proche, corrige le chinois ; sinon garde l’OCR tel quel. N’invente rien. Traduis la version corrigée.\n\n")
        }
        p.append("Réponds uniquement par un objet JSON {\"t\":{\"1\":\"traduction arabe de la ligne 1\",\"2\":\"...\"},\"g\":{\"nom chinois\":\"nom arabe\"}} ; ")
        p.append("t contient exactement une entrée par numéro, avec les mêmes numéros (${b.size} lignes). ")
        p.append("g ne contient que les NOUVEAUX noms propres ou termes clés. ")
        p.append("Dans les traductions, n’utilise jamais le guillemet droit \" : utilise « » à la place. Le JSON doit être complet et valide.")
        if (withAudio) p.append(" Ajoute aussi la clé z : {\"numéro\":\"chinois corrigé\"} avec UNIQUEMENT les lignes dont tu as corrigé le chinois (objet vide si aucune).")
        return p.toString()
    }

    private fun unesc(s: String): String = try { JSONArray("[\"" + s + "\"]").getString(0) } catch (e: Exception) { s }

    private fun clean(s: String) = s.replace(Regex("[\\p{Cc}\\p{Cf}\\uFFFD]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun addTerm(k: String, v: String) {
        val kk = k.trim(); val vv = v.trim()
        if (kk.isEmpty() || vv.isEmpty() || kk == vv || kk.all { it.isDigit() }) return
        if (!glossary.containsKey(kk) && !userGl.containsKey(kk)) glossary[kk] = vv
    }

    private fun parse(raw: String, n: Int): List<String> {
        val out = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("```json", "").replace("```", "")
        val res = MutableList(n) { "" }
        val fx = HashMap<Int, String>()
        lastFix = fx
        var ok = false
        try {
            val a = out.indexOf('{'); val z = out.lastIndexOf('}')
            if (a >= 0 && z > a) {
                val j = JSONObject(out.substring(a, z + 1))
                val t = j.opt("t")
                if (t is JSONObject) {
                    for (i in 1..n) { val v = t.optString(i.toString(), ""); res[i - 1] = if (v == "null") "" else v }
                    ok = true
                } else if (t is JSONArray && t.length() == n) {
                    for (i in 0 until n) res[i] = t.optString(i, "")
                    ok = true
                }
                j.optJSONObject("g")?.let { g -> g.keys().forEach { addTerm(it, g.optString(it, "")) } }
                j.optJSONObject("z")?.let { z -> for (i in 1..n) { val v = z.optString(i.toString(), ""); if (v.isNotBlank() && v != "null") fx[i - 1] = v } }
            }
        } catch (e: Exception) { ok = false }
        if (!ok) {
            // JSON cassé : on récupère toutes les lignes complètes, on ignore le reste
            val rx = Regex("\"(\\d{1,4})\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            for (m in rx.findAll(out)) {
                val i = m.groupValues[1].toIntOrNull() ?: continue
                val v = unesc(m.groupValues[2]).trim()
                if (i in 1..n && res[i - 1].isEmpty() && v.isNotEmpty() && v != "null" && !(v.length <= 2 && v.all { it.isDigit() })) res[i - 1] = v
            }
            val rg = Regex("\"([^\"\\\\]*[\\u4e00-\\u9fff][^\"\\\\]*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            for (m in rg.findAll(out)) addTerm(unesc(m.groupValues[1]), unesc(m.groupValues[2]))
        }
        if (res.all { it.isBlank() }) throw ParseErr("réponse illisible (JSON cassé)")
        while (glossary.size > 5000) glossary.remove(glossary.keys.first())
        return res
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 30000; c.readTimeout = 120000
        c.setRequestProperty("content-type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        quotaFrom(url, c)
        val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) { val ex = ApiErr(code, txt); ex.resetMs = resetFrom(url, c, txt); throw ex }
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
            } catch (e: Exception) {
                err = e
                if (e is java.io.IOException || (e is ApiErr && (e.code == 401 || e.code == 403 || e.code == 429))) throw e
                Thread.sleep(4000L * a)
            }
        }
        throw err!!
    }

    private fun gemini(key: String, ctx: List<Cue>, b: List<Cue>, tries: Int = 12, name: String = "Gemini"): List<String> {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(ctx, b))))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
        var err: Exception? = null
        for (a in 1..tries) {
            try {
                val model = if (a % 2 == 1) "gemini-flash-latest" else "gemini-flash-lite-latest"
                synchronized(pmodel) { pmodel[name] = model }
                val txt = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
                val cands = JSONObject(txt).optJSONArray("candidates")
                if (cands == null || cands.length() == 0) throw ParseErr("Gemini : réponse vide ou bloquée " + txt.replace(Regex("\\s+"), " ").take(100))
                val out = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""
                if (out.isBlank()) throw ParseErr("Gemini : texte vide (fin : " + cands.getJSONObject(0).optString("finishReason", "?") + ")")
                val r = parse(out, b.size)
                Thread.sleep(5000)
                return r
            } catch (e: Exception) {
                if (e is ApiErr && (e.code == 401 || e.code == 403)) throw e
                err = e
                if (e is java.io.IOException) throw e
                // 1er essai raté (limite, 5xx, réponse vide…) : on tente tout de suite l'autre modèle ;
                // si ça rate aussi, la boucle principale décide (nouvel essai dans 20 s, puis serveur suivant)
                if (a >= 2) throw e
            }
        }
        throw err!!
    }

    // ---------- mémoire de phrases ----------
    private fun learnPhrase(zh: String, ar: String) {
        if (srcIsEn) return
        val k = norm(zh)
        if (k.length < 2 || k.length > 30 || ar.isBlank()) return
        synchronized(phrases) {
            phrases[k] = ar
            while (phrases.size > 8000) phrases.remove(phrases.keys.first())
            phDirty = true
        }
    }

    private fun loadPhrases() {
        try {
            val f = File(filesDir, "phrases.json"); if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.keys().forEach { phrases[it] = o.getString(it) }
        } catch (e: Exception) {}
    }

    @Synchronized private fun savePhrases() {
        if (!phDirty) return
        try {
            val snap = synchronized(phrases) { LinkedHashMap(phrases) }
            File(filesDir, "phrases.json").writeText(JSONObject(snap.toMap()).toString())
            phDirty = false
        } catch (e: Exception) {}
    }

    private fun pickPhrases() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 5)
    }

    private fun importPhrases(u: Uri) {
        Thread {
            try {
                val txt = contentResolver.openInputStream(u)!!.use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val sep = Regex("\\s*(.+?)\\s*(?:=|＝|→|->|：|:|\\t)\\s*(.+)")
                var n = 0
                synchronized(phrases) {
                    for (l in txt.lines()) {
                        val m = sep.matchEntire(l.trim()) ?: continue
                        val k = norm(m.groupValues[1]); val v = m.groupValues[2].trim()
                        if (k.isEmpty() || v.isEmpty()) continue
                        phrases[k] = v; n++
                    }
                    phDirty = true
                }
                savePhrases()
                runOnUiThread { Toast.makeText(this, "$n phrases importées (mémoire : ${phrases.size})", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Import impossible : ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------- bibliothèque hors ligne ----------
    private val libLangs = listOf(
        TranslateLanguage.CHINESE to "Chinois",
        TranslateLanguage.ARABIC to "Arabe",
        TranslateLanguage.ENGLISH to "Anglais (langue relais)"
    )

    private fun libStatus(): Map<String, Boolean> {
        val mm = RemoteModelManager.getInstance()
        val have = Tasks.await(mm.getDownloadedModels(TranslateRemoteModel::class.java)).map { it.language }.toSet()
        return libLangs.associate { it.first to have.contains(it.first) }
    }

    private fun openLibrary() {
        if (running) { Toast.makeText(this, "Attends la fin du travail en cours.", Toast.LENGTH_LONG).show(); return }
        if (libBusy) { Toast.makeText(this, "Un téléchargement est déjà en cours.", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "Vérification de la bibliothèque…", Toast.LENGTH_SHORT).show()
        Thread {
            val s: Map<String, Boolean>? = try { libStatus() } catch (e: Exception) { null }
            runOnUiThread {
                val sb = StringBuilder()
                if (s == null) {
                    sb.append("Impossible de lire l’état de la bibliothèque.")
                } else {
                    for (l in libLangs) sb.append(if (s[l.first] == true) "✓ " else "✗ ").append(l.second).append("\n")
                    if (s[TranslateLanguage.CHINESE] == true && s[TranslateLanguage.ARABIC] == true)
                        sb.append("\nLa bibliothèque est déjà sur ton téléphone : rien à retélécharger, le téléchargement ne se fait qu’une seule fois.")
                    else
                        sb.append("\nIl manque des éléments : touche « Télécharger » (Wi‑Fi conseillé, environ 30 Mo par langue).")
                }
                sb.append("\n\nGoogle ne donne aucun moyen de savoir si une version plus récente existe. « Mettre à jour » efface les modèles puis les retélécharge.")
                AlertDialog.Builder(this).setTitle("Bibliothèque hors ligne").setMessage(sb.toString())
                    .setPositiveButton("Télécharger") { _, _ -> downloadLibrary(false) }
                    .setNeutralButton("Mettre à jour") { _, _ ->
                        confirmDanger(
                            "Mettre à jour la bibliothèque ?",
                            "Les modèles hors ligne seront effacés puis retéléchargés (environ 100 Mo). Garde internet allumé : si le téléchargement échoue, le hors ligne ne marche plus jusqu’à ce que tu relances « Télécharger ».",
                            null
                        ) { downloadLibrary(true) }
                    }
                    .setNegativeButton("Fermer", null).show()
            }
        }.start()
    }

    private fun downloadLibrary(refresh: Boolean) {
        if (!online()) { Toast.makeText(this, "Pas d’internet : connecte-toi puis réessaie.", Toast.LENGTH_LONG).show(); return }
        if (libBusy) { Toast.makeText(this, "Un téléchargement est déjà en cours.", Toast.LENGTH_LONG).show(); return }
        libBusy = true
        Thread {
            var fail = ""
            val mm = RemoteModelManager.getInstance()
            try {
                val have: Map<String, Boolean> = if (refresh) emptyMap() else libStatus()
                for (l in libLangs) {
                    if (!refresh && have[l.first] == true) continue
                    ui("Bibliothèque hors ligne : téléchargement " + l.second + "…")
                    val model = TranslateRemoteModel.Builder(l.first).build()
                    try {
                        if (refresh) { try { Tasks.await(mm.deleteDownloadedModel(model)) } catch (e: Exception) {} }
                        Tasks.await(mm.download(model, DownloadConditions.Builder().build()))
                    } catch (e: Exception) { fail += l.second + " " }
                }
            } catch (e: Exception) { fail = "état illisible " }
            libBusy = false
            runOnUiThread {
                if (fail.isEmpty()) {
                    st.text = "Bibliothèque hors ligne prête ✓"
                    Toast.makeText(this, "Bibliothèque hors ligne prête ✓", Toast.LENGTH_LONG).show()
                } else {
                    st.text = "Téléchargement incomplet (" + fail.trim() + "). Réessaie avec une meilleure connexion."
                    Toast.makeText(this, "Échec : " + fail.trim() + ". Réessaie.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- recherche dans le glossaire ----------
    private fun searchGlossary() {
        val q = addZh.text.toString().trim().ifEmpty { addAr.text.toString().trim() }
        if (q.isEmpty()) { Toast.makeText(this, "Écris le mot à chercher dans l’une des deux cases.", Toast.LENGTH_SHORT).show(); return }
        val sep = Regex("\\s*(.+?)\\s*(?:=|＝|→|->|：|:|\\t)\\s*(.+)")
        val exact = mutableListOf<String>(); val near = mutableListOf<String>()
        for (l in gedit.text.toString().lines()) {
            val m = sep.matchEntire(l.trim()) ?: continue
            val k = m.groupValues[1].trim(); val v = m.groupValues[2].trim()
            if (k == q || v == q) exact.add(k + " = " + v)
            else if (k.contains(q, true) || v.contains(q, true) || (k.length >= 2 && q.contains(k))) near.add(k + " = " + v)
        }
        val ph = synchronized(phrases) { phrases[norm(q)] }
        val msg = StringBuilder()
        if (exact.isNotEmpty()) msg.append("✓ Ce mot existe dans le glossaire :\n").append(exact.take(10).joinToString("\n"))
        else msg.append("✗ Ce mot n’existe pas tel quel dans le glossaire.")
        if (near.isNotEmpty()) msg.append("\n\nTermes proches (").append(near.size).append(") :\n").append(near.take(15).joinToString("\n"))
        if (ph != null) msg.append("\n\nMémoire de phrases : ").append(q).append(" = ").append(ph)
        AlertDialog.Builder(this).setTitle("Recherche : " + q).setMessage(msg.toString()).setPositiveButton("OK", null).show()
    }

    // ---------- ajout de termes ----------
    private fun addTermFromFields() {
        val z = addZh.text.toString().trim(); val a = addAr.text.toString().trim()
        if (z.isEmpty()) { Toast.makeText(this, "Écris d’abord le mot chinois.", Toast.LENGTH_SHORT).show(); return }
        if (a.isEmpty()) {
            val k1 = key.text.toString().trim(); val k2 = key2.text.toString().trim(); val k3 = key3.text.toString().trim()
            Toast.makeText(this, "Traduction en cours…", Toast.LENGTH_SHORT).show()
            Thread {
                val m: Map<String, String> = try { translateTerms(k1, k2, k3, listOf(z), emptyMap()) } catch (e: Exception) { emptyMap() }
                runOnUiThread {
                    val t = m[z]
                    if (t == null) Toast.makeText(this, "Pas de traduction automatique : écris-la toi-même.", Toast.LENGTH_LONG).show()
                    else { addAr.setText(t); Toast.makeText(this, "Vérifie la traduction puis touche Ajouter.", Toast.LENGTH_LONG).show() }
                }
            }.start()
            return
        }
        val sepK = Regex("\\s*(.+?)\\s*(?:=|＝|→|->|：|:|\\t)\\s*(.+)")
        val all = gedit.text.toString().lines()
        val had = all.any { l -> sepK.matchEntire(l.trim())?.groupValues?.get(1)?.trim() == z }
        val rest = all.filter { l -> sepK.matchEntire(l.trim())?.groupValues?.get(1)?.trim() != z }.joinToString("\n").trim()
        gedit.setText(if (rest.isEmpty()) "$z = $a" else "$z = $a\n$rest")
        saveGlossary(false)
        addZh.setText(""); addAr.setText("")
        Toast.makeText(this, (if (had) "Remplacé : " else "Ajouté : ") + z + " = " + a, Toast.LENGTH_SHORT).show()
    }

    // ---------- mots fréquents de la vidéo ----------
    private val stopCh = "的了是在我你他她它们这那有不就也都和与及而或但把被让给对向从到着过吗呢吧啊呀哦嗯么说道"

    private fun detectTerms(snap: List<Pair<Double, String>>): List<Pair<String, Int>> {
        val cjk = Regex("[\\u4e00-\\u9fff]+")
        val counts = HashMap<String, Int>()
        for ((_, zh) in snap) {
            for (run in cjk.findAll(zh)) {
                val s = run.value
                for (n in 2..4) for (i in 0..s.length - n) {
                    val g = s.substring(i, i + n); counts[g] = (counts[g] ?: 0) + 1
                }
            }
        }
        val cand = counts.entries.filter { e ->
            e.value >= 3 && stopCh.indexOf(e.key.first()) < 0 && stopCh.indexOf(e.key.last()) < 0 &&
                !userGl.containsKey(e.key) && !glossary.containsKey(e.key)
        }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key.length }.thenByDescending { it.value })
        val acc = mutableListOf<Pair<String, Int>>()
        for (e in cand) {
            if (acc.any { p -> p.first.contains(e.key) && p.second >= e.value * 0.7 }) continue
            acc.add(e.key to e.value)
        }
        return acc.sortedByDescending { it.second }.take(150)
    }

    private fun openDetected() {
        if (running) { Toast.makeText(this, "Attends la fin du travail en cours.", Toast.LENGTH_LONG).show(); return }
        if (items.isEmpty()) {
            Toast.makeText(this, "Choisis d’abord ta vidéo : l’analyse déjà faite est retrouvée automatiquement.", Toast.LENGTH_LONG).show(); return
        }
        val snap = items.toList()
        Toast.makeText(this, "Recherche des mots fréquents…", Toast.LENGTH_SHORT).show()
        Thread {
            val list: List<Pair<String, Int>> = try { detectTerms(snap) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (list.isEmpty()) {
                    Toast.makeText(this, "Aucun nouveau mot fréquent trouvé.", Toast.LENGTH_LONG).show()
                } else {
                    val labels = list.map { it.first + "  ·  " + it.second + "×" }.toTypedArray()
                    val checked = BooleanArray(list.size) { list[it].second >= 5 }
                    AlertDialog.Builder(this).setTitle("Mots fréquents de la vidéo")
                        .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                        .setPositiveButton("Traduire et ajouter") { _, _ ->
                            val words = list.indices.filter { checked[it] }.map { list[it].first }
                            if (words.isNotEmpty()) addTranslated(words, snap)
                        }
                        .setNegativeButton("Annuler", null).show()
                }
            }
        }.start()
    }

    /** Avant chaque traduction : les mots fréquents de la vidéo sont traduits et ajoutés tout seuls au glossaire. */
    private fun autoGlossary(k1: String, k2: String, k3: String) {
        autoGlN = 0
        if (srcIsEn || cues.isEmpty() || stop) return
        if (!online() || (k1.isEmpty() && k2.isEmpty() && k3.isEmpty())) return
        try {
            phase = "analyse"; phaseInfo = "Glossaire automatique : recherche des mots fréquents…"
            ui(phaseInfo); runOnUiThread { renderPanel() }
            val snap = cues.map { it.start to it.zh }
            val words = detectTerms(snap).filter { it.second >= 5 }.take(40).map { it.first }
            if (words.isEmpty()) return
            val ex = HashMap<String, String>()
            for (w in words) snap.firstOrNull { it.second.contains(w) }?.let { ex[w] = it.second.take(50) }
            phaseInfo = "Glossaire automatique : traduction de ${words.size} mots…"
            ui(phaseInfo); runOnUiThread { renderPanel() }
            val m = translateTerms(k1, k2, k3, words, ex, false)
            var n = 0
            for ((w, t) in m) if (!userGl.containsKey(w) && !glossary.containsKey(w)) { userGl[w] = t; n++ }
            if (n > 0) saveGl()
            autoGlN = n
        } catch (e: Exception) {}
    }

    private fun addTranslated(words: List<String>, snap: List<Pair<Double, String>>) {
        val k1 = key.text.toString().trim(); val k2 = key2.text.toString().trim(); val k3 = key3.text.toString().trim()
        Toast.makeText(this, "Traduction de ${words.size} mots…", Toast.LENGTH_LONG).show()
        Thread {
            val ex = HashMap<String, String>()
            for (w in words) snap.firstOrNull { it.second.contains(w) }?.let { ex[w] = it.second.take(50) }
            val m: Map<String, String> = try { translateTerms(k1, k2, k3, words, ex) } catch (e: Exception) { emptyMap() }
            runOnUiThread {
                if (m.isEmpty()) {
                    Toast.makeText(this, "Traduction impossible pour l’instant (quota ou internet).", Toast.LENGTH_LONG).show()
                } else {
                    val lines = m.entries.joinToString("\n") { it.key + " = " + it.value }
                    val cur = gedit.text.toString().trimEnd()
                    gedit.setText(if (cur.isEmpty()) lines else lines + "\n" + cur)
                    saveGlossary(false)
                    Toast.makeText(this, "${m.size} termes ajoutés en haut de la liste : vérifie-les.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun translateTerms(k1: String, k2: String, k3: String, words: List<String>, ex: Map<String, String>, allowOffline: Boolean = true): Map<String, String> {
        val obj = JSONObject(); words.forEach { obj.put(it, ex[it] ?: "") }
        val p = "Ce sont des mots ou expressions chinois fréquents dans un manhua (clé = le mot, valeur = une ligne d’exemple où il apparaît). " +
            "Traduis chaque mot en arabe standard moderne : transcris phonétiquement en arabe les noms de personnes et de lieux, traduis les termes et les titres. " +
            "Si ce n’est pas un vrai mot ou un vrai nom (simple morceau de phrase), mets une chaîne vide. " +
            "Réponds uniquement par un objet JSON {\"mot chinois\":\"traduction arabe\"}, sans guillemet droit à l’intérieur des traductions.\n\n" + obj.toString()
        val order = mutableListOf<() -> String>()
        if (k1.startsWith("sk-ant")) order.add { askClaude(k1, p) } else if (k1.isNotEmpty()) order.add { askGemini(k1, p) }
        if (k2.isNotEmpty()) order.add { askOai("https://api.groq.com/openai/v1", k2, listOf("llama-3.3-70b", "llama-4", "qwen", "gpt-oss-120b"), false, p) }
        if (k3.isNotEmpty()) order.add { askOai("https://openrouter.ai/api/v1", k3, listOf("qwen", "llama-3.3-70b", "gpt-oss-120b", "gemma"), true, p) }
        var raw = ""
        if (online()) {
            for (f in order) {
                try { raw = f(); if (raw.isNotBlank()) break } catch (e: Exception) {}
            }
        }
        val res = LinkedHashMap<String, String>()
        if (raw.isNotBlank()) {
            val s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val rx = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            for (m in rx.findAll(s)) {
                val k = unesc(m.groupValues[1]); val v = unesc(m.groupValues[2]).trim()
                if (words.contains(k) && v.isNotEmpty() && v != k) res[k] = v
            }
        }
        if (res.isEmpty() && allowOffline) {
            // aucun serveur n'a répondu : traduction hors ligne mot à mot (moins bonne, à vérifier)
            try {
                val tr = Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.ARABIC).build())
                Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
                for (w in words) { val t = Tasks.await(tr.translate(w)).trim(); if (t.isNotEmpty()) res[w] = t }
                tr.close()
            } catch (e: Exception) {}
        }
        return res
    }

    private fun askGemini(key: String, text: String): String {
        geminiName = "Gemini"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        var err: Exception? = null
        for (model in listOf("gemini-flash-latest", "gemini-flash-lite-latest")) {
            try {
                val txt = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
                val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text")
                if (out.isNotBlank()) return out
            } catch (e: Exception) { err = e }
        }
        throw err ?: Exception("réponse vide")
    }

    private fun askOai(base: String, key: String, prefs: List<String>, freeOnly: Boolean, text: String): String {
        val model = pickModels(base, key, prefs, freeOnly).first()
        val body = JSONObject().put("model", model).put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val txt = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        return JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
    }

    private fun askClaude(key: String, text: String): String {
        val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val txt = post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
        return JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun pickSrt() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 4)
    }

    private fun nameOf(u: Uri): String = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "fichier.srt"
    } catch (e: Exception) { "fichier.srt" }

    private fun parseSrt(t: String): List<Cue> {
        val out = mutableListOf<Cue>()
        val tm = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")
        fun sec(h: String, m: String, s: String, ms: String) =
            h.toInt() * 3600 + m.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').take(3).toInt() / 1000.0
        for (blk in t.replace("\r", "").split(Regex("\n\\s*\n"))) {
            val lines = blk.trim().split("\n")
            val ti = lines.indexOfFirst { tm.containsMatchIn(it) }
            if (ti < 0) continue
            val m = tm.find(lines[ti])!!.groupValues
            val txt = lines.drop(ti + 1).joinToString(" ")
                .replace(Regex("<[^>]+>|\\{\\\\[^}]*\\}"), "").replace(Regex("\\s+"), " ").trim()
            if (txt.isEmpty()) continue
            out.add(Cue(sec(m[1], m[2], m[3], m[4]), sec(m[5], m[6], m[7], m[8]), txt))
        }
        return out
    }

    private fun loadSrtState() {
        try {
            val f = File(filesDir, "srt_$srtKey.json"); if (!f.exists()) return
            val o = JSONObject(f.readText())
            val m = o.getJSONObject("memo"); m.keys().forEach { memo[it] = m.getString(it) }
            o.optJSONObject("src")?.let { s2 -> s2.keys().forEach { srcMap[it] = s2.getString(it) } }
            o.optJSONArray("off")?.let { a -> for (i in 0 until a.length()) offlineKeys.add(a.getString(i)) }
        } catch (e: Exception) {}
    }

    private fun outFile(name: String, text: String): Boolean {
        val tree = getSharedPreferences("p", 0).getString("tree", null)?.let { Uri.parse(it) }
        if (tree != null) { try { putTree(tree, name, text); return true } catch (e: Exception) {} }
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
        }
        val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(u)!!.use { it.write(("\uFEFF" + text).toByteArray()) }
        return false
    }

    private fun translateSrtFile(u: Uri) {
        if (running) return
        val k = key.text.toString().trim(); val k2 = key2.text.toString().trim(); val k3 = key3.text.toString().trim()
        getSharedPreferences("p", 0).edit().putString("k", k).putString("kg", k2).putString("ko", k3).apply(); k4 = key4.text.toString().trim(); getSharedPreferences("p", 0).edit().putString("k4", k4).apply()
        val name = nameOf(u)
        val outBase = name.substringBeforeLast('.')
        running = true; stop = false; srtMode = true; goB.isEnabled = false; lastOk = false; phase = ""; retraSet.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ensureChannel(this)
        startForegroundService(Intent(this, KeepAliveService::class.java))
        askBackground()
        Thread {
            try {
                val raw = contentResolver.openInputStream(u)!!.use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val blocks = parseSrt(raw)
                if (blocks.isEmpty()) { ui("Fichier .srt vide ou illisible."); return@Thread }
                val sample = blocks.joinToString(" ") { it.zh }
                val cjk = Regex("[\\u4e00-\\u9fff]").findAll(sample).count()
                val lat = Regex("[A-Za-z]").findAll(sample).count()
                srcIsEn = lat > cjk; srcLang = if (srcIsEn) "anglais" else "chinois"
                cues = blocks; memo.clear(); offlineKeys.clear(); srcMap.clear()
                srtKey = abs(u.toString().hashCode()).toString()
                loadSrtState()
                ui("Fichier lu : ${cues.size} sous-titres ($srcLang → arabe). Traduction…")
                autoGlossary(k, k2, k3)
                translateAll(k, k2, k3)
                if (stop) { saveState(); ui("Arrêté. Relance le même fichier pour reprendre."); return@Thread }
                cues.forEach { it.ar = memo[it.zh] ?: "" }
                val inTree = outFile("$outBase.ar.srt", srt { it.ar })
                File(filesDir, "srt_$srtKey.json").delete()
                lastOk = true
                ui("SRT traduit ✓ ${cues.size} sous-titres ($srcLang → arabe). Fichier : $outBase.ar.srt " +
                    (if (inTree) "dans le dossier de sortie choisi." else "dans Téléchargements."))
            } catch (e: Exception) {
                try { saveState() } catch (x: Exception) {}
                ui("Erreur : ${e.message ?: e}. Progression gardée : relance le même fichier.")
            } finally {
                srtMode = false; srcIsEn = false; srcLang = "chinois"; running = false
                phase = if (stop) "arrêté" else if (lastOk) "terminé" else "erreur"
                stopService(Intent(this, KeepAliveService::class.java))
                runOnUiThread {
                    goB.isEnabled = true; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (uri != null) { try { loadState() } catch (e: Exception) {} }
                    else { cues = listOf(); memo.clear(); srcMap.clear(); offlineKeys.clear() }
                    renderPanel()
                }
            }
        }.start()
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
                putTree(tree, "$base.zh.srt", srt { zhOf(it) })
                putTree(tree, "$base.bilingue.srt", srt { zhOf(it) + "\n" + it.ar })
                if (useAsr && asr.isNotEmpty()) putTree(tree, "$base.audio.srt", audioSrt())
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
        put("$base.zh.srt", srt { zhOf(it) })
        put("$base.bilingue.srt", srt { zhOf(it) + "\n" + it.ar })
        if (useAsr && asr.isNotEmpty()) put("$base.audio.srt", audioSrt())
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
                ov.text = when { c == null -> ""; mode == 0 -> c.ar; mode == 2 -> zhOf(c); else -> zhOf(c) + "\n" + c.ar }
                ov.visibility = if (c == null) View.INVISIBLE else View.VISIBLE
                h.postDelayed(this, 250)
            }
        })
    }

    private fun closePlayer() { vv.stopPlayback(); box.visibility = View.GONE }

    override fun onBackPressed() {
        if (gbox.visibility == View.VISIBLE) closeGlossary()
        else if (box.visibility == View.VISIBLE) closePlayer()
        else super.onBackPressed()
    }
}
