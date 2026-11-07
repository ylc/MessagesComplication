# Messages Complication

A Wear OS complication that shows how many unread Google Messages notifications are on your watch. It was built for and tested on the Pixel Watch.

## Features

- Supports the `SHORT_TEXT` and `LONG_TEXT` complication types and shows a chat icon.
- Tapping the complication opens Google Messages.
- Two counting modes, switchable in the app:
  - **Total Messages**: every unread message across all threads.
  - **Conversations**: unread conversations only.
- Counts both kinds of notifications:
  - Ones the watch posts itself (standalone on LTE or Wi-Fi).
  - Ones bridged from your phone.

## How it works

- `MessagesNotificationListener` is a `NotificationListenerService`. It counts the local Google Messages notifications.
- Notifications bridged from the phone don't appear in the local `NotificationManager`. To count those, the listener reads the Wear OS SysUI `NotificationTrayDataStore` through a dumpsys call, which needs the `DUMP` permission.
- `UnreadMessagesComplicationService` provides the stored count to the watch face.

## Install (sideload)

Requirements: Android Studio, and a watch with developer options and ADB debugging enabled.

1. Build and install the app on your watch from Android Studio.
2. Grant the DUMP and PACKAGE_USAGE_STATS permissions, which are needed to count notifications bridged from your phone:

   ```sh
   adb shell pm grant io.github.ylc.messagescomplication android.permission.DUMP
   adb shell pm grant io.github.ylc.messagescomplication android.permission.PACKAGE_USAGE_STATS
   ```

3. Open the app on the watch and tap **Grant Notification Access**.
4. Long-press your watch face, then add the **Unread Messages** complication to a slot.

Without step 2, the complication only counts notifications that the watch posts itself.

## Building a signed release

1. Create an upload key. Keep this file and its passwords safe, and never commit them:

   ```sh
   keytool -genkeypair -v -keystore upload-keystore.jks -alias upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Copy `keystore.properties.example` to `keystore.properties` and fill in your values. This file is gitignored.
3. Build the app bundle to upload to Google Play:

   ```sh
   ./gradlew bundleRelease
   ```

   The output is written to `app/build/outputs/bundle/release/app-release.aab`.

## Privacy

The app has no internet permission and no data leaves the watch. See the [privacy policy](docs/privacy-policy.md).

## License

[Apache 2.0](LICENSE)
