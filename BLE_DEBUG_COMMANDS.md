# BLE Connection Debugging Commands

## Step 1: Identify Connected Devices

First, check which devices are connected:

```powershell
adb devices
```

You should see output like:
```
List of devices attached
ABC123XYZ    device    # This might be your glasses
DEF456UVW    device    # This might be your phone
```

Note the device serial numbers (ABC123XYZ, DEF456UVW, etc.)

## Step 2: Monitor Logs from Both Devices

### Option A: Two Separate Terminal Windows (Recommended)

**Terminal 1 - Glasses Logs:**
```powershell
# If only one device connected, use:
adb logcat -s guida BleGattServer BluetoothWifiServer

# If multiple devices, specify glasses device serial:
adb -s ABC123XYZ logcat -s guida BleGattServer BluetoothWifiServer
```

**Terminal 2 - Phone Logs:**
```powershell
# If only one device connected, use:
adb logcat -s BleGattClient ProvisioningViewModel BluetoothWifiClient

# If multiple devices, specify phone device serial:
adb -s DEF456UVW logcat -s BleGattClient ProvisioningViewModel BluetoothWifiClient
```

### Option B: Combined Logs in One Terminal

```powershell
# Monitor all BLE-related logs from all devices
adb logcat -s guida BleGattServer BleGattClient ProvisioningViewModel BluetoothWifiServer BluetoothWifiClient
```

### Option C: Filter by Tag Pattern

```powershell
# Show all logs containing "BLE", "GATT", "Bluetooth", or "guida"
adb logcat | Select-String -Pattern "BLE|GATT|Bluetooth|guida|BleGatt|Provisioning"
```

## Step 3: Clear Logs Before Testing

Clear logs on both devices before starting:

```powershell
# Clear logs on glasses
adb -s GLASSES_SERIAL logcat -c

# Clear logs on phone  
adb -s PHONE_SERIAL logcat -c

# Or clear all if only one device connected:
adb logcat -c
```

## Step 4: Key Log Messages to Watch For

### Glasses Side (Success Indicators):
```
I/guida: F2 long press - starting Bluetooth WiFi provisioning
I/guida: BLE advertising started successfully with Service UUID 0xFFF0
I/BleGattServer: GATT server started successfully with service 0xFFF0
I/BleGattServer: Connection state changed: ... STATE_CONNECTED
I/BleGattServer: Device connected: [MAC_ADDRESS]
I/BleGattServer: Characteristic write request: uuid=0xFFF2
I/BleGattServer: === GLASSES APP RECEIVING CREDENTIALS (BLE) ===
I/BleGattServer: SSID: 'YourSSID' (length: X)
I/BleGattServer: Password: 'YourPassword' (length: X)
I/BleGattServer: Sent notification 'OK'
```

### Phone Side (Success Indicators):
```
I/BleGattClient: Starting BLE scan for Service 0xFFF0...
I/BleGattClient: Found device with Service 0xFFF0: GuidaGlasses-0001 ([MAC])
I/BleGattClient: Connected to GATT server
I/BleGattClient: Services discovered: status=0
I/BleGattClient: Found service 0xFFF0
I/BleGattClient: Notifications enabled, sending credentials
I/BleGattClient: === SENDING CREDENTIALS (BLE) ===
I/BleGattClient: Credentials written, waiting for notification...
I/BleGattClient: Received notification: OK
I/ProvisioningViewModel: Credentials sent successfully (ACK received)
```

## Step 5: Common Error Messages

### Glasses Errors:
```
E/BleGattServer: Bluetooth adapter not available
E/BleGattServer: Bluetooth not enabled
E/BleGattServer: Failed to open GATT server
E/BleGattServer: Failed to add GATT service
E/BleGattServer: ERROR: SSID or password is empty!
```

### Phone Errors:
```
E/BleGattClient: Bluetooth not enabled
E/BleGattClient: BLE scanner not available
E/BleGattClient: Service 0xFFF0 not found
E/BleGattClient: Characteristic 0xFFF2 not found
E/BleGattClient: Write failed with status: [error_code]
E/BleGattClient: Connection state changed: ... STATE_DISCONNECTED
```

## Quick Debugging Script

Save this as `debug-ble.ps1`:

```powershell
# BLE Connection Debugging Script
Write-Host "=== BLE Connection Debugging ===" -ForegroundColor Cyan

# Check devices
Write-Host "`nChecking connected devices..." -ForegroundColor Yellow
adb devices

# Ask which device is glasses
$glassesSerial = Read-Host "Enter glasses device serial (or press Enter to use default)"
$phoneSerial = Read-Host "Enter phone device serial (or press Enter to use default)"

# Clear logs
Write-Host "`nClearing logs..." -ForegroundColor Yellow
if ($glassesSerial) { adb -s $glassesSerial logcat -c } else { adb logcat -c }
if ($phoneSerial) { adb -s $phoneSerial logcat -c }

Write-Host "`nStarting log monitoring..." -ForegroundColor Green
Write-Host "Press Ctrl+C to stop`n" -ForegroundColor Yellow

# Monitor combined logs
if ($glassesSerial -and $phoneSerial) {
    # Two devices - need separate monitoring
    Write-Host "Glasses logs:" -ForegroundColor Cyan
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "adb -s $glassesSerial logcat -s guida BleGattServer"
    Write-Host "Phone logs:" -ForegroundColor Cyan  
    adb -s $phoneSerial logcat -s BleGattClient ProvisioningViewModel
} else {
    # Single device or default
    adb logcat -s guida BleGattServer BleGattClient ProvisioningViewModel
}
```

## Real-Time Testing Workflow

1. **Open two terminal windows**

2. **Terminal 1 - Glasses:**
   ```powershell
   adb -s GLASSES_SERIAL logcat -c
   adb -s GLASSES_SERIAL logcat -s guida BleGattServer
   ```

3. **Terminal 2 - Phone:**
   ```powershell
   adb -s PHONE_SERIAL logcat -c
   adb -s PHONE_SERIAL logcat -s BleGattClient ProvisioningViewModel
   ```

4. **Start testing:**
   - Long press F2 on glasses (watch Terminal 1)
   - Scan for devices on phone (watch Terminal 2)
   - Send credentials (watch both terminals)

5. **Look for the success flow** in both terminals

## Advanced: Save Logs to File

```powershell
# Save glasses logs
adb -s GLASSES_SERIAL logcat -s guida BleGattServer > glasses-ble-log.txt

# Save phone logs
adb -s PHONE_SERIAL logcat -s BleGattClient ProvisioningViewModel > phone-ble-log.txt

# View saved logs
Get-Content glasses-ble-log.txt
Get-Content phone-ble-log.txt
```

