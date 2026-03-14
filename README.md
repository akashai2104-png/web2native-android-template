# Native App AI - Android WebView Template (v1.3)

This is a template Android project used by Web2Native to generate Android apps from websites.

## How it works

Codemagic CI/CD builds this project with custom environment variables:

| Variable | Description |
|----------|-------------|
| `CM_APP_NAME` | Display name of the app |
| `CM_APP_ID` | Android package ID (e.g., `com.example.myapp`) |
| `CM_WEBSITE_URL` | The website URL to wrap in a WebView |
| `CM_SPLASH_TEXT` | Optional splash screen text |
| `CM_ICON_URL` | URL to download custom app icon from |

## Features

- Full-screen WebView with JavaScript enabled
- Swipe-to-refresh
- Progress bar during page load
- Back button navigation within WebView
- External links open in browser
- Optional splash screen with custom text
- Custom app icon support
- Both APK and AAB output

## Setup

1. Push this repo to GitHub
2. Builds are triggered via GitHub Actions workflow_dispatch with custom environment variables
3. Artifacts (APK/AAB) are uploaded as GitHub Actions artifacts
