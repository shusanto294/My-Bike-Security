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
                                
                                Log.d(TAG, "SMS received from: " + sender + ", Message: " + messageBody);
                                
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
            Log.d(TAG, "Processing SMS command from: " + sender);
            
            String message_lower = message.trim().toLowerCase();
            String response = null;
            
            // Check for setadminnumber command (needs special handling for number parameter)
            if (message_lower.startsWith("setadminnumber ")) {
                String[] parts = message.trim().split("\\s+", 2);
                if (parts.length == 2) {
                    String newAdminNumber = parts[1].trim();
                    if (isValidPhoneNumber(newAdminNumber)) {
                        stateManager.setAdminNumber(newAdminNumber);
                        response = "Admin number changed to " + newAdminNumber;
                        Log.d(TAG, "Admin number changed to: " + newAdminNumber);
                    } else {
                        response = "Invalid phone number format. Please use: setadminnumber 01234567890";
                        Log.d(TAG, "Invalid phone number format: " + newAdminNumber);
                    }
                } else {
                    response = "Invalid format. Use: setadminnumber 01234567890";
                    Log.d(TAG, "Invalid setadminnumber command format");
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
            // Remove any non-digit characters for validation
            String cleanNumber = phoneNumber.replaceAll("[^0-9]", "");
            
            // Check if it's a reasonable length (8-15 digits is typical for phone numbers)
            if (cleanNumber.length() >= 8 && cleanNumber.length() <= 15) {
                return true;
            }
            
            Log.d(TAG, "Phone number length invalid: " + cleanNumber.length());
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating phone number", e);
            return false;
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