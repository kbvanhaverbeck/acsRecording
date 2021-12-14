package com.example.videocallingquickstart;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.azure.android.communication.calling.OutboundVirtualVideoDevice;
import com.azure.android.communication.calling.VirtualDeviceFlowControlListener;
import com.azure.android.communication.calling.VirtualDeviceIdentification;
import com.azure.android.communication.calling.VirtualDeviceRunningState;
import com.azure.android.communication.calling.MediaFrameKind;
import com.azure.android.communication.calling.PixelFormat;
import com.azure.android.communication.calling.FrameConfirmation;
import com.azure.android.communication.calling.MediaFrameSender;
import com.azure.android.communication.calling.LocalVideoStream;
import com.azure.android.communication.calling.*;

import java.util.ArrayList;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;

import android.content.Intent;


import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;

import android.os.Environment;

import androidx.annotation.RequiresApi;

import android.os.Build;

import com.google.firebase.Timestamp;
import com.skype.android.video.hw.format.ColorFormat;
import com.google.protobuf.ByteString;
//import com.google.protobuf.Timestamp;
import android.os.AsyncTask;


public class virtualCamera {

    static int count = 0;
    static int count2 = 0;

    public static Socket socket = null;
    Context context;
    MediaFrameSender m_mediaFrameSender ;


//    public void virtualCamera(Context context) {
//        this.context = context;
//    }

    public OutboundVirtualVideoDeviceOptions createOutboundVirtualVideoDeviceOptions() throws Exception {

        OutboundVirtualVideoDevice m_outboundVirtualVideoDevice;
        OutboundVirtualVideoDeviceOptions m_options;

        VirtualDeviceIdentification deviceId = new VirtualDeviceIdentification();
        deviceId.setId("QuickStartVirtualVideoDevice");
        deviceId.setName("My First Virtual Video Device");

        VideoFormat format = new VideoFormat();
        format.setWidth(1280); //needs to match the
        format.setHeight(720);
        format.setPixelFormat(PixelFormat.RGBA);

        format.setMediaFrameKind(MediaFrameKind.VIDEO_SOFTWARE); //stick with this until we know our final hardware
        format.setFramesPerSecond(30);
        format.setStride1(1280 * 4);
        VideoFormat format2 = new VideoFormat();
        format2.setWidth(1280);
        format2.setHeight(720);
        format2.setPixelFormat(PixelFormat.RGBA);
        format2.setMediaFrameKind(MediaFrameKind.VIDEO_HARDWARE);
        format2.setFramesPerSecond(30);
        format2.setStride1(1280 * 4);

        m_options = new OutboundVirtualVideoDeviceOptions();
        Log.i("mOptions: ", m_options.toString() + "videoFormat " + m_options.getVideoFormats().toString());
        m_options.setDeviceIdentification(deviceId);

        try{
          // m_options.setVideoFormats(new VideoFormat[]{format, format2});
        }
        catch (Exception e) {
            e.printStackTrace();
            Log.i("DEBUG", "Unable to set the video formats " + " exception: " + e.toString());
        }
        List<VideoFormat> fetchFormats = m_options.getVideoFormats();
        Log.i("DEBUG: ", String.valueOf(fetchFormats.size()));

        //  m_outboundVirtualVideoDevice = m_deviceManager.createOutboundVirtualVideoDevice(m_options).get();
        return m_options;
    }

    public void onStartCommand(Intent intent, int flags, int startId) throws IOException {
        Log.d("DEBUG", "camera service is running..");

        DataInputStream in = null;

        Timer timer = new Timer();
        //timer.schedule(new TimerTask() {

        try {
            //if (socket != null) {
            while (count < 10) {
                // takes input from the client socket
                in = new DataInputStream(socket.getInputStream());

                // Receiving data from client
                if (in.available() > 0) {
                    byte[] lengthByte = new byte[4];
                    in.read(lengthByte, 0, 4);

                    int length = convertByteArrayToInt(lengthByte);

                    byte[] buffer = new byte[length];
                    in.readFully(buffer, 0, length);

                    System.out.println("Received bytes from clients: " + buffer.length);
                }
                count++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // }, 15000, 15000);


        //return super.onStartCommand(intent, flags, startId);
        //return null;
    }

    String[] arrName = new String[]{"CameraProto1", "CameraProto2", "CameraProto3", "CameraProto4", "CameraProto5"};
    int writeIndex = 0;

    private void writeToSdFile(byte[] bytes) {
        File root = Environment.getExternalStorageDirectory();
        File dir = new File(root.getAbsolutePath() + "/sdv");

        File file = new File(dir, arrName[writeIndex]);

        try {
            FileOutputStream stream = new FileOutputStream(file);
            stream.write(bytes);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        writeIndex++;
        if (writeIndex > 4) {
            writeIndex = 0;
        }

        //MainActivity.busManager.publish("camera.exterior.svcfrontcenter.image", file.getAbsolutePath().getBytes());

    }


    public int convertByteArrayToInt(byte[] data) {
        if (data == null || data.length != 4) return 0x0;
        return (int) (
                (0xff & data[0]) << 24 |
                        (0xff & data[1]) << 16 |
                        (0xff & data[2]) << 8 |
                        (0xff & data[3]) << 0
        );
    }


    public char[] getCameraData(int size) {
        InputStream stream = openYuvFile();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream));
        // do reading, usually loop until end of file reading
        char[] charArray
                = new char[size];
        try {
            int videoSize = stream.available();
            System.out.println("reader skip=" + reader.skip(count * size));
            System.out.println("videosize=" + videoSize);
            if ((count * size) < videoSize) {
                int offset = 0;
                //videosize = 22809599
                //size= 152064
                while (offset < size) {
                    int charsRead = reader.read(charArray, offset, size - offset);
                    System.out.println("charsreed=" + charsRead);
                    if (charsRead <= 0) {
                        throw new IOException("Stream terminated early");
                    }
                    offset += charsRead;
                }
            } else {
                count = 0;
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    //log the exception
                }
            }

            return charArray;

        }
    }


    public InputStream openYuvFile() {
        InputStream stream = null;
        try {
            // stream = getApplicationContext().getResources().openRawResource
            //         (getApplicationContext().getResources().getIdentifier("bus_cif",
            //                 "raw", getApplicationContext().getPackageName()));
            System.out.println("stream-> " + stream);
            System.out.println("stream len-> " + stream.read());
        } catch (Exception e) {
            //log the exception
        }
        return stream;
    }

    public void ensureLocalVideoStreamWithVirtualCamera() {
        Log.d("DEBUG", "VideoDeviceInfo ${deviceManager!!.getCameras().toString()}");
        CallClient callClient = new CallClient();
        try {
            DeviceManager deviceManager = callClient.getDeviceManager(context).get();
            //  for(int i=0;i< deviceManager.getCameras().size())
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
       /* for (videoDeviceInfo in deviceManager!!.getCameras()) {
            // val deviceId = videoDeviceInfo.id
            // for testing hard code device id
            val deviceId = "QuickStartVirtualVideoDevice"
            Log.d("DEBUG", "Device ID: $deviceId")
            if (deviceId.equals("QuickStartVirtualVideoDevice", ignoreCase = true)) {
                m_virtualVideoStream = LocalVideoStream(videoDeviceInfo, applicationContext)
            }
        }*/


    }


}
