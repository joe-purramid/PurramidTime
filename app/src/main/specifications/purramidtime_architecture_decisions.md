# Purramid Pets - Technical Architecture Decisions

## Overview
This document defines the mandatory architectural patterns and implementation approaches for the Purramid Pets classroom management application.

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
class PurrPetsActivity : AppCompatActivity()
```

**Injection Strategy:**
- Repository instances: `@Singleton`
- ViewModel instances: `@ViewModelScoped`
- Database: `@Singleton`
- Audio manager: `@Singleton`
- Service: System-managed singleton

## State Management

### **UI State Pattern**
```kotlin
sealed class PurrPetsUiState {
    object Loading : PurrPetsUiState()
    data class Success(val data: PurrPetsData) : PurrPetsUiState()
    data class Error(val message: String) : PurrPetsUiState()
}
```

### **Settings State Pattern**
```kotlin
data class PurrPetsSettings(
    val windowSize: WindowSize = WindowSize.DEFAULT,
    val screenPosition: ScreenPosition = ScreenPosition.DEFAULT,
    val activePetId: Long? = null,
    val mode: PetMode = PetMode.REWARDS,
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
class PurrPetsRepository @Inject constructor(
    private val dao: PurrPetsDao,
    private val audioManager: AudioManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun savePetData(pet: Pet) = withContext(ioDispatcher) {
        dao.insertPet(pet)
    }
    
    fun observePets() = dao.getAllPets().flowOn(ioDispatcher)
}
```

## Service Architecture

### **Foreground Service Pattern**
```kotlin
@AndroidEntryPoint
class PurrPetsService : Service() {
    @Inject lateinit var repository: PurrPetsRepository
    @Inject lateinit var audioManager: AudioManager
    
    // Service manages overlay window lifecycle
    // Repository handles all data operations
    // AudioManager handles microphone operations
}
```

**Service Responsibilities:**
- Overlay window management
- System permission handling
- Alarm scheduling and execution
- Background audio monitoring (Responsive Mode)
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
- Lottie animations for pet behaviors
- Vector Drawable animations for UI elements
- AnimationDrawable for simple frame animations
- Custom AnimationManager for pet state transitions

### **Performance Requirements**
- Maximum 60 FPS for all animations
- Memory-efficient animation caching
- Lazy loading for non-active pet animations
- Background animation cleanup

## Error Handling

### **Repository Error Handling**
```kotlin
sealed class PurrPetsResult<T> {
    data class Success<T>(val data: T) : PurrPetsResult<T>()
    data class Error<T>(val exception: Exception) : PurrPetsResult<T>()
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
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<application android:supportsRtl="true">
    <service android:name=".service.PurrPetsService" 
             android:foregroundServiceType="specialUse" />
</application>
```

## Performance Requirements

### **Memory Management**
- Maximum 100MB RAM usage
- Efficient bitmap handling for pet graphics
- Proper lifecycle cleanup for animations
- Service memory monitoring

### **Responsiveness**
- UI interactions: < 16ms response time
- Database operations: < 100ms for simple queries
- Audio detection: 2-second polling interval
- Animation frame rate: 60 FPS target, 30 FPS minimum

## Security Considerations

### **Data Protection**
- No sensitive user data collection
- Local storage only (no cloud sync)
- Audio data processed locally, never stored
- Overlay permission usage limited to app functionality

### **Permission Handling**
- Request permissions on first use
- Graceful degradation when permissions denied
- Clear permission rationale to users
- Regular permission state validation