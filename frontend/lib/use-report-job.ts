"use client"

import * as React from "react"
import { toast } from "sonner"
import { apiClient, API_BASE_URL } from "@/lib/api-client"

export type ReportType = "GENERAL" | "TIBCO_ERRORS" | "PACKAGES" | "SUBSCRIBERS" | "APN"

export type ReportJobStatus = "IDLE" | "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED"

export interface ReportJob {
  id: string
  reportType: string
  status: ReportJobStatus
  fileName: string | null
  fileSize: number | null
  requestedBy: string
  requestedAt: string
  startedAt: string | null
  completedAt: string | null
  downloadedAt?: string | null
  errorMessage: string | null
  rowsWritten?: number | null
  totalRows?: number | null
  downloadUrl: string | null
}

export function useReportJob(reportType: ReportType) {
  const [job, setJob] = React.useState<ReportJob | null>(null)
  const [isRequesting, setIsRequesting] = React.useState(false)
  const [isDownloading, setIsDownloading] = React.useState(false)
  const pollTimerRef = React.useRef<NodeJS.Timeout | null>(null)

  const stopPolling = React.useCallback(() => {
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current)
      pollTimerRef.current = null
    }
  }, [])

  const pollJobStatus = React.useCallback(
    (jobId: string) => {
      stopPolling()
      let consecutiveErrors = 0
      let isTickRunning = false
      const startTime = Date.now()
      const MAX_POLL_TIME_MS = 190 * 60 * 1000 // slightly above backend export timeout (180 min)
      const MAX_CONSECUTIVE_ERRORS = 10

      pollTimerRef.current = setInterval(async () => {
        if (isTickRunning) return
        if (Date.now() - startTime > MAX_POLL_TIME_MS) {
          stopPolling()
          toast.error("Export request timed out")
          return
        }

        isTickRunning = true
        try {
          const updated: ReportJob = await apiClient(`/reports/jobs/${jobId}`)
          consecutiveErrors = 0
          setJob(updated)

          if (updated.status === "COMPLETED") {
            stopPolling()
            const sizeStr = updated.fileSize
              ? ` (${(updated.fileSize / 1024).toFixed(1)} KB)`
              : ""
            toast.success(`Report is ready to download!${sizeStr}`, {
              description: updated.fileName || "Click the download button to save your report.",
            })
          } else if (updated.status === "FAILED") {
            stopPolling()
            toast.error("Report generation failed", {
              description: updated.errorMessage || "An unexpected error occurred during report generation.",
            })
          }
        } catch {
          consecutiveErrors++
          if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            stopPolling()
            toast.error("Unable to check export status after multiple attempts")
          }
        } finally {
          isTickRunning = false
        }
      }, 2500)
    },
    [stopPolling]
  )

  // On mount, check if there's an ongoing or recent job for this page
  React.useEffect(() => {
    let mounted = true
    apiClient(`/reports/jobs/latest?reportType=${reportType}`)
      .then((existing: ReportJob | null) => {
        if (!mounted || !existing) return
        const downloadedId = typeof window !== "undefined" ? localStorage.getItem(`downloaded_report_${reportType}`) : null
        if (existing.status === "COMPLETED" && (existing.id === downloadedId || existing.downloadedAt)) {
          return // Already downloaded, start fresh in IDLE state to allow new requests
        }
        const dismissedId = typeof window !== "undefined" ? localStorage.getItem(`dismissed_failed_report_${reportType}`) : null
        if (existing.status === "FAILED" && existing.id === dismissedId) {
          return // User already dismissed this failed job
        }
        setJob(existing)
        if (existing.status === "QUEUED" || existing.status === "PROCESSING") {
          pollJobStatus(existing.id)
        }
      })
      .catch(() => {
        // No previous job or 404, start IDLE
      })

    return () => {
      mounted = false
      stopPolling()
    }
  }, [reportType, pollJobStatus, stopPolling])

  const requestExport = async (filters: Record<string, string | number | boolean | null | undefined>) => {
    setIsRequesting(true)
    try {
      if (typeof window !== "undefined") {
        try {
          localStorage.removeItem(`downloaded_report_${reportType}`)
        } catch {}
      }

      // Sanitize filter map to strings
      const sanitized: Record<string, string> = {}
      for (const [k, v] of Object.entries(filters)) {
        if (v !== undefined && v !== null && String(v).trim() !== "" && String(v).toLowerCase() !== "all") {
          sanitized[k] = String(v).trim()
        }
      }

      const created: ReportJob = await apiClient("/reports/jobs", {
        method: "POST",
        body: JSON.stringify({
          reportType,
          filters: sanitized,
        }),
      })

      setJob(created)
      toast.info("Report export requested", {
        description: "Processing in the background. You can leave this page and come back anytime.",
      })

      pollJobStatus(created.id)
    } catch (e: unknown) {
      toast.error("Failed to request report export: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsRequesting(false)
    }
  }

  const downloadFile = async () => {
    if (!job || job.status !== "COMPLETED") return
    setIsDownloading(true)
    try {
      // Short-lived, job-bound ticket: lets the browser stream the file to disk natively
      const { ticket } = await apiClient(`/reports/jobs/${job.id}/download-ticket`, { method: "POST" })
      const a = document.createElement("a")
      a.href = `${API_BASE_URL}/reports/jobs/${job.id}/download?ticket=${encodeURIComponent(ticket)}`
      a.download = job.fileName || `report-${reportType.toLowerCase()}.zip`
      document.body.appendChild(a)
      a.click()
      a.remove()

      toast.success("Download started", { description: job.fileName || "File saved" })

    } catch (e: unknown) {
      toast.error("Download failed: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsDownloading(false)
    }
  }

  const resetJob = () => {
    if (job && typeof window !== "undefined") {
      try {
        if (job.status === "FAILED") {
          localStorage.setItem(`dismissed_failed_report_${reportType}`, job.id)
        } else if (job.status === "COMPLETED") {
          localStorage.setItem(`downloaded_report_${reportType}`, job.id)
        }
      } catch {}
    }
    stopPolling()
    setJob(null)
  }

  const cancelJob = async () => {
    if (job?.id) {
      try {
        await apiClient(`/reports/jobs/${job.id}`, { method: "DELETE" })
      } catch {}
    }
    stopPolling()
    setJob(null)
    toast.info("Export cancelled")
  }

  const status: ReportJobStatus = job?.status || "IDLE"
  const isProcessing = status === "QUEUED" || status === "PROCESSING" || isRequesting
  const isReady = status === "COMPLETED"

  return {
    job,
    status,
    isProcessing,
    isReady,
    isDownloading,
    isRequesting,
    requestExport,
    downloadFile,
    resetJob,
    cancelJob,
  }
}
