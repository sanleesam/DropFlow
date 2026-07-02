# DropFlow Specification

## Goal

DropFlow is a local-first file transfer application designed to make transferring files between macOS and Android feel as effortless as AirDrop while keeping the user experience simple and requiring as few interactions as possible.

## Design Philosophy

DropFlow follows these principles:

- Local-first. Files never leave the local network.
- Simplicity over complexity.
- Fast transfers with minimal user interaction.
- Designed for one user and one trusted Android device.
- No cloud services or user accounts.
- Native platform features should be used whenever possible.

## Supported Platforms

### Version 0.1

Desktop:
- macOS (Apple Silicon)

Mobile:
- Android 12+

Other operating systems are out of scope for Version 0.1.

## User Experience

The file transfer process should require as few interactions as possible.

### Mac → Android

1. Open DropFlow.
2. Drag one or more files into the window.
3. Redmi receives a notification.
4. User taps Accept.
5. Transfer begins.
6. User receives a completion notification.

### Android → Mac

1. Select files.
2. Tap Share.
3. Choose DropFlow.
4. Mac receives a notification.
5. User clicks Save.
6. Transfer begins.

## Desktop UI

Framework:
- React
- TypeScript
- Tailwind CSS