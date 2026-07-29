import {
  createInitialSessionStore,
  startSendSession,
  applyProgressEvent,
  applyCompletionEvent,
  applyFailureEvent,
  dismissActiveSession,
} from "./transferSessionManager.ts";

function assertEqual(actual: any, expected: any, message: string) {
  const actualStr = JSON.stringify(actual);
  const expectedStr = JSON.stringify(expected);
  if (actualStr !== expectedStr) {
    throw new Error(`[FAIL] ${message}\nExpected: ${expectedStr}\nActual:   ${actualStr}`);
  }
}

function assertTrue(condition: boolean, message: string) {
  if (!condition) {
    throw new Error(`[FAIL] ${message}`);
  }
}

export function runLifecycleTests() {
  console.log("Running Transfer Session Lifecycle Tests (10 Scenarios)...");

  // ── Scenario 1: create → progress → complete ────────────────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-1", "Windows PC", "photo.png", 1);
    assertTrue(store.activeTransfers["tx-1"].progress === 0, "S1: Initial progress 0%");

    store = applyProgressEvent(store, {
      sessionId: "tx-1",
      currentFileIndex: 0,
      currentFileName: "photo.png",
      percentage: 50,
      speedBytesPerSec: 10485760,
      sessionBytesSent: 500000,
      sessionTotalBytes: 1000000,
    });
    assertTrue(store.activeTransfers["tx-1"].progress === 50, "S1: Progress updated to 50%");
    assertTrue(store.activeTransfers["tx-1"].status === "Sending...", "S1: Status is Sending...");

    store = applyCompletionEvent(store, {
      sessionId: "tx-1",
      fileName: "photo.png",
      deviceName: "Windows PC",
      size: "1.0 MB",
      direction: "send",
    });
    assertTrue(store.activeTransfers["tx-1"].progress === 100, "S1: Completed progress 100%");
    assertTrue(store.activeTransfers["tx-1"].status === "Completed", "S1: Completed status");
    assertEqual(store.recentTransfers.length, 1, "S1: Exactly 1 recent transfer added");
  }

  // ── Scenario 2: create → complete with no progress event ─────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-2", "MacBook", "document.pdf", 1);
    store = applyCompletionEvent(store, {
      sessionId: "tx-2",
      fileName: "document.pdf",
      deviceName: "MacBook",
      size: "2.5 MB",
    });
    assertTrue(store.activeTransfers["tx-2"].progress === 100, "S2: Progress jumps to 100%");
    assertEqual(store.recentTransfers.length, 1, "S2: Exactly 1 recent transfer added");
  }

  // ── Scenario 3: completion before invoke resolves ───────────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-3", "Linux Desktop", "archive.zip", 1);
    
    // Event fires first
    store = applyCompletionEvent(store, {
      sessionId: "tx-3",
      fileName: "archive.zip",
      deviceName: "Linux Desktop",
      size: "10.0 MB",
    });
    
    // invoke() resolution fallback fires second
    store = applyCompletionEvent(store, {
      sessionId: "tx-3",
      fileName: "archive.zip",
      deviceName: "Linux Desktop",
      size: "10.0 MB",
    });
    assertEqual(store.recentTransfers.length, 1, "S3: Idempotent: 1 recent transfer despite dual completion triggers");
  }

  // ── Scenario 4: invoke resolves before completion event ─────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-4", "Tablet", "notes.txt", 1);

    // invoke() resolution fallback fires first
    store = applyCompletionEvent(store, {
      sessionId: "tx-4",
      fileName: "notes.txt",
      deviceName: "Tablet",
      size: "12 KB",
    });

    // Event fires second
    store = applyCompletionEvent(store, {
      sessionId: "tx-4",
      fileName: "notes.txt",
      deviceName: "Tablet",
      size: "12 KB",
    });
    assertEqual(store.recentTransfers.length, 1, "S4: Idempotent: 1 recent transfer when invoke resolves first");
  }

  // ── Scenario 5: duplicate completion ───────────────────────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-5", "Phone", "video.mp4", 1);
    store = applyCompletionEvent(store, { sessionId: "tx-5", fileName: "video.mp4" });
    store = applyCompletionEvent(store, { sessionId: "tx-5", fileName: "video.mp4" });
    store = applyCompletionEvent(store, { sessionId: "tx-5", fileName: "video.mp4" });
    assertEqual(store.recentTransfers.length, 1, "S5: Duplicate completions ignored cleanly");
  }

  // ── Scenario 6: failure ──────────────────────────────────────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-6", "Desktop", "data.db", 1);
    store = applyFailureEvent(store, { sessionId: "tx-6", error: "Connection reset by peer" });
    assertTrue(store.activeTransfers["tx-6"].status === "Failed", "S6: Status set to Failed");
    assertTrue(store.activeTransfers["tx-6"].error === "Connection reset by peer", "S6: Error message preserved");
    assertEqual(store.recentTransfers.length, 0, "S6: No recent transfer added on failure");
  }

  // ── Scenario 7: cancellation ────────────────────────────────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-7", "Laptop", "iso_image.iso", 1);
    store = applyFailureEvent(store, { sessionId: "tx-7", error: "Transfer cancelled by user" });
    assertTrue(store.activeTransfers["tx-7"].status === "Failed", "S7: Cancelled transfer sets Failed status");
    assertEqual(store.recentTransfers.length, 0, "S7: No history entry created for cancelled transfer");

    store = dismissActiveSession(store, "tx-7");
    assertTrue(store.activeTransfers["tx-7"] === undefined, "S7: Dismiss removes session cleanly");
  }

  // ── Scenario 8: two concurrent sessions ────────────────────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-8A", "Device A", "fileA.txt", 1);
    store = startSendSession(store, "tx-8B", "Device B", "fileB.txt", 1);

    store = applyProgressEvent(store, { sessionId: "tx-8A", percentage: 40 });
    store = applyProgressEvent(store, { sessionId: "tx-8B", percentage: 80 });

    assertTrue(store.activeTransfers["tx-8A"].progress === 40, "S8: Session A progress 40%");
    assertTrue(store.activeTransfers["tx-8B"].progress === 80, "S8: Session B progress 80%");

    store = applyCompletionEvent(store, { sessionId: "tx-8A", fileName: "fileA.txt" });
    assertTrue(store.activeTransfers["tx-8A"].status === "Completed", "S8: Session A completed");
    assertTrue(store.activeTransfers["tx-8B"].status === "Sending...", "S8: Session B still sending");
    assertEqual(store.recentTransfers.length, 1, "S8: 1 recent transfer for A");
  }

  // ── Scenario 9: fast tiny transfer ─────────────────────────────────────────
  {
    let store = createInitialSessionStore();
    // Receiving tiny transfer directly via event before local state initialization
    store = applyProgressEvent(store, {
      sessionId: "tx-9",
      currentFileName: "small.png",
      percentage: 100,
      totalFiles: 1,
    });
    store = applyCompletionEvent(store, {
      sessionId: "tx-9",
      fileName: "small.png",
      direction: "receive",
    });
    assertTrue(store.activeTransfers["tx-9"].progress === 100, "S9: Fast receive transfer 100%");
    assertEqual(store.recentTransfers.length, 1, "S9: 1 recent transfer added for tiny receive");
  }

  // ── Scenario 10: multi-file transfer ────────────────────────────────────────
  {
    let store = createInitialSessionStore();
    store = startSendSession(store, "tx-10", "MacBook", "image.jpg (+1 other file)", 2);

    store = applyProgressEvent(store, {
      sessionId: "tx-10",
      currentFileIndex: 0,
      currentFileName: "image.jpg",
      totalFiles: 2,
      percentage: 25,
    });
    assertTrue(store.activeTransfers["tx-10"].currentFileLabel === "[1/2] image.jpg", "S10: Per-file label [1/2]");

    store = applyProgressEvent(store, {
      sessionId: "tx-10",
      currentFileIndex: 1,
      currentFileName: "video.mp4",
      totalFiles: 2,
      percentage: 75,
    });
    assertTrue(store.activeTransfers["tx-10"].currentFileLabel === "[2/2] video.mp4", "S10: Per-file label [2/2]");

    store = applyCompletionEvent(store, {
      sessionId: "tx-10",
      fileName: "image.jpg",
      totalFiles: 2,
      files: [
        { fileIndex: 0, relativePath: "image.jpg", sizeBytes: 1000, finalPath: "/Users/test/Downloads/DropFlow/image.jpg" },
        { fileIndex: 1, relativePath: "video.mp4", sizeBytes: 5000, finalPath: "/Users/test/Downloads/DropFlow/video.mp4" },
      ],
      receiveDir: "/Users/test/Downloads/DropFlow",
    });

    assertTrue(store.recentTransfers[0].fileName === "image.jpg (+1 other file)", "S10: Formatted multi-file summary name");
    assertEqual(store.recentTransfers[0].files?.length, 2, "S10: Preserves 2 completed file entries in recentTransfers");
    assertEqual(store.recentTransfers[0].files?.[0].finalPath, "/Users/test/Downloads/DropFlow/image.jpg", "S10: Preserves finalPath for file 1");
    assertEqual(store.recentTransfers[0].files?.[1].finalPath, "/Users/test/Downloads/DropFlow/video.mp4", "S10: Preserves finalPath for file 2");
    assertEqual(store.activeTransfers["tx-10"].receiveDir, "/Users/test/Downloads/DropFlow", "S10: Preserves receiveDir");
  }

  // ── Scenario 11: Filenames with spaces, Unicode, collision paths & finalPath ──
  {
    let store = createInitialSessionStore();
    const collisionPath = "/Users/test/Downloads/DropFlow/Screenshot 2026-07-28 165721 (1).png";
    const unicodePath = "/Users/test/Downloads/DropFlow/写真_📷_test.png";

    store = applyCompletionEvent(store, {
      sessionId: "tx-11",
      fileName: "Screenshot 2026-07-28 165721.png",
      deviceName: "Windows-PC",
      size: "3.4 MB",
      direction: "receive",
      files: [
        {
          fileIndex: 0,
          relativePath: "Screenshot 2026-07-28 165721.png",
          sizeBytes: 3500000,
          finalPath: collisionPath,
        },
        {
          fileIndex: 1,
          relativePath: "写真_📷_test.png",
          sizeBytes: 1200000,
          finalPath: unicodePath,
        },
      ],
      receiveDir: "/Users/test/Downloads/DropFlow",
    });

    const activeSession = store.activeTransfers["tx-11"];
    assertTrue(activeSession.completedFiles[0].finalPath === collisionPath, "S11: Authoritative collision finalPath preserved for space/collision file");
    assertTrue(activeSession.completedFiles[1].finalPath === unicodePath, "S11: Authoritative unicode finalPath preserved for unicode file");
    assertTrue(activeSession.receiveDir === "/Users/test/Downloads/DropFlow", "S11: receiveDir preserved");
  }

  // ── Scenario 12: Receiver side cancellation & cleanup ──────────────────────
  {
    let store = createInitialSessionStore();
    store = applyProgressEvent(store, {
      sessionId: "rx-12",
      currentFileName: "large_iso.iso",
      percentage: 30,
      totalFiles: 1,
    });
    assertTrue(store.activeTransfers["rx-12"].status === "Receiving...", "S12: Receiver status is Receiving...");

    store = applyFailureEvent(store, {
      sessionId: "rx-12",
      error: "Transfer cancelled by receiver",
    });
    assertTrue(store.activeTransfers["rx-12"].status === "Failed", "S12: Receiver status set to Failed on cancel");
    assertTrue(store.activeTransfers["rx-12"].error === "Transfer cancelled by receiver", "S12: Receiver cancellation error preserved");
    assertEqual(store.recentTransfers.length, 0, "S12: No recent transfer added for cancelled receive");

    store = dismissActiveSession(store, "rx-12");
    assertTrue(store.activeTransfers["rx-12"] === undefined, "S12: Active card dismissed cleanly");
  }

  console.log("All Transfer Session Lifecycle & Path Propagation Tests PASSED cleanly! ✓");
}

// Execute tests if run directly
runLifecycleTests();
