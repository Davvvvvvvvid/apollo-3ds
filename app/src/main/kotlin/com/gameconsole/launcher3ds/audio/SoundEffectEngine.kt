package com.gameconsole.launcher3ds.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Environment
import com.gameconsole.launcher3ds.model.Theme3DS
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 🎵 Nintendo 3DS 实机风格音效与 HOME 菜单背景音乐引擎
 * - 实时合成 3DS 标志性木琴光标音、图标缩放音、顶栏水晶铃音、卡带启动和弦
 * - 默认循环播放内置的 3DS 实机背景音乐 (`assets/3ds_bgm.mp3`)，亦可在设置中切换自定义本地音频
 */
object SoundEffectEngine {

    private const val SAMPLE_RATE = 24000
    private const val BUILTIN_BGM_ASSET = "3ds_bgm.mp3"
    private val sfxExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var sfxVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            sfxEnabled = (field > 0.001f)
        }

    @Volatile
    var sfxEnabled: Boolean = true

    @Volatile
    var bgmEnabled: Boolean = true

    @Volatile
    private var isBgmRunning: Boolean = false

    @Volatile
    private var currentThemeNotes: IntArray = intArrayOf(523, 659, 784, 987, 1046)

    private var bgmThread: Thread? = null
    private var customMediaPlayer: MediaPlayer? = null
    private var customBgmPath: String = ""

    private var soundPool: SoundPool? = null
    private var soundIdCursor: Int = 0
    private var soundIdLaunch: Int = 0
    private var soundIdCancel: Int = 0
    private var soundIdSelect: Int = 0
    private var soundIdFolder: Int = 0
    private var soundIdZoomIn: Int = 0
    private var soundIdZoomOut: Int = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        Thread {
            loadSoundPool(context.applicationContext)
        }.start()
    }

    private fun loadSoundPool(context: Context) {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(audioAttributes)
                .build()

            // 优先检查外部自定义音频目录 (/sdcard/Apollo/3ds_sfx/ 或 /sdcard/3ds_sfx/)
            val extDirs = listOf(
                File(Environment.getExternalStorageDirectory(), "Apollo/3ds_sfx"),
                File(Environment.getExternalStorageDirectory(), "3ds_sfx"),
                File(context.getExternalFilesDir(null), "3ds_sfx")
            )
            val extDir = extDirs.firstOrNull { it.exists() && it.isDirectory }

            fun loadSound(baseName: String, assetName: String): Int {
                if (extDir != null) {
                    val candidateFiles = listOf(
                        "$baseName.wav", "$baseName.ogg", "$baseName.mp3",
                        "se_$baseName.wav", "se_$baseName.ogg", "se_$baseName.mp3"
                    )
                    candidateFiles.map { File(extDir, it) }
                        .firstOrNull { it.exists() && it.length() > 0 }
                        ?.let { extFile ->
                            val id = sp.load(extFile.absolutePath, 1)
                            if (id > 0) return id
                        }
                }
                // 从 assets/3ds_sfx/ 中加载内置的 3DS 实机高质量音频采样
                return try {
                    val afd = context.assets.openFd("3ds_sfx/$assetName")
                    val id = sp.load(afd, 1)
                    afd.close()
                    id
                } catch (_: Exception) {
                    try {
                        val cacheFile = File(context.cacheDir, "sfx_v2_$assetName")
                        if (!cacheFile.exists() || cacheFile.length() == 0L) {
                            context.assets.open("3ds_sfx/$assetName").use { input ->
                                cacheFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        sp.load(cacheFile.absolutePath, 1)
                    } catch (_: Exception) {
                        0
                    }
                }
            }

            soundIdCursor = loadSound("cursor", "se_cursor.wav")
            soundIdLaunch = loadSound("launch", "se_launch.wav")
            soundIdCancel = loadSound("cancel", "se_cancel.wav")
            soundIdSelect = loadSound("select", "se_select.wav")
            soundIdFolder = loadSound("folder", "se_folder.wav")
            soundIdZoomIn = loadSound("zoom_in", "se_zoom_in.wav")
            soundIdZoomOut = loadSound("zoom_out", "se_zoom_out.wav")

            soundPool = sp
        } catch (_: Exception) {
        }
    }

    /**
     * 3DS 下屏网格移动光标音效（清脆木琴短音）
     */
    fun playCursorMove() {
        if (!sfxEnabled) return
        val vol = sfxVolume.coerceIn(0f, 1f)
        val sp = soundPool
        val sid = soundIdCursor
        if (sp != null && sid > 0) {
            sp.play(sid, vol, vol, 1, 0, 1.0f)
            return
        }
        sfxExecutor.execute {
            val samples = synthesizeTone(
                freqs = doubleArrayOf(740.0, 1480.0),
                weights = doubleArrayOf(0.75, 0.25),
                durationMs = 42,
                decayRate = 48.0,
                pitchSlide = 1.08
            )
            playPcmBuffer(samples, 0.55f * vol)
        }
    }

    /**
     * 3DS 缩放网格行数 (+/-) 音效
     */
    fun playZoomChange(zoomIn: Boolean) {
        if (!sfxEnabled) return
        val vol = sfxVolume.coerceIn(0f, 1f)
        val sp = soundPool
        val sid = if (zoomIn) soundIdZoomIn else soundIdZoomOut
        if (sp != null && sid > 0) {
            sp.play(sid, vol, vol, 1, 0, 1.0f)
            return
        }
        sfxExecutor.execute {
            val f1 = if (zoomIn) 587.33 else 783.99
            val f2 = if (zoomIn) 783.99 else 587.33
            val part1 = synthesizeTone(doubleArrayOf(f1, f1 * 2), doubleArrayOf(0.8, 0.2), 55, 28.0)
            val part2 = synthesizeTone(doubleArrayOf(f2, f2 * 2), doubleArrayOf(0.8, 0.2), 75, 22.0)
            playPcmBuffer(part1 + part2, 0.60f * vol)
        }
    }

    /**
     * 3DS 顶部五图标栏（笔记、好友、通知、浏览器、Miiverse）选中音效
     */
    fun playSelectApplet() {
        if (!sfxEnabled) return
        val vol = sfxVolume.coerceIn(0f, 1f)
        val sp = soundPool
        val sid = soundIdSelect
        if (sp != null && sid > 0) {
            sp.play(sid, vol, vol, 1, 0, 1.0f)
            return
        }
        sfxExecutor.execute {
            val part1 = synthesizeTone(doubleArrayOf(659.25, 987.77), doubleArrayOf(0.6, 0.4), 55, 24.0)
            val part2 = synthesizeTone(doubleArrayOf(987.77, 1318.5), doubleArrayOf(0.6, 0.4), 95, 18.0)
            playPcmBuffer(part1 + part2, 0.60f * vol)
        }
    }

    /**
     * 3DS 打开文件夹 / 抽屉弹窗音效
     */
    fun playFolderOpen() {
        if (!sfxEnabled) return
        val vol = sfxVolume.coerceIn(0f, 1f)
        val sp = soundPool
        val sid = soundIdFolder
        if (sp != null && sid > 0) {
            sp.play(sid, vol, vol, 1, 0, 1.0f)
            return
        }
        sfxExecutor.execute {
            val n1 = synthesizeTone(doubleArrayOf(523.25), doubleArrayOf(1.0), 40, 30.0)
            val n2 = synthesizeTone(doubleArrayOf(659.25), doubleArrayOf(1.0), 40, 30.0)
            val n3 = synthesizeTone(doubleArrayOf(783.99, 1046.5), doubleArrayOf(0.7, 0.3), 90, 16.0)
            playPcmBuffer(n1 + n2 + n3, 0.60f * vol)
        }
    }

    /**
     * 3DS 返回 / 关闭弹窗音效
     */
    fun playBackCancel() {
        if (!sfxEnabled) return
        val vol = sfxVolume.coerceIn(0f, 1f)
        val sp = soundPool
        val sid = soundIdCancel
        if (sp != null && sid > 0) {
            sp.play(sid, vol, vol, 1, 0, 1.0f)
            return
        }
        sfxExecutor.execute {
            val n1 = synthesizeTone(doubleArrayOf(659.25), doubleArrayOf(1.0), 45, 32.0)
            val n2 = synthesizeTone(doubleArrayOf(493.88), doubleArrayOf(1.0), 65, 26.0)
            playPcmBuffer(n1 + n2, 0.55f * vol)
        }
    }

    /**
     * 3DS 点击「打开 (Open)」启动卡带时的标志性水晶上行琶音 + 共鸣和弦
     */
    fun playLaunchGame() {
        if (!sfxEnabled) return
        val vol = sfxVolume.coerceIn(0f, 1f)
        val sp = soundPool
        val sid = soundIdLaunch
        if (sp != null && sid > 0) {
            sp.play(sid, vol, vol, 1, 0, 1.0f)
            return
        }
        sfxExecutor.execute {
            val c5 = synthesizeTone(doubleArrayOf(523.25, 1046.5), doubleArrayOf(0.7, 0.3), 55, 20.0)
            val g5 = synthesizeTone(doubleArrayOf(783.99, 1567.98), doubleArrayOf(0.7, 0.3), 55, 20.0)
            val b5 = synthesizeTone(doubleArrayOf(987.77, 1975.5), doubleArrayOf(0.7, 0.3), 55, 20.0)
            val e6Chord = synthesizeTone(
                freqs = doubleArrayOf(659.25, 987.77, 1318.5, 1567.98),
                weights = doubleArrayOf(0.35, 0.25, 0.25, 0.15),
                durationMs = 360,
                decayRate = 5.5
            )
            playPcmBuffer(c5 + g5 + b5 + e6Chord, 0.75f * vol)
        }
    }

    fun updateTheme(theme: Theme3DS, customAudioFile: String = "") {
        currentThemeNotes = theme.bgmScaleNotes
        if (customAudioFile != customBgmPath) {
            customBgmPath = customAudioFile
            if (isBgmRunning) {
                stopBgm()
                startBgm()
            }
        }
    }

    @Synchronized
    fun startBgm() {
        if (!bgmEnabled || isBgmRunning) return
        isBgmRunning = true
        sfxExecutor.execute {
            startBgmInternal()
        }
    }

    private fun startBgmInternal() {

        // 1. 若用户手动指定了外部自定义 BGM 路径且文件存在，优先播放该外部文件
        if (customBgmPath.isNotBlank() && File(customBgmPath).exists()) {
            try {
                customMediaPlayer?.release()
                customMediaPlayer = MediaPlayer().apply {
                    setDataSource(customBgmPath)
                    isLooping = true
                    setVolume(0.65f, 0.65f)
                    prepare()
                    start()
                }
                return
            } catch (_: Exception) {
                customMediaPlayer?.release()
                customMediaPlayer = null
            }
        }

        // 2. 默认直接播放打包进 APK 的 3DS.mp3 (assets/3ds_bgm.mp3)
        val ctx = appContext
        if (ctx != null) {
            try {
                customMediaPlayer?.release()
                val afd = ctx.assets.openFd(BUILTIN_BGM_ASSET)
                customMediaPlayer = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    isLooping = true
                    setVolume(0.65f, 0.65f)
                    prepare()
                    start()
                }
                return
            } catch (_: Exception) {
                // 若 AAPT 压缩导致 openFd 异常，解压到缓存目录后再用 MediaPlayer 循环播放
                try {
                    val cachedMp3 = File(ctx.cacheDir, "builtin_3ds_bgm.mp3")
                    if (!cachedMp3.exists() || cachedMp3.length() == 0L) {
                        ctx.assets.open(BUILTIN_BGM_ASSET).use { input ->
                            cachedMp3.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    customMediaPlayer?.release()
                    customMediaPlayer = MediaPlayer().apply {
                        setDataSource(cachedMp3.absolutePath)
                        isLooping = true
                        setVolume(0.65f, 0.65f)
                        prepare()
                        start()
                    }
                    return
                } catch (_: Exception) {
                    customMediaPlayer?.release()
                    customMediaPlayer = null
                }
            }
        }

        // 3. 极端兜底：合成环境音
        bgmThread = Thread {
            var step = 0
            while (isBgmRunning && bgmEnabled) {
                try {
                    val notes = currentThemeNotes
                    if (notes.isNotEmpty()) {
                        val rootHz = notes[step % notes.size].toDouble()
                        val fifthHz = notes[(step + 2) % notes.size].toDouble() * 0.5
                        val buf = synthesizeAmbientNote(rootHz, fifthHz, 650)
                        if (isBgmRunning && bgmEnabled) {
                            playPcmBuffer(buf, 0.12f)
                        }
                    }
                    step++
                    Thread.sleep(420)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "3DS-Home-BGM"
            start()
        }
    }

    @Synchronized
    fun stopBgm() {
        isBgmRunning = false
        try {
            bgmThread?.interrupt()
            bgmThread = null
        } catch (_: Exception) {
        }
        try {
            customMediaPlayer?.stop()
            customMediaPlayer?.release()
            customMediaPlayer = null
        } catch (_: Exception) {
        }
    }

    private fun synthesizeTone(
        freqs: DoubleArray,
        weights: DoubleArray,
        durationMs: Int,
        decayRate: Double,
        pitchSlide: Double = 1.0
    ): ShortArray {
        val count = (SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / count.coerceAtLeast(1)
            val slideFactor = 1.0 + (pitchSlide - 1.0) * progress
            val env = exp(-decayRate * t) * (if (i < 48) i / 48.0 else 1.0)
            var sum = 0.0
            for (k in freqs.indices) {
                sum += weights[k] * sin(2.0 * PI * freqs[k] * slideFactor * t)
            }
            val sample = (sum * env * Short.MAX_VALUE * 0.85).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = sample.toShort()
        }
        return out
    }

    private fun synthesizeAmbientNote(melodyHz: Double, padHz: Double, durationMs: Int): ShortArray {
        val count = (SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(count)
        val attackSamples = (SAMPLE_RATE * 0.06).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val decay = exp(-3.8 * t)
            val bell = sin(2.0 * PI * melodyHz * t) * 0.55 + sin(2.0 * PI * melodyHz * 2.0 * t) * 0.15 * exp(-8.0 * t)
            val pad = sin(2.0 * PI * padHz * t) * 0.30
            val sample = ((bell + pad) * attack * decay * Short.MAX_VALUE * 0.7).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = sample.toShort()
        }
        return out
    }

    private fun playPcmBuffer(samples: ShortArray, volume: Float) {
        if (samples.isEmpty()) return
        var track: AudioTrack? = null
        try {
            val byteSize = samples.size * 2
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(byteSize, minBuf)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size)
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            val sleepMs = (samples.size * 1000L / SAMPLE_RATE) + 30L
            Thread.sleep(sleepMs)
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }
}
