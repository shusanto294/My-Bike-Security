package com.example.mybike;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (SMS_RECEIVED.equals(intent.getAction())) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    String format = bundle.getString("format");
                    
                    if (pdus != null) {
                        for (Object pdu : pdus) {
                            SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                            if (smsMessage != null) {
                                String sender = smsMessage.getDisplayOriginatingAddress();
                                String messageBody = smsMessage.getDisplayMessageBody();
                                
                                Log.w(TAG, "🔔 SMS RECEIVED from: " + sender + ", Message: '" + messageBody + "'");
                                
                                processCommand(context, sender, messageBody);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing SMS", e);
        }
    }
    
    private void processCommand(Context context, String sender, String message) {
        try {
            AppStateManager stateManager = AppStateManager.getInstance(context);
            
            // Process commands from any phone number
            Log.w(TAG, "🔍 PROCESSING SMS COMMAND from: " + sender);
            Log.w(TAG, "📝 Original message: '" + message + "'");
            
            String message_lower = message.trim().toLowerCase();
            Log.w(TAG, "📝 Lowercase message: '" + message_lower + "'");
            String response = null;
            
            // Check for setadminnumber command (needs special handling for number parameter)
            if (message_lower.startsWith("setadminnumber")) {
                Log.d(TAG, "Processing setadminnumber command");
                String[] parts = message.trim().split("\\s+", 2);
                Log.d(TAG, "Command parts: " + java.util.Arrays.toString(parts));
                
                if (parts.length == 2) {
                    String newAdminNumber = parts[1].trim();
                    Log.d(TAG, "New admin number candidate: '" + newAdminNumber + "'");
                    
                    if (isValidPhoneNumber(newAdminNumber)) {
                        String oldNumber = stateManager.getAdminNumber();
                        stateManager.setAdminNumber(newAdminNumber);
                        String updatedNumber = stateManager.getAdminNumber();
                        
                        response = "Admin number changed from " + oldNumber + " to " + updatedNumber;
                        Log.w(TAG, "✅ ADMIN NUMBER UPDATED: " + oldNumber + " → " + updatedNumber);
                    } else {
                        response = "Invalid phone number format. Please use: setadminnumber 01743395086";
                        Log.w(TAG, "❌ Invalid phone number format: '" + newAdminNumber + "'");
                    }
                } else {
                    response = "Invalid format. Use: setadminnumber 01743395086";
                    Log.w(TAG, "❌ Invalid setadminnumber command format - expected 2 parts, got: " + parts.length);
                }
            } else {
                // Handle other commands
                switch (message_lower) {
                    case "lock":
                        stateManager.setStatus("locked");
                        response = "Status changed to locked";
                        break;
                        
                    case "unlock":
                        stateManager.setStatus("unlocked");
                        response = "Status changed to unlocked";
                        break;
                        
                    case "call true":
                        stateManager.setCall(true);
                        response = "Call setting changed to true";
                        break;
                        
                    case "call false":
                        stateManager.setCall(false);
                        response = "Call setting changed to false";
                        break;
                        
                    case "alerm true":
                        stateManager.setAlarm(true);
                        response = "Alarm setting changed to true";
                        break;
                        
                    case "alerm false":
                        stateManager.setAlarm(false);
                        response = "Alarm setting changed to false";
                        break;
                        
                    case "testcall":
                        // Test calling functionality
                        Log.w(TAG, "📞 TEST CALL command received - triggering test call");
                        testPhoneCall(context);
                        response = "Test call initiated. Check logs for results.";
                        break;
                        
                    case "status":
                        // Get current status
                        response = "Status: " + stateManager.getStatus() + 
                                 "\nAdmin: " + stateManager.getAdminNumber() +
                                 "\nCall: " + stateManager.getCall() +
                                 "\nAlarm: " + stateManager.getAlarm();
                        break;
                        
                    default:
                        // No response for unrecognized commands
                        Log.d(TAG, "Unrecognized command: " + message_lower);
                        return;
                }
            }
            
            // Send reply if command was recognized
            if (response != null) {
                sendSmsReply(sender, response);
                
                // Notify UI to update
                Intent updateIntent = new Intent("STATE_CHANGED");
                LocalBroadcastManager.getInstance(context).sendBroadcast(updateIntent);
                
                Log.d(TAG, "Command processed: " + message_lower + " -> " + response);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing command", e);
        }
    }
    
    private boolean isValidPhoneNumber(String phoneNumber) {
        try {
            Log.d(TAG, "Validating phone number: '" + phoneNumber + "'");
            
            // Allow phone numbers with or without + prefix
            String cleanNumber = phoneNumber.replaceAll("[^0-9+]", "");
            
            // Remove + from beginning for length check
            String digitsOnly = cleanNumber.replaceAll("\\+", "");
            
            Log.d(TAG, "Clean number: '" + cleanNumber + "', digits only: '" + digitsOnly + "', length: " + digitsOnly.length());
            
            // Check if it's a reasonable length (7-15 digits is typical for phone numbers)
            // Bangladesh numbers like 01743395086 are 11 digits
            if (digitsOnly.length() >= 7 && digitsOnly.length() <= 15) {
                Log.d(TAG, "Phone number validation PASSED");
                return true;
            }
            
            Log.w(TAG, "Phone number validation FAILED - length invalid: " + digitsOnly.length());
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating phone number", e);
            return false;
        }
    }
    
    private void testPhoneCall(Context context) {
        try {
            Log.w(TAG, "📞 TEST CALL - Starting direct call test");
            AppStateManager stateManager = AppStateManager.getInstance(context);
            
            // Test calling logic similar to the service
            String adminNumber = stateManager.getAdminNumber();
            Log.w(TAG, "📞 TEST CALL - Admin number: " + adminNumber);
            
            if (adminNumber == null || adminNumber.isEmpty() || adminNumber.equals("+11111111111")) {
                Log.e(TAG, "❌ TEST CALL FAILED - No valid admin number");
                return;
            }
            
            // Create a direct call intent
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(android.net.Uri.parse("tel:" + adminNumber));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                              Intent.FLAG_ACTIVITY_CLEAR_TOP);
            
            context.startActivity(callIntent);
            Log.w(TAG, "✅ TEST CALL - Call intent started to: " + adminNumber);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ TEST CALL ERROR", e);
        }
    }
    
    private void sendSmsReply(String recipient, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(recipient, null, message, null, null);
            Log.d(TAG, "SMS reply sent to: " + recipient + ", Message: " + message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending SMS reply", e);
        }
    }
}