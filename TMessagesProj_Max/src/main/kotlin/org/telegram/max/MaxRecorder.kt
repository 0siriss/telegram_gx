package org.telegram.max

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.telegram.messenger.MediaController
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Запись голосовых для MAX кодировщиком Opus самого Telegram. MAX принимает Ogg/Opus;
 * Komet пишет, что нативный Opus Android сервер не дообрабатывает (вложение навсегда
 * «not.ready»), и кодирует своим libopus. У Telegram свой libopus с Ogg-обёрткой — те же
 * методы, которыми Telegram пишет свои голосовые: startRecord(путь, 48000) (0 — ошибка),
 * writeFrame(кадр 20 мс, длина), stopRecord(). Они закрытые, поэтому через рефлексию;
 * R8 их не трогает — пакет org.telegram.messenger целиком в keep.
 */
object MaxRecorder {

    private const val SAMPLE_RATE = 48_000
    private const val FRAME_BYTES = 1920 // 20 мс моно PCM16 на 48 кГц

    private var startRecord: Method? = null
    private var writeFrame: Method? = null
    private var stopRecord: Method? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var file: File? = null
    private val peaks = ArrayList<Float>()
    @Volatile
    private var bytes = 0L

    val isRecording: Boolean
        get() = running

    /** Сколько уже записано, мс: 96 байт PCM16 моно на миллисекунду при 48 кГц. */
    val elapsedMs: Long
        get() = bytes / 96L

    private fun bind(): Boolean {
        if (startRecord != null) return true
        return try {
            val cls = MediaController::class.java
            startRecord = cls.getDeclaredMethod("startRecord", String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            writeFrame = cls.getDeclaredMethod("writeFrame", ByteBuffer::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            stopRecord = cls.getDeclaredMethod("stopRecord").apply { isAccessible = true }
            true
        } catch (e: Throwable) {
            MaxLog.e("кодировщик голосовых Telegram недоступен", e)
            false
        }
    }

    /** Начать запись. false — не получилось (нет кодировщика или микрофона). */
    fun start(context: Context): Boolean {
        if (running || !bind()) return false
        val controller = MediaController.getInstance()
        val target = File(context.cacheDir, "max_voice_" + System.currentTimeMillis() + ".ogg")
        val ok = try {
            (startRecord!!.invoke(controller, target.path, SAMPLE_RATE) as Int) != 0
        } catch (e: Throwable) {
            MaxLog.e("startRecord упал", e)
            false
        }
        if (!ok) return false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME_BYTES * 4))
        } catch (e: Throwable) {
            MaxLog.e("микрофон недоступен", e)
            safeStop(controller)
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            safeStop(controller)
            return false
        }
        file = target
        synchronized(peaks) { peaks.clear() }
        bytes = 0L
        running = true
        recorder.startRecording()
        thread = Thread({ loop(recorder, controller) }, "max-voice").apply { start() }
        return true
    }

    /**
     * Блокирующее чтение возвращает целый кадр. В прямой буфер AudioRecord пишет всегда
     * с начала и на position не смотрит, поэтому кадр читаем целиком и сразу отдаём
     * кодировщику — как сам Telegram в MediaController.
     */
    private fun loop(recorder: AudioRecord, controller: MediaController) {
        val frame = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.nativeOrder())
        try {
            while (running) {
                frame.clear()
                val read = recorder.read(frame, FRAME_BYTES)
                if (read <= 0) continue
                writeFrame!!.invoke(controller, frame, read)
                synchronized(peaks) { peaks.add(peak(frame, read)) }
                bytes += read
            }
        } catch (e: Throwable) {
            MaxLog.e("запись голосового прервалась", e)
        } finally {
            try {
                recorder.stop()
            } catch (ignored: Throwable) {
            }
            recorder.release()
            safeStop(controller)
        }
    }

    private fun safeStop(controller: MediaController) {
        try {
            stopRecord?.invoke(controller)
        } catch (e: Throwable) {
            MaxLog.e("stopRecord упал", e)
        }
    }

    /** Пик кадра от 0 до 1. */
    private fun peak(frame: ByteBuffer, bytes: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < bytes) {
            val sample = Math.abs(frame.getShort(i).toInt())
            if (sample > max) max = sample
            i += 2
        }
        return (max / 32768f).coerceIn(0f, 1f)
    }

    class Result(val file: File, val durationMs: Long, val wave: ByteArray)

    /**
     * Остановить запись. [send] = false — выбросить файл. Колбэк на фоновом потоке,
     * после того как кодировщик дописал и закрыл файл.
     */
    fun stop(send: Boolean, onDone: (Result?) -> Unit) {
        if (!running) {
            onDone(null)
            return
        }
        running = false
        val worker = thread
        val target = file
        Thread {
            try {
                worker?.join(3_000L)
            } catch (ignored: InterruptedException) {
            }
            val duration = elapsedMs
            if (!send || target == null || duration < 700L) {
                target?.delete()
                onDone(null)
                return@Thread
            }
            finishOgg(target)
            onDone(Result(target, duration, wave()))
        }.start()
    }

    /**
     * Кодировщик Telegram при остановке сбрасывает страницы, но не ставит на последнюю
     * флаг конца потока. Сервер MAX такой файл не дообрабатывает (attachment.not.ready),
     * поэтому дописываем EOS сами и пересчитываем CRC страницы.
     */
    private fun finishOgg(file: File) {
        val bytes = try {
            file.readBytes()
        } catch (e: Throwable) {
            MaxLog.e("не прочитать запись для завершения Ogg", e)
            return
        }
        var offset = 0
        var last = -1
        while (offset + 27 <= bytes.size) {
            if (bytes[offset] != 'O'.code.toByte() || bytes[offset + 1] != 'g'.code.toByte() ||
                bytes[offset + 2] != 'g'.code.toByte() || bytes[offset + 3] != 'S'.code.toByte()
            ) return
            val segments = bytes[offset + 26].toInt() and 0xFF
            if (offset + 27 + segments > bytes.size) return
            var body = 0
            for (i in 0 until segments) body += bytes[offset + 27 + i].toInt() and 0xFF
            val end = offset + 27 + segments + body
            if (end > bytes.size) return
            last = offset
            offset = end
        }
        if (last < 0 || offset != bytes.size) return
        if (bytes[last + 5].toInt() and 0x04 != 0) return
        bytes[last + 5] = (bytes[last + 5].toInt() or 0x04).toByte()
        for (i in 22..25) bytes[last + i] = 0
        var crc = 0
        for (i in last until bytes.size) {
            crc = (crc shl 8) xor OGG_CRC[((crc ushr 24) xor (bytes[i].toInt() and 0xFF)) and 0xFF]
        }
        bytes[last + 22] = (crc and 0xFF).toByte()
        bytes[last + 23] = ((crc ushr 8) and 0xFF).toByte()
        bytes[last + 24] = ((crc ushr 16) and 0xFF).toByte()
        bytes[last + 25] = ((crc ushr 24) and 0xFF).toByte()
        try {
            file.writeBytes(bytes)
        } catch (e: Throwable) {
            MaxLog.e("не записать завершённый Ogg", e)
        }
    }

    /** CRC Ogg: полином 0x04C11DB7, старший бит вперёд, без отражения. */
    private val OGG_CRC = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) {
            value = if (value and 0x80000000.toInt() != 0) {
                (value shl 1) xor 0x04C11DB7
            } else {
                value shl 1
            }
        }
        value
    }

    /** Волна для MAX, как у Komet: 80 столбиков, пики 0..120. */
    private fun wave(): ByteArray {
        val amps = synchronized(peaks) { peaks.toList() }
        val bars = 80
        val out = ByteArray(bars)
        if (amps.isEmpty()) return out
        for (i in 0 until bars) {
            val start = i * amps.size / bars
            val end = maxOf(start + 1, minOf(amps.size, ((i + 1) * amps.size + bars - 1) / bars))
            var peak = 0f
            for (j in start until end) if (amps[j] > peak) peak = amps[j]
            out[i] = (peak * 120).toInt().coerceIn(0, 120).toByte()
        }
        return out
    }
}
