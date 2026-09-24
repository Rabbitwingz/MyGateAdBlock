# Gate Ad Skipper

Closes the full-screen ad that MyGate (`com.mygate.user`) opens after you approve or deny a visitor.

## How it works
An Android **Accessibility Service** that only receives events from MyGate. When a MyGate screen opens, it counts as an ad if:

1. it's a known ad-SDK screen (Google AdMob/Ad Manager `AdActivity`, Meta, AppLovin, InMobi, …), **or**
2. you marked that screen as an ad in the app ("learn mode"), **or**
3. it opens within 10 s of you tapping Approve/Deny/Leave at gate/etc. **and** it shows an "Ad"/"Sponsored" label or ad view ids. The approve/deny screen itself is never closed.

To close the ad it taps a Close/Skip/× button if it finds one. Otherwise it presses Back. It retries for about 5 s in case the ad has a countdown.

Nothing leaves the phone: no internet permission, no data collection.

## Build (GitHub Actions)
1. Create an empty **private** repo on GitHub and push this folder to it.
2. Open the repo's **Actions** tab → "Build APK" run → download the **GateAdSkipper-apk** artifact (a zip containing `app-release.apk`).

## Install
1. Copy the APK to the phone and open it. Allow "install unknown apps" for your file manager or browser when asked.
2. Open **Gate Ad Skipper** → *Open Accessibility settings* → *Gate Ad Skipper* → On.
   - Android 13+: if the switch is greyed out ("Restricted setting"), go to Settings → Apps → Gate Ad Skipper → ⋮ → **Allow restricted settings**, then turn it on.
3. Xiaomi/Oppo/Vivo/Realme/Samsung: set the app's battery usage to **Unrestricted** / **No restrictions**, and turn on Autostart if your phone has it, so the phone doesn't kill the service.

## If an ad still gets through
Open Gate Ad Skipper. The "Recent MyGate screens" list shows a 👆 *tapped: approve* entry, and the screen right after it is usually the ad. Tap that row → **Block**. From then on, that screen is closed as soon as it opens.
