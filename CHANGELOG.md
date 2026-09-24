# Changelog

Every change to the app, newest first, grouped by the kind of branch it was
merged from - the same grouping as the notes on each GitHub Release. The
commit behind each line explains why the change was made.

## [Unreleased]

## [0.4.5] - 2026-09-24

### New features

- Extract .rar, .7z and .tar archives, plain or compressed
- Offer to extract .rar, .7z and .tar files when they are tapped
- Say in the README which archives the app opens
- Build the native core for the app's minSdk, not API 21
- Give the Android link the libpthread UnRAR asks for

### Behind the scenes

- Add 0.4.4 and the archive work to the changelog

## [0.4.4] - 2026-09-24

### Fixes

- Keep a trashed folder listed when its original cannot all be removed
- Say a duplicate scan was cancelled instead of showing part of it
- Keep the trash where the next launch will look for it
- Stop a move into the folder it is already in from deleting it
- Open folders with a % or + in their name instead of crashing
- Open a .rar, .7z or .tar with another app instead of failing to extract it
- Leave no duplicate in the trash when a file could not be removed

### Behind the scenes

- Add a changelog covering every release so far

## [0.4.3] - 2026-09-23

### New features

- Install .xapk, .apks and .apkm bundles by tapping them

### Fixes

- Let tapping an .apk install it

## [0.4.2] - 2026-09-23

### New features

- Put Recent files back at the top of Home as one row

## [0.4.1] - 2026-09-23

### Fixes

- Remember categories across restarts and when left early
- Open Downloads like the other categories, from the top, cache first

### Other changes

- Create CODEOWNERS

## [0.4.0] - 2026-09-22

### Fixes

- Fix the ways a copy, a move, the trash or a cleanup could lose files
- Fix a crash on SD cards and the screens that lied or lost things
- Stop half-written archives and trashed files passing for real ones
- Stop browsing a server from breaking the transfer in progress
- Keep favourites on a removed SD card, and lists on the right folder

## [0.3.6] - 2026-09-18

### New features

- Put the reasoning into the release notes, grouped by what it was
- Show a category's last results while the new ones are found

## [0.3.5] - 2026-09-18

### Fixes

- Stop every opened category leaving its results behind

## [0.3.4] - 2026-09-18

### New features

- Say that a category is still scanning

## [0.3.3] - 2026-09-18

### Faster

- Keep the search results in Rust instead of shipping them to Kotlin

## [0.3.2] - 2026-09-18

### New features

- Put a changelog in every release

### Fixes

- Make the scans several times faster
- Stop a selection outliving what it pointed at
- Reach the category through a safe call, not a smart cast

## [0.3.1] - 2026-09-18

### New features

- Let the FTP server share a folder you choose

## [0.2.4] - 2026-09-18

### New features

- Add network storage and a built-in FTP server

### Fixes

- Tell R8 about the desktop-only classes the network libraries reach for
- Build the WebDAV test's server from something Android actually has
- Publish the release with gh, and check the APK actually attached

## [0.2.3] - 2026-09-15

### Fixes

- Show categories newest-first as they scan, and list encrypted archives
- Stop the SDK setup asking for a package Google has removed

## [0.2.2] - 2026-09-14

### New features

- Give each list its own layout, zip behind a password, stop lists jumping

### Fixes

- Sign with all three schemes and let the build prove the APK installs

## [0.2.1] - 2026-09-14

### New features

- Persist view settings, fix scroll and hidden favourites, watch folders

### Fixes

- Lower minSdk to 30, the version the app actually needs
- Fix the search view model's initialisation order and shared converters

## [0.2.0] - 2026-09-14

### New features

- Cache video thumbnails, hide the SD row without a slot, animate storage

### Fixes

- Back deselects everywhere, pin markers, favourite removal, readable gauge

## [0.1.14] - 2026-09-14

### New features

- Add hide, favourites, pin to top, audio art, and progressive storage

## [0.1.13] - 2026-09-14

### Fixes

- Fix blank error messages, lost scroll position, and five other issues

## [0.1.12] - 2026-09-14

### New features

- Theme setting, back navigation, extract prompt and four other fixes

## [0.1.11] - 2026-09-14

### New features

- Share folders by zipping them, and expand Details
- Share the files inside a folder rather than zipping it
- Show video and installer previews, and make largest files actionable

## [0.1.10] - 2026-09-14

### New features

- Add share, properties and real view modes; surface sort by name

## [0.1.9] - 2026-09-13

### New features

- Raise the keyboard on the search screen, and add an About screen
- Make select all reachable without first selecting something

### Fixes

- Fix deleting files, which never worked outside a plain rename
- Pass onAbout at the HomeOverflowMenu call site

## [0.1.8] - 2026-09-13

### Fixes

- Add selection to search results, create files, and drop the result cap
- Declare only the permission the app actually uses

## [0.1.7] - 2026-09-13

### Fixes

- Narrow search results in memory, and hide the scan from the user

## [0.1.6] - 2026-09-13

### New features

- Stream search results as they are found

## [0.1.5] - 2026-09-13

### Fixes

- Fix the white flash between screens, and make storage analysis fast

## [0.1.4] - 2026-09-13

### New features

- Rework the home screen to match the reference screenshot
- Make a re-run of a manual build use the latest commit

### Fixes

- Restore the functions my refactor silently deleted

## [0.1.3] - 2026-09-12

### New features

- Rebuild the UI in the One UI design language

### Fixes

- Publish a release from manual runs, and say what each run produced

## [0.1.2] - 2026-09-12

### Fixes

- Do not reinstall cargo-ndk when the cache already provides it

## [0.1.1] - 2026-09-12

### Fixes

- Fail loudly when a tag is pushed without signing secrets

## [0.1.0] - 2026-09-12

### New features

- Add Kotlin + Rust file manager scaffold
- Add GitHub Actions release workflow

### Fixes

- Fix generated UniFFI bindings being invisible to the Kotlin compiler
- Fix three bugs found by reproducing the build locally
- Generate bindings from a host build, not the Android .so
- Never produce an unsigned APK

### Other changes

- Initial commit

[Unreleased]: https://github.com/techhonar/File-Manager/compare/v0.4.5...HEAD
[0.4.5]: https://github.com/techhonar/File-Manager/compare/v0.4.4...v0.4.5
[0.4.4]: https://github.com/techhonar/File-Manager/compare/v0.4.3...v0.4.4
[0.4.3]: https://github.com/techhonar/File-Manager/compare/v0.4.2...v0.4.3
[0.4.2]: https://github.com/techhonar/File-Manager/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/techhonar/File-Manager/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/techhonar/File-Manager/compare/v0.3.6...v0.4.0
[0.3.6]: https://github.com/techhonar/File-Manager/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/techhonar/File-Manager/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/techhonar/File-Manager/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/techhonar/File-Manager/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/techhonar/File-Manager/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/techhonar/File-Manager/compare/v0.2.4...v0.3.1
[0.2.4]: https://github.com/techhonar/File-Manager/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/techhonar/File-Manager/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/techhonar/File-Manager/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/techhonar/File-Manager/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/techhonar/File-Manager/compare/v0.1.14...v0.2.0
[0.1.14]: https://github.com/techhonar/File-Manager/compare/v0.1.13...v0.1.14
[0.1.13]: https://github.com/techhonar/File-Manager/compare/v0.1.12...v0.1.13
[0.1.12]: https://github.com/techhonar/File-Manager/compare/v0.1.11...v0.1.12
[0.1.11]: https://github.com/techhonar/File-Manager/compare/v0.1.10...v0.1.11
[0.1.10]: https://github.com/techhonar/File-Manager/compare/v0.1.9...v0.1.10
[0.1.9]: https://github.com/techhonar/File-Manager/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/techhonar/File-Manager/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/techhonar/File-Manager/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/techhonar/File-Manager/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/techhonar/File-Manager/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/techhonar/File-Manager/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/techhonar/File-Manager/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/techhonar/File-Manager/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/techhonar/File-Manager/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/techhonar/File-Manager/releases/tag/v0.1.0
