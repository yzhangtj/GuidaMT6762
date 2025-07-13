# Guida Assistant - Complete Setup Instructions

## Overview
This system consists of:
1. **Android App** (MTK6762 board) - Captures images and speech, sends to laptop
2. **Python Server** (Laptop) - Receives data, processes it, sends back responses

## Setup Instructions

### 1. Android App Setup

#### Prerequisites
- Android Studio
- MTK6762 board connected to laptop
- Board and laptop on same WiFi network

#### Steps
1. **Build and install the APK:**
   ```bash
   ./gradlew assembleDebug
   ```
   Install `app/build/outputs/apk/debug/app-debug.apk` on your board.

2. **Configure the server URL:**
   - Find your laptop's IP address (e.g., `192.168.1.100`)
   - Update `HttpClient.kt` line 8:
     ```kotlin
     private const val SERVER_URL = "http://YOUR_LAPTOP_IP:5000/upload"
     ```

3. **Test the app:**
   - Launch the app on your board
   - Press F1 to capture image + start speech recognition
   - Press F1 again to stop and send data

### 2. Python Server Setup

#### Prerequisites
- Python 3.7+ installed on your laptop
- pip package manager

#### Steps
1. **Install dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

2. **Start the server:**
   ```bash
   python server.py
   ```
   The server will start on `http://0.0.0.0:5000`

3. **Test the server:**
   - Open browser: `http://localhost:5000`
   - Should see server info page

### 3. Network Configuration

#### Find Your Laptop's IP Address
**Windows:**
```cmd
ipconfig
```
Look for "IPv4 Address" under your WiFi adapter.

**Mac/Linux:**
```bash
ifconfig
# or
ip addr show
```

#### Ensure Connectivity
1. Both devices must be on the same WiFi network
2. Test connectivity: ping your laptop's IP from another device
3. Check firewall settings - allow port 5000

### 4. Testing the Complete System

1. **Start the Python server on your laptop**
2. **Launch the Android app on your board**
3. **Test the workflow:**
   - Press F1 on the board
   - Speak something (e.g., "What do you see?")
   - Press F1 again to stop and send
   - Check the laptop console for received data
   - Check the `uploads/` folder for saved images

### 5. Troubleshooting

#### Common Issues

**"Network error" in Android app:**
- Check if laptop IP is correct in `HttpClient.kt`
- Ensure both devices are on same WiFi
- Check laptop firewall settings

**"Server error" in Android app:**
- Make sure Python server is running
- Check server console for error messages

**No images in uploads folder:**
- Check file permissions
- Ensure `uploads/` directory exists

**Speech recognition not working:**
- Check microphone permissions
- Ensure Vosk model is properly loaded

### 6. Next Steps

Once basic communication works:

1. **Add AI processing** in `server.py` `process_data()` function
2. **Add TTS response** in Android app
3. **Implement video recording** with F2 button
4. **Add settings** for server URL configuration

### 7. File Structure

```
GuidaMT6762/
├── app/                          # Android app
│   └── src/main/java/com/guidaco/guidaapp0606/
│       ├── MainActivity.kt       # Main UI
│       ├── MainViewModel.kt      # Business logic
│       ├── HttpClient.kt         # HTTP communication
│       └── ...
├── server.py                     # Python server
├── requirements.txt              # Python dependencies
└── SETUP_INSTRUCTIONS.md         # This file
```

## Support

If you encounter issues:
1. Check the logs in Android Studio
2. Check the Python server console
3. Verify network connectivity
4. Ensure all permissions are granted 