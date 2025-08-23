package com.example.mybike;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MotionDetectionService extends Service implements SensorEventListener {
    private static final String TAG = "MotionDetectionService";
    private static final String CHANNEL_ID = "motion_detection_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private PowerManager.WakeLock wakeLock;
    private Handler motionHandler;
    private HandlerThread motionThread;
    
    private boolean motionDetected = false;
    private NotificationManager notificationManager;
    
    private static final float MOTION_THRESHOLD = 0.5f; // rad/s - more sensitive
    private static final long MOTION_RESET_DELAY = 3000; // 3 seconds
    private long lastMotionTime = 0;
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        try {
            Log.d(TAG, "Creating motion detection service");
            
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            createNotificationChannel();
            
            // Initialize gyroscope first before starting other components
            initializeGyroscope();
            
            Log.d(TAG, "Motion detection service created successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Log.d(TAG, "Starting foreground service");
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "Foreground service started successfully");
            return START_STICKY;
        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand", e);
            return START_NOT_STICKY;
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Motion Detection Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background motion detection monitoring");
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        String contentText = motionDetected ? "🚨 MOTION DETECTED!" : "✓ Monitoring - No Motion";
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Motion Detection Active")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    private void updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, createNotification());
    }
    
    private void startMotionThread() {
        motionThread = new HandlerThread("MotionDetection");
        motionThread.start();
        motionHandler = new Handler(motionThread.getLooper());
    }
    
    private void stopMotionThread() {
        if (motionThread != null) {
            motionThread.quitSafely();
            try {
                motionThread.join();
                motionThread = null;
                motionHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping motion thread", e);
            }
        }
    }
    
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyBike:MotionDetection");
                wakeLock.acquire(10*60*1000L /*10 minutes*/);
                Log.d(TAG, "Wake lock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring wake lock", e);
        }
    }
    
    private void initializeGyroscope() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                
                if (gyroscope != null) {
                    boolean registered = sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
                    if (registered) {
                        Log.d(TAG, "Gyroscope sensor initialized successfully");
                    } else {
                        Log.e(TAG, "Failed to register gyroscope listener");
                    }
                } else {
                    Log.e(TAG, "Gyroscope sensor not available on this device");
                }
            } else {
                Log.e(TAG, "SensorManager not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing gyroscope", e);
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            if (event != null && event.sensor != null && event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                if (event.values != null && event.values.length >= 3) {
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    
                    // Calculate magnitude of rotation
                    float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
                    
                    boolean currentMotionDetected = magnitude > MOTION_THRESHOLD;
                    
                    if (currentMotionDetected) {
                        lastMotionTime = System.currentTimeMillis();
                        if (!motionDetected) {
                            updateMotionStatus(true);
                        }
                    } else {
                        // Check if motion should stop (no motion for MOTION_RESET_DELAY)
                        if (motionDetected && (System.currentTimeMillis() - lastMotionTime > MOTION_RESET_DELAY)) {
                            updateMotionStatus(false);
                        }
                    }
                    
                    // Reduce logging frequency to avoid spam
                    if (System.currentTimeMillis() % 1000 < 100) {
                        Log.d(TAG, String.format("Gyro: x=%.2f, y=%.2f, z=%.2f, magnitude=%.2f", x, y, z, magnitude));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onSensorChanged", e);
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy changed: " + accuracy);
    }
    
    private void updateMotionStatus(boolean detected) {
        try {
            if (motionDetected != detected) {
                motionDetected = detected;
                
                Intent intent = new Intent("MOTION_DETECTED");
                intent.putExtra("motion_detected", detected);
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                
                updateNotification();
                
                Log.d(TAG, "Motion " + (detected ? "DETECTED" : "stopped"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating motion status", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
                Log.d(TAG, "Sensor listener unregistered");
            }
            
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "Wake lock released");
            }
            
            stopMotionThread();
            
            Log.d(TAG, "Motion detection service destroyed");
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
    }
}