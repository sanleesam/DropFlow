# DropFlow Architecture

## Overview

DropFlow consists of two native applications:

- macOS Desktop Application
- Android Mobile Application

The applications communicate directly over the local network without any cloud services.

Both applications are independent and communicate using a lightweight local network protocol.

## System Architecture

Mac Application
│
├── User Interface
├── Device Discovery
├── Transfer Manager
├── Notification Manager
└── Local File Access
        │
        │ Local Network
        ▼
Android Application
│
├── User Interface
├── Device Discovery
├── Transfer Receiver
├── Notification Manager
└── Downloads Storage

