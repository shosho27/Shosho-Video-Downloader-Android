# Shosho Video Downloader

An Android app for downloading public videos from YouTube, X/Twitter,
Facebook, Instagram, TikTok, and LinkedIn — paste a link or share directly
from any of those apps, pick a quality, and save an MP4 straight to your
phone's Downloads folder.

Sideloaded only. Not on the Play Store.

Free, open source, no account, no ads, no tracking. This is a general-purpose
tool, the same category as yt-dlp, NewPipe, and Seal — it doesn't host or
index anyone's content. What you download and what you do with it is your
own call: respect copyright, respect the platforms' terms of service, and
use it responsibly.

## Installing

1. Download the `.apk` file to your phone.
2. Tap it. Android will say it's not allowed to install unknown apps from
   that source — tap **Settings** in that prompt, turn on **Allow from this
   source**, then go back and tap **Install**.
3. You may see a one-time "Android App Compatibility" warning. It's
   harmless — tap **OK**.
4. Open the app. Paste a link (or tap **Share** on a video in YouTube,
   TikTok, X, Instagram, or Facebook and pick this app), choose a quality,
   and tap **Download**.

No extra permissions to grant, nothing else to configure.

## Using it

- **Quality:** Best available, Up to 1080p, or Up to 720p.
- **Custom file name:** optional field — leave it blank and the app picks
  the video's title (falling back to its description or uploader if the
  title is empty), or type your own name to use instead.
- **Cancel:** stops a download in progress.
- **Share:** after a download finishes, tap Share to send the file to any
  app that takes video (Google Drive, Messages, etc.).
- **Folder:** jumps straight to the phone's Downloads folder.
- **Update Downloader:** pulls the latest yt-dlp release (checksum-verified)
  without needing a new APK. Use this if downloads start failing - sites
  change their player/challenge code often enough that the bundled copy
  can go stale between app releases.
- MKV downloads are automatically converted to MP4.

## How it works

Real `yt-dlp` running under a bundled Python 3.14 interpreter, with QuickJS
as the JavaScript engine for solving YouTube's player challenge when the
default client needs it, and a bundled `ffmpeg` for merging streams and
MKV→MP4 conversion. All three binaries are prebuilt Android ARM64 builds
extracted from [ytdlnis](https://github.com/deniscerri/ytdlnis)'s own
open-source release artifacts, bundled directly into this app's APK.

## Status

**Verified working, on a real device, for each of the six supported
sites:** YouTube, Facebook, X, Instagram, TikTok, LinkedIn. Covers the
default (no-sign-in) path and the QuickJS-solved fallback for YouTube's
player challenge.

**Known gaps / not built yet:**

- **No sign-in / cookies support.** Only public videos work. The Mac app
  can read cookies from a desktop browser for private/followers-only
  content; Android can't read another app's cookie jar the same way, so
  this would need its own cookies-file import flow. Not started.
- **No scheduled/background update check.** Update Downloader is manual
  only - there's no periodic check or notification when yt-dlp goes stale.
- **ffmpeg conversion isn't cancellable.** Cancel stops the yt-dlp download
  itself; if a download finished and MKV→MP4 conversion is already running,
  Cancel won't interrupt that (conversions are normally fast, so this
  hasn't come up in practice, but it's a real gap).
- **No automated tests.** Everything above was verified manually in this
  session, device-side, via adb. There's no test suite.

## iOS/iPadOS version

Discussed, not started - no code written, no repo created yet. Shane has
no iPhone; testing would happen on his iPads instead (same iOS platform
and constraints). This section is the plan as discussed, kept here so it
isn't lost between sessions.

**Technically possible**, and one piece is actually easier than Android:
iOS ships JavaScriptCore system-wide, so the YouTube-challenge-solver role
QuickJS plays here wouldn't need a separate bundled engine at all. The
rest of the engine (Python, yt-dlp, ffmpeg) would need to be embedded as
linked frameworks rather than exec'd as standalone binaries the way
Android's `ProcessBuilder` + `jniLibs` trick works - no equivalent
subprocess-exec model on iOS, so this part is a real rewrite, not a port.

**Distribution is the actual blocker, and the reason it's not the App
Store:** Apple's review consistently rejects apps that download video from
other platforms. Shane already has a paid Apple Developer Program
membership, so the real path is **ad-hoc distribution**: build an `.ipa`,
register each recipient's device UDID in the developer account (up to 100
devices/year), install directly via Xcode - no App Store review at all,
which sidesteps the policy entirely. TestFlight was considered and ruled
out as the default path: it's more convenient (email invite, up to 10,000
testers, no UDID collection) but goes through a lighter "Beta App Review"
that still risks rejecting a downloader-type app.

**Scope:** would be its own from-scratch project - separate repo, Swift,
Xcode - not a port of this codebase. The only things carried over are the
site rules/selector logic (same reasoning as `SiteRules.kt` here) and the
distribution lessons above.

## Rebuilding from source

Clone this repo - the bundled Python/yt-dlp/QuickJS/ffmpeg binaries are
already checked in under `app/src/main/jniLibs/` and `app/src/main/assets/`,
so no separate download or extraction step is needed for those.

Toolchain setup (macOS, Homebrew) - each of these is a one-line install,
nothing to keep backed up:

```
brew install openjdk@21
brew install --cask android-commandlinetools
brew install gradle

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Then from this directory: `gradle wrapper --gradle-version 8.13` (one time,
to recreate `gradlew`), then `./gradlew assembleDebug` to build.

**The one thing that can't be recreated** is the release signing keystore
(`keystore/release.keystore` + `signing.properties`, both gitignored on
purpose since they're the real signing credential). Android requires the
same signing key to ship an update to people who already installed a build,
so losing it means any future release needs a new keystore and everyone
who installed v0.1.0 has to uninstall/reinstall instead of updating in
place. Keep a copy of the `keystore/` folder and `signing.properties`
somewhere safe outside this Mac - that's the only genuinely irreplaceable
part of this project's local setup.

## License

This app's own source is in this repository. The bundled runtime binaries
(Python, ffmpeg, QuickJS) come from ytdlnis, licensed
[GPL-3.0](https://github.com/deniscerri/ytdlnis/blob/master/LICENSE) — ffmpeg
in that build includes libx264, which is itself GPL. Per GPL's
source-availability requirement, this repository is public so anyone who
receives the APK can get the corresponding source.
