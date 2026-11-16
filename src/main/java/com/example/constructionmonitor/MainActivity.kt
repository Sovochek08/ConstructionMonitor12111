package com.example.constructionmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    // Элементы UI
    private lateinit var tvNoiseValue: TextView
    private lateinit var pbNoise: ProgressBar
    private lateinit var tvNoiseStatus: TextView
    private lateinit var tvMaxNoise: TextView

    private lateinit var tvVibrationValue: TextView
    private lateinit var pbVibration: ProgressBar
    private lateinit var tvVibrationStatus: TextView
    private lateinit var tvMaxVibration: TextView
    private lateinit var btnReset: Button

    // Датчики вибрации
    private lateinit var sensorManager: SensorManager
    private var linearAccelerometer: Sensor? = null
    private var maxVibration = 0.0

    // Аудиозапись для шумомера
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var maxNoise = 0.0
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSensors()

        btnReset.setOnClickListener {
            maxNoise = 0.0
            maxVibration = 0.0
            tvMaxNoise.text = "Пик: 0.0 дБ"
            tvMaxVibration.text = "Пик: 0.00 м/с²"
        }

        checkAudioPermission()
    }

    private fun initViews() {
        tvNoiseValue = findViewById(R.id.tvNoiseValue)
        pbNoise = findViewById(R.id.pbNoise)
        tvNoiseStatus = findViewById(R.id.tvNoiseStatus)
        tvMaxNoise = findViewById(R.id.tvMaxNoise)

        tvVibrationValue = findViewById(R.id.tvVibrationValue)
        pbVibration = findViewById(R.id.pbVibration)
        tvVibrationStatus = findViewById(R.id.tvVibrationStatus)
        tvMaxVibration = findViewById(R.id.tvMaxVibration)
        btnReset = findViewById(R.id.btnReset)
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Используем линейное ускорение, чтобы исключить гравитацию (9.8 м/с²)
        linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (linearAccelerometer == null) {
            Toast.makeText(this, "Датчик линейного ускорения не найден!", Toast.LENGTH_LONG).show()
        }
    }

    // --- РАБОТА С ВИБРАЦИЕЙ ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Общий вектор ускорения
            val magnitude = sqrt((x * x + y * y + z * z).toDouble())

            if (magnitude > maxVibration) {
                maxVibration = magnitude
            }

            updateVibrationUI(magnitude)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateVibrationUI(vibration: Double) {
        tvVibrationValue.text = String.format("%.2f м/с²", vibration)
        tvMaxVibration.text = String.format("Пик: %.2f м/с²", maxVibration)

        // Масштабируем для ProgressBar (макс 20 м/с² = 100%)
        pbVibration.progress = (vibration * 5).toInt().coerceIn(0, 100)

        // Строительные пороги вибрации
        when {
            vibration < 1.5 -> {
                tvVibrationValue.setTextColor(Color.parseColor("#2E7D32")) // Зеленый
                tvVibrationStatus.text = "Норма (фоновая вибрация)"
            }
            vibration < 5.0 -> {
                tvVibrationValue.setTextColor(Color.parseColor("#F57C00")) // Оранжевый
                tvVibrationStatus.text = "Повышенная (работа инструмента)"
            }
            else -> {
                tvVibrationValue.setTextColor(Color.parseColor("#C62828")) // Красный
                tvVibrationStatus.text = "ОПАСНО! Критическая нагрузка"
            }
        }
    }

    // --- РАБОТА С ШУМОМ ---
    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startNoiseMeter()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startNoiseMeter()
        } else {
            Toast.makeText(this, "Нужен доступ к микрофону для замера шума", Toast.LENGTH_LONG).show()
        }
    }

    private fun startNoiseMeter() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true

        // Запускаем чтение звука в отдельном потоке, чтобы не тормозить интерфейс
        Thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val readCount = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (readCount > 0) {
                    var sum = 0.0
                    for (i in 0 until readCount) {
                        sum += buffer[i] * buffer[i]
                    }
                    // Среднеквадратичное значение (RMS)
                    val rms = sqrt(sum / readCount)
                    // Перевод в децибелы (с поправкой калибровки микрофона)
                    val db = if (rms > 0) 20 * log10(rms) else 0.0

                    if (db > maxNoise) {
                        maxNoise = db
                    }

                    runOnUiThread {
                        updateNoiseUI(db)
                    }
                }
                Thread.sleep(100) // Обновление 10 раз в секунду
            }
        }.start()
    }

    private fun updateNoiseUI(db: Double) {
        tvNoiseValue.text = String.format("%.1f дБ", db)
        tvMaxNoise.text = String.format("Пик: %.1f дБ", maxNoise)
        pbNoise.progress = db.toInt().coerceIn(0, 120)

        // Строительные нормы шума (СНиП / СанПиН)
        when {
            db < 75.0 -> {
                tvNoiseValue.setTextColor(Color.parseColor("#2E7D32")) // Зеленый
                tvNoiseStatus.text = "Норма (допустимый фон)"
            }
            db < 85.0 -> {
                tvNoiseValue.setTextColor(Color.parseColor("#F57C00")) // Оранжевый
                tvNoiseStatus.text = "Внимание! Порог СНиП (80-85 дБ)"
            }
            else -> {
                tvNoiseValue.setTextColor(Color.parseColor("#C62828")) // Красный
                tvNoiseStatus.text = "ОПАСНО! Нужны защитные наушники"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        linearAccelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && !isRecording) {
            startNoiseMeter()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}