"use client";

import { useEffect, useRef, useCallback } from "react";

interface UseIdleTimeoutOptions {
  /** Timeout in milliseconds (default: 10 minutes) */
  timeout?: number;
  /** Callback fired when the user has been idle for the timeout duration */
  onTimeout: () => void;
  /** Whether the idle timeout is enabled (default: true) */
  enabled?: boolean;
}

/**
 * Hook that detects user inactivity and fires a callback after a specified timeout.
 * Tracks mouse, keyboard, touch, scroll, and click events.
 */
export function useIdleTimeout({
  timeout = 10 * 60 * 1000, // 10 minutes
  onTimeout,
  enabled = true,
}: UseIdleTimeoutOptions) {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onTimeoutRef = useRef(onTimeout);

  // Keep the callback ref in sync without re-registering listeners
  useEffect(() => {
    onTimeoutRef.current = onTimeout;
  }, [onTimeout]);

  const resetTimer = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }
    timerRef.current = setTimeout(() => {
      onTimeoutRef.current();
    }, timeout);
  }, [timeout]);

  useEffect(() => {
    if (!enabled) {
      return;
    }

    const events: (keyof WindowEventMap)[] = [
      "mousemove",
      "keydown",
      "mousedown",
      "touchstart",
      "scroll",
      "click",
    ];

    // Start the initial timer
    resetTimer();

    // Reset on any user activity
    const handleActivity = () => {
      resetTimer();
    };

    for (const event of events) {
      window.addEventListener(event, handleActivity, { passive: true });
    }

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
      }
      for (const event of events) {
        window.removeEventListener(event, handleActivity);
      }
    };
  }, [enabled, resetTimer]);
}
