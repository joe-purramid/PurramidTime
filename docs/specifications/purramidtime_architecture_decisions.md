# PurramidTime - Technical Architecture Decisions

## Overview
This document defines the mandatory architectural patterns and implementation approaches for Purramid applications.

## Core Architecture Pattern

### **MVVM + Repository Pattern**
- **Activities**: Handle UI lifecycle and system interactions only
- **ViewModels**: Manage UI state and business logic
- **Repository**: Single source of truth for data operations
- **Room Database**: Local data persistence
- **Foreground Service**: Background operations and overlay management

## Dependency Injection

### **Hilt Framework (Mandatory)**
```kotlin
// Application-level module
@Module
@InstallIn(SingletonComponent::class)
object AppModule

// Service-level injection
@Module 
@InstallIn(ServiceComponent::class)
object ServiceModule

// Activity-level injection
@AndroidEntryPoint
class ClockActivity : AppCompatActivity()
```

**Injection Strategy:**
- Repository instances: `@Singleton`
- ViewModel instances: `@ViewModelScoped`
- Database: `@Singleton`
- Audio manager: `@Singleton`
- Service: System-managed singleton

### **Dispatcher Qualifiers**
```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
```

## State Management

> **Reactive type (best practice):** Expose UI state from ViewModels as Kotlin `StateFlow`/`SharedFlow` collected with `repeatOnLifecycle`/`flowWithLifecycle`, not `LiveData`. The project still pulls in `lifecycle-livedata-ktx` for legacy paths, but new ViewModel/Repository code should be Flow-based end to end; reserve `LiveData` only where an existing API forces it.

### **UI State Pattern**
```kotlin
sealed class ClockUiState {
    object Loading : ClockUiState()
    data class Success(val data: ClockData) : ClockUiState()
    data class Error(val message: String) : ClockUiState()
}
```

### **Settings State Pattern**
```kotlin
data class ClockSettings(
    val windowSize: WindowSize = WindowSize.DEFAULT,
    val screenPosition: ScreenPosition = ScreenPosition.DEFAULT,
    val activeClockId: Long? = null,
    val musicEnabled: Boolean = false,
    val marathonEnabled: Boolean = false
)
```

## Coroutines and Threading

### **Scope Management**
- **ViewModels**: Use `viewModelScope` for UI-related operations
- **Repository**: Use `viewModelScope` from calling ViewModel
- **Service**: Use `serviceScope` for background operations
- **Database**: All operations on `Dispatchers.IO`

### **Threading Rules**
```kotlin
class TimeZoneRepository @Inject constructor(
    private val dao: ClockDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun saveClockData(clock: ClockStateEntity) = withContext(ioDispatcher) {
        dao.insertOrUpdate(clock)
    }
    
    fun observeClocks() = dao.getAllStates().flowOn(ioDispatcher)
}
```

## Service Architecture

### **Foreground Service Pattern**
```kotlin
@AndroidEntryPoint
class ClockOverlayService : Service() {
    @Inject lateinit var timeZoneRepository: TimeZoneRepository
    
    // Service manages overlay window lifecycle
    // Repository handles all data operations
}
```

**Service Responsibilities:**
- Overlay window management
- System permission handling
- Alarm scheduling and execution
- Inter-component communication

## Data Flow Architecture

### **Unidirectional Data Flow**
```
User Input → ViewModel → Repository → Database/Service
          ← StateFlow ← StateFlow ← Flow/LiveData
```

### **Communication Patterns**
- **Activity ↔ Service**: Bound service with IBinder interface
- **ViewModel ↔ Repository**: Direct injection, coroutine-based
- **Repository ↔ Database**: Room DAO with Flow/suspend functions
- **Service ↔ Audio**: Direct AudioManager integration

## Window Management

### **Overlay Window Architecture**
- Service creates and manages overlay window
- WindowManager.LayoutParams for positioning
- Touch event handling through OnTouchListener
- State persistence through Repository

### **Window State Management**
```kotlin
data class WindowState(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val isFullScreen: Boolean = false,
    val isMinimized: Boolean = false
)
```

## Animation Architecture

### **Animation Management**
1. Core Animation Types
 1.1 Time Display Animations
  - Second Hand Movement (Analog Mode)
   - Smooth sweep option: Continuous rotation at 6°/second
   - Tick option: Discrete 6° jumps every second
   - Requirement: User-configurable per instance
  - Digit Transitions (Digital Mode)
   - Fade in/out for changing digits
   - Optional flip animation for retro-style display
   - Requirement: Smooth transitions without text jumping

 1.2 Mode Transition Animations
    - Digital ↔ Analog Switching
   - Duration: 300-500ms
   - Type: Crossfade with optional rotation
   - Requirement: No jarring visual artifacts during transition

 1.3 Window State Animations
  - Nest Mode Transitions
   - Scale down animation: 500ms ease-in-out
   - Position animation to stack location
   - Requirement: Synchronized movement for multiple clocks
  - Window Resize Animations
   - Smooth scaling during pinch gestures
   - Requirement: Real-time response without lag

 1.4 Interactive Animations
  - Hand Dragging Feedback (Analog Mode)
   - Visual highlight on touched hand
   - Smooth rotation following touch
   - Snap-to-position on release
  - Button Press Feedback
   - Scale/opacity change on touch
   - Ripple effect for Material Design compliance

2. Performance Requirements
 - 2.1 Frame Rate Targets
  - Minimum 30 FPS for all animations
  - Target 60 FPS for time-critical animations (second hand, digit changes)
  - Degradation: Graceful fallback for multiple active instances

 - 2.2 Resource Management
  - Memory Budget: Max 10MB per clock instance for animation resources
  - CPU Usage: Animation thread should not exceed 5% CPU per instance
  - Battery Impact: Implement frame skipping when device is in power-saving mode

 - 2.3 Multi-Instance Coordination
  - Shared Animation Thread: Single animator for all clock instances
  - Priority System:
   - Foreground/focused clocks: Full animation quality
   - Background/nested clocks: Reduced animation frequency
   - Off-screen clocks: Animation paused

3. Technical Implementation Requirements
 - 3.1 Animation Framework
  - Primary: Android Animator API for property animations
  - Secondary: Custom Canvas drawing for analog clock hands
  - Constraint: Must work with WindowManager overlay system

 - 3.2 Synchronization
  - Time Sync: All animations must sync with the shared time ticker
  - Frame Alignment: Coordinate updates to prevent screen tearing
  - State Consistency: Animation state must persist through configuration changes

 - 3.3 Lifecycle Management
```kotlin
interface AnimationLifecycle {
    fun onAnimationStart()
    fun onAnimationPause()
    fun onAnimationResume()
    fun onAnimationStop()
    fun onAnimationDestroy()
}
```

4. Special Animation Requirements
 - 4.1 Alarm Animations
  - Alarm Trigger: Pulsing glow effect around clock
  - Snooze Indicator: Subtle breathing animation
  - Dismissal: Particle burst or fade effect

 - 4.2 Timezone Transitions
  - Globe Rotation: Smooth interpolation between timezone positions
  - Overlay Morphing: Animated transition between timezone highlights
  - City Pin Animations: Pop-in effect for city markers

 - 4.3 Error State Animations
  - Connection Lost: Gentle shake animation
  - Invalid Time: Red pulse on affected elements
  - Permission Denied: Bounce-back effect

## Error Handling

### **Repository Error Handling**
```kotlin
sealed class ClockResult<T> {
    data class Success<T>(val data: T) : ClockResult<T>()
    data class Error<T>(val exception: Exception) : ClockResult<T>()
}
```

### **Error Propagation Strategy**
- Repository catches and wraps exceptions
- ViewModel converts to UI-appropriate error states
- Service logs critical errors and attempts recovery
- User-facing errors show in Snackbars (per code style guide)

## Testing Architecture

### **Testing Layers**
- **Unit Tests**: Repository, ViewModel logic
- **Integration Tests**: Database operations, Service functionality
- **UI Tests**: Activity interactions, overlay behavior
- **Accessibility Tests**: Keyboard navigation, screen reader support

### **Dependency Injection for Testing**
```kotlin
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule
```

## Configuration Management

### **Build Variants**
- `debug`: Development with extensive logging
- `release`: Production with minimal logging
- Shared configuration through `BuildConfig`

### **Manifest Requirements**
```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" /> <!-- Used for streaming music URLs only -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<application android:supportsRtl="true">
    <service android:name=".clock.ClockOverlayService"
             android:foregroundServiceType="specialUse">
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="persistent_classroom_overlay" />
    </service>
</application>
```

**Foreground-service type (Android 14/15):** The overlay services (`ClockOverlayService`, `TimerService`, `StopwatchService`) use `foregroundServiceType="specialUse"` (permission `FOREGROUND_SERVICE_SPECIAL_USE`) with a declared `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Do **not** revert them to `dataSync`:
- On **Android 14 (API 34)** the system enforces a match between the declared FGS type and the granted permission, and `dataSync` is intended for finite sync work.
- On **Android 15 (API 35)** `dataSync` foreground services are subject to a cumulative **~6-hour-per-24h runtime cap**, after which the system invokes `onTimeout()` and stops the service. Roughly half of PurramidTime's install base runs Android 15, so an all-day classroom clock/timer would be force-stopped mid-lesson.

`specialUse` has no such runtime cap and honestly describes a persistent, user-visible overlay. The services call the two-arg `startForeground(id, notification)`, so the type is taken from the manifest — there is no `startForeground(..., type)` constant to keep in sync. When alarm/timer audio is actively playing, `mediaPlayback` may additionally be declared for that window if desired. Note: publishing with `specialUse` requires a short justification in the Play Console.

**Exact alarms across the split install base:** `USE_EXACT_ALARM` (API 33+) is auto-granted to alarm-clock apps and is the correct backing for the Clock alarm feature. `SCHEDULE_EXACT_ALARM` is user-revocable on Android 13; before scheduling, check `AlarmManager.canScheduleExactAlarms()` and degrade gracefully (in-app rationale + `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`) rather than crashing.

PurramidTime has no microphone or noise-monitoring features. Do not add `RECORD_AUDIO` — see [purramid_audio_requirements.md](purramid_audio_requirements.md).

## Performance Requirements

### **Memory Management**
- Maximum 100MB RAM usage
- Efficient bitmap handling for graphics
- Proper lifecycle cleanup for animations
- Service memory monitoring

### **Responsiveness**
- UI interactions: < 16ms response time
- Database operations: < 100ms for simple queries
- Animation frame rate: 60 FPS target, 30 FPS minimum

## Security Considerations

### **Data Protection**
- No sensitive user data collection
- Local storage only (no cloud sync); INTERNET permission is used exclusively for streaming music URLs
- Overlay permission usage limited to app functionality

### **Permission Handling**
- Request permissions on first use
- Graceful degradation when permissions denied
- Clear permission rationale to users
- Regular permission state validation
