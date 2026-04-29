# S21 FE Pro Camera

Professional Camera App for Samsung Galaxy S21 FE with computational photography features.

## Features

✨ **Camera2 API** - Full access to 4 sensors (Wide, Ultra-wide, Telephoto, Front)
✨ **HDR+ Algorithm** - RAW burst capture with intelligent frame alignment
✨ **4K60 Recording** - With OIS (Optical) and EIS (Electronic) stabilization
✨ **LOG Profile** - Flat color profile for post-production
✨ **Hybrid Zoom** - Up to 30x combining optical and digital zoom
✨ **Real-time Histogram** - For exposure control
✨ **Focus Peaking** - Manual focus assistance
✨ **Advanced Settings** - Complete camera control panel
✨ **4 Capture Modes** - Photo, Video, Pro, Portrait

## Technical Specifications

- **Target SDK:** Android 14 (API 34)
- **Min SDK:** Android 7.0 (API 24)
- **Architecture:** ARM64 + ARMv7
- **Bitrate:** 100 Mbps
- **Libraries:** OpenCV 4.8.0, TensorFlow Lite 2.12.0, Camera2 API

## Build Instructions

### Option 1: GitHub Actions (Automatic)
The project includes GitHub Actions workflow that automatically compiles the APK.
- Push to main branch
- GitHub Actions builds and releases the APK
- Download from Releases

### Option 2: Local Build (Android Studio)
1. Clone the repository
2. Open in Android Studio
3. Build → Build APK
4. APK will be in `app/build/outputs/apk/release/`

### Option 3: Command Line (Gradle)
```bash
./gradlew assembleRelease
```

## Installation

1. Enable "Unknown Sources" in Settings → Security
2. Download the APK
3. Open the APK file
4. Grant permissions: Camera, Microphone, Storage
5. Launch the app

## Permissions Required

- `android.permission.CAMERA` - Camera access
- `android.permission.RECORD_AUDIO` - Audio recording
- `android.permission.WRITE_EXTERNAL_STORAGE` - Save media
- `android.permission.READ_EXTERNAL_STORAGE` - Read media

## Project Structure

```
S21FEProCamera/
├── app/
│   ├── src/main/
│   │   ├── java/com/s21fe/procamera/
│   │   │   └── MainActivity.java
│   │   ├── cpp/
│   │   │   ├── hdr_processor.cpp
│   │   │   ├── denoise_processor.cpp
│   │   │   ├── super_resolution.cpp
│   │   │   ├── raw_processor.cpp
│   │   │   └── image_processor.cpp
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── drawable/
│   │       └── values/
│   └── build.gradle
├── .github/workflows/build.yml
└── README.md
```

## Development

### Adding New Features

1. Edit `MainActivity.java` for Java logic
2. Edit C++ files in `app/src/main/cpp/` for native processing
3. Update layouts in `app/src/main/res/layout/`
4. Commit and push to trigger GitHub Actions build

### Camera2 API Usage

The app uses Camera2 API for:
- Accessing individual camera sensors
- Controlling exposure, ISO, white balance
- Capturing RAW images
- Recording video with custom bitrate

### Native Processing

C++ modules handle:
- HDR+ frame fusion
- Denoise filtering
- Super-resolution upscaling
- RAW demosaicing and tone mapping

## Supported Devices

- Samsung Galaxy S21 FE (Snapdragon 888) ✓
- Android 7.0+ devices with Camera2 support ✓

## License

Proprietary - S21 FE Pro Camera

## Support

For issues or feature requests, please create an issue in the repository.

---

**Built with:** Java 11, C++17, Gradle 8.1, Android SDK 34
**Last Updated:** 2026-04-29
