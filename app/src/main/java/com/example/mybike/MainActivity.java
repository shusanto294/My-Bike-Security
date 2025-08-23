package com.example.mybike;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1002;
    private static final int SMS_PERMISSION_REQUEST = 1003;
    private static final int PHONE_PERMISSION_REQUEST = 1004;
    
    private TextView motionStatusText;
    private TextView statusText;
    private TextView adminNumberText;
    private TextView callText;
    private TextView alarmText;
    private TextView lastCallTimeText;
    private TextView nextCallTimerText;
    
    private BroadcastReceiver motionReceiver;
    private BroadcastReceiver stateReceiver;
    private AppStateManager stateManager;
    
    private Handler timerHandler;
    private Runnable timerRunnable;
    private static final long CALL_COOLDOWN = 60000;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            android.util.Log.d("MainActivity", "Creating MainActivity");
            
            setContentView(R.layout.activity_main);
            
            // Initialize views
            motionStatusText = findViewById(R.id.motionStatusText);
            statusText = findViewById(R.id.statusText);
            adminNumberText = findViewById(R.id.adminNumberText);
            callText = findViewById(R.id.callText);
            alarmText = findViewById(R.id.alarmText);
            lastCallTimeText = findViewById(R.id.lastCallTimeText);
            nextCallTimerText = findViewById(R.id.nextCallTimerText);
            
            if (motionStatusText == null) {
                android.util.Log.e("MainActivity", "Failed to find required views");
                return;
            }
            
            // Initialize state manager
            stateManager = AppStateManager.getInstance(this);
            
            setupReceivers();
            requestPermissions();
            updateStateDisplay();
            setupTimer();
            
            // Delay service start to ensure everything is initialized
            motionStatusText.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startMotionDetectionService();
                }
            }, 500);
            
            android.util.Log.d("MainActivity", "MainActivity created successfully");
            
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error in onCreate", e);
            Toast.makeText(this, "Error starting app: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void setupReceivers() {
        // Motion receiver
        motionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (intent != null) {
                        boolean motionDetected = intent.getBooleanExtra("motion_detected", false);
                        updateMotionStatus(motionDetected);
                    }
                } catch (Exception e) {
                    android.util.Log.e("MainActivity", "Error in motion receiver", e);
                }
            }
        };
        
        // State change receiver
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    android.util.Log.d("MainActivity", "State changed, updating display");
                    updateStateDisplay();
                } catch (Exception e) {
                    android.util.Log.e("MainActivity", "Error in state receiver", e);
                }
            }
        };
    }
    
    private void updateMotionStatus(boolean motionDetected) {
        try {
            if (motionStatusText != null) {
                if (motionDetected) {
                    motionStatusText.setText("🚨 MOTION DETECTED!");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        motionStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
                    } else {
                        motionStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    }
                } else {
                    motionStatusText.setText("✓ No Motion - Monitoring...");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        motionStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
                    } else {
                        motionStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error updating motion status", e);
        }
    }
    
    private void requestPermissions() {
        // Notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            }
        }
        
        // SMS permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS}, 
                SMS_PERMISSION_REQUEST);
        }
        
        // Phone call permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE}, PHONE_PERMISSION_REQUEST);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission recommended for status updates", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == SMS_PERMISSION_REQUEST) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "SMS permissions required for remote control functionality", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == PHONE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Phone permission required for auto-calling during motion alerts", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    private void updateStateDisplay() {
        try {
            if (stateManager != null) {
                if (statusText != null) {
                    String status = stateManager.getStatus();
                    statusText.setText(status);
                    if ("locked".equals(status)) {
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    } else {
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    }
                }
                
                if (adminNumberText != null) {
                    adminNumberText.setText(stateManager.getAdminNumber());
                }
                
                if (callText != null) {
                    boolean call = stateManager.getCall();
                    callText.setText(String.valueOf(call));
                    callText.setTextColor(call ? 
                        getResources().getColor(android.R.color.holo_green_dark) :
                        getResources().getColor(android.R.color.holo_red_dark));
                }
                
                if (alarmText != null) {
                    boolean alarm = stateManager.getAlarm();
                    alarmText.setText(String.valueOf(alarm));
                    alarmText.setTextColor(alarm ? 
                        getResources().getColor(android.R.color.holo_green_dark) :
                        getResources().getColor(android.R.color.holo_red_dark));
                }
                
                updateCallTimeDisplay();
                
                android.util.Log.d("MainActivity", "State display updated: " + stateManager.getAllStatesString());
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error updating state display", e);
        }
    }
    
    private void updateCallTimeDisplay() {
        try {
            if (stateManager != null && lastCallTimeText != null && nextCallTimerText != null) {
                long lastCallTime = stateManager.getLastCallTime();
                
                if (lastCallTime == 0) {
                    lastCallTimeText.setText("Never");
                    nextCallTimerText.setText("Ready");
                    nextCallTimerText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                } else {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    lastCallTimeText.setText(sdf.format(new Date(lastCallTime)));
                    
                    long currentTime = System.currentTimeMillis();
                    long timeSinceLastCall = currentTime - lastCallTime;
                    
                    if (timeSinceLastCall >= CALL_COOLDOWN) {
                        nextCallTimerText.setText("Ready");
                        nextCallTimerText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    } else {
                        long remainingTime = CALL_COOLDOWN - timeSinceLastCall;
                        long seconds = remainingTime / 1000;
                        nextCallTimerText.setText(seconds + "s");
                        nextCallTimerText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error updating call time display", e);
        }
    }
    
    private void setupTimer() {
        try {
            timerHandler = new Handler();
            timerRunnable = new Runnable() {
                @Override
                public void run() {
                    updateCallTimeDisplay();
                    timerHandler.postDelayed(this, 1000); // Update every second
                }
            };
            timerHandler.post(timerRunnable);
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error setting up timer", e);
        }
    }
    
    private void startMotionDetectionService() {
        try {
            Intent serviceIntent = new Intent(this, SimpleMotionDetectionService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            android.util.Log.d("MainActivity", "Simple motion detection service started");
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error starting service", e);
            Toast.makeText(this, "Failed to start motion detection service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        try {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            
            if (motionReceiver != null) {
                lbm.registerReceiver(motionReceiver, new IntentFilter("MOTION_DETECTED"));
                android.util.Log.d("MainActivity", "Motion receiver registered");
            }
            
            if (stateReceiver != null) {
                lbm.registerReceiver(stateReceiver, new IntentFilter("STATE_CHANGED"));
                android.util.Log.d("MainActivity", "State receiver registered");
            }
            
            // Refresh state display
            updateStateDisplay();
            
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error registering receivers", e);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        try {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            
            if (motionReceiver != null) {
                lbm.unregisterReceiver(motionReceiver);
                android.util.Log.d("MainActivity", "Motion receiver unregistered");
            }
            
            if (stateReceiver != null) {
                lbm.unregisterReceiver(stateReceiver);
                android.util.Log.d("MainActivity", "State receiver unregistered");
            }
            
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error unregistering receivers", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (timerHandler != null && timerRunnable != null) {
                timerHandler.removeCallbacks(timerRunnable);
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error cleaning up timer", e);
        }
    }
}