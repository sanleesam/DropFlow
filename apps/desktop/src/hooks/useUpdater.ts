/**
 * DropFlow — useUpdater Hook
 *
 * Custom React hook for subscribing to `UpdateService` reactive state and dispatching actions.
 */

import { useState, useEffect, useCallback } from "react";
import { updateService } from "../services/updateService";
import type { UpdaterState } from "../types/updater";

export function useUpdater() {
  const [state, setState] = useState<UpdaterState>(() => updateService.getState());

  useEffect(() => {
    const unsubscribe = updateService.subscribe((newState) => {
      setState(newState);
    });
    return unsubscribe;
  }, []);

  const checkForUpdates = useCallback(async () => {
    await updateService.checkForUpdates();
  }, []);

  const downloadUpdate = useCallback(async () => {
    await updateService.downloadUpdate();
  }, []);

  const restartApplication = useCallback(async () => {
    await updateService.restartApplication();
  }, []);

  return {
    state,
    checkForUpdates,
    downloadUpdate,
    restartApplication,
  };
}
