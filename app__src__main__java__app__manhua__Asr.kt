package app.manhua

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class Seg(val start: Double, val end: Double, val text: String)

object Asr {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

    fun ensureModel(c: Context, progress: (String) -> Unit, stop: () -> Boolean): File {
        val root = File(c.filesDir, "vosk-cn")
        val marker = File(root, "ready")
        if (marker.exists()) {
            val d = File(root, marker.readText().trim())
            if (d.isDirectory) return d
        }
        root.deleteRecursively(); root.mkdirs()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000; conn.readTimeout = 60000
        if (conn.responseCode >= 400) throw Exception("téléchargement du modèle vocal impossible (${conn.responseCode})")
        var top = ""
        var read = 0L
        var lastShown = 0L
        val rootPath = root.canonicalPath
        ZipInputStream(conn.inputStream.buffered()).use { zin ->
            val buf = ByteArray(65536)
            var e = zin.nextEntry
            while (e != null) {
                if (stop()) throw Exception("arrêté")
                val name = e.name
                if (top.isEmpty()) top = name.substringBefore('/')
                val out = File(root, name)
                if (!out.canonicalPath.startsWith(rootPath)) throw Exception("archive du modèle invalide")
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fo ->
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            fo.write(buf, 0, n); read += n
                            if (read - lastShown > 2_000_000) { lastShown = read; progress("Téléchargement du modèle vocal : ${read / 1_000_000} Mo…") }
                        }
                    }
                }
                e = zin.nextEntry
            }
        }
        if (top.isEmpty() || !File(root, top).isDirectory) throw Exception("modèle vocal incomplet")
        marker.writeText(top)
        return File(root, top)
    }

    /** Décode la piste audio de la vidéo, la passe en 16 kHz mono et la donne à Vosk en flux (jamais tout en mémoire). */
    fun transcribe(
        c: Context, uri: Uri, model: File, fromSec: Double, toSec: Double,
        onSeg: (Seg) -> Unit, onProgress: (Double) -> Unit, stop: () -> Boolean
    ) {
        val ex = MediaExtractor()
        ex.setDataSource(c, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
        }
        if (track < 0) { ex.release(); throw Exception("la vidéo n’a pas de piste audio") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = maxOf(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        var isFloat = false
        var step = rate / 16000.0
        if (fromSec > 0) ex.seekTo((fromSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val vm = Model(model.absolutePath)
        val rec = Recognizer(vm, 16000f)
        rec.setWords(true)

        val chunk = ShortArray(8000)
        var fill = 0
        var acc = 0.0
        var sum = 0.0
        var cnt = 0
        var base = -1.0
        var lastT = fromSec
        var lastReport = fromSec

        fun emit(js: String) {
            val j = JSONObject(js)
            val text = j.optString("text", "").replace(" ", "")
            if (text.isEmpty()) return
            val arr = j.optJSONArray("result")
            val s: Double
            val e: Double
            if (arr != null && arr.length() > 0 && base >= 0) {
                s = base + arr.getJSONObject(0).getDouble("start")
                e = base + arr.getJSONObject(arr.length() - 1).getDouble("end")
            } else { s = maxOf(0.0, lastT - 2.0); e = lastT }
            onSeg(Seg(s, maxOf(e, s + 0.3), text))
        }

        fun feed() {
            if (fill == 0) return
            val n = fill
            fill = 0
            if (rec.acceptWaveForm(chunk, n)) emit(rec.result)
        }

        fun push(v: Double) {
            sum += v; cnt++; acc += 1.0
            if (acc >= step) {
                acc -= step
                chunk[fill++] = (sum / cnt).toInt().coerceIn(-32768, 32767).toShort()
                sum = 0.0; cnt = 0
                if (fill == chunk.size) feed()
            }
        }

        fun handle(ob: ByteBuffer, ptsUs: Long) {
            val t = ptsUs / 1e6
            if (t < fromSec - 0.05) return
            if (base < 0) base = t
            lastT = t
            ob.order(ByteOrder.nativeOrder())
            if (isFloat) {
                val fb = ob.asFloatBuffer()
                while (fb.remaining() >= ch) {
                    var s = 0f
                    for (k in 0 until ch) s += fb.get()
                    push((s / ch * 32767f).toDouble())
                }
            } else {
                val sb = ob.asShortBuffer()
                while (sb.remaining() >= ch) {
                    var s = 0
                    for (k in 0 until ch) s += sb.get().toInt()
                    push(s.toDouble() / ch)
                }
            }
        }

        try {
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone && !stop()) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        handle(ob, info.presentationTimeUs)
                        if (toSec > 0 && lastT >= toSec) outDone = true
                        if (lastT - lastReport >= 5.0) { lastReport = lastT; onProgress(lastT) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = maxOf(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                    step = rate / 16000.0
                }
            }
            feed()
            emit(rec.finalResult)
            onProgress(lastT)
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            try { codec.release() } catch (e: Exception) {}
            try { ex.release() } catch (e: Exception) {}
            try { rec.close() } catch (e: Exception) {}
            try { vm.close() } catch (e: Exception) {}
        }
    }
}
