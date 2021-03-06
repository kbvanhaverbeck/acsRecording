package com.example.videocallingquickstart

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.android.volley.*
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.azure.android.communication.calling.*
import com.azure.android.communication.common.CommunicationIdentifier
import com.azure.android.communication.common.CommunicationTokenCredential
import com.azure.android.communication.common.CommunicationUserIdentifier
import com.google.gson.Gson
import kotlinx.coroutines.*
import nl.bravobit.ffmpeg.*
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.Locale.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import com.android.volley.VolleyError
import com.android.volley.RetryPolicy

class MainActivity : AppCompatActivity(), CoroutineScope {
    private var callAgent: CallAgent? = null
    private var currentVideoStream: LocalVideoStream? = null
    private var deviceManager: DeviceManager? = null
    private var incomingCall: IncomingCall? = null
    private var call: Call? = null
    private var m_virtualVideoStream: LocalVideoStream? = null
    private var m_frameGeneratorThread: Thread? = null
    private var outboundVirtualVideoDevice: OutboundVirtualVideoDevice? = null
    private var mediaFrameSender: MediaFrameSender? = null
    private var m_mediaFrameSender = arrayOfNulls<MediaFrameSender>(1)
    // Bytes holding the video frame content.private
    private lateinit var mqttClient: MQTTClient
    private val gson = Gson()

    var identity: String? = null
    var azureUserToken: String? = null

    var previewRenderer: VideoStreamRenderer? = null
    var preview: VideoStreamRendererView? = null
    val streamData: MutableMap<Int, StreamData> = HashMap()
    private val renderRemoteVideo = true
    var serverCallId: String? = null
    var recordingID: String? = null
    var ffmPegMessage:String? =" "
    var RecordingInitiated = false
    val usersToCall: MutableList<String> = ArrayList()
    var toRecord = true
    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        allPermissions
        GlobalScope.launch {
            launch{extractRTSPVideo()}
        }

        // Check internet connection
        if(!isNetworkConnected()) {
            Log.d("Debug", "Internet is NOT available")
            // NoInternetAlert.show()

        } else {
            Log.d("Debug", "Internet IS available")

            //generate Azure User Token & Identity
            generateTokens()
            Log.d("DEBUG", "Is Tokens Generated? ${(identity != null && azureUserToken != null)}")
            if ( identity != null && azureUserToken != null) {
                createAgent()
                connectMQTTClient()
                handleIncomingCall()
            } else {
                //  NoInternetAlert.show()
            }
        }


        if (FFmpeg.getInstance(this).isSupported()) {
            // ffmpeg is supported
            Log.d("DEBUG", "ffmeg is suported")
        } else {
            // ffmpeg is not supported
            Log.d("DEBUG", "ffmeg is NOT suported")
        }


        val hangupButton = findViewById<ImageButton>(R.id.hang_up)
        hangupButton.setOnClickListener { l: View? -> hangUp() }

        val startCallButton = findViewById<Button>(R.id.startCall)
        startCallButton.setOnClickListener { l: View? -> startCall() }

        val calleeText = findViewById<TextView>(R.id.textView)
        calleeText.text = "Callee: "

        volumeControlStream = AudioManager.STREAM_VOICE_CALL
    }

    /**      * GENERATE AZURE COMMUNICATION TOKEN DYNAMICALLY VIA EXTERNAL API.       */
    private fun generateTokens() {

        val SDK_INT = Build.VERSION.SDK_INT
        if (SDK_INT > 8) {
            val policy = ThreadPolicy.Builder()
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

                Log.d("DEBG", "API ID: ${name.toString()}")
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


    /**      * START: HADLE VIDEO FEED   - NEEDS ATTENTION  (Step 1)
     *
     *          extractRTSPVideo uses FFMPEG to convert a RTSP steam to a LOCAL .yuv
     *              - Reported Issue: Cannot access RawData Frames from .yuv until FFMPEG is complete
     *
     *          Depending on how the video is extracted, VirualCamera.java virutalCamera Formats will need to be changed to reflect the same settings.
     *              - I.E. if extracting video to RBGA, the virtual camera will need to be formatted to send RGBA
     *
     *
     * */
    fun extractRTSPVideo() {
        val root = Environment.getExternalStorageDirectory()

        //val vehicleVideo = assets.open("IMG_9879.mov")

        val blobStorage =
                "https://testacsrecording.file.core.windows.net/acs-pet-detection/IMG_9879.mov"

        val dir = File(root.absolutePath + "/sdv")
        Log.d("DEBUG", "Directory Exists? $ ${dir}  : ${dir.exists()}")
        if (!dir.exists()) {
            dir.mkdirs()
            Log.d("DEBUG", "Directory Exists? ${dir.exists()}")
        }

        val yuvExists = File(root.absolutePath + "/sdv/out.yuv")
        Log.d("DEBUG", "Directory Exists? $ ${yuvExists}  : ${yuvExists.exists()}")
        /*  if (yuvExists.exists()) {
              yuvExists.delete()
              Log.d("DEBUG", "Directory Exists? $ ${yuvExists}  : ${yuvExists.exists()}")
          }*/

        Log.d("DEBUG", root.toString())

        val STREAM_URL =
                "rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mov" //check resolution and put that size into virtualCamera //try and find one in 720

        val cmd = arrayOf(
                "-i", STREAM_URL, "-c:v", "rawvideo", "-pix_fmt", "yuv420p", "${root}/sdv/out.yuv"
        )

        try {
            FFmpeg.getInstance(this).execute(cmd, object : ExecuteBinaryResponseHandler() {
                override fun onStart() {
                    super.onStart()
                    Log.d("DEBUG", "ffmeg Start")
                }

                override fun onFailure(message: String) {
                    super.onFailure(message)
                    Log.d("DEBUG", "ffmeg $message")
                }

                override fun onSuccess(message: String) {
                    super.onSuccess(message)
                    Log.d("DEBUG", "ffmeg OnSuccess... $message")
                }

                override fun onProgress(message: String) {
                    super.onProgress(message)
                    ffmPegMessage = "ffmeg" + "$message"
                    Log.d("DEBUG", "ffmeg onProgress $message")
                    // extractLocalVideo()

                }

                override fun onFinish() {
                    super.onFinish()
                    // extractLocalVideo()
                    Log.d("DEBUG", "onFinish")
                    extractLocalVideo()
                }
            })
        } catch (e: Exception) {
            // do nothing for now
            Log.d("execFFmpegBinary", "Exception $e");
        }
    }

    fun extractLocalVideo() {
        Log.d("DEBUG", "Let's start reading the .yuv file - hopefully");

        val root = Environment.getExternalStorageDirectory()
        //val output = assets.open("output.yuv");

//        //turn DataInput stream into the raw data that can be sent through SendFrames()

        if ((File(root.absolutePath + "/sdv/out.yuv")).exists()) {
            //3-Nov
            val inputSt = FileInputStream(root.absolutePath + "/sdv/out.yuv")
            val dIn = DataInputStream(inputSt)
            val count = dIn.available()
            Log.d("count", count.toString())
            dIn.close()


        }
    }

    /**      * END: HADLE VIDEO FEED       */


    /**      * START: HADLE VIDEO FEED  - NEEDS ATTENTION  (Step 2)   */
    /**      * Updates the video content to be sent to remote participants..       */


    //the sendFrame() function will need to be integrated into answerIncomingCall() -> Similar to startCall in Example.txt
    fun sendFrame() {
        Log.d("Debug - sendFrame", ffmPegMessage.toString());
        val virtualCamera01 = virtualCamera()
        val options = virtualCamera01.createOutboundVirtualVideoDeviceOptions()
        options.addOnFlowChangedListener { virtualDeviceFlowControlArgs: VirtualDeviceFlowControlArgs ->
            if (virtualDeviceFlowControlArgs.mediaFrameSender.runningState == VirtualDeviceRunningState.STARTED) {
                mediaFrameSender = virtualDeviceFlowControlArgs.mediaFrameSender
            } else {
                mediaFrameSender = null
            }
        }
        outboundVirtualVideoDevice =
                deviceManager!!.createOutboundVirtualVideoDevice(options).get()
        Log.d(
                "Debug - sendOutbound",
                outboundVirtualVideoDevice.toString() + "State: " + outboundVirtualVideoDevice!!.getRunningState()
        );

        //this is where i need to integrate in the RTSP straem
        //ORIGINAL code from MS (example.tx)
        m_frameGeneratorThread = Thread {
            try {
                var plane1: ByteBuffer? = null
                var plane2: ByteBuffer? = null
                Log.d("Debug - sendFrameNull", ffmPegMessage.toString());

                while (outboundVirtualVideoDevice != null) {
                    Log.d("DEBUG-OutboundCamera ", "Device ID : " + deviceManager!!.cameras.get(0).id + "Device Name: " + deviceManager!!.cameras.get(0).name)

                    while (mediaFrameSender != null) {
                        Log.d("Debug - sendFrame032", ffmPegMessage.toString())
                        if (mediaFrameSender!!.getMediaFrameKind() === MediaFrameKind.VIDEO_SOFTWARE) {
                            val sender = mediaFrameSender as SoftwareBasedVideoFrame
                            val videoFormat = sender.videoFormat

                            // Gets the timestamp for when the video frame has been created.
                            // This allows better synchronization with audio.
                            val timeStamp = sender.timestamp

                            // Adjusts frame dimensions to the video format that network conditions can manage.
                            if (plane1 == null || videoFormat.stride1 * videoFormat.height !== plane1.capacity()) {
                                plane1 =
                                        ByteBuffer.allocateDirect(videoFormat.stride1 * videoFormat.height)
                                plane1.order(ByteOrder.nativeOrder())
                            }

                            if (plane2 == null || videoFormat.stride2 * videoFormat.height !== plane2.capacity()) {
                                plane2 =
                                        ByteBuffer.allocateDirect(videoFormat.stride2 * videoFormat.height)
                                plane2.order(ByteOrder.nativeOrder())
                            }

                            // Sends video frame to the other participants in the call.
                            val fr = sender.sendFrame(plane1, timeStamp).get()
                            Log.d("frame", fr.toString())
                            // Waits before generating the next video frame.
                            // Video format defines how many frames per second app must generate.
                            Thread.sleep((1000.0f / videoFormat.framesPerSecond).toLong())
                        }
                    }


                    // Virtual camera hasn't been created yet.
                    // Let's wait a little bit before checking again.
                    // This is for demo only purpose.
                    // Please use a better synchronization mechanism.
                    Thread.sleep(32)
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            } catch (e: ExecutionException) {
                e.printStackTrace()
            }
        }
        m_frameGeneratorThread!!.start()

        try {
            Thread.sleep(2000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }


    private fun answerIncomingCall() {
        Log.d("DEBUG", "ANSWER INCOMING CALL")
        val context = this.applicationContext
        if (incomingCall == null) {
            return
        }
        val acceptCallOptions = AcceptCallOptions()




        //start running the RTSP feed through FFMEG -> SendFrames()
        //Need to: consume and produce a problem...
        //must be consuming the frames even when the sdk is saying not to
        //will run into overbuffer somewhere

        val videoStreams = arrayOfNulls<LocalVideoStream>(1)

        if (true) {
            val cameras = deviceManager!!.cameras
            val camera = chooseCamera(cameras)
            currentVideoStream = LocalVideoStream(camera, context)
            videoStreams[0] = currentVideoStream
            val videoOptions = VideoOptions(videoStreams)
            acceptCallOptions.videoOptions = videoOptions
            showPreview(currentVideoStream!!)
        } else {
            val externalCameras = outboundVirtualVideoDevice
            Log.d("DEBUG", "EXTERNAL CAMERA $externalCameras")

            Log.d("DEBUG", "Virtual Camera: ${m_virtualVideoStream.toString()}")

            //3-Nov
            sendFrame()
            val cameras = deviceManager!!.cameras
            if (!cameras.isEmpty()) {
                val camera: VideoDeviceInfo = choosevirtualCamera(cameras)!!
                currentVideoStream = LocalVideoStream(camera, context)
                val videoStreams = arrayOfNulls<LocalVideoStream>(1)
                videoStreams[0] = currentVideoStream
                val videoOptions = VideoOptions(videoStreams)
                acceptCallOptions.videoOptions = videoOptions
                showPreview(currentVideoStream!!)
            }
        }

        try {
            call = incomingCall!!.accept(context, acceptCallOptions).get()
            call!!.mute(context).get()

        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        }
        call!!.addOnRemoteParticipantsUpdatedListener { args: ParticipantsUpdatedEvent -> handleRemoteParticipantsUpdate(args) }
        call!!.addOnStateChangedListener { args: PropertyChangedEvent -> handleCallOnStateChanged(args) }

        call!!.addOnRemoteParticipantsUpdatedListener { args: ParticipantsUpdatedEvent ->
            handleRemoteParticipantsUpdate(
                    args
            )
        }
        call!!.addOnStateChangedListener { args: PropertyChangedEvent ->
            handleCallOnStateChanged(
                    args
            )
        }
        //turn on Local Video
       // turnOnLocalVideo()
        Log.d("DEBUG", "AnswerIncomingCall")
        getParticipantInformation()
    }

    fun choosevirtualCamera(cameras: List<VideoDeviceInfo>): VideoDeviceInfo? {
        for (camera in cameras) {
            val deviceId = camera.id
            Log.d("DEBUG:", "Found device in device manager: $deviceId")
            if (deviceId.equals("QuickStartVirtualVideoDevice", ignoreCase = true)) {
                Log.d("DEBUG:", "Found the virtual device in device manager")
                return camera
            } else {
                Log.d("DEBUG:", "Virtual Device not found")
            }
        }
        return cameras[0]
    }


    private fun handleRecordingActiveChanged(args: PropertyChangedEvent) {
        try {
            val callRecordingFeature = call!!.feature(Features.RECORDING)
            val isRecordingActive: Boolean = callRecordingFeature.isRecordingActive()
            Log.d("DEBUG", "isRecordingActive? ${isRecordingActive}")
        } catch (e: Exception) {
            Log.d("DEBUG", "ERROR  {${e.toString()}")
        }
    }

    /**      * END: HADLE VIDEO FEED       */

    /**      * START: HANDLE ACS CAMERAS       */
    /**      * Select the recently created outbound virtual video device.       */

    fun ensureLocalVideoStreamWithVirtualCamera() {
        Log.d("DEBUG", "VideoDeviceInfo ${deviceManager!!.getCameras().toString()}")
        for (videoDeviceInfo in deviceManager!!.getCameras()) {
            // val deviceId = videoDeviceInfo.id
            // for testing hard code device id
            val deviceId = "QuickStartVirtualVideoDevice"
            Log.d("DEBUG", "Device ID: $deviceId")
            if (deviceId.equals("QuickStartVirtualVideoDevice", ignoreCase = true)) {
                m_virtualVideoStream = LocalVideoStream(videoDeviceInfo, applicationContext)
            }
        }
    }

    fun chooseCamera(cameras: List<VideoDeviceInfo>): VideoDeviceInfo {

        for (camera in cameras) {
            Log.d("DEBUG", "Cameras: ${camera.toString()}")
            if (camera.cameraFacing == CameraFacing.FRONT) {
                return camera
            }
        }
        return cameras[0]
    }

    /**      * END: HANDLE ACS CAMERAS       */



    /**      * START: HANDLE ACS RECORDING       */

    getRecordingServerID

    fun showToast(text: String) {
        val context = this.applicationContext
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    }

    fun handleStopAndStart() {
        toRecord = false
        hangUp()
        startCall()
    }

    fun handleRecording(id: String, action: String) {
        val callRecordingFeature = call!!.feature(Features.RECORDING)
        Log.d("DEBUG", "IS RECORDING ACTIVE ${callRecordingFeature.isRecordingActive}")

        callRecordingFeature.addOnIsRecordingActiveChangedListener { args: PropertyChangedEvent -> handleRecordingActiveChanged(args) }

        var baseUrl = "https://recording.azurefd.net/"
        var function: String

        if (action == "start") {
            function = "startRecording?serverCallId=$id"
        } else {
            //triggered from hangUp()
            function = "stopRecording?serverCallId=$id&recordingId=$recordingID"
        }


        val queue = Volley.newRequestQueue(this)

        // Request a string response from the provided URL.
        val stringRequest = StringRequest(Request.Method.GET, (baseUrl+function),
                Response.Listener<String> { response ->
                    // Display the first 500 characters of the response string.
                    Log.d("DEBUG", "RESPONSE from Recording ${response}")
                    try {
                        val jsonobject = JSONObject(response)
                        recordingID = jsonobject.getString("recordingId")
                        Log.d("DEBUG", "ID from Recording ${recordingID}")
                        if ( recordingID != null) {
                            Thread.sleep(10_000)  // wait for 1 second
                            handleRecording(serverCallId!!, "end")
                        }
                    } catch (e: Exception) {
                        Log.d("DEBUG", "NO recordingId ")
                        recordingID = null
                        Thread.sleep(10_000)  // wait for 1 second
                        handleRecording(serverCallId!!, "start")
                    }
                },
                Response.ErrorListener { VolleyLog ->
                    Log.d("DEBUG", "ERROR: ${VolleyLog.toString()}")
                    handleRecording(serverCallId!!, "start")
                    recordingID = null
                })

        // Add the request to the RequestQueue.
        if (call!!.state.toString() === "CONNECTED" && toRecord == true) {
            Log.d("DEBUG", "triggering api request ${baseUrl+function}")
            queue.add(stringRequest)
            Log.i("VolleyQueue is here: " , queue.sequenceNumber.toString() + " ")
        }else {
            Log.d("DEBUG", "Better not record!")
        }
        stringRequest.setRetryPolicy(object : RetryPolicy {
            override fun getCurrentTimeout(): Int {
                return 50000
            }

            override fun getCurrentRetryCount(): Int {
                return 50000
            }

            @Throws(VolleyError::class)
            override fun retry(error: VolleyError) {
            }
        })
    }
    /**      * END: HANDLE ACS RECORDING       */

    //custom function to get as much debug information as possible about the stream
    //triggered from multiple on change events
    private fun getParticipantInformation() {
        val remoteParticipants = call!!.remoteParticipants
        Log.d("DEBUG", "ACS Remote Participants: ${remoteParticipants.toString()}")
        var i = 0;
        for (remoteParticipant in remoteParticipants) {
            val acsUserRemoteParticipant: RemoteParticipant = call!!.remoteParticipants.get(i)
            Log.d("DEBUG", "Dispylay name: ${acsUserRemoteParticipant.displayName}")
            Log.d("DEBUG", "ACS Remote Participant ${acsUserRemoteParticipant.toString()} STATE: ${acsUserRemoteParticipant.state}")
            if (acsUserRemoteParticipant.state.toString() == "DISCONNECTED") {
                val callEnded = acsUserRemoteParticipant.getCallEndReason();
                Log.d("DEBUG", "ACS Remote Participant ${acsUserRemoteParticipant.toString()} CALL ENDED: ${callEnded}")
            }
            i++
        }
    }

    /**      * START: HADLE MQTT CONNECTION & MESSAGES       */
    // connecting to MQTT client
    private fun connectMQTTClient() {
        // Create MQTT Client
        Log.d("DEBUG", "MQTT - SERVER URI : $MQTT_SERVER_URI / $MQTT_CLIENT_ID")
        mqttClient = MQTTClient(this, MQTT_SERVER_URI, MQTT_CLIENT_ID)
        mqttClient.connect(MQTT_USERNAME, MQTT_PWD,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d("DEBUG", "MQTT - connection success")
//                  Attempt to subscribe
                        mqttClient.subscribe(MQTT_TOPIC, 1, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                Log.d("DEBUG", "MQTT - subscribed to : $MQTT_TOPIC")
                                Log.d("DEBUG", "MQTT SENDING: $identity")
                                mqttClient.publish(MQTT_TOPIC, identity!!, 1, false);
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                Log.d("DEBUG", "MQTT - failed to subscribe to: $MQTT_TOPIC")


                            }
                        })
                        mqttClient.subscribe("/sdv_perception_output", 1, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                Log.d("DEBUG", "MQTT - subscribed to : /sdv_perception_output")
                                mqttClient.publish("/sdv_perception_output", "Connecting to /sdv_perception_output", 1, false);
                            }
                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                Log.d("DEBUG", "MQTT - failed to subscribe to: /sdv_perception_output")
                            }
                        })
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.d("DEBUG", "MQTT - connection failure: ${exception.toString()}")
                    }
                }, object : MqttCallback {
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Log.d("DEBUG", "MQTT - Message Arrived! $topic $message")
                Log.d("DEBUG", "MessageArrived: ${message.toString()}")

                if (!(message.toString()).equals(identity.toString())) {
                    Log.d("DEBUG", "UserToCall: ${message.toString()}")
                    if ((message.toString()).startsWith("8:") && !usersToCall.contains(message.toString())) {
                        //Log.d("DEBUG", "startcall")
                        usersToCall.add(message.toString())
                        Log.d("DEBUG", "UserToCall: ${usersToCall.toString()}")
                        showToast("Consumer Connecting...")
                    }
                    if ((message.toString()).equals("StopRecording")) {
                        Log.d("DEBUG", "stop recording / end call / restart Call")
                        toRecord = false
                        handleStopAndStart()
                    }
                }

                val calleeText = findViewById<TextView>(R.id.textView)
                calleeText.text = "Callee: ${usersToCall.count()}"


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
                //hangUp()
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.d(this.javaClass.name, "MQTT - failed to disconnect")
            }
        })
        super.onDestroy()
    }

    /**      * END: HADLE MQTT CONNECTION & MESSAGES       */

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

    /**      * START: ACS FUNCTIONS THAT HAVE NOT BEEN MODIFIED       */

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
            val callOptions = CallAgentOptions()

            callOptions.setDisplayName("Vehicle");

            deviceManager = callClient.getDeviceManager(context).get()
            callAgent = callClient.createCallAgent(applicationContext, credential, callOptions).get()

            Log.i("Agent", callAgent.toString())
        } catch (ex: Exception) {
            Toast.makeText(context, "Failed to create call agent.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIncomingCall() {
        callAgent!!.addOnIncomingCallListener { incomingCall: IncomingCall? ->
            this.incomingCall = incomingCall
            Executors.newCachedThreadPool().submit { answerIncomingCall() }
        }
    }

    private fun startCall() {
        Log.d("DEBUG", "LET'S TRY AND START THE CALL")
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

        for (user in usersToCall) {
            Log.d("DEBUG", "Adding user to call: $user")
            participants.add(CommunicationUserIdentifier(user))
        }

        Log.i("Agent Participants:", participants.toString())
        call = callAgent!!.startCall(
                context,
                participants,
                options)
        call!!.addOnRemoteParticipantsUpdatedListener(ParticipantsUpdatedListener { args: ParticipantsUpdatedEvent -> handleRemoteParticipantsUpdate(args) })
        call!!.addOnStateChangedListener(PropertyChangedListener { args: PropertyChangedEvent -> handleCallOnStateChanged(args) })
    }

    private fun hangUp() {
        Log.d("DEBUG", "Let's hangup the call")
        try {
            //end recording

            handleRecording(serverCallId!!, "end")
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
                val cameraToUse = choosevirtualCamera(cameras)
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
            //call!!.mute(this, currentVideoStream).get()
        } catch (acsException: CallingCommunicationException) {
            acsException.printStackTrace()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun showPreview(stream: LocalVideoStream) {
        // Create renderer
        previewRenderer = VideoStreamRenderer(stream, this)
        val layout = findViewById<View>(R.id.localvideocontainer) as LinearLayout
        preview = previewRenderer!!.createView(CreateViewOptions(ScalingMode.FIT))
        runOnUiThread { layout.addView(preview) }
    }

    private fun handleCallOnStateChanged(args: PropertyChangedEvent) {
        Log.d("DEBUG", "CallState : ${call!!.state}")

        if (call!!.state == CallState.CONNECTED || call!!.state == CallState.CONNECTING) {
            handleCallState()


        }
        if (call!!.state == CallState.DISCONNECTED) {
            if (previewRenderer != null) {
                previewRenderer!!.dispose()
            }
        }
        Log.d("DEBUG", "HandleCallOnStateChanged")
        getParticipantInformation()
    }

    private fun handleCallState() {
        val participantVideoContainer = findViewById<LinearLayout>(R.id.remotevideocontainer)
        Log.d("DEBUG", "CallState : ${call!!.state}")

        if (call!!.state.toString() == "CONNECTED" && RecordingInitiated == false && toRecord == true) {
            getRecordingServerID()
        }

        handleAddedParticipants(call!!.remoteParticipants, participantVideoContainer)

    }

    private fun handleAddedParticipants(participants: List<RemoteParticipant>, participantVideoContainer: LinearLayout) {
        for (remoteParticipant in participants) {
            remoteParticipant.addOnVideoStreamsUpdatedListener { videoStreamsEventArgs: RemoteVideoStreamsEvent -> videoStreamsUpdated(videoStreamsEventArgs) }
        }
        Log.d("DEBUG", "HandleAddedParticipants")
        getParticipantInformation()
    }

    fun handleRemoteParticipantsUpdate(args: ParticipantsUpdatedEvent) {
        val participantVideoContainer = findViewById<LinearLayout>(R.id.remotevideocontainer)
        handleAddedParticipants(args.addedParticipants, participantVideoContainer)
    }

    private fun videoStreamsUpdated(videoStreamsEventArgs: RemoteVideoStreamsEvent) {
        Log.d("DEBUG", "Is Video REMOTE Stream Updated?")
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
        Log.d("DEBUG", "Start Rendering Remote Video!")
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

            /*
        val hangupButton = findViewById<ImageButton>(R.id.hang_up)
        hangupButton.setVisibility(View.VISIBLE);
        */

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

    /**      * END: ACS FUNCTIONS THAT HAVE NOT BEEN MODIFIED       */


    /**         * START: MISC FUNCITONS                             */
    /*fun createDialogBox() {
        NoInternetAlert.setMessage("Could not Generate Azure Tokens")
            .setPositiveButton("OK",
                DialogInterface.OnClickListener { dialog, id ->
                    // FIRE ZE MISSILES!
                    //should automatically dismiss
                })
            .setNegativeButton("CANCEL",
                DialogInterface.OnClickListener { dialog, id ->
                    // User cancelled the dialog
                })
        // Create the AlertDialog object and return it
        NoInternetAlert.create()
    }*/


    /**         * END: MISC FUNCITONS                             */

    //    public void virtualCamera(Context context) {
    //        this.context = context;
    //    }
    @kotlin.Throws(Exception::class)
    open fun createOutboundVirtualVideoDeviceOptions(): OutboundVirtualVideoDeviceOptions? {
        var m_outboundVirtualVideoDevice: OutboundVirtualVideoDevice
        val m_options: OutboundVirtualVideoDeviceOptions
        val deviceId = arrayOf(VirtualDeviceIdentification())
        deviceId[0].id = "QuickStartVirtualVideoDevice"
        deviceId[0].name = "My First Virtual Video Device"
        val format = VideoFormat()
        format.width = 1280 //needs to match the
        format.height = 720
        format.pixelFormat = PixelFormat.NV12
        format.mediaFrameKind = MediaFrameKind.VIDEO_SOFTWARE //stick with this until we know our final hardware
        format.framesPerSecond = 30f
        format.stride1 = (1280 * 4)
        val format2 = VideoFormat()
        format2.width = 1280 //needs to match the
        format2.height = 720
        format2.pixelFormat = PixelFormat.NV12
        format2.mediaFrameKind = MediaFrameKind.VIDEO_HARDWARE //stick with this until we know our final hardware
        format2.framesPerSecond = 30f
        format2.stride1 = (1280 * 4)
        m_options = OutboundVirtualVideoDeviceOptions()
        m_options.deviceIdentification = deviceId[0]
       // m_options.setVideoFormats(arrayOf(format, format2))
        m_options.addOnFlowChangedListener { virtualDeviceFlowControlArgs: VirtualDeviceFlowControlArgs ->
            if (virtualDeviceFlowControlArgs.mediaFrameSender.runningState == VirtualDeviceRunningState.STARTED) {
                m_mediaFrameSender[0] = virtualDeviceFlowControlArgs.mediaFrameSender
            } else {
                m_mediaFrameSender[0] = null
            }
        }
        return m_options
    }

}