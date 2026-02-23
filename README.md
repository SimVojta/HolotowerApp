# HoloTowerApp

Unofficial Android client for browsing Holotower.

## Features
- Catalog view with search, sort, and filters
- Thread view with:
  - tappable `>>` references
  - minithread popup context
  - media gallery
  - in-thread refresh at bottom
- Media viewer:
  - images, GIF, WEBM, MP4 (supported by app flow)
  - swipe navigation
  - save media
- In-app reply/new-thread composer
- Global Entry screen (status + attach token form)
- Cloudflare-aware fetching flow (shared WebView session path)

## Navigation Quick Guide
- Catalog:
  - Tap thread card -> open a thread
  - Swipe right-to-left -> create a new thread
  - Tap logo -> open Global Entry
- Thread:
  - Swipe left-to-right -> back to catalog
  - Swipe right-to-left -> open gallery
  - Tap >> reply to open minithreads
  - Tap bottom refresh pill -> refresh thread
- Minithread:
  - Swipe left-to-right -> close
- Gallery:
  - Swipe left-to-right -> close
- Media viewer:
  - Swipe left-to-right -> close
  - Swipe up/down -> previous/next media
- Composer:
  - Swipe left-to-right -> close

## Build
### Requirements
- Android Studio (recent stable)
- Android SDK configured
- JDK (Android Studio bundled JBR is fine)

### Debug build
```bash
./gradlew :app:assembleDebug
```

## Open Source / Legal Notes
- This is an **unofficial** client.
- Holotower, Hololive, and related names/assets are property of their respective owners.
- Users are responsible for following board rules and terms of service.

## Project Structure
- `app/src/main/java/com/holotower/app/ui/catalog` - catalog UI
- `app/src/main/java/com/holotower/app/ui/thread` - thread, gallery, media viewer, composer
- `app/src/main/java/com/holotower/app/ui/globalentry` - Global Entry UI
- `app/src/main/java/com/holotower/app/data` - models, repository, network helpers
- `app/src/main/java/com/holotower/app/navigation` - app navigation graph

## License
This project is licensed under the Apache License 2.0. See `LICENSE`.