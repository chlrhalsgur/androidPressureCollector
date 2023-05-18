package com.example.pressureCollector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Vibrator
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.kircherelectronics.fsensor.filter.gyroscope.OrientationGyroscope
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.angleFixMode
import kotlinx.android.synthetic.main.activity_main.angleView
import kotlinx.android.synthetic.main.activity_main.btnGyroReset
import kotlinx.android.synthetic.main.activity_main.btnSamplingStart
import kotlinx.android.synthetic.main.activity_main.fileName
import kotlinx.android.synthetic.main.activity_main.gyroView
import kotlinx.android.synthetic.main.activity_main.magXView
import kotlinx.android.synthetic.main.activity_main.magYView
import kotlinx.android.synthetic.main.activity_main.magZView
import kotlinx.android.synthetic.main.activity_main.positionXView
import kotlinx.android.synthetic.main.activity_main.positionYView
import kotlinx.android.synthetic.main.activity_main.stepCountView
import java.io.*
import java.util.*
import kotlin.math.*


private const val l : Double = 10.0

class MainActivity : AppCompatActivity(), SensorEventListener{
    private val mSensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val vibrator by lazy {
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private var accMatrix = FloatArray(3)
    private var startingTime = System.currentTimeMillis()
    private var path: String? = null
    private var permission_list = arrayOf(
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private var saveData : String = ""
    private var quaRoll : Float = 0f
    private var quaPitch : Float = 0f
    private var quaYaw : Float = 0f
    private var positionX : Double = 0.0
    private var positionY : Double = 0.0
    private var stepCount : Int = 0
    private var isAutoSampling : Boolean = false
    private var isRoundSampling : Boolean = false
    private var isGradientSampling : Boolean = false
    private var isAngleFixed : Boolean = false
    private var nowSampling : Boolean = false

    private var dataPosition : Queue<String> = LinkedList()
    private var dataMagx : Queue<String> = LinkedList()
    private var dataMagy : Queue<String> = LinkedList()
    private var dataMagz : Queue<String> = LinkedList()

    //---------------원준 변수-----------------------------
    private val orientationGyroscope by lazy {
        OrientationGyroscope()
    }
    private var mRot = FloatArray(3)
    private var mAzimuth : Float = 0f
    private var caliX : Double= 0.0
    private var caliY : Double= 0.0
    private var caliZ : Double= 0.0
    private var offsetX : Double= 0.0
    private var offsetY : Double= 0.0
    private var uncali_magnetic = FloatArray(6)

    private var isPressureStabilizated = false
    private var pressureStbCount = 0

    private var collectOn = false
    private var pressureData = ""
    private var pressureQueue = arrayListOf<Float>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkFunction()
        init()

        btnSamplingStart.setOnClickListener{
            startingTime = System.currentTimeMillis()
            collectOn = true
//            btnSamplingStop.isClickable = true
            pressureData = ""
        }
        btnSamplingStop.setOnClickListener {
            collectOn = false
            val filename = fileName.text.toString() + ".txt"
            checkPermission()

            path = Environment.getExternalStorageDirectory().absolutePath + "/android/data/" + packageName

            writeTextFile(filename, pressureData)
            val file = File(path)
            if(!file.exists()){
                file.mkdir()
            }
            saveToExternalStorage(pressureData, filename)
            collectionLog.text = "$filename has saved!"
        }


        groupSamplingMode.setOnCheckedChangeListener { group, checkedId ->
            nowSampling = false
            if (manualSampling.isChecked) {
                isAutoSampling = false
                isRoundSampling = false
                btnSamplingStart.text = "RECORD"
                circleCenter.visibility = View.INVISIBLE
                edge.visibility = View.INVISIBLE
            }
        }
    }

    private fun checkFunction() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun init() {
        manualSampling.isChecked = true
        edge.visibility = View.INVISIBLE
        circleCenter.visibility = View.INVISIBLE
        positionXView.setText("0")
        positionYView.setText("0")

        var path = getExternalPath()
        val file = File(path)
        if (!file.exists()) file.mkdir()
    }

    private fun getExternalPath() : String {
        var sdPath = ""
        val ext = Environment.getExternalStorageState()
        sdPath = if (ext == Environment.MEDIA_MOUNTED) {
            Environment.getExternalStorageDirectory()
                .absolutePath + "/pressureCollector/"
        } else {
            "$filesDir/pressureCollector/"
        }
        return sdPath
    }


    private fun writeTextFile(filename: String, contents: String) {
        try {
            //파일 output stream 생성
            val fos = FileOutputStream(getExternalPath() + filename, true)
            //파일쓰기
            val writer = BufferedWriter(OutputStreamWriter(fos))
            writer.write(contents)
            writer.flush()
            writer.close()
            fos.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun sensorStablization(){
        var count = 50
        if (pressureStbCount < 50){
            pressureStbCount ++
            collectionLog.text = ("sensor 안정화 중" + pressureStbCount.toString() + "/ $count")
            return
        }
        isPressureStabilizated = true
    }

    fun onChangePositionClicked(v: View) {
        positionX = positionXView.text.toString().toDouble()
        positionY = positionYView.text.toString().toDouble()
        when (v.id) {
            R.id.plusX -> positionX++
            R.id.minusX -> if (positionX > 0) positionX--
            R.id.plusY -> positionY++
            R.id.minusY -> if (positionY > 0) positionY--
        }
        positionXView.setText(positionX.toString())
        positionYView.setText(positionY.toString())
    }

    private fun checkPermission(){
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M){ return }
        for (permission: String in permission_list){
            val chk = checkCallingOrSelfPermission(permission)
            if (chk == PackageManager.PERMISSION_DENIED){
                requestPermissions(permission_list, 0)
                break
            }
        }
    }
    private fun getAppDataFileFromExternalStorage(filename: String) : File{
        val dir = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT){
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        }else{
            File(Environment.getExternalStorageDirectory().absolutePath+"/Documents")
        }

        dir?.mkdirs()
        return File("${dir!!.absolutePath}${File.separator}${filename}")
    }
    private fun saveToExternalStorage(text: String, filename: String){
        val fileOutputStream = FileOutputStream(getAppDataFileFromExternalStorage(filename))

        fileOutputStream.write(text.toString().toByteArray())
        fileOutputStream.close()
    }


    private fun axisTransform(axis : Char, rawDataX : Float, rawDataY : Float, rawDataZ : Float) : Float {
        return when(axis) {
            'x' -> { cos(-quaYaw) * cos(-quaRoll) * rawDataX+ (cos(-quaYaw) * sin(-quaRoll) * sin(quaPitch) - sin(-quaYaw) * cos(quaPitch)) * rawDataY+ (cos(-quaYaw) * sin(-quaRoll) * cos(quaPitch) + sin(-quaYaw) * sin(quaPitch)) * rawDataZ }
            'y' -> { sin(-quaYaw) * cos(-quaRoll) * rawDataX+ (sin(-quaYaw) * sin(-quaRoll) * sin(quaPitch) + cos(-quaYaw) * cos(quaPitch)) * rawDataY+ (sin(-quaYaw) * sin(-quaRoll) * cos(quaPitch) - cos(-quaYaw) * sin(quaPitch)) * rawDataZ }
            'z' -> { -sin(quaRoll) * rawDataX+ (cos(quaRoll) * sin(-quaPitch)) * rawDataY+ (cos(quaRoll) * cos(-quaPitch)) * rawDataZ }
            else -> -966.966966f
        }
    }


    private fun extractSaveData() {
        val extractPeriod = dataPosition.size / 6
        for (i in 0..5) {
            for (j in 0 until extractPeriod - 1) {
                dataPosition.poll()
                dataMagx.poll()
                dataMagy.poll()
                dataMagz.poll()
            }
            var posi = dataPosition.poll().split("\t")
            if (isRoundSampling) {
                val alpha = alphaView.text.toString().toDouble()
                val beta = betaView.text.toString().toDouble()
                val r = rView.text.toString().toDouble()
                val theta = ((i+1)+(6*stepCount))*l/r

                var nextX = round((posi[0].toDouble()-alpha)*cos(theta) + (posi[1].toDouble()-beta)*sin(theta) + alpha)
                var nextY = round(-(posi[0].toDouble()-alpha)*sin(theta) + (posi[1].toDouble()-beta)*cos(theta) + beta)
                saveData += nextX.toString() + "\t" + nextY + "\t" + dataMagx.poll() + "\t" + dataMagy.poll() + "\t" + dataMagz.poll() + "\r\n"
                //((posi[0].toDouble()-alpha)*cos((i+1)*l* PI/(r*180))+(posi[1].toDouble()-beta)*sin((i+1)*l* PI/(r*180))+alpha).toString() +"\t"+ (-(posi[0].toDouble()-alpha)*sin((i+1)*l* PI/(r*180))+(posi[1].toDouble()-beta)*cos((i+1)*l* PI/(r*180)) +beta).toString() + "\t" + data_magx.poll() + "\t" + data_magy.poll() + "\t" + data_magz.poll() + "\r\n"
            } else if (isGradientSampling) {
                var r = 0.0
                if (isAngleFixed) {
                    r = angleView.text.toString().toDouble()
                } else {
                    r = gyroView.text.toString().toDouble()
                }
                var nextX = round(posi[0].toDouble() + sin(Math.toRadians(r)) * (i+1))
                var nextY = round(posi[1].toDouble() + cos(Math.toRadians(r)) * (i+1))
                saveData += nextX.toString() + "\t" + nextY + "\t" + dataMagx.poll() + "\t" + dataMagy.poll() + "\t" + dataMagz.poll() + "\r\n"
            } else {
                saveData += posi[0] + "\t" + posi[1] + "\t" + dataMagx.poll() + "\t" + dataMagy.poll() + "\t" + dataMagz.poll() + "\r\n"
            }
        }
        dataPosition.clear()
        dataMagx.clear()
        dataMagy.clear()
        dataMagz.clear()
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

    }


    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accMatrix = event.values.clone()
                Sensor.TYPE_PRESSURE -> {
                    sensorStablization()
                    if (isPressureStabilizated) {
                        if (collectOn) {
                            pressureData += (event.values[0].toString() + "\t")

                            if (pressureQueue.size < 5){
                                pressureQueue.add(event.values[0])
                            }else{
                                pressureQueue.add(event.values[0])
                                pressureQueue.removeAt(0)
                            }

                            var pressureString = ""
                            for (i in pressureQueue){
                                pressureString += (i.toString() + "\n")
                            }
                            collectionLog.text = pressureString
                        }
                    }
                }

            }
        }
    }


    override fun onResume() {
        super.onResume()
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE), SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        super.onPause()
        mSensorManager.unregisterListener(this)
    }

}