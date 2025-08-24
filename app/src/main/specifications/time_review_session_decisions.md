# PurramidTime - Implementation Decisions

## Deviations from Specifications
- **Reviewed**: 14 June 2025
- **Architecture**: Instance management
- **Key Decisions**:
  - Do not use AtomicInteger for instance numbering
  - Use thepurramid.instance.InstanceManager.kt

- **Reviewed**: 15 June 2025
- **Architecture**: Service settings Window Implementation
- **Key Decisions**:
  - Settings should open at the center of the screen
  - Use an explosion animation
  - Future efforts may revise this implemenation to have settings open from the settings button in the app-intent window
 
- **Reviewed**: 20 June 2025
- **Architecture**: HiltViewModelFactory is incompatible with LifecycleService
- **Key Decisions**:
  - Use the standard ViewModelProvider with just the factory
  - Use a unique key for each ViewModel instance
  - Add an "initialize()" method to set the instance ID after creation
  - Remove the HiltViewModelFactory usage
 
- **Reviewed**: 18 August 2025
- **Architecture**: TimerService, TimerActivity, Room Database
- **Key Decisions**:
  - Add an icon to the bottom right of the timer layout
  - Use the ic_lap.xml vector image
  - Following new specifications for pre-set time
  - Saved pre-sets are universal and persist across sessions

- **Reviewed**: 20 August 2025
- **Architecture**: Inactive Iconography
- **Key Decisions**:
  - To demonstrate an inactive button, add alpha = 0.5f for disabled state

  
  
  
  


- **Reviewed**: [Date]
- **Architecture**: [Relevant file]
- **Key Decisions**:
  - 
	- 
