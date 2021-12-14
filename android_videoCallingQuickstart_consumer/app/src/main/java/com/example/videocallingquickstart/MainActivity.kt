package com.example.videocallingquickstart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import com.example.videocallingquickstart.MainActivity.StreamData
import android.os.Bundle
import android.os.Build
import android.media.AudioManager
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.os.StrictMode
import android.util.Log
import android.view.View
import android.widget.Button
import com.azure.android.communication.common.CommunicationTokenCredential
import android.widget.Toast
import android.widget.EditText
import com.azure.android.communication.common.CommunicationIdentifier
import com.azure.android.communication.common.CommunicationUserIdentifier
import android.widget.LinearLayout
import com.azure.android.communication.calling.*
import java.lang.Exception
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import org.eclipse.paho.client.mqttv3.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.StringBuilder
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private var callAgent: CallAgent? = null
    private var currentVideoStream: LocalVideoStream? = null
    private var deviceManager: DeviceManager? = null
    private var incomingCall: IncomingCall? = null
    private var call: Call? = null

    private lateinit var mqttClient: MQTTClient
    private val gson = Gson()

    var identity: String? = null
    var azureUserToken: String? = null
    var userToCall: String? = null


    var previewRenderer: VideoStreamRenderer? = null
    var preview: VideoStreamRendererView? = null
    val streamData: MutableMap<Int, StreamData> = HashMap()
    private val renderRemoteVideo = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        allPermissions

        generateTokens()

        createAgent()
        handleIncomingCall()
        connectMQTTClient()

        // Check internet connection
        if(!isNetworkConnected()) {
            Log.d("Debug", "Internet is NOT available")
        } else {
            Log.d("Debug", "Internet IS available")
        }

        val callButton = findViewById<Button>(R.id.call_button)
        callButton.setOnClickListener { l: View? -> startCall() }
        val hangupButton = findViewById<Button>(R.id.hang_up)
        hangupButton.setOnClickListener { l: View? -> hangUp() }
        val startVideo = findViewById<Button>(R.id.show_preview)
        startVideo.setOnClickListener { l: View? -> turnOnLocalVideo() }
        val stopVideo = findViewById<Button>(R.id.hide_preview)
        stopVideo.setOnClickListener { l: View? -> turnOffLocalVideo() }
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
    }

    private fun generateTokens() {

        val SDK_INT = Build.VERSION.SDK_INT
        if (SDK_INT > 8) {
            val policy = StrictMode.ThreadPolicy.Builder()
                .permitAll().build()
            StrictMode.setThreadPolicy(policy)
            //your codes here
        }

        Log.d("DEBUG", "Let's generate some tokens!")
        // Instantiate the RequestQueue.
        var url = "http://sdv-access-token-gmsdv.akscluster.sdvgm.com/token"
        val tokenEndpoint = URL(url)


        // Request a string response from the provided URL.
        val myConnection: HttpURLConnection = tokenEndpoint.openConnection() as HttpURLConnection

        try {
            val `in`: InputStream = myConnection.getInputStream()
            val isw = InputStreamReader(`in`)
            var data: Int = isw.read()

            var response = StringBuilder()
            while (data != -1) {
                val current = data.toChar()
                data = isw.read()
                response.append(current)
            }

            Log.d("DEBUG", "API DATA: ${response.toString()}")
            val obj = JSONArray(response.toString())

            val jsonArray = JSONArray(response.toString())

            for (i in 0 until jsonArray.length()) {
                val jsonobject: JSONObject = jsonArray.getJSONObject(i)
                val desc = jsonobject.getString("description")
                val name = jsonobject.getString("name")

                Log.d("DEBG", "API ID: ${name.toString()}" )
                Log.d("DEBUG", "API Token: ${desc.toString()}")

                if (name.toString() !== "Error") {
                    identity = name.toString()
                    azureUserToken = desc.toString()
                }
            }

        } catch (e: Exception) {
            Log.d("DEBUG", "API error: ${e.printStackTrace()}")
        } finally {
            if (myConnection != null) {
                myConnection.disconnect()
            }
        }
    }


    private val allPermissions: Unit
        private get() {
            val requiredPermissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_PHONE_STATE)
            val permissionsToAskFor = ArrayList<String>()
            for (permission in requiredPermissions) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToAskFor.add(permission)
                }
            }
            if (!permissionsToAskFor.isEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsToAskFor.toTypedArray(), 1)
            }
        }

    private fun createAgent() {
        val context = this.applicationContext
        try {
            val credential = CommunicationTokenCredential(azureUserToken)
            val callClient = CallClient()
            deviceManager = callClient.getDeviceManager(context).get()
            callAgent = callClient.createCallAgent(applicationContext, credential).get()
            Log.i("Agent", callAgent.toString())
        } catch (ex: Exception) {
            Toast.makeText(context, "Failed to create call agent.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIncomingCall() {
        try {
            callAgent!!.addOnIncomingCallListener { incomingCall: IncomingCall? ->
                this.incomingCall = incomingCall
                Executors.newCachedThreadPool().submit { answerIncomingCall() }
            }
        } catch (e: Exception) {
            Log.d("DEBUG", "EXCEPTION: ${e.toString()}")
        }

    }

    private fun startCall() {
        val context = this.applicationContext
        val participants = ArrayList<CommunicationIdentifier>()
        val options = StartCallOptions()
        val cameras = deviceManager!!.cameras
        if (!cameras.isEmpty()) {
            Log.i("Agent", "cameras not empty")
            val camera = chooseCamera(cameras)
            currentVideoStream = LocalVideoStream(camera, context)
            val videoStreams = arrayOfNulls<LocalVideoStream>(1)
            videoStreams[0] = currentVideoStream
            val videoOptions = VideoOptions(videoStreams)
            options.videoOptions = videoOptions
            showPreview(currentVideoStream!!)
        }
        Log.i("Agent", userToCall!!)
        participants.add(CommunicationUserIdentifier(userToCall!!))
        Log.i("Agent Participants:", participants.toString())
        call = callAgent!!.startCall(
                context,
                participants,
                options)
        call!!.addOnRemoteParticipantsUpdatedListener(ParticipantsUpdatedListener { args: ParticipantsUpdatedEvent -> handleRemoteParticipantsUpdate(args) })
        call!!.addOnStateChangedListener(PropertyChangedListener { args: PropertyChangedEvent -> handleCallOnStateChanged(args) })
    }

    private fun hangUp() {
        try {
            call!!.hangUp().get()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        if (previewRenderer != null) {
            previewRenderer!!.dispose()
        }
    }

    fun turnOnLocalVideo() {
        val cameras = deviceManager!!.cameras
        if (!cameras.isEmpty()) {
            try {
                val cameraToUse = chooseCamera(cameras)
                currentVideoStream = LocalVideoStream(cameraToUse, this)
                showPreview(currentVideoStream!!)
                call!!.startVideo(this, currentVideoStream).get()
            } catch (acsException: CallingCommunicationException) {
                acsException.printStackTrace()
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    fun turnOffLocalVideo() {
        try {
            (findViewById<View>(R.id.localvideocontainer) as LinearLayout).removeAllViews()
            previewRenderer!!.dispose()
            previewRenderer = null
            call!!.stopVideo(this, currentVideoStream).get()
        } catch (acsException: CallingCommunicationException) {
            acsException.printStackTrace()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun chooseCamera(cameras: List<VideoDeviceInfo>): VideoDeviceInfo {
        for (camera in cameras) {
            if (camera.cameraFacing == CameraFacing.FRONT) {
                return camera
            }
        }
        return cameras[0]
    }

    private fun showPreview(stream: LocalVideoStream) {
        // Create renderer
        previewRenderer = VideoStreamRenderer(stream, this)
        val layout = findViewById<View>(R.id.localvideocontainer) as LinearLayout
        preview = previewRenderer!!.createView(CreateViewOptions(ScalingMode.FIT))
        runOnUiThread { layout.addView(preview) }
    }

    private fun handleCallOnStateChanged(args: PropertyChangedEvent) {
        Log.d("DEBUG", "Call State: ${call!!.state}")
        if (call!!.state == CallState.CONNECTED) {
            handleCallState()
        }
        if (call!!.state == CallState.DISCONNECTED) {
            if (previewRenderer != null) {
                previewRenderer!!.dispose()
            }
        }
    }

    private fun handleCallState() {
        val participantVideoContainer = findViewById<LinearLayout>(R.id.remotevideocontainer)
        handleAddedParticipants(call!!.remoteParticipants, participantVideoContainer)
    }

    private fun answerIncomingCall() {
        val context = this.applicationContext
        if (incomingCall == null) {
            return
        }
        val acceptCallOptions = AcceptCallOptions()
        val cameras = deviceManager!!.cameras
        if (!cameras.isEmpty()) {
            val camera = chooseCamera(cameras)
            currentVideoStream = LocalVideoStream(camera, context)
            val videoStreams = arrayOfNulls<LocalVideoStream>(1)
            videoStreams[0] = currentVideoStream
            val videoOptions = VideoOptions(videoStreams)
            acceptCallOptions.videoOptions = videoOptions
            showPreview(currentVideoStream!!)
        }
        try {
            call = incomingCall!!.accept(context, acceptCallOptions).get()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        }
        call!!.addOnRemoteParticipantsUpdatedListener { args: ParticipantsUpdatedEvent -> handleRemoteParticipantsUpdate(args) }
        call!!.addOnStateChangedListener { args: PropertyChangedEvent -> handleCallOnStateChanged(args) }
    }

    fun handleRemoteParticipantsUpdate(args: ParticipantsUpdatedEvent) {
        Log.d("DEBUG", "handleRemoteParticipantsUpdate")
        val participantVideoContainer = findViewById<LinearLayout>(R.id.remotevideocontainer)
        handleAddedParticipants(args.addedParticipants, participantVideoContainer)
    }

    private fun handleAddedParticipants(participants: List<RemoteParticipant>, participantVideoContainer: LinearLayout) {
        for (remoteParticipant in participants) {
            remoteParticipant.addOnVideoStreamsUpdatedListener { videoStreamsEventArgs: RemoteVideoStreamsEvent -> videoStreamsUpdated(videoStreamsEventArgs) }
        }
    }

    private fun videoStreamsUpdated(videoStreamsEventArgs: RemoteVideoStreamsEvent) {
        for (stream in videoStreamsEventArgs.addedRemoteVideoStreams) {
            val data = StreamData(stream, null, null)
            streamData[stream.id] = data
            if (renderRemoteVideo) {
                startRenderingVideo(data)
            }
        }
        for (stream in videoStreamsEventArgs.removedRemoteVideoStreams) {
            stopRenderingVideo(stream)
        }
    }

    fun startRenderingVideo(data: StreamData) {
        Log.d("DEBUG", "startRenderingVideo")
        if (data.renderer != null) {
            return
        }
        val layout = findViewById<View>(R.id.remotevideocontainer) as LinearLayout
        data.renderer = VideoStreamRenderer(data.stream, this)
        data.renderer!!.addRendererListener(object : RendererListener {
            override fun onFirstFrameRendered() {
                val text = data.renderer!!.size.toString()
                Log.i("MainActivity", "Video rendering at: $text")
            }

            override fun onRendererFailedToStart() {
                val text = "Video failed to render"
                Log.i("MainActivity", text)
            }
        })
        data.rendererView = data.renderer!!.createView(CreateViewOptions(ScalingMode.FIT))
        runOnUiThread { layout.addView(data.rendererView) }
    }

    fun stopRenderingVideo(stream: RemoteVideoStream) {
        val data = streamData[stream.id]
        if (data == null || data.renderer == null) {
            return
        }
        runOnUiThread { (findViewById<View>(R.id.remotevideocontainer) as LinearLayout).removeAllViews() }
        data.rendererView = null
        // Dispose renderer
        data.renderer!!.dispose()
        data.renderer = null
    }

    class StreamData(var stream: RemoteVideoStream, var renderer: VideoStreamRenderer?, var rendererView: VideoStreamRendererView?)


    // connecting to MQTT client
    private fun connectMQTTClient() {
        // Create MQTT Client
        Log.d("DEBUG","MQTT - SERVER URI : $MQTT_SERVER_URI / $MQTT_CLIENT_ID")
        mqttClient = MQTTClient(this, MQTT_SERVER_URI, MQTT_CLIENT_ID)
        mqttClient.connect(MQTT_USERNAME, MQTT_PWD,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d("DEBUG", "MQTT - connection success")
//                  Attempt to subscribe
                        mqttClient.subscribe(MQTT_TOPIC, 1, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                Log.d("DEBUG", "MQTT - subscribed to : $MQTT_TOPIC")

                                mqttClient.publish(MQTT_TOPIC, identity!!, 1, false);


                            }
                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                Log.d("DEBUG", "MQTT - failed to subscribe to: $MQTT_TOPIC")
                            }
                        })
                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.d("DEBUG", "MQTT - connection failure: ${exception.toString()}")
                    }
                }, object : MqttCallback {
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Log.d("DEBUG", "MQTT - Message Arrived! $topic $message")
                try {
                    Log.d("DEBUG", "Identiity: $identity UserToCall: ${message.toString()}")
                    userToCall = message.toString()
                    Log.d("DEBUG", "NOT Calling Self?: ${!userToCall.equals(identity.toString())}")

                    if (!userToCall.equals(identity.toString())) {
                        Log.d("DEBUG", "UserToCall: $userToCall")
                        if (userToCall !== "") {
                            Log.d("DEBUG", "startcall")

                            startCall()
                        }
                    }
                } catch (ex: Exception) {
                    Log.d("DEBUG", "MQTT ERROR: $ex")
                }
                val jsonObject = gson.fromJson(message.toString(), JsonObject::class.java)
                Log.d("DEBUG", "MQTT - json reply outside " + jsonObject.toString())
                if (jsonObject.has("event")) {
                    val isCatDetected =  jsonObject.get("cat_detected").asString
                    val isDogDetected = jsonObject.get("dog_detected").asString
                    val isPersonDetected = jsonObject.get("person_detected").asString
                    Log.d("DEBUG", "MQTT - json reply inside " + jsonObject.toString())
                    when (jsonObject.get("event").asString) {
                        "PET_OR_PERSON_PRESENT" -> {
                            Log.d("MainActivity", "MQTT - Message arrived : A pet or person is detected")
                            val toastCatMessage : String = if (isCatDetected.equals("True")) "A Cat is detected " else ""
                            val toastDogMessage: String = if (isDogDetected.equals("True")) "A Dog is detected " else ""
                            val personMessage: String = if (isPersonDetected.equals("True") && isCatDetected.equals("False") && isDogDetected.equals("False")) "No Pet detected only People are present!" else if (isPersonDetected.equals("True")) "along with a Person!" else ""
                            Toast.makeText(this@MainActivity, toastCatMessage + toastDogMessage  +
                                    personMessage,Toast.LENGTH_LONG).show()
                        }
                        "PET_OR_PERSON_ABSENT" -> {
                            Log.d("MainActivity", "MQTT - Message arrivced : A pet or person is absent")
                            Toast.makeText(this@MainActivity,
                                    "Pet or person is not present", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (jsonObject.has("type")) {
                    when (jsonObject.get("type").asString) {
                        "call" -> {
                            /*Toast.makeText(
                                    applicationContext,
                                    "Stream request received - beginning stream",
                                    Toast.LENGTH_SHORT
                            ).show()*/
                            Log.d("DEBUG", "MQTT - Received call message")
                        }
                        "answer" -> {
                            Log.d("DEBUG", "MQTT - Received answer message")
                            val jsonOffer = jsonObject.get("answer").asJsonObject

                        }
                        "candidate" -> {
                            // Verify we didn't send the candidate

                        }
                        "leave" -> {
                            // Verify we didn't send the candidate
                            //if(jsonObject.get("sender").asString != applicationID) {
                                Log.d("DEBUG", "MQTT - Received leave message")
                                //rtcClient.closeConnection()
                            //}
                            hangUp()
                        }
                        else -> Log.d("DEBUG", "MQTT - Received message: ${message.toString()}")
                    }
                }
            }
            override fun connectionLost(cause: Throwable?) {
                Log.d("DEBUG", "MQTT - connection lost ${cause.toString()}")
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                Log.d("DEBUG", "MQTT - delivery complete")
            }
        }
        )
    }

    //Disconnecting from MQTT client and closing RTC connection
    override fun onDestroy() {
        mqttClient.disconnect(object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.d(this.javaClass.name, "MQTT - disconnected")
                hangUp()
            }
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) { Log.d(this.javaClass.name, "MQTT - failed to disconnect") }
        })
        super.onDestroy()
    }


    //Checking for internet connection
    private fun isNetworkConnected(): Boolean {
        var result = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            if (capabilities != null) {
                result = when {
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) -> true
                    else -> false
                }
            }
        }
        else {
            val activeNetwork = cm.activeNetworkInfo
            if (activeNetwork != null) {
                // Connected to the internet
                result = when (activeNetwork.type) {
                    ConnectivityManager.TYPE_WIFI,
                    ConnectivityManager.TYPE_MOBILE,
                    ConnectivityManager.TYPE_VPN -> true
                    else -> false
                }
            }
        }
        return result
    }

}