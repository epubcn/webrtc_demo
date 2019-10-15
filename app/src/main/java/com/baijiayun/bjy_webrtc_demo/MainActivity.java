package com.baijiayun.bjy_webrtc_demo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import com.visionular.vsdnn.VsdnnRenderView;
import com.visionular.vsdnn.VsdnnVideoTool;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.jetbrains.annotations.Nullable;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.YuvHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    static {
        VsdnnVideoTool.loadLibraries();
    }

    private static boolean mExit = false;
    private static final String TAG = "MainActivity";
    private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private List<PeerConnection.IceServer> iceServers = new ArrayList<PeerConnection.IceServer>();
    private LinearLayout mLayoutLocalVideo;
    private ProxyVideoSink localProxyVideoSink;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean mStartCapture;

    //for video frame dump
    private boolean bDumpVideoFrameToFile = false;  // set to true to save video frame to FRAME_SAVE_PATH
    private int mDumpFrameCount;
    private static final int MAX_DUMP_FRAME_COUNT = 100;
    private static final String FRAME_SAVE_PATH = "/sdcard/bjy_save/";
    private static final String FRAME_SAVE_SUFFIX = ".yuv";

    //capture parameters
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;
    private static final int CAPTURE_FPS = 15;

    //org.webrtc objects
    private VideoCapturer mVideoCapture;
    private boolean captureToTexture = true;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private PeerConnectionFactory factory;
    private final EglBase rootEglBase = EglBase.create();
    private VideoTrack localVideoTrack;
    private PeerConnection peerConnection;

    private static byte[] bufferToArray ( byte[] b, ByteBuffer bb){
        int remaining = bb.remaining();
        if (b == null || remaining > b.length) {
            b = new byte[remaining];
        }
        bb.get(b, 0, b.length);
        return b;
    }

    private void saveFrameToYuvFile(VideoFrame videoFrame) {
        if(!bDumpVideoFrameToFile) {
            return;
        }

        ++mDumpFrameCount;
        if(mDumpFrameCount > MAX_DUMP_FRAME_COUNT) {
            return;
        }

        videoFrame.retain();
        VideoFrame.I420Buffer i420Buffer = videoFrame.getBuffer().toI420();
        byte[] yData = bufferToArray(null, i420Buffer.getDataY());
        byte[] uData = bufferToArray(null, i420Buffer.getDataU());
        byte[] vData = bufferToArray(null, i420Buffer.getDataV());

        int width = i420Buffer.getWidth();
        int height = i420Buffer.getHeight();

        final int chromaWidth = (width + 1) / 2;
        final int chromaHeight = (height + 1) / 2;

        // Test org.webrtc.YuvHelper convert functions
//        final int minSize = width * height + chromaWidth * chromaHeight * 2;
//        ByteBuffer yuvBuffer = ByteBuffer.allocateDirect(minSize);
//        YuvHelper.I420Copy(i420Buffer.getDataY(), i420Buffer.getStrideY(),
//                i420Buffer.getDataU(), i420Buffer.getStrideU(),
//                i420Buffer.getDataV(), i420Buffer.getStrideV(),
//                yuvBuffer, width, height);
//        YuvHelper.I420ToNV12(y, i420Buffer.getStrideY(), v, i420Buffer.getStrideV(), u, i420Buffer.getStrideU(), yuvBuffer, width, height);
//        YuvImage yuvImage = new YuvImage(
//                yuvBuffer.array(),
//                ImageFormat.NV21,
//                width,
//                height,
//                strides
//        );
        videoFrame.release();

        File file = new File(FRAME_SAVE_PATH + "bjy_videoframe_dump" + mDumpFrameCount + FRAME_SAVE_SUFFIX);
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
//            yuvImage.compressToJpeg(
//                    new Rect(0, 0, width, height),
//                    100,
//                    outputStream
//            );

//            outputStream.write(yuvBuffer.array());

            //write Y data
            outputStream.write(yData);

            int offset = 0;
            int h;

            //write U data
            for(h = 0; h < chromaHeight; h++) {
                outputStream.write(uData, offset, chromaWidth);
                offset += i420Buffer.getStrideU();
            }

            //write V data
            offset = 0;
            for(h = 0; h < chromaHeight; h++) {
                outputStream.write(vData, offset, chromaWidth);
                offset += i420Buffer.getStrideV();
            }

            outputStream.flush();
            outputStream.close();

            Log.d(TAG, "write "+mDumpFrameCount+" frame to file");

        } catch (IOException io) {
            io.printStackTrace();
        }
    }

    private class ProxyVideoSink implements VideoSink {

        private boolean bUseVisonular = true;
        private SurfaceViewRenderer mSurfaceRenderer;
        private VsdnnRenderView mVsdnnRenderView;

        @Override
        public void onFrame(VideoFrame frame) {

            if(bUseVisonular) {

                frame.retain();

                VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
                byte[] y = bufferToArray(null, i420Buffer.getDataY());
                byte[] u = bufferToArray(null, i420Buffer.getDataU());
                byte[] v = bufferToArray(null, i420Buffer.getDataV());

                int width = i420Buffer.getWidth();
                int height = i420Buffer.getHeight();
                final int chromaWidth = (width + 1) / 2;
                final int chromaHeight = (height + 1) / 2;

                frame.release();

                // it is weird gray screen when using u & v directly to pass to renderYUV420P()
                // so I had to convert u & v to new byte array, it works but inefficiency......
                byte[] dst_u = new byte[chromaWidth * chromaHeight];
                byte[] dst_v = new byte[chromaWidth * chromaHeight];
                int src_offset = 0;
                int dst_offset = 0;
                int h;
                for(h = 0; h < chromaHeight; h++) {
                    System.arraycopy(u, src_offset, dst_u, dst_offset, chromaWidth);
                    src_offset += i420Buffer.getStrideU();
                    dst_offset += chromaWidth;
                }
                src_offset = 0;
                dst_offset = 0;
                for(h = 0; h < chromaHeight; h++) {
                    System.arraycopy(v, src_offset, dst_v, dst_offset, chromaWidth);
                    src_offset += i420Buffer.getStrideV();
                    dst_offset += chromaWidth;
                }

                mVsdnnRenderView.renderYUV420P(width, height,
                        i420Buffer.getStrideY(), y, chromaWidth, dst_u, chromaWidth, dst_v);

            }else {
                // transport vide frame
                if (mSurfaceRenderer != null) {
                    mSurfaceRenderer.onFrame(frame);
                }
            }

            if(checkStoragePermission() && bDumpVideoFrameToFile) {
                saveFrameToYuvFile(frame);
            }
        }

        public ProxyVideoSink(Context context) {

            if(bUseVisonular) {
                //use Visionular view
                mVsdnnRenderView = new VsdnnRenderView(context, null);

                // **CRASH** using ENHANCED_ONLY, BOTH_SIDE_BY_SIDE and BOTH_SPLIT
                mVsdnnRenderView.setDisplayMode(VsdnnRenderView.DisplayMode.ORIGINAL_ONLY);
//                mVsdnnRenderView.setSlidePercent(0.5f);
                mVsdnnRenderView.init();
            }else {
                //use Google WebRTC view
                mSurfaceRenderer = new SurfaceViewRenderer(context);
                mSurfaceRenderer.init(rootEglBase.getEglBaseContext(), null);
            }
        }

        public View getRenderer() {
            return bUseVisonular ? mVsdnnRenderView : mSurfaceRenderer;
        }

        public void dispose() {
            if(mSurfaceRenderer != null) {
                mSurfaceRenderer.release();
                mSurfaceRenderer = null;
            }
            if(mVsdnnRenderView != null) {
                mVsdnnRenderView.release();
                mVsdnnRenderView = null;
            }
        }

    }

    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(this);
    }

    private @Nullable
    VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private @Nullable void createVideoCapturerInternal() {
        if (useCamera2() && captureToTexture) {
            Logging.d(TAG, "Creating capturer using camera2 API.");
            mVideoCapture = createCameraCapturer(new Camera2Enumerator(this));
        }else {
            Logging.d(TAG, "Creating capturer using camera1 API.");
            mVideoCapture = createCameraCapturer(new Camera1Enumerator(captureToTexture));
        }
    }

    @Nullable
    private VideoTrack createVideoTrack(VideoCapturer capturer) {
        surfaceTextureHelper =
                SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(capturer.isScreencast());
        capturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
        mStartCapture = true;

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.setEnabled(true);
        localVideoTrack.addSink(localProxyVideoSink);

        return localVideoTrack;
    }

    private void createPeerConnectionFactoryInternal() {

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                        .setEnableInternalTracer(true)
                        .createInitializationOptions());

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;

        final VideoEncoderFactory encoderFactory;
        final VideoDecoderFactory decoderFactory;

        encoderFactory = new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true,false);
        decoderFactory = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
        Log.d(TAG, "Peer connection factory created.");
    }

    private PeerConnection.Observer pcObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {

        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {

        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }
    };

    private void createPeerConnectionInternal() {

        iceServers.add(PeerConnection.IceServer.builder("stun:116.196.83.61:443").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(iceServers);

        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.RSA;

        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver);

        List<String> mediaStreamLabels = Collections.singletonList("ARDAMS");
        peerConnection.addTrack(createVideoTrack(mVideoCapture), mediaStreamLabels);
    }

    public void close() {
        executor.execute(this ::closeInternal);
    }

    private void closeInternal() {
        if (peerConnection != null) {
            peerConnection.dispose();
            peerConnection = null;
        }
        if (mVideoCapture != null) {
            try {
                mVideoCapture.stopCapture();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            mStartCapture = false;
            mVideoCapture.dispose();
            mVideoCapture = null;
        }
        Log.d(TAG, "Closing video source.");
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        if(localProxyVideoSink != null) {
            localProxyVideoSink.dispose();
            localProxyVideoSink = null;
        }

        Log.d(TAG, "Closing peer connection factory.");
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        rootEglBase.release();
        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();
    }

    private boolean checkCameraPermission() {
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},0);
        }
        return false;
    }

    private boolean checkStoragePermission() {
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},0);
        }
        return false;
    }

    private void createDefaultView() {
        if(mLayoutLocalVideo == null) {
            return;
        }
        TextView v = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        v.setLayoutParams(params);
        v.setGravity(Gravity.CENTER);
        v.setText("No camera permission");
        v.setTextColor(getResources().getColor(R.color.colorAccent));
        v.setTextSize(24);
        Log.e(TAG, "Error to create local video view");
        mLayoutLocalVideo.addView(v);
    }

    private boolean setupCapture() {
        if(mStartCapture) {
            return true;
        }
        if(checkCameraPermission()) {
            mLayoutLocalVideo.removeAllViews();
            localProxyVideoSink = new ProxyVideoSink(getApplicationContext());
            mLayoutLocalVideo.addView(localProxyVideoSink.getRenderer(),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            executor.execute(() -> {
                createVideoCapturerInternal();
                createPeerConnectionFactoryInternal();
                createPeerConnectionInternal();
            });
            return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        mLayoutLocalVideo = findViewById(R.id.layout_local_video);
        createDefaultView();

        setupCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(permissions[0].compareToIgnoreCase(Manifest.permission.CAMERA) == 0) {
             if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                 setupCapture();
             }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        close();
    }

    Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            mExit = false;
        }
    };

    private void exit() {
        if (!mExit) {
            mExit = true;
            Toast.makeText(getApplicationContext(), "Press back again to exit app", Toast.LENGTH_SHORT).show();
            mHandler.sendEmptyMessageDelayed(0, 2000);
        } else {
            finish();
            System.exit(0);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_BACK) {
            exit();
            return false;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
