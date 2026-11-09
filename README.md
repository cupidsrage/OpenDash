# OpenDash

Turns Android Auto into a full Android screen, like an OttoAibox, using just your phone.
Open any app on the car display, control it by touch, and use voice for apps, navigation and music.

## How it works

Requires **Android 15 or newer**.

- Android Auto on Android 15+ can show a normal app screen on the car display. OpenDash's
  car screen is one of those: an Android-Auto-style launcher (rail on the left, Home,
  All apps, Phone screen, mic).
- Picking an app opens it on the phone, and the phone's screen is mirrored onto the car display.
- Touches on the car screen are recorded and replayed on the phone through an Accessibility
  service, including swipes, long presses and two-finger pinch.
- While connected, the phone is held in landscape so apps fill the wide car screen.
- When the car disconnects, sharing stops and the phone goes back to normal.

Android Auto 17.9+ ignores sideloaded Car App Library ("template") apps even with Unknown
sources on, which is why OpenDash doesn't use that library.

## Build the APK

**Option A: GitHub (no Android Studio needed)**
1. Create a new repo on GitHub and push this folder to it.
2. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push (or press *Run workflow*).
3. When it finishes, download the **OpenDash-apk** artifact, unzip it, and install `app-debug.apk` on your phone.

**Option B: Android Studio**
Open this folder, let Gradle sync, then *Build → Build APK(s)*.

## How apps open on the car

1. **Directly in Android Auto**: the app runs on the car display itself.
2. **OpenDash's own screen (Shizuku)**: for apps Android Auto refuses. OpenDash creates a
   hidden, always-unlocked screen sized to the car display and runs the app there, with live
   touch. The phone can stay locked with its screen off, and there's no screen-share prompt.
3. **Phone mirroring**: last resort if Shizuku isn't running. Needs the phone screen on.

Long-press the phone button in the car menu to see the phone's own screen instead.

### Setting up Shizuku
Install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api),
turn on Developer options, then in Shizuku tap **Start** under *Start via Wireless debugging*
and follow the pairing steps. Open OpenDash and tap **Allow** when asked. After a phone restart,
open Shizuku and tap Start again (needs Wi-Fi).

## Automatic updates

Every push to `main` builds a new APK and publishes it as a GitHub release (`v<build number>`).
The app checks for a newer release every 3 hours, when you open it, and a couple of minutes
after you disconnect from the car. It never installs while connected to the car.

- The first update shows a one-tap **Update** prompt. After that, Android 12+ installs
  updates silently, because OpenDash is then the app that installed itself.
- The repo must be **public** so the phone can read the releases.
- Builds are signed with a key stored only in repo secrets (`KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Without them the workflow builds a test
  APK but publishes no release. Never commit the key.

## One-time phone setup

Open OpenDash on your phone and work down the checklist:

1. **Display over other apps**: lets it launch apps from the car and hold landscape.
2. **Accessibility → OpenDash → On**: makes car-screen touches control the phone.
3. **Microphone, location, notifications**.
4. **Android Auto developer mode → Unknown sources**: Android Auto settings → tap *Version*
   ~10 times → ⋮ → *Developer settings* → *Unknown sources* on. Then *Customize launcher* and
   make sure OpenDash is checked. Sideloaded apps don't appear in Android Auto without this.
5. **Smart Lock / Extend Unlock** with the car's Bluetooth, so the phone stays unlocked.

## Starting automatically

Android Auto itself starts when the phone connects (wired or wireless). It reopens the
last app you used, so leave OpenDash open when you park and it comes back up next drive.
OpenDash asks for screen sharing as soon as it opens. Android requires you to tap
**Start now** on the phone once per drive; no app can skip that prompt.

## Voice

Tap the blue mic on the car screen:
- "open Spotify", "launch YouTube Music"
- "navigate to Walmart", "take me to 123 Main St"
- "play Zach Bryan"
- "home", "apps", "back"

## Safety

"Pause video apps while the car is moving" is on by default. It covers known video apps
(YouTube, Netflix, Disney+, etc.) when GPS speed is above ~10 mph. You can turn it off on the phone.

## Known limits

- Android Auto updates have broken this kind of app before; if it stops appearing, check
  that Unknown sources is still on.
- Pinch-zoom isn't passed through yet (tap, long scrolls and flings are).
- Some apps that block screen capture (banking, some DRM video) show black.
