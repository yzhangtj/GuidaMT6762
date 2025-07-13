# Guida Assistant - Multi-App Architecture

This repository contains the complete Guida Assistant system with a multi-app architecture:

## Project Structure

```
GuidaMT6762/
├── glasses-app/          # Android app for embedded glasses system
│   ├── app/              # Glasses app source code
│   ├── build.gradle.kts  # Glasses app build configuration
│   └── settings.gradle.kts
├── phone-app/            # Android app for user's phone
│   ├── app/              # Phone app source code
│   ├── build.gradle.kts  # Phone app build configuration
│   └── settings.gradle.kts
├── server.py             # Flask backend server
├── requirements.txt      # Python dependencies
└── README.md            # This file
```

## Apps Overview

### Glasses App (`glasses-app/`)
- **Purpose**: Embedded system in glasses
- **Features**:
  - Camera capture
  - Speech recognition
  - WiFi communication with phone app
  - Text-to-Speech (for response playback)
  - Minimal UI for embedded system

### Phone App (`phone-app/`)
- **Purpose**: User's phone application
- **Features**:
  - WiFi server to receive data from glasses
  - Backend communication
  - Response processing
  - User interface and settings

### Backend Server (`server.py`)
- **Purpose**: AI processing and response generation
- **Features**:
  - Receives image and text from phone app
  - Processes data with AI
  - Returns response to phone app

## Development Workflow

1. **Glasses App** captures image and speech
2. **Glasses App** sends data to **Phone App** via WiFi
3. **Phone App** sends data to **Backend Server**
4. **Backend Server** processes and returns response
5. **Phone App** sends response back to **Glasses App**
6. **Glasses App** plays response via Text-to-Speech

## Building the Apps

### Glasses App
```bash
cd glasses-app
./gradlew assembleDebug
```

### Phone App
```bash
cd phone-app
./gradlew assembleDebug
```

## Running the Backend Server
```bash
python server.py
```

## Current Status

- ✅ Image capture and speech recognition working
- ✅ Backend server receiving and processing data
- ✅ Multi-app structure created
- 🔄 WiFi communication between apps (in progress)
- 🔄 Text-to-Speech implementation (pending)
- 🔄 Phone app UI development (pending)

## Next Steps

1. Implement WiFi communication between glasses and phone apps
2. Add Text-to-Speech to glasses app
3. Develop phone app UI and functionality
4. Test end-to-end communication flow

# Guida App - Speech Recognition Implementation
~~~~
This Android app has been modified to use Android's built-in SpeechRecognizer for speech-to-text conversion instead of audio recording. The app now captures speech input, converts it to text, and sends both the text and a base64-encoded image to a mock Azure endpoint.

## Key Changes Made

### 1. Speech Recognition Manager
- Created `SpeechRecognitionManager.kt` that uses Android's `SpeechRecognizer` API
- Supports real-time partial speech recognition results
- Handles various speech recognition errors gracefully
- Uses coroutines for asynchronous speech recognition

### 2. Updated API Service
- Modified `ApiService.kt` to send JSON data instead of multipart form data
- Now sends `ProcessRequest` containing:
  - `text`: The recognized speech text
  - `imageBase64`: Base64-encoded image data
- Returns `ProcessResponse` with `responseText` field

### 3. Updated Main ViewModel
- Replaced audio recording logic with speech recognition
- Converts captured images to base64 format
- Shows partial speech recognition results in real-time
- Uses text-to-speech for response playback instead of audio file playback

### 4. Updated UI
- Shows partial speech recognition text during listening
- Updated button labels and instructions for speech recognition
- Added accessibility descriptions for speech recognition functionality

## Testing the Implementation

### 1. Start the Mock Server

First, install Node.js dependencies and start the mock server:

```bash
npm install
npm start
```

The mock server will run on `http://localhost:3000` and provides:
- `POST /process` - Processes text and image data
- `GET /health` - Health check endpoint

### 2. Configure Network Access

For Android Emulator:
- The app is configured to use `http://10.0.2.2:3000/` (emulator's localhost)
- No additional configuration needed

For Physical Device:
1. Find your computer's IP address on the local network
2. Update `BASE_URL` in `ApiService.kt` to use your IP address:
   ```kotlin
   private const val BASE_URL = "http://YOUR_COMPUTER_IP:3000/"
   ```
3. Uncomment and update the IP address in `network_security_config.xml`:
   ```xml
   <domain includeSubdomains="true">YOUR_COMPUTER_IP</domain>
   ```

### 3. Required Permissions

The app requires these permissions:
- `CAMERA` - For capturing images
- `RECORD_AUDIO` - For speech recognition
- `INTERNET` - For API calls
- `ACCESS_NETWORK_STATE` - For network status

### 4. Testing Flow

1. **Launch the app** - Grant camera and microphone permissions when prompted
2. **Press "Start Listening"** - The app will:
   - Capture an image using the camera
   - Start listening for speech input
   - Show partial speech recognition results in real-time
3. **Speak clearly** - The app will display what it hears as you speak
4. **Speech recognition completes** - The app will:
   - Send the recognized text and base64 image to the mock server
   - Receive a text response from the server
   - Use text-to-speech to read the response aloud

## Mock Server Responses

The mock server provides context-aware responses based on the input text:
- Greetings ("hello", "hi") → Friendly greeting response
- Questions about vision ("what do you see") → Image analysis response
- Help requests → Assistance offer
- Weather queries → Weather service explanation
- Default → General acknowledgment with functionality explanation

## Architecture Overview

```
User Speech → SpeechRecognizer → Text + Image → API → Text Response → TTS
```

1. **Speech Input**: Android SpeechRecognizer converts speech to text
2. **Image Capture**: Camera captures image and converts to base64
3. **API Call**: Sends JSON with text and image data
4. **Response**: Receives text response from server
5. **Output**: Text-to-speech reads response to user

## Key Features

- **Real-time Speech Recognition**: Shows partial results as you speak
- **Error Handling**: Graceful handling of speech recognition errors
- **Accessibility**: Full accessibility support with content descriptions
- **Network Security**: Configured for HTTP testing with local mock server
- **Responsive UI**: Visual feedback for different app states

## Dependencies Added

- `retrofit2:converter-gson` - For JSON serialization/deserialization
- Android SpeechRecognizer (built-in) - For speech-to-text conversion

## Notes

- The app uses Android's built-in speech recognition, which requires an internet connection
- Speech recognition quality depends on device microphone and ambient noise
- The mock server simulates processing delay (1 second) for realistic testing
- Base64 image encoding may result in large payloads for high-resolution images 