# frozen_string_literal: true

# Release tooling shared by the iOS App Store, Android Google Play, and Android
# Firebase App Distribution pipelines (docs/ios-app-store-release.md,
# docs/android-play-store-release.md, docs/android-firebase-beta.md).
# Bundler finds this Gemfile from ios/EthosProtocol and android/, so both
# platforms use one pinned fastlane.
# Pinned to exact versions so CI and local runs resolve the same gems;
# bump deliberately and commit the regenerated Gemfile.lock.
source "https://rubygems.org"

gem "fastlane", "2.240.1"

# Firebase App Distribution support for Android beta testing (#462).
# Plugin source is rubygems.org (same source as fastlane itself).
gem "fastlane-plugin-firebase_app_distribution", "0.10.1"
