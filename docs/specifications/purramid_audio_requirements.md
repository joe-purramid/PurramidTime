# PurramidTime - Audio Requirements

## Overview
This document defines the audio system requirements for PurramidTime: alarm playback (Clock), countdown-finish playback (Timer), and lap/button beep feedback (Stopwatch). PurramidTime has no microphone or noise-monitoring features — do not add `RECORD_AUDIO` or any decibel-metering code; that functionality belongs to a different Purramid app and is out of scope here.

## Audio Permissions

### **Required Permissions**
```xml
<uses-permission android:name="android.permission.INTERNET" /> <!-- streaming music URLs only -->
```
No microphone permission is required. `INTERNET` is used exclusively for validating and streaming user-supplied music URLs (Timer "Music URL" setting).

## Music Playback System (Timer)

### **Supported Audio Formats**
- **Primary**: MP3, AAC, OGG Vorbis
- **Secondary**: WAV, FLAC (if device supports)
- **Streaming**: HTTP/HTTPS URLs only
- **Validation**: Must validate URL before playback attempt

### **Music URL Requirements**
```kotlin
class MusicUrlValidator {
    suspend fun validateMusicUrl(url: String): MusicUrlResult {
        // Validate URL format
        // Test HTTP connectivity
        // Verify audio content type
        // Return success/failure with error details
    }
}

sealed class MusicUrlResult {
    data class Valid(val title: String?) : MusicUrlResult()
    data class Invalid(val reason: String) : MusicUrlResult()
}
```

### **Playback Implementation**
```kotlin
class MusicPlayer @Inject constructor() {
    private var mediaPlayer: MediaPlayer? = null

    fun playFromUrl(url: String): Flow<PlaybackState> = flow {
        // Handle streaming audio from URL
        // Emit playback states: Loading, Playing, Stopped, Error
        // Support play/stop controls (per Timer spec: tap again, tap elsewhere, or end of track stops playback)
    }

    fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

sealed class PlaybackState {
    object Loading : PlaybackState()
    object Playing : PlaybackState()
    object Stopped : PlaybackState()
    data class Error(val message: String) : PlaybackState()
}
```

- Music does not loop (per Timer "Music URL" spec).
- Invalid URLs show a snackbar: "This is not a valid music URL. Please provide an updated URL to play."
- The three most recently used URLs are persisted per `TimerStateEntity.recentMusicUrlsJson`.

## Alarm Audio System (Clock)

### **Audio Source Options**
```kotlin
sealed class AlarmAudioSource {
    data class SystemAlarm(val uri: Uri) : AlarmAudioSource()
    data class MusicUrl(val url: String, val title: String?) : AlarmAudioSource()
    object DefaultAlarm : AlarmAudioSource()
}
```

### **System Integration**
- **Available System Alarms**: Query `RingtoneManager.TYPE_ALARM`
- **Custom Music**: Use URLs from the Music Playback System above
- **Default Fallback**: Use system default alarm if custom source fails
- **Volume**: Use system alarm volume level
- **Duration**: Play until user taps to dismiss
- Per the Clock spec, alarms must be self-contained in the Clock app-intent rather than deferring to the Android system alarm app.

### **Alarm Playback Requirements**
```kotlin
class AlarmPlayer @Inject constructor(
    private val context: Context,
    private val musicPlayer: MusicPlayer
) {
    suspend fun playAlarm(source: AlarmAudioSource) {
        when (source) {
            is AlarmAudioSource.SystemAlarm -> playSystemAlarm(source.uri)
            is AlarmAudioSource.MusicUrl -> playMusicAlarm(source.url)
            is AlarmAudioSource.DefaultAlarm -> playDefaultAlarm()
        }
    }

    private fun playSystemAlarm(uri: Uri) {
        // Use MediaPlayer with system alarm URI
        // Respect system alarm volume
        // Loop until dismissed
    }
}
```

## Countdown/Notification Sounds (Timer, Stopwatch)

- **Timer "Play Sound on Finish"**: plays a selected device sound or music URL when the countdown reaches zero (see `TimerStateEntity.playSoundOnEnd`, `selectedSoundUri`, `musicUrl`).
- **Stopwatch "Sounds"**: when toggled on, plays a monotone beep for 0.1 seconds on every play/pause or reset press (see `StopwatchStateEntity.soundsEnabled`).

## Audio Focus Management

### **Audio Focus Strategy**
```kotlin
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun requestAudioFocus(usage: AudioUsage): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage.toAudioAttribute())
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()

        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}

enum class AudioUsage {
    MUSIC_PLAYBACK,     // Timer music URL preview/playback
    ALARM_NOTIFICATION, // Clock alarms, Timer countdown-finish sound
    UI_FEEDBACK         // Stopwatch button beep
}
```

### **Background Audio Behavior**
- **Music Playback**: Continue when app minimized
- **Alarm Audio**: Continue when app minimized until dismissed
- **Audio Focus**: Handle phone calls, other apps requesting audio focus

## Performance Requirements

### **Latency Requirements**
- **Music Playback Start**: < 500ms from URL to first audio output
- **Alarm Trigger**: < 50ms from scheduled time to audio start
- **Stopwatch Beep**: < 50ms from button press to sound start

### **Memory Usage**
- **Music Streaming**: 2MB buffer for network audio

### **Battery Optimization**
- **Doze Mode**: Handle Android Doze mode for alarm functionality (uses `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, see architecture decisions)

## Error Recovery

### **Network Audio Errors**
```kotlin
class AudioErrorRecovery {
    fun handleMusicUrlError(error: Exception): UserAction {
        return when (error) {
            is UnknownHostException -> UserAction.ShowSnackbar("No internet connection for music")
            is FileNotFoundException -> UserAction.ShowSnackbar("Music URL not found")
            is SecurityException -> UserAction.ShowSnackbar("Cannot access music URL")
            else -> UserAction.ShowSnackbar("Unable to play music from URL")
        }
    }
}
```

## Testing Requirements

### **Audio Testing Strategy**
```kotlin
// Unit Tests
class AudioManagerTest {
    @Test fun `music URL validation catches invalid URLs`
    @Test fun `alarm audio source selection works`
}

// Integration Tests
class AudioIntegrationTest {
    @Test fun `music playback stops on tap, on outside tap, and on track end`
    @Test fun `alarm audio plays at scheduled time`
    @Test fun `stopwatch beep plays on play, pause, and reset`
}
```

### **Manual Testing Checklist**
- [ ] Music URL playback with various formats and sources
- [ ] Alarm functionality with system sounds and custom music
- [ ] Audio focus handling during phone calls
- [ ] Background audio behavior when app minimized

## Platform Compatibility

### **Android Version Support**
- **Minimum SDK**: API 33 (Android 13), per `app/build.gradle.kts`
- **Target SDK**: API 36
- **Audio Features**: Graceful degradation on older devices

### **Hardware Requirements**
- **Speakers/Headphones**: Required for music and alarm audio
- **Network**: Required for streaming music URLs
