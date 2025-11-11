# BLE GATT Connection Testing Guide

## Prerequisites

1. **Install both APKs:**
   - Glasses app: `D:\guidaFrontend\GuidaRadarFix\GuidaMT6762\glasses-app\app\build\outputs\apk\debug\app-debug.apk`
   - Phone app: `D:\guidaFrontend\GuidaRadarFix\GuidaMT6762\gemma-phone-app\app\build\outputs\apk\debug\app-debug.apk`

2. **Enable Bluetooth on both devices**
3. **Grant Bluetooth permissions** on both apps when prompted

## Testing Steps

### Method 1: Using the Phone App (Recommended)

#### Step 1: Start BLE Advertising on Glasses
1. Open the **glasses app** on your glasses device
2. **Long press F2** button (hold for ~500ms)
3. You should hear: "Bluetooth pairing mode activated. Device is now discoverable for 2 minutes."
4. The glasses will:
   - Start BLE advertising with Service UUID `0xFFF0`
   - Set Bluetooth name to "GuidaGlasses-0001"
   - Start the BLE GATT server

#### Step 2: Scan and Connect from Phone
1. Open the **phone app** (gemma-phone-app)
2. Tap the **Settings** icon (top right)
3. Tap **"Connect Glasses"** or similar provisioning option
4. The provisioning dialog will open
5. Click **"Scan for devices"** button
6. Wait for the glasses device to appear (should show as "GuidaGlasses-0001")
7. Select the device from the dropdown
8. Enter your **Wi-Fi SSID** and **password**
9. Click **"Send to Glasses"**

#### Step 3: Verify Connection
- The phone app will:
  1. Connect to the glasses via BLE GATT
  2. Discover Service `0xFFF0`
  3. Enable notifications on Characteristic `0xFFF3`
  4. Write credentials to Characteristic `0xFFF2`
  5. Wait for "OK" notification from glasses

- The glasses app will:
  1. Receive credentials via Characteristic `0xFFF2`
  2. Parse CSV format: `ssid,password,phoneUrl`
  3. Send "OK" notification via Characteristic `0xFFF3`
  4. Connect to Wi-Fi network
  5. Speak: "WiFi credentials received, connecting to network."

### Method 2: Using nRF Connect (Alternative Testing)

#### Step 1: Install nRF Connect
- Download from Google Play Store: "nRF Connect"

#### Step 2: Start Glasses Advertising
- Same as Method 1, Step 1

#### Step 3: Scan with nRF Connect
1. Open nRF Connect app
2. Tap **"Scan"** button
3. Look for device named **"GuidaGlasses-0001"**
4. Check that it advertises Service UUID: **0xFFF0**

#### Step 4: Connect and Explore GATT
1. Tap on **"GuidaGlasses-0001"** device
2. Tap **"Connect"**
3. After connection, you should see:
   - **Service: 0xFFF0**
     - **Characteristic 0xFFF1** (READ) - Device info
     - **Characteristic 0xFFF2** (WRITE) - Credentials
     - **Characteristic 0xFFF3** (NOTIFY) - Status

#### Step 5: Test Characteristics
1. **Read 0xFFF1:**
   - Tap on Characteristic `0xFFF1`
   - Tap "Read" button
   - Should return: "GuidaGlasses-0001"

2. **Write to 0xFFF2:**
   - Tap on Characteristic `0xFFF2`
   - Tap "Write" button
   - Enter: `YourSSID,YourPassword,http://192.168.43.1:5000`
   - Tap "Send"
   - Glasses should receive and process credentials

3. **Enable Notifications on 0xFFF3:**
   - Tap on Characteristic `0xFFF3`
   - Tap the notification icon (bell) to enable
   - After writing to 0xFFF2, you should receive "OK" notification

### Method 3: Using ADB Logcat (Debugging)

#### Monitor Glasses Logs:
```powershell
adb logcat -s guida BleGattServer BluetoothWifiServer
```

#### Monitor Phone Logs:
```powershell
adb logcat -s BleGattClient ProvisioningViewModel BluetoothWifiClient
```

#### Key Log Messages to Look For:

**Glasses Side:**
- `"BLE advertising started successfully with Service UUID 0xFFF0"`
- `"GATT server started successfully with service 0xFFF0"`
- `"Device connected: [address]"`
- `"Characteristic write request: uuid=0xFFF2"`
- `"=== GLASSES APP RECEIVING CREDENTIALS (BLE) ==="`
- `"Sent notification 'OK'"`

**Phone Side:**
- `"Starting BLE scan for Service 0xFFF0..."`
- `"Found device with Service 0xFFF0: [name] ([address])"`
- `"Connected to GATT server"`
- `"Found service 0xFFF0"`
- `"Notifications enabled, sending credentials"`
- `"Received notification: OK"`

## Troubleshooting

### Glasses Not Found During Scan
- ✅ Ensure glasses are in pairing mode (F2 long press)
- ✅ Check Bluetooth is enabled on both devices
- ✅ Verify glasses are within range (~10 meters)
- ✅ Check logs: `adb logcat -s guida | grep -i "advertising"`

### Connection Fails
- ✅ Check Bluetooth permissions are granted
- ✅ Ensure glasses GATT server started successfully
- ✅ Verify Service UUID `0xFFF0` is advertised
- ✅ Check logs for connection errors

### Credentials Not Received
- ✅ Verify notification is enabled before writing
- ✅ Check CSV format: `ssid,password,phoneUrl`
- ✅ Ensure message length < 200 bytes
- ✅ Check logs for write/notification errors

### Notification Not Received
- ✅ Verify CCCD descriptor was written successfully
- ✅ Check `notifyEnabled` flag in glasses logs
- ✅ Ensure connection is still active

## Expected Behavior

### Successful Flow:
1. Glasses: BLE advertising starts → GATT server starts
2. Phone: Scan finds glasses → Connect → Discover services
3. Phone: Enable notifications → Write credentials
4. Glasses: Receive credentials → Parse → Send "OK"
5. Phone: Receive "OK" → Show success message
6. Glasses: Connect to Wi-Fi → Speak confirmation

### Error Scenarios:
- **Scan timeout**: Glasses not advertising or out of range
- **Connection failed**: Bluetooth stack issue or permissions
- **Service not found**: GATT server didn't start properly
- **Write failed**: Connection lost or characteristic not writable
- **No ACK**: Notification not enabled or connection dropped

## Quick Test Commands

```powershell
# Install glasses app
adb install "D:\guidaFrontend\GuidaRadarFix\GuidaMT6762\glasses-app\app\build\outputs\apk\debug\app-debug.apk"

# Install phone app
adb install "D:\guidaFrontend\GuidaRadarFix\GuidaMT6762\gemma-phone-app\app\build\outputs\apk\debug\app-debug.apk"

# Check connected devices
adb devices

# Monitor glasses logs
adb logcat -s guida BleGattServer

# Monitor phone logs  
adb logcat -s BleGattClient ProvisioningViewModel
```

