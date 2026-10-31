package com.localradio.talk;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class MainActivity extends AppCompatActivity {
    private static final int PORT = 50005;
    private static final int SAMPLE_RATE = 8000; 
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private WifiManager.MulticastLock multicastLock;
    
    private boolean isRecording = false;
    private DatagramSocket socket;
    private InetAddress broadcastAddress;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); 
        Button pttButton = findViewById(R.id.ptt_button);
        statusText = findViewById(R.id.status_text);

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("RadioLock");
        multicastLock.acquire();

        initNetwork();
        startListeningServer();

        pttButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    isRecording = true;
                    statusText.setText("TRANSMITTING...");
                    startTransmittingAudio();
                } else {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                isRecording = false;
                statusText.setText("Ready (Listening)");
            }
            return true;
        });
    }

    private void initNetwork() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("LAN_Walkie_Talkie");
        serviceInfo.setServiceType("_http._tcp.");
        serviceInfo.setPort(PORT);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                runOnUiThread(() -> statusText.setText("Ready (Listening)"));
            }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            @Override public void onServiceUnregistered(NsdServiceInfo arg0) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);

        try {
            socket = new DatagramSocket(PORT);
            socket.setBroadcast(true);
            broadcastAddress = InetAddress.getByName("255.255.255.255"); 
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startTransmittingAudio() {
        new Thread(() -> {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            byte[] buffer = new byte[bufferSize];

            recorder.startRecording();
            while (isRecording) {
                int read = recorder.read(buffer, 0, buffer.length);
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, read, broadcastAddress, PORT);
                    socket.send(packet);
                } catch (IOException e) { e.printStackTrace(); }
            }
            recorder.stop();
            recorder.release();
        }).start();
    }

    private void startListeningServer() {
        new Thread(() -> {
            int bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
            AudioTrack player = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT, bufferSize, AudioTrack.MODE_STREAM);
            byte[] buffer = new byte[bufferSize];
            player.play();

            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    player.write(packet.getData(), 0, packet.getLength());
                } catch (IOException e) { e.printStackTrace(); }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nsdManager != null && registrationListener != null) {
            nsdManager.unregisterService(registrationListener);
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
