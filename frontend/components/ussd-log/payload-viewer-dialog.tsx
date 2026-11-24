"use client"

import { Copy, Check } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { useState } from "react"

/**
 * [FIX UI-6] Shared payload viewer dialog extracted from History and LiveTrace pages.
 */

interface PayloadViewerDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  content: string
}

export function PayloadViewerDialog({ open, onOpenChange, title, content }: PayloadViewerDialogProps) {
  const [copied, setCopied] = useState(false)

  const handleCopy = () => {
    navigator.clipboard.writeText(content)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[85vh] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <span>{title}</span>
            <Button variant="ghost" size="sm" className="ml-auto h-8" onClick={handleCopy}>
              {copied ? <Check className="h-3.5 w-3.5 mr-1.5" /> : <Copy className="h-3.5 w-3.5 mr-1.5" />}
              {copied ? "Copied!" : "Copy"}
            </Button>
          </DialogTitle>
        </DialogHeader>
        <div className="bg-[#0d1117] text-[#c9d1d9] p-4 rounded-lg font-mono text-xs overflow-auto flex-1 border border-[#30363d] custom-scrollbar">
          <pre className="m-0 leading-relaxed whitespace-pre-wrap break-all">
            {content}
          </pre>
        </div>
      </DialogContent>
    </Dialog>
  )
}

