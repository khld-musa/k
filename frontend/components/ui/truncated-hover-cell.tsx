"use client"

import * as React from "react"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { Copy, Check } from "lucide-react"

interface TruncatedHoverCellProps {
  text: string | null | undefined
  maxChars?: number
  maxWidthClass?: string
  className?: string
  mono?: boolean
  dir?: "ltr" | "rtl" | "auto"
  variant?: "destructive" | "amber" | "muted" | "badge" | "default"
}

export function TruncatedHoverCell({
  text,
  maxChars = 35,
  maxWidthClass = "max-w-[220px]",
  className = "",
  mono = false,
  dir = "auto",
  variant = "default",
}: TruncatedHoverCellProps) {
  const [copied, setCopied] = React.useState(false)

  if (!text || text.trim() === "") {
    return <span className="text-muted-foreground text-xs">—</span>
  }

  const cleanText = text.trim()
  const isLong = cleanText.length > maxChars
  const displayPreview = isLong ? `${cleanText.slice(0, maxChars)}...` : cleanText

  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation()
    navigator.clipboard?.writeText(cleanText)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  const variantClasses = {
    destructive: "bg-destructive/10 text-destructive border border-destructive/20 font-mono",
    amber: "bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/30",
    muted: "bg-muted/70 text-foreground border border-border/40 font-mono",
    badge: "border border-border bg-background text-foreground font-mono",
    default: "text-foreground",
  }[variant]

  const trigger = (
    <span
      dir={dir}
      className={`inline-flex items-center text-xs px-2 py-0.5 rounded cursor-pointer select-text truncate ${maxWidthClass} ${variantClasses} ${mono ? "font-mono" : ""} ${className}`}
      title={cleanText}
    >
      <span className="truncate">{displayPreview}</span>
    </span>
  )

  if (!isLong) {
    return trigger
  }

  return (
    <TooltipProvider delayDuration={100}>
      <Tooltip>
        <TooltipTrigger asChild>
          {trigger}
        </TooltipTrigger>
        <TooltipContent
          side="top"
          align="start"
          className="max-w-lg max-h-72 overflow-y-auto p-3 text-xs leading-relaxed bg-popover text-popover-foreground border shadow-xl rounded-lg z-50 animate-in fade-in-0 zoom-in-95"
        >
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between gap-4 border-b pb-1.5 text-[11px] text-muted-foreground">
              <span>Full Content ({cleanText.length} characters)</span>
              <button
                type="button"
                onClick={handleCopy}
                className="inline-flex items-center gap-1 hover:text-foreground transition-colors font-medium cursor-pointer"
                title="Copy to clipboard"
              >
                {copied ? (
                  <>
                    <Check className="h-3 w-3 text-emerald-500" />
                    <span className="text-emerald-500 text-[10px]">Copied</span>
                  </>
                ) : (
                  <>
                    <Copy className="h-3 w-3" />
                    <span className="text-[10px]">Copy</span>
                  </>
                )}
              </button>
            </div>
            <div
              dir={dir}
              className={`whitespace-pre-wrap break-all select-all leading-normal text-xs ${mono ? "font-mono" : ""}`}
            >
              {cleanText}
            </div>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
