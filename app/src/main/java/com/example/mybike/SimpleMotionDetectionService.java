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
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.app.KeyguardManager;
import android.view.WindowManager;
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
    
    // Power management
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private PowerManager powerManager;
    private KeyguardManager keyguardManager;
    
    // Audio and call functionality
    private MediaPlayer mediaPlayer;
    private Handler beepHandler;
    private Runnable beepRunnable;
    private boolean isBeeping = false;
    private boolean isCurrentlyPlayingBeep = false;
    
    private static final float MOTION_THRESHOLD = 0.3f;
    private static final long MOTION_ALERT_COOLDOWN = 30000; // 30 seconds between motion alerts
    private static final long CALL_COOLDOWN = 60000; // 60 seconds between calls
    private static final int BEEP_INTERVAL = 800; // 800ms between beep starts (500ms beep + 300ms silence)
    private static final long SENSOR_REREGISTER_INTERVAL = 30000; // Re-register sensor every 30 seconds
    private static final long CALL_DELAY = 30000; // 30 seconds delay before calling
    
    // Sensor health monitoring
    private long lastSensorEventTime = 0;
    private Handler sensorHealthHandler;
    private Runnable sensorHealthRunnable;
    
    // Single timer system
    private Handler uiUpdateHandler;
    private Runnable uiUpdateRunnable;
    private boolean isCallDelayActive = false;
    private long motionStartTime = 0;
    
    // Call scheduling
    private Handler callSchedulerHandler;
    private Runnable scheduledCallRunnable;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        stateManager = AppStateManager.getInstance(this);
        createNotificationChannel();
        
        // Clear any leftover delay state on service start
        if (stateManager != null) {
            stateManager.setCallDelayActive(false);
            stateManager.setMotionStartTime(0);
            stateManager.setCallReady(false);
        }
        initPowerManager();
        initSensor();
        initBeepSystem();
        setupSensorHealthMonitoring();
        setupUIUpdates();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");
        
        try {
            // Sync with state manager on startup to get correct timer state
            syncServiceStateWithManager();
            
            startForeground(NOTIFICATION_ID, createNotification());
            acquireWakeLock();
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
        String text;
        
        if (motionDetected) {
            if (isCallDelayActive && motionStartTime > 0) {
                long timeRemaining = CALL_DELAY - (System.currentTimeMillis() - motionStartTime);
                if (timeRemaining > 0) {
                    text = "🚨 Motion! Calling in " + Math.max(1, (timeRemaining / 1000)) + "s (" + status + ")";
                } else {
                    text = "🚨 Calling now! (" + status + ")";
                }
            } else {
                text = "🚨 Motion Detected! (" + status + ")";
            }
        } else {
            // No current motion detected
            if (isCallDelayActive && motionStartTime > 0) {
                long timeRemaining = CALL_DELAY - (System.currentTimeMillis() - motionStartTime);
                if (timeRemaining > 0) {
                    text = "⏱️ Calling in " + Math.max(1, (timeRemaining / 1000)) + "s (" + status + ")";
                } else {
                    text = "📞 Ready to call (" + status + ")";
                }
            } else if (stateManager != null && stateManager.isCallReady()) {
                // In Ready state - waiting for motion to trigger call
                text = "🟡 READY - Motion will trigger call (" + status + ")";
            } else {
                text = "✓ Monitoring (" + status + ")";
            }
        }
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MyBike Security Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    private void initPowerManager() {
        try {
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (powerManager != null) {
                Log.d(TAG, "Power manager initialized");
            }
            if (keyguardManager != null) {
                Log.d(TAG, "Keyguard manager initialized");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing power manager", e);
        }
    }
    
    private void acquireWakeLock() {
        try {
            if (powerManager != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "MyBike::MotionDetectionWakeLock"
                );
                wakeLock.acquire();
                Log.d(TAG, "WakeLock acquired for motion detection");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring wake lock", e);
        }
    }
    
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing wake lock", e);
        }
    }
    
    private void wakeUpScreen() {
        try {
            if (powerManager != null) {
                // Create a screen wake lock to turn on the screen
                if (screenWakeLock == null || !screenWakeLock.isHeld()) {
                    screenWakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | 
                        PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "MyBike::ScreenWakeLock"
                    );
                    screenWakeLock.acquire(40000); // Keep screen on for 40 seconds (enough for 30s timer + call)
                    Log.d(TAG, "Screen wake lock acquired");
                }
                
                // Also try to dismiss keyguard if possible
                if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                    Log.d(TAG, "Keyguard is locked, attempting to wake up screen");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error waking up screen", e);
        }
    }
    
    private void releaseScreenWakeLock() {
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                screenWakeLock.release();
                screenWakeLock = null;
                Log.d(TAG, "Screen wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing screen wake lock", e);
        }
    }
    
    private void initSensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                if (gyroscope != null) {
                    // Use SENSOR_DELAY_GAME for better responsiveness during deep sleep
                    boolean registered = sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
                    if (registered) {
                        Log.d(TAG, "Gyroscope registered successfully with GAME delay");
                    } else {
                        Log.e(TAG, "Failed to register gyroscope");
                    }
                } else {
                    Log.e(TAG, "No gyroscope sensor available");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error init sensor", e);
        }
    }
    
    private void setupSensorHealthMonitoring() {
        try {
            sensorHealthHandler = new Handler(Looper.getMainLooper());
            sensorHealthRunnable = new Runnable() {
                @Override
                public void run() {
                    checkSensorHealth();
                    // Schedule next health check
                    sensorHealthHandler.postDelayed(this, SENSOR_REREGISTER_INTERVAL);
                }
            };
            
            // Start health monitoring
            sensorHealthHandler.postDelayed(sensorHealthRunnable, SENSOR_REREGISTER_INTERVAL);
            Log.d(TAG, "Sensor health monitoring started");
        } catch (Exception e) {
            Log.e(TAG, "Error setting up sensor health monitoring", e);
        }
    }
    
    private void checkSensorHealth() {
        try {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastEvent = currentTime - lastSensorEventTime;
            
            // If no sensor events for too long, try to re-register
            if (lastSensorEventTime > 0 && timeSinceLastEvent > SENSOR_REREGISTER_INTERVAL) {
                Log.w(TAG, "No sensor events for " + timeSinceLastEvent + "ms, re-registering sensor");
                reregisterSensor();
            }
            
            // Update notification with health status
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, createNotification());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking sensor health", e);
        }
    }
    
    private void reregisterSensor() {
        try {
            if (sensorManager != null && gyroscope != null) {
                // Unregister first
                sensorManager.unregisterListener(this);
                
                // Wait a bit
                Thread.sleep(100);
                
                // Re-register with higher priority
                boolean registered = sensorManager.registerListener(
                    this, 
                    gyroscope, 
                    SensorManager.SENSOR_DELAY_GAME,
                    sensorHealthHandler
                );
                
                if (registered) {
                    Log.d(TAG, "Sensor re-registered successfully");
                } else {
                    Log.e(TAG, "Failed to re-register sensor");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error re-registering sensor", e);
        }
    }
    
    private void setupUIUpdates() {
        try {
            // Simple UI update handler - just broadcasts state changes
            uiUpdateHandler = new Handler(Looper.getMainLooper());
            uiUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isCallDelayActive && motionStartTime > 0) {
                        // Send broadcast to update UI - MainActivity will do the calculations
                        updateNotificationAndUI();
                        
                        // Schedule next update in 1 second
                        uiUpdateHandler.postDelayed(this, 1000);
                    }
                }
            };
            
            Log.d(TAG, "UI update system initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error setting up UI updates", e);
        }
    }
    
    private void startCallTimer() {
        try {
            // Check state manager instead of local variable - this is the single source of truth
            boolean isCurrentlyActive = (stateManager != null) ? stateManager.isCallDelayActive() : false;
            
            if (!isCurrentlyActive) {
                // First time motion detected - start the timer
                motionStartTime = System.currentTimeMillis();
                
                // Update state manager with timer info - this is the SINGLE source of truth
                if (stateManager != null) {
                    stateManager.setCallDelayActive(true);
                    stateManager.setMotionStartTime(motionStartTime);
                    // Sync local variable with state manager
                    isCallDelayActive = true;
                }
                
                Log.w(TAG, "📞 CALL TIMER STARTED - Single timer system active");
                
                // Start UI updates and initial notification
                startUIUpdates();
                updateNotificationAndUI();
                
                // Schedule the actual call after 30 seconds - THIS IS THE KEY FIX
                scheduleDelayedCall();
            } else {
                Log.d(TAG, "📞 MOTION CONTINUES - Timer already running");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting call timer", e);
        }
    }
    
    private void scheduleDelayedCall() {
        try {
            Log.w(TAG, "📞 SCHEDULING DELAYED CALL - Will execute in 30 seconds");
            
            // Cancel any existing scheduled call first
            cancelScheduledCall();
            
            callSchedulerHandler = new Handler(Looper.getMainLooper());
            scheduledCallRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.w(TAG, "📞 DELAYED CALL TIMER EXPIRED - Checking conditions");
                        
                        // Check if we should still make the call
                        if (stateManager != null && isCallDelayActive && motionStartTime > 0) {
                            long currentTime = System.currentTimeMillis();
                            long elapsed = currentTime - motionStartTime;
                            
                            Log.w(TAG, "📞 TIMER CHECK: elapsed=" + elapsed + "ms, threshold=30000ms");
                            
                            if (elapsed >= CALL_DELAY) {
                                Log.w(TAG, "✅ TIMER EXPIRED - Setting to READY state, waiting for motion");
                                
                                // Update state to Ready (don't call immediately)
                                stateManager.setCallDelayActive(false);
                                stateManager.setCallReady(true);
                                
                                // Reset local timer states 
                                isCallDelayActive = false;
                                motionStartTime = 0;
                                stateManager.setMotionStartTime(0);
                                
                                // Stop beeping when timer expires
                                stopBeeping();
                                
                                // Update UI to show "Ready" state
                                updateNotificationAndUI();
                                
                                Log.w(TAG, "🟡 Service in READY state - stopped beeping, waiting for motion to trigger call");
                            } else {
                                Log.w(TAG, "⏰ Timer not yet expired, waiting...");
                            }
                        } else {
                            Log.w(TAG, "❌ Call cancelled or timer reset");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in delayed call execution", e);
                    }
                }
            };
            
            callSchedulerHandler.postDelayed(scheduledCallRunnable, CALL_DELAY); // 30 seconds
            Log.w(TAG, "✅ Delayed call scheduled successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling delayed call", e);
        }
    }
    
    private void cancelScheduledCall() {
        try {
            if (callSchedulerHandler != null && scheduledCallRunnable != null) {
                callSchedulerHandler.removeCallbacks(scheduledCallRunnable);
                Log.w(TAG, "📞 Scheduled call cancelled");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling scheduled call", e);
        }
    }
    
    private void syncServiceStateWithManager() {
        try {
            if (stateManager != null) {
                // Sync local variables with state manager (single source of truth)
                boolean stateManagerActive = stateManager.isCallDelayActive();
                if (isCallDelayActive != stateManagerActive) {
                    Log.d(TAG, "🔄 Syncing service state: isCallDelayActive " + isCallDelayActive + " -> " + stateManagerActive);
                    isCallDelayActive = stateManagerActive;
                    if (!stateManagerActive) {
                        // Timer was reset by MainActivity - reset local state too
                        motionStartTime = 0;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing service state", e);
        }
    }
    
    private void startUIUpdates() {
        try {
            if (uiUpdateHandler != null && uiUpdateRunnable != null) {
                // Start immediate update and schedule periodic updates
                uiUpdateHandler.post(uiUpdateRunnable);
                Log.d(TAG, "⏱️ UI updates started");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting UI updates", e);
        }
    }
    
    private void stopUIUpdates() {
        try {
            if (uiUpdateHandler != null && uiUpdateRunnable != null) {
                uiUpdateHandler.removeCallbacks(uiUpdateRunnable);
                Log.d(TAG, "⏱️ UI updates stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping UI updates", e);
        }
    }
    
    private void cancelCallTimer() {
        try {
            if (isCallDelayActive) {
                isCallDelayActive = false;
                
                // Cancel any scheduled call
                cancelScheduledCall();
                
                // Update state manager - clear timer state
                if (stateManager != null) {
                    stateManager.setCallDelayActive(false);
                    stateManager.setMotionStartTime(0);
                    // Don't clear callReady state here - only clear after actual call
                }
                
                Log.w(TAG, "📞 CALL TIMER CANCELLED");
                
                // Stop UI updates
                stopUIUpdates();
                
                // Update notification and UI
                updateNotificationAndUI();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling call timer", e);
        }
    }
    
    private void updateNotificationAndUI() {
        try {
            // Update notification
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, createNotification());
            }
            
            // Send broadcast to update UI
            Intent updateIntent = new Intent("STATE_CHANGED");
            LocalBroadcastManager.getInstance(this).sendBroadcast(updateIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification and UI", e);
        }
    }
    
    private void initBeepSystem() {
        try {
            // Initialize MediaPlayer for police sound
            mediaPlayer = new MediaPlayer();
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("police.mp3");
            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            mediaPlayer.setLooping(false); // Don't loop the audio file itself
            mediaPlayer.prepare();
            
            beepHandler = new Handler(Looper.getMainLooper());
            
            // Create beep runnable
            beepRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isBeeping && stateManager != null && stateManager.getAlarm()) {
                        try {
                            // Play police sound
                            isCurrentlyPlayingBeep = true;
                            
                            if (mediaPlayer != null) {
                                mediaPlayer.seekTo(0); // Reset to beginning
                                mediaPlayer.start();
                                
                                // Get the duration of the audio file
                                int duration = mediaPlayer.getDuration();
                                Log.d(TAG, "🚨 Police sound started - duration: " + duration + "ms");
                                
                                // Schedule the NEXT sound only after this one finishes
                                beepHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        isCurrentlyPlayingBeep = false;
                                        Log.d(TAG, "🚨 Police sound completed");
                                        
                                        // Only schedule next sound if still beeping
                                        if (isBeeping && stateManager != null && stateManager.getAlarm()) {
                                            Log.d(TAG, "🚨 Scheduling next police sound");
                                            beepHandler.postDelayed(beepRunnable, 500); // 500ms gap between sounds
                                        } else {
                                            Log.d(TAG, "🔇 Police sound cycle ended");
                                        }
                                    }
                                }, duration); // Wait for the full audio duration
                            }
                            
                        } catch (Exception e) {
                            Log.e(TAG, "Error playing police sound", e);
                            isCurrentlyPlayingBeep = false;
                            // Try again after a delay if still beeping
                            if (isBeeping) {
                                beepHandler.postDelayed(this, 2000);
                            }
                        }
                    } else {
                        Log.d(TAG, "🔇 Police sound stopped - isBeeping:" + isBeeping + ", alarm:" + 
                             (stateManager != null ? stateManager.getAlarm() : "null"));
                        isCurrentlyPlayingBeep = false;
                    }
                }
            };
            
            Log.d(TAG, "Police sound system initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing beep system", e);
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            // Update last sensor event time for health monitoring
            lastSensorEventTime = System.currentTimeMillis();
            
            if (event != null && event.values != null && event.values.length >= 3) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                
                float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
                boolean detected = magnitude > MOTION_THRESHOLD;
                
                if (detected != motionDetected) {
                    motionDetected = detected;
                    
                    // Sync with state manager before processing motion changes
                    syncServiceStateWithManager();
                    
                    // Update notification
                    if (notificationManager != null) {
                        notificationManager.notify(NOTIFICATION_ID, createNotification());
                    }
                    
                    // Send broadcast
                    Intent intent = new Intent("MOTION_DETECTED");
                    intent.putExtra("motion_detected", detected);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                    
                    if (detected) {
                        // Motion detected - check if we should call immediately or start timer
                        Log.w(TAG, "🚨 MOTION DETECTED! Checking call state...");
                        
                        // Check if we're in Ready state (timer expired, waiting for motion)
                        boolean isReady = (stateManager != null) ? stateManager.isCallReady() : false;
                        boolean isTimerActive = (stateManager != null) ? stateManager.isCallDelayActive() : false;
                        
                        Log.w(TAG, "🚨 Call state check: Ready=" + isReady + ", TimerActive=" + isTimerActive);
                        
                        if (isReady && !isTimerActive) {
                            // We're in Ready state - trigger call immediately
                            Log.w(TAG, "🚨 READY STATE + MOTION = CALLING IMMEDIATELY!");
                            wakeUpScreen();
                            makePhoneCall();
                        } else if (!isTimerActive) {
                            // First motion detection - start the alarm sequence
                            Log.w(TAG, "🚨 Starting new alarm sequence...");
                            Log.w(TAG, "🚨 Step 1: WAKING UP SCREEN immediately");
                            wakeUpScreen();
                            Log.w(TAG, "🚨 Step 2: Sending SMS alert");
                            sendMotionAlert();
                            Log.w(TAG, "🚨 Step 3: Starting beeping");
                            startBeeping();
                            Log.w(TAG, "🚨 Step 4: Starting 30-second call timer");
                            startCallTimer();
                            Log.w(TAG, "🚨 All alarm actions initiated with screen awake");
                        } else {
                            // Timer already active - restart beeping if motion detected again
                            Log.w(TAG, "🚨 Motion detected during active timer - restarting beeping");
                            startBeeping();
                        }
                    } else {
                        // Motion stopped - stop beeping but KEEP the call timer running
                        Log.d(TAG, "Motion stopped - stopping beeping but keeping call timer");
                        stopBeeping();
                        // DON'T cancel the delayed call - let it complete
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
            
            // Beep if alarm is enabled (remove locked requirement for better reliability)
            if (!stateManager.getAlarm()) {
                Log.d(TAG, "Beeping skipped - alarm disabled");
                return;
            }
            
            if (!isBeeping && !isCurrentlyPlayingBeep) {
                isBeeping = true;
                isCurrentlyPlayingBeep = false; // Ensure clean start
                beepHandler.post(beepRunnable);
                Log.w(TAG, "🚨 Started police sound - one sound at a time");
            } else {
                Log.d(TAG, "🔊 Beeping already active or playing - isBeeping:" + isBeeping + ", playing:" + isCurrentlyPlayingBeep);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting beeping", e);
        }
    }
    
    private void stopBeeping() {
        try {
            if (isBeeping) {
                isBeeping = false;
                isCurrentlyPlayingBeep = false;
                
                // Remove ALL pending beep-related callbacks
                beepHandler.removeCallbacks(beepRunnable);
                beepHandler.removeCallbacksAndMessages(null); // Remove any nested callbacks too
                
                // Stop any currently playing audio immediately
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    mediaPlayer.seekTo(0); // Reset to beginning for next time
                }
                
                Log.d(TAG, "🔇 Stopped police sound - removed all callbacks and stopped current audio");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping beeping", e);
        }
    }
    
    private void makePhoneCall() {
        try {
            Log.w(TAG, "📞 MAKE PHONE CALL - Starting call attempt");
            
            if (stateManager == null) {
                Log.e(TAG, "❌ StateManager is null - cannot make call");
                return;
            }
            
            // Check all conditions with detailed logging
            boolean isLocked = stateManager.isLocked();
            boolean alarmEnabled = stateManager.getAlarm();
            boolean callEnabled = stateManager.getCall();
            
            Log.w(TAG, "📞 CALL CONDITIONS CHECK:");
            Log.w(TAG, "  🔒 Device locked: " + isLocked + " (status: " + stateManager.getStatus() + ")");
            Log.w(TAG, "  🚨 Alarm enabled: " + alarmEnabled);
            Log.w(TAG, "  📲 Call enabled: " + callEnabled);
            
            if (!isLocked || !alarmEnabled || !callEnabled) {
                Log.e(TAG, "❌ Phone call BLOCKED - conditions not met!");
                return;
            }
            
            Log.w(TAG, "✅ All conditions met - proceeding with call");
            
            // Check cooldown period using stored time from state manager
            long currentTime = System.currentTimeMillis();
            long storedLastCallTime = stateManager.getLastCallTime();
            long timeSinceLastCall = currentTime - storedLastCallTime;
            
            Log.w(TAG, "📞 COOLDOWN CHECK:");
            Log.w(TAG, "  ⏰ Current time: " + currentTime);
            Log.w(TAG, "  ⏰ Last call time: " + storedLastCallTime);
            Log.w(TAG, "  ⏰ Time since last call: " + (timeSinceLastCall / 1000) + "s");
            Log.w(TAG, "  ⏰ Cooldown period: " + (CALL_COOLDOWN / 1000) + "s");
            
            if (timeSinceLastCall < CALL_COOLDOWN) {
                Log.e(TAG, "❌ Phone call BLOCKED - cooldown active (" + 
                      ((CALL_COOLDOWN - timeSinceLastCall) / 1000) + "s remaining)");
                return;
            }
            
            Log.w(TAG, "✅ Cooldown period passed");
            
            String adminNumber = stateManager.getAdminNumber();
            Log.w(TAG, "📞 ADMIN NUMBER CHECK:");
            Log.w(TAG, "  📞 Admin number: '" + adminNumber + "'");
            
            if (adminNumber == null || adminNumber.isEmpty() || adminNumber.equals("+11111111111")) {
                Log.e(TAG, "❌ Phone call BLOCKED - invalid admin number: " + adminNumber);
                return;
            }
            
            Log.w(TAG, "✅ Valid admin number found");
            Log.w(TAG, "📞 ATTEMPTING TO CALL: " + adminNumber);
            
            // Step 1: Wake up the screen first
            wakeUpScreen();
            
            // Step 2: Wait a moment for screen to wake up, then initiate call
            Handler callHandler = new Handler(Looper.getMainLooper());
            callHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.d(TAG, "Initiating phone call after screen wake-up");
                        
                        Intent callIntent = new Intent(Intent.ACTION_CALL);
                        callIntent.setData(Uri.parse("tel:" + adminNumber));
                        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                          Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                          Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        
                        // Add flags to bring the call to foreground
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            callIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        }
                        
                        startActivity(callIntent);
                        stateManager.setLastCallTime(System.currentTimeMillis());
                        
                        // Clear call ready state and set to never after calling
                        stateManager.setCallReady(false);
                        
                        // Stop beeping after successful call
                        stopBeeping();
                        
                        Log.w(TAG, "✅ Phone call initiated successfully to: " + adminNumber + " - Stopped beeping, Timer set to Never");
                        
                        // Update UI to show "Never" after call
                        updateNotificationAndUI();
                        
                        // Release screen wake lock after a delay
                        callHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                releaseScreenWakeLock();
                            }
                        }, 5000); // Release after 5 seconds
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting phone call to " + adminNumber, e);
                        releaseScreenWakeLock();
                    }
                }
            }, 1000); // Wait 1 second for screen to wake up
            
        } catch (Exception e) {
            Log.e(TAG, "Error making phone call", e);
            releaseScreenWakeLock();
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
            
            // Stop sensor health monitoring
            if (sensorHealthHandler != null && sensorHealthRunnable != null) {
                sensorHealthHandler.removeCallbacks(sensorHealthRunnable);
            }
            
            // Stop call timer and UI updates
            cancelCallTimer();
            cancelScheduledCall();
            stopUIUpdates();
            
            // Release media player
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
            
            // Reset beep states
            isBeeping = false;
            isCurrentlyPlayingBeep = false;
            
            // Release wake locks
            releaseWakeLock();
            releaseScreenWakeLock();
            
            Log.d(TAG, "Service destroyed");
        } catch (Exception e) {
            Log.e(TAG, "Error in destroy", e);
        }
    }
}