# Smart Classifier

On-device image classification app — ML Kit + Jetpack Compose + Clean Architecture + Hilt + Coroutines/Flow.

## Setup
1. Open in Android Studio (Hedgehog or newer).
2. Let Gradle sync (downloads ML Kit, Compose, Hilt, CameraX, Coil).
3. Run on device/emulator with Play Services.
4. Tap "Pick Image" → select a photo → labels + confidence % appear.

## Architecture
- `domain/` — model, repository interface, use case (pure Kotlin, no Android deps)
- `data/` — ML Kit-backed repository implementation
- `presentation/` — ViewModel (StateFlow) + Compose screen
- `di/` — Hilt bindings

## Next steps to extend for portfolio
- Add CameraX live capture instead of gallery-only picking
- Swap default ML Kit labeler for a custom TensorFlow Lite model
- Add Room to save classification history
- Add unit tests for `ClassifyImageUseCase` + `ClassifierViewModel` using `runTest`/Turbine
