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
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class SimpleMotionDetectionService extends Service implements SensorEventListener {
    private static final String TAG = "SimpleMotionService";
    private static final String CHANNEL_ID = "motion_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private NotificationManager notificationManager;
    private AppStateManager stateManager;
    private boolean motionDetected = false;
    private long lastMotionAlertTime = 0;
    
    // Beep and call functionality
    private ToneGenerator toneGenerator;
    private Handler beepHandler;
    private Runnable beepRunnable;
    private boolean isBeeping = false;
    
    private static final float MOTION_THRESHOLD = 0.3f;
    private static final long MOTION_ALERT_COOLDOWN = 30000; // 30 seconds between motion alerts
    private static final long CALL_COOLDOWN = 60000; // 60 seconds between calls
    private static final int BEEP_INTERVAL = 1000; // 1 second between beeps
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        stateManager = AppStateManager.getInstance(this);
        createNotificationChannel();
        initSensor();
        initBeepSystem();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");
        
        try {
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "Foreground service started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground", e);
        }
        
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Motion Detection",
                NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        String status = stateManager != null ? stateManager.getStatus() : "unknown";
        String text = motionDetected ? 
            ("🚨 Motion Detected! (" + status + ")") : 
            ("✓ Monitoring (" + status + ")");
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MyBike Security Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    private void initSensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                if (gyroscope != null) {
                    sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
                    Log.d(TAG, "Gyroscope registered");
                } else {
                    Log.e(TAG, "No gyroscope sensor");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error init sensor", e);
        }
    }
    
    private void initBeepSystem() {
        try {
            // Initialize tone generator for beep sounds
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            beepHandler = new Handler(Looper.getMainLooper());
            
            // Create beep runnable
            beepRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isBeeping && stateManager != null && stateManager.isLocked()) {
                        try {
                            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500);
                            Log.d(TAG, "Beep played");
                        } catch (Exception e) {
                            Log.e(TAG, "Error playing beep", e);
                        }
                        
                        // Schedule next beep
                        beepHandler.postDelayed(this, BEEP_INTERVAL);
                    }
                }
            };
            
            Log.d(TAG, "Beep system initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing beep system", e);
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            if (event != null && event.values != null && event.values.length >= 3) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                
                float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
                boolean detected = magnitude > MOTION_THRESHOLD;
                
                if (detected != motionDetected) {
                    motionDetected = detected;
                    
                    // Update notification
                    if (notificationManager != null) {
                        notificationManager.notify(NOTIFICATION_ID, createNotification());
                    }
                    
                    // Send broadcast
                    Intent intent = new Intent("MOTION_DETECTED");
                    intent.putExtra("motion_detected", detected);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                    
                    if (detected) {
                        // Motion detected - start alarm actions
                        sendMotionAlert();
                        startBeeping();
                        makePhoneCall();
                    } else {
                        // Motion stopped - stop alarm actions
                        stopBeeping();
                    }
                    
                    Log.d(TAG, "Motion: " + detected);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in sensor changed", e);
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do nothing
    }
    
    private void startBeeping() {
        try {
            if (stateManager == null) return;
            
            // Only beep if device is locked and alarm is enabled
            if (!stateManager.isLocked() || !stateManager.getAlarm()) {
                Log.d(TAG, "Beeping skipped - device unlocked or alarm disabled");
                return;
            }
            
            if (!isBeeping) {
                isBeeping = true;
                beepHandler.post(beepRunnable);
                Log.d(TAG, "Started continuous beeping");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting beeping", e);
        }
    }
    
    private void stopBeeping() {
        try {
            if (isBeeping) {
                isBeeping = false;
                beepHandler.removeCallbacks(beepRunnable);
                Log.d(TAG, "Stopped beeping");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping beeping", e);
        }
    }
    
    private void makePhoneCall() {
        try {
            if (stateManager == null) return;
            
            // Only call if device is locked, alarm enabled, and call enabled
            if (!stateManager.isLocked() || !stateManager.getAlarm() || !stateManager.getCall()) {
                Log.d(TAG, "Phone call skipped - conditions not met");
                return;
            }
            
            // Check cooldown period using stored time from state manager
            long currentTime = System.currentTimeMillis();
            long storedLastCallTime = stateManager.getLastCallTime();
            if (currentTime - storedLastCallTime < CALL_COOLDOWN) {
                Log.d(TAG, "Phone call skipped - cooldown period");
                return;
            }
            
            String adminNumber = stateManager.getAdminNumber();
            if (adminNumber != null && !adminNumber.isEmpty()) {
                Log.d(TAG, "Attempting to call admin number: " + adminNumber);
                
                try {
                    Intent callIntent = new Intent(Intent.ACTION_CALL);
                    callIntent.setData(Uri.parse("tel:" + adminNumber));
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    
                    startActivity(callIntent);
                    stateManager.setLastCallTime(currentTime);
                    
                    Log.d(TAG, "Phone call initiated successfully to: " + adminNumber);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting phone call to " + adminNumber, e);
                }
            } else {
                Log.e(TAG, "No admin number set for phone calls");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error making phone call", e);
        }
    }
    
    private void sendMotionAlert() {
        try {
            if (stateManager == null) return;
            
            // Only send alerts if device is locked and alarm is enabled
            if (!stateManager.isLocked() || !stateManager.getAlarm()) {
                Log.d(TAG, "Motion alert skipped - device unlocked or alarm disabled");
                return;
            }
            
            // Check cooldown period
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastMotionAlertTime < MOTION_ALERT_COOLDOWN) {
                Log.d(TAG, "Motion alert skipped - cooldown period");
                return;
            }
            
            String adminNumber = stateManager.getAdminNumber();
            if (adminNumber != null && !adminNumber.isEmpty()) {
                String alertMessage = "🚨 MOTION ALERT: Your bike has been moved! Status: " + 
                    stateManager.getStatus() + ", Time: " + 
                    new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
                
                SmsManager smsManager = SmsManager.getDefault();
                smsManager.sendTextMessage(adminNumber, null, alertMessage, null, null);
                
                lastMotionAlertTime = currentTime;
                
                Log.d(TAG, "Motion alert sent to: " + adminNumber);
            } else {
                Log.e(TAG, "No admin number set for motion alerts");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending motion alert", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            
            // Stop beeping
            stopBeeping();
            
            // Release tone generator
            if (toneGenerator != null) {
                toneGenerator.release();
                toneGenerator = null;
            }
            
            Log.d(TAG, "Service destroyed");
        } catch (Exception e) {
            Log.e(TAG, "Error in destroy", e);
        }
    }
}