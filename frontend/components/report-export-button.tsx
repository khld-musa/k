"use client"

import * as React from "react"
import { Download, RefreshCw, CheckCircle2, AlertTriangle, RotateCcw } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useReportJob, type ReportType } from "@/lib/use-report-job"

interface ReportExportButtonProps {
  reportType: ReportType
  getFilters: () => Record<string, string | number | boolean | null | undefined>
  defaultLabel?: string
  disabled?: boolean
  className?: string
}

export function ReportExportButton({
  reportType,
  getFilters,
  defaultLabel = "Export Report (.xlsx)",
  disabled = false,
  className,
}: ReportExportButtonProps) {
  const {
    job,
    status,
    isProcessing,
    isReady,
    isDownloading,
    downloadFile,
    resetJob,
    cancelJob,
    requestExport,
  } = useReportJob(reportType)



  const handleExport = () => {
    requestExport(getFilters())
  }

  const progressLabel = () => {
    if (status === "QUEUED") return "Queued in background..."
    const done = job?.rowsWritten ?? 0
    const total = job?.totalRows ?? 0
    if (total > 0 && done > 0) {
      const pct = Math.min(99, Math.floor((done / total) * 100))
      return `Exporting ${pct}% (${done.toLocaleString()} / ${total.toLocaleString()})`
    }
    return "Preparing report..."
  }

  // If a background job is already processing or ready, show its state
  if (isProcessing) {
    return (
      <div className="flex items-center gap-1.5">
        <Button
          variant="outline"
          disabled
          className={`gap-2 border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400 font-medium ${className || ""}`}
        >
          <RefreshCw className="h-4 w-4 animate-spin text-amber-600 dark:text-amber-400" />
          {progressLabel()}
        </Button>
        <Button
          variant="outline"
          size="icon"
          onClick={cancelJob}
          title="Cancel this export"
          className="h-9 w-9 text-muted-foreground hover:text-destructive hover:border-destructive"
        >
          <RotateCcw className="h-4 w-4" />
        </Button>
      </div>
    )
  }

  if (isReady) {
    const sizeStr = job?.fileSize ? ` (${(job.fileSize / (1024 * 1024)).toFixed(1)} MB)` : ""
    return (
      <div className="flex items-center gap-1.5">
        <Button
          variant="default"
          onClick={downloadFile}
          disabled={isDownloading}
          className={`gap-2 bg-emerald-600 hover:bg-emerald-700 text-white font-medium shadow-sm ${className || ""}`}
        >
          <CheckCircle2 className="h-4 w-4" />
          {isDownloading ? "Downloading..." : `Download Report (.zip)${sizeStr}`}
        </Button>
        <Button
          variant="outline"
          size="icon"
          onClick={resetJob}
          title="Dismiss and request a new report"
          className="h-9 w-9 text-muted-foreground hover:text-foreground"
        >
          <RotateCcw className="h-4 w-4" />
        </Button>
      </div>
    )
  }

  if (status === "FAILED") {
    return (
      <div className="flex items-center gap-1.5">
        <Button
          variant="destructive"
          onClick={handleExport}
          title={job?.errorMessage || undefined}
          className={`gap-2 ${className || ""}`}
        >
          <AlertTriangle className="h-4 w-4" />
          Export failed — Retry
        </Button>
        <Button
          variant="ghost"
          size="icon"
          onClick={resetJob}
          className="h-9 w-9 text-muted-foreground"
        >
          <RotateCcw className="h-4 w-4" />
        </Button>
      </div>
    )
  }



  return (
    <Button
      variant="outline"
      onClick={handleExport}
      disabled={disabled}
      className={`gap-2 ${className || ""}`}
    >
      <Download className="h-4 w-4" />
      {defaultLabel}
    </Button>
  )
}
