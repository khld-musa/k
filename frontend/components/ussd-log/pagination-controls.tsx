"use client"

import {
  ChevronRight,
  ChevronLeft,
  ChevronsLeft,
  ChevronsRight,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

/**
 * [FIX UI-6] Shared pagination component extracted from History and LiveTrace pages.
 */

interface PaginationControlsProps {
  totalResults: number
  currentPage: number
  pageSize: number
  totalPages: number
  startIndex: number
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
}

export function PaginationControls({
  totalResults,
  currentPage,
  pageSize,
  totalPages,
  startIndex,
  onPageChange,
  onPageSizeChange,
}: PaginationControlsProps) {
  return (
    <div className="mt-auto pt-6 flex flex-col sm:flex-row items-center justify-between gap-4 border-t">
      <div className="text-xs text-muted-foreground tabular-nums">
        Showing <span className="font-medium text-foreground">{totalResults === 0 ? 0 : startIndex + 1}</span> to{" "}
        <span className="font-medium text-foreground">{Math.min(startIndex + pageSize, totalResults)}</span> of{" "}
        <span className="font-medium text-foreground">{totalResults}</span> entries
      </div>
      <div className="flex items-center gap-1.5">
        <div className="flex items-center gap-2 mr-4">
          <span className="text-xs text-muted-foreground whitespace-nowrap">Rows per page</span>
          <Select value={pageSize.toString()} onValueChange={(v) => { onPageSizeChange(parseInt(v)); }}>
            <SelectTrigger className="h-8 w-[70px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="5">5</SelectItem>
              <SelectItem value="10">10</SelectItem>
              <SelectItem value="20">20</SelectItem>
              <SelectItem value="50">50</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-1">
          <Button variant="outline" size="icon" className="h-8 w-8" onClick={() => onPageChange(1)} disabled={currentPage === 1}>
            <ChevronsLeft className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="icon" className="h-8 w-8" onClick={() => onPageChange(currentPage - 1)} disabled={currentPage === 1}>
            <ChevronLeft className="h-4 w-4" />
          </Button>

          <div className="flex items-center gap-1 mx-2">
            <span className="text-xs font-medium">Page {currentPage} of {totalPages || 1}</span>
          </div>

          <Button variant="outline" size="icon" className="h-8 w-8" onClick={() => onPageChange(currentPage + 1)} disabled={currentPage === totalPages || totalPages === 0}>
            <ChevronRight className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="icon" className="h-8 w-8" onClick={() => onPageChange(totalPages)} disabled={currentPage === totalPages || totalPages === 0}>
            <ChevronsRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}

