package com.aplichant.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aplichant.audio.AudioDecoder
import com.aplichant.audio.ENGINE_RATE
import com.aplichant.audio.VoiceRecorder
import com.aplichant.audio.Wav
import com.aplichant.audio.playPcm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class Mode { IDLE, PREPARING, PLAYING_SONG, PLAYING_BACKING, RECORDING, RECORDING_SOLO, PLAYING_VOICE, PLAYING_MIX }

/**
 * Une prise de voix. [withBacking] : enregistrée sur le backing de l'extrait [startMs, endMs]
 * (sinon enregistrée seule, sans backing). [name] : nom donné lors de la sauvegarde, ou null.
 */
data class Take(
    val file: File,
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val withBacking: Boolean,
    val name: String?,
) {
    val durationMs: Long get() = ((file.length() - 44) / 2) * 1000 / ENGINE_RATE
    val saved: Boolean get() = name != null
}

data class UiState(
    val songUri: Uri? = null,
    val songName: String? = null,
    val backingUri: Uri? = null,
    val backingName: String? = null,
    val songDurationMs: Long = 0,
    val backingDurationMs: Long = 0,
    val waveform: FloatArray? = null,
    val loadingWaveform: Boolean = false,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val mode: Mode = Mode.IDLE,
    /** Position de lecture dans la chronologie du morceau (ms), ou null. */
    val positionMs: Long? = null,
    /** Durée écoulée de l'enregistrement en cours (ms). */
    val recordElapsedMs: Long = 0,
    val inputLevel: Float = 0f,
    val takes: List<Take> = emptyList(),
    val selectedTake: Take? = null,
    val latencyMs: Int = 100,
    val voiceVolume: Float = 1f,
    val backingVolume: Float = 0.8f,
    val message: String? = null,
) {
    /** Durée maximale sélectionnable : celle du morceau (ou du backing si pas de morceau). */
    val timelineMs: Long get() = if (songDurationMs > 0) songDurationMs else backingDurationMs
}

private const val MAX_SOLO_MS = 10 * 60 * 1000L

class SingViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("aplichant", 0)
    private val takesDir = File(app.filesDir, "takes").apply { mkdirs() }
    /** Noms donnés aux prises sauvegardées (nom du fichier -> nom). */
    private val names = app.getSharedPreferences("take_names", 0)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    // Cache du dernier extrait décodé pour éviter de re-décoder à chaque lecture.
    private data class CacheKey(val uri: Uri, val start: Long, val end: Long)

    private val cache = HashMap<CacheKey, ShortArray>()

    init {
        _state.update {
            it.copy(
                latencyMs = prefs.getInt("latency", 100),
                voiceVolume = prefs.getFloat("voiceVol", 1f),
                backingVolume = prefs.getFloat("backingVol", 0.8f),
                startMs = prefs.getLong("start", 0),
                endMs = prefs.getLong("end", 0),
            )
        }
        loadTakes()
        prefs.getString("song", null)?.let { restoreFile(Uri.parse(it), isSong = true) }
        prefs.getString("backing", null)?.let { restoreFile(Uri.parse(it), isSong = false) }
    }

    // ---------------------------------------------------------------- Fichiers

    fun onSongPicked(uri: Uri) = onFilePicked(uri, isSong = true)
    fun onBackingPicked(uri: Uri) = onFilePicked(uri, isSong = false)

    private fun onFilePicked(uri: Uri, isSong: Boolean) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        prefs.edit().putString(if (isSong) "song" else "backing", uri.toString()).apply()
        // Nouveau fichier : on repart sur une sélection vide.
        if (isSong) _state.update { it.copy(startMs = 0, endMs = 0) }
        loadFile(uri, isSong, keepSelection = false)
    }

    private fun restoreFile(uri: Uri, isSong: Boolean) = loadFile(uri, isSong, keepSelection = true)

    private fun loadFile(uri: Uri, isSong: Boolean, keepSelection: Boolean) {
        val name = displayName(uri)
        viewModelScope.launch {
            if (isSong) {
                _state.update { it.copy(songUri = uri, songName = name, loadingWaveform = true, waveform = null) }
                try {
                    val (wave, duration) = withContext(Dispatchers.Default) {
                        AudioDecoder.waveform(getApplication(), uri, 400)
                    }
                    _state.update { it.copy(songDurationMs = duration, waveform = wave, loadingWaveform = false) }
                    fixSelection(keepSelection)
                } catch (e: Exception) {
                    _state.update {
                        it.copy(songUri = null, songName = null, loadingWaveform = false,
                            message = "Impossible de lire la musique : ${e.message}")
                    }
                }
            } else {
                try {
                    val duration = withContext(Dispatchers.IO) { AudioDecoder.durationMs(getApplication(), uri) }
                    _state.update { it.copy(backingUri = uri, backingName = name, backingDurationMs = duration) }
                    fixSelection(keepSelection)
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Impossible de lire le backing track : ${e.message}") }
                }
            }
        }
    }

    private fun fixSelection(keep: Boolean) {
        val s = _state.value
        val total = s.timelineMs
        if (total <= 0) return
        var start = s.startMs.coerceIn(0, total)
        var end = s.endMs.coerceIn(0, total)
        if (!keep || end <= start) {
            start = 0
            end = minOf(total, 30_000) // 30 s par défaut
        }
        setSelection(start, end)
    }

    private fun displayName(uri: Uri): String {
        try {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "fichier"
    }

    // ---------------------------------------------------------------- Sélection

    fun setSelection(startMs: Long, endMs: Long) {
        val total = _state.value.timelineMs
        val s = startMs.coerceIn(0, maxOf(0, total - 500))
        val e = endMs.coerceIn(s + 500, maxOf(s + 500, total))
        _state.update { it.copy(startMs = s, endMs = e) }
        prefs.edit().putLong("start", s).putLong("end", e).apply()
    }

    fun nudgeStart(deltaMs: Long) = _state.value.let { setSelection(it.startMs + deltaMs, it.endMs) }
    fun nudgeEnd(deltaMs: Long) = _state.value.let { setSelection(it.startMs, it.endMs + deltaMs) }

    // ---------------------------------------------------------------- Réglages

    fun setLatency(ms: Int) {
        _state.update { it.copy(latencyMs = ms) }
        prefs.edit().putInt("latency", ms).apply()
    }

    fun setVoiceVolume(v: Float) {
        _state.update { it.copy(voiceVolume = v) }
        prefs.edit().putFloat("voiceVol", v).apply()
    }

    fun setBackingVolume(v: Float) {
        _state.update { it.copy(backingVolume = v) }
        prefs.edit().putFloat("backingVol", v).apply()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }

    // ---------------------------------------------------------------- Lecture

    fun stop() {
        stopRequested = true
    }

    private suspend fun decodeExcerpt(uri: Uri, start: Long, end: Long): ShortArray {
        val key = CacheKey(uri, start, end)
        cache[key]?.let { return it }
        val pcm = withContext(Dispatchers.Default) { AudioDecoder.decodeStereo(getApplication(), uri, start, end) }
        // On ne garde que quelques extraits en mémoire.
        if (cache.size >= 2) cache.clear()
        cache[key] = pcm
        return pcm
    }

    /** Lance une action audio exclusive (arrête la précédente). */
    private fun launchExclusive(block: suspend () -> Unit) {
        val previous = job
        stopRequested = true
        job = viewModelScope.launch {
            previous?.join()
            stopRequested = false
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Erreur audio : ${e.message}") }
            } finally {
                _state.update { it.copy(mode = Mode.IDLE, positionMs = null, inputLevel = 0f, recordElapsedMs = 0) }
            }
        }
    }

    private suspend fun playWithCursor(pcm: ShortArray, mode: Mode, originMs: Long) {
        _state.update { it.copy(mode = mode, positionMs = originMs) }
        playPcm(pcm, shouldStop = { stopRequested }) { frames ->
            _state.update { it.copy(positionMs = originMs + frames * 1000 / ENGINE_RATE) }
        }
    }

    fun playSong() {
        val s = _state.value
        val uri = s.songUri ?: return showMessage("Choisissez d'abord une musique")
        launchExclusive {
            _state.update { it.copy(mode = Mode.PREPARING) }
            val pcm = decodeExcerpt(uri, s.startMs, s.endMs)
            playWithCursor(pcm, Mode.PLAYING_SONG, s.startMs)
        }
    }

    fun playBacking() {
        val s = _state.value
        val uri = s.backingUri ?: return showMessage("Choisissez d'abord un backing track")
        launchExclusive {
            _state.update { it.copy(mode = Mode.PREPARING) }
            val pcm = decodeExcerpt(uri, s.startMs, s.endMs)
            playWithCursor(pcm, Mode.PLAYING_BACKING, s.startMs)
        }
    }

    // ---------------------------------------------------------------- Enregistrement

    fun record() {
        val s = _state.value
        val uri = s.backingUri ?: return showMessage("Choisissez d'abord un backing track")
        launchExclusive {
            _state.update { it.copy(mode = Mode.PREPARING) }
            val backing = decodeExcerpt(uri, s.startMs, s.endMs)
            val recorder = VoiceRecorder()
            withContext(Dispatchers.IO) { recorder.start() }
            var playStartFrame = 0L
            val meter = viewModelScope.launch {
                while (isActive) {
                    _state.update { it.copy(inputLevel = recorder.level) }
                    delay(50)
                }
            }
            try {
                _state.update { it.copy(mode = Mode.RECORDING, positionMs = s.startMs) }
                playPcm(
                    backing,
                    shouldStop = { stopRequested },
                    onStart = { playStartFrame = recorder.framesRecorded },
                ) { frames ->
                    _state.update { it.copy(positionMs = s.startMs + frames * 1000 / ENGINE_RATE) }
                }
            } finally {
                meter.cancel()
                withContext(NonCancellable + Dispatchers.IO) {
                    val raw = recorder.stop()
                    // On retire ce qui a été capté avant le démarrage du backing.
                    val from = playStartFrame.toInt().coerceIn(0, raw.size)
                    val voice = raw.copyOfRange(from, raw.size)
                    if (voice.size > ENGINE_RATE / 2) saveTake(voice, s.startMs, s.endMs, withBacking = true)
                }
            }
        }
    }

    /** Enregistre la voix seule, sans backing, jusqu'à l'appui sur Stop (10 min max). */
    fun recordSolo() {
        val s = _state.value
        launchExclusive {
            val recorder = VoiceRecorder()
            withContext(Dispatchers.IO) { recorder.start() }
            _state.update { it.copy(mode = Mode.RECORDING_SOLO, recordElapsedMs = 0) }
            try {
                while (!stopRequested) {
                    val elapsed = recorder.framesRecorded * 1000 / ENGINE_RATE
                    _state.update { it.copy(inputLevel = recorder.level, recordElapsedMs = elapsed) }
                    if (elapsed >= MAX_SOLO_MS) break
                    delay(50)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    val voice = recorder.stop()
                    if (voice.size > ENGINE_RATE / 2) {
                        saveTake(voice, s.startMs, s.startMs + voice.size * 1000L / ENGINE_RATE, withBacking = false)
                    }
                }
                _state.update { it.copy(recordElapsedMs = 0) }
            }
        }
    }

    private fun saveTake(voice: ShortArray, start: Long, end: Long, withBacking: Boolean) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val suffix = if (withBacking) "" else "_solo"
        val file = File(takesDir, "prise_${stamp}_${start}_${end}$suffix.wav")
        Wav.write(file, voice, 1, ENGINE_RATE)
        loadTakes()
        _state.update { st -> st.copy(selectedTake = st.takes.firstOrNull { it.file == file }) }
    }

    private fun loadTakes() {
        val regex = Regex("""prise_(\d{8})_(\d{6})_(\d+)_(\d+)(_solo)?\.wav""")
        val takes = (takesDir.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                val m = regex.matchEntire(f.name) ?: return@mapNotNull null
                val (d, t, a, b, solo) = m.destructured
                val label = "${d.substring(6, 8)}/${d.substring(4, 6)} ${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}"
                Take(f, a.toLong(), b.toLong(), label, withBacking = solo.isEmpty(), name = names.getString(f.name, null))
            }
            // Les prises sauvegardées d'abord, puis les plus récentes.
            .sortedWith(compareBy<Take> { !it.saved }.thenByDescending { it.file.name })
        _state.update { st ->
            st.copy(
                takes = takes,
                selectedTake = takes.firstOrNull { it.file == st.selectedTake?.file } ?: takes.firstOrNull(),
            )
        }
    }

    fun selectTake(take: Take) = _state.update { it.copy(selectedTake = take) }

    fun deleteTake(take: Take) {
        if (_state.value.mode != Mode.IDLE) stop()
        take.file.delete()
        names.edit().remove(take.file.name).apply()
        if (_state.value.selectedTake?.file == take.file) _state.update { it.copy(selectedTake = null) }
        loadTakes()
    }

    // ---------------------------------------------------------------- Réécoute

    /** Voix mono -> stéréo, décalée de la latence et au volume choisi. */
    private fun voiceStereo(voice: ShortArray, latencyFrames: Int, volume: Float): ShortArray {
        val n = maxOf(0, voice.size - latencyFrames)
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            val v = (voice[i + latencyFrames] * volume).roundToInt().coerceIn(-32768, 32767).toShort()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    fun playVoice() {
        val take = _state.value.selectedTake ?: return showMessage("Aucune prise sélectionnée")
        launchExclusive {
            _state.update { it.copy(mode = Mode.PREPARING) }
            val st = _state.value
            val pcm = withContext(Dispatchers.Default) {
                val voice = Wav.read(take.file)
                voiceStereo(voice, takeLatencyFrames(take, st.latencyMs), st.voiceVolume)
            }
            playWithCursor(pcm, Mode.PLAYING_VOICE, take.startMs)
        }
    }

    fun playMix() {
        val take = _state.value.selectedTake ?: return showMessage("Aucune prise sélectionnée")
        if (!take.withBacking) return showMessage("Cette prise a été enregistrée sans backing")
        val uri = _state.value.backingUri ?: return showMessage("Choisissez d'abord un backing track")
        launchExclusive {
            _state.update { it.copy(mode = Mode.PREPARING) }
            val st = _state.value
            val backing = decodeExcerpt(uri, take.startMs, take.endMs)
            val mix = withContext(Dispatchers.Default) {
                val voice = voiceStereo(Wav.read(take.file), takeLatencyFrames(take, st.latencyMs), st.voiceVolume)
                val out = ShortArray(maxOf(backing.size, voice.size))
                for (i in out.indices) {
                    val b = if (i < backing.size) backing[i] * st.backingVolume else 0f
                    val v = if (i < voice.size) voice[i].toFloat() else 0f
                    out[i] = (b + v).roundToInt().coerceIn(-32768, 32767).toShort()
                }
                out
            }
            playWithCursor(mix, Mode.PLAYING_MIX, take.startMs)
        }
    }

    private fun latencyFrames(ms: Int) = (ms.toLong() * ENGINE_RATE / 1000).toInt()

    /** La compensation de latence ne concerne que les prises enregistrées sur le backing. */
    private fun takeLatencyFrames(take: Take, ms: Int) = if (take.withBacking) latencyFrames(ms) else 0

    // ---------------------------------------------------------------- Sauvegarde / export

    /** Donne un nom à une prise pour la garder dans « Mes voix sauvegardées ». */
    fun saveTakeAs(take: Take, name: String) {
        val clean = name.trim().ifEmpty { "Voix du ${take.label}" }
        names.edit().putString(take.file.name, clean).apply()
        loadTakes()
        showMessage("Voix sauvegardée : $clean")
    }

    /** Nom de fichier proposé pour l'export de la voix seule. */
    fun exportFileName(take: Take): String {
        val base = (take.name ?: "voix_${take.file.nameWithoutExtension.removePrefix("prise_")}")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        return "$base.wav"
    }

    /** Écrit la voix seule (latence compensée) dans le fichier choisi par l'utilisateur. */
    fun exportTake(take: Take, dest: Uri) {
        val latency = _state.value.latencyMs
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val voice = Wav.read(take.file)
                    val skip = takeLatencyFrames(take, latency).coerceAtMost(voice.size)
                    val tmp = File(getApplication<Application>().cacheDir, "export.wav")
                    Wav.write(tmp, voice.copyOfRange(skip, voice.size), 1, ENGINE_RATE)
                    getApplication<Application>().contentResolver.openOutputStream(dest)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: error("impossible d'ouvrir le fichier de destination")
                    tmp.delete()
                }
                showMessage("Fichier exporté")
            } catch (e: Exception) {
                showMessage("Échec de l'export : ${e.message}")
            }
        }
    }

    override fun onCleared() {
        stopRequested = true
        super.onCleared()
    }
}
