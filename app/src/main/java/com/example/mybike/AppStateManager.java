package com.example.mybike;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class AppStateManager {
    private static final String TAG = "AppStateManager";
    private static final String PREFS_NAME = "MyBikePrefs";
    
    private static final String KEY_STATUS = "status";
    private static final String KEY_ADMIN_NUMBER = "admin_number";
    private static final String KEY_CALL = "call";
    private static final String KEY_ALARM = "alarm";
    private static final String KEY_LAST_CALL_TIME = "last_call_time";
    private static final String KEY_MOTION_START_TIME = "motion_start_time";
    private static final String KEY_IS_CALL_DELAY_ACTIVE = "is_call_delay_active";
    private static final String KEY_IS_CALL_READY = "is_call_ready";
    
    private static AppStateManager instance;
    private SharedPreferences prefs;
    
    private AppStateManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initializeDefaults();
    }
    
    public static synchronized AppStateManager getInstance(Context context) {
        if (instance == null) {
            instance = new AppStateManager(context);
        }
        return instance;
    }
    
    private void initializeDefaults() {
        if (!prefs.contains(KEY_STATUS)) {
            setStatus("locked");
        }
        if (!prefs.contains(KEY_ADMIN_NUMBER)) {
            setAdminNumber("+11111111111");
        }
        if (!prefs.contains(KEY_CALL)) {
            setCall(true);
        }
        if (!prefs.contains(KEY_ALARM)) {
            setAlarm(true);
        }
        Log.d(TAG, "Default states initialized");
    }
    
    public String getStatus() {
        return prefs.getString(KEY_STATUS, "locked");
    }
    
    public void setStatus(String status) {
        prefs.edit().putString(KEY_STATUS, status).apply();
        Log.d(TAG, "Status changed to: " + status);
    }
    
    public String getAdminNumber() {
        return prefs.getString(KEY_ADMIN_NUMBER, "+11111111111");
    }
    
    public void setAdminNumber(String number) {
        String oldNumber = prefs.getString(KEY_ADMIN_NUMBER, "+11111111111");
        boolean success = prefs.edit().putString(KEY_ADMIN_NUMBER, number).commit();
        String verifyNumber = prefs.getString(KEY_ADMIN_NUMBER, "+11111111111");
        
        Log.w(TAG, "🔄 ADMIN NUMBER UPDATE:");
        Log.w(TAG, "  📞 Old number: " + oldNumber);
        Log.w(TAG, "  📞 New number: " + number);
        Log.w(TAG, "  💾 Save success: " + success);
        Log.w(TAG, "  ✅ Verified number: " + verifyNumber);
    }
    
    public boolean getCall() {
        return prefs.getBoolean(KEY_CALL, true);
    }
    
    public void setCall(boolean call) {
        prefs.edit().putBoolean(KEY_CALL, call).apply();
        Log.d(TAG, "Call status changed to: " + call);
    }
    
    public boolean getAlarm() {
        return prefs.getBoolean(KEY_ALARM, true);
    }
    
    public void setAlarm(boolean alarm) {
        prefs.edit().putBoolean(KEY_ALARM, alarm).apply();
        Log.d(TAG, "Alarm status changed to: " + alarm);
    }
    
    public boolean isLocked() {
        return "locked".equals(getStatus());
    }
    
    public long getLastCallTime() {
        return prefs.getLong(KEY_LAST_CALL_TIME, 0);
    }
    
    public void setLastCallTime(long time) {
        prefs.edit().putLong(KEY_LAST_CALL_TIME, time).apply();
        Log.d(TAG, "Last call time updated: " + time);
    }
    
    public long getMotionStartTime() {
        return prefs.getLong(KEY_MOTION_START_TIME, 0);
    }
    
    public void setMotionStartTime(long time) {
        prefs.edit().putLong(KEY_MOTION_START_TIME, time).commit();
        Log.d(TAG, "Motion start time updated: " + time);
    }
    
    public boolean isCallDelayActive() {
        return prefs.getBoolean(KEY_IS_CALL_DELAY_ACTIVE, false);
    }
    
    public void setCallDelayActive(boolean active) {
        prefs.edit().putBoolean(KEY_IS_CALL_DELAY_ACTIVE, active).commit();
        Log.d(TAG, "Call delay active status changed to: " + active);
    }
    
    public boolean isCallReady() {
        return prefs.getBoolean(KEY_IS_CALL_READY, false);
    }
    
    public void setCallReady(boolean ready) {
        prefs.edit().putBoolean(KEY_IS_CALL_READY, ready).commit();
        Log.d(TAG, "Call ready status changed to: " + ready);
    }
    
    public String getAllStatesString() {
        return String.format("Status: %s\nAdmin: %s\nCall: %s\nAlarm: %s",
            getStatus(), getAdminNumber(), getCall(), getAlarm());
    }
}