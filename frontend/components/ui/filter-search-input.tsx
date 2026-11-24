"use client"

import * as React from "react"
import { Search, X } from "lucide-react"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

export interface FilterSearchInputProps {
  value: string
  onChange: (value: string) => void
  onSearch?: () => void
  placeholder?: string
  suggestions?: string[]
  listId?: string
  className?: string
  inputClassName?: string
  dir?: "ltr" | "rtl" | "auto"
}

export function FilterSearchInput({
  value,
  onChange,
  onSearch,
  placeholder = "Search...",
  suggestions = [],
  listId,
  className,
  inputClassName,
  dir = "auto",
}: FilterSearchInputProps) {
  const generatedId = React.useId()
  const actualListId = listId || (suggestions.length > 0 ? `datalist-${generatedId.replace(/:/g, "")}` : undefined)

  const displayValue = value === "all" ? "" : value

  return (
    <div className={cn("relative min-w-[170px]", className)}>
      <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground pointer-events-none" />
      <Input
        list={actualListId}
        value={displayValue}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && onSearch) {
            e.preventDefault()
            onSearch()
          }
        }}
        placeholder={placeholder}
        dir={dir}
        className={cn(
          "h-9 pl-8 pr-7 text-xs font-normal",
          inputClassName
        )}
      />
      {displayValue && (
        <button
          type="button"
          onClick={() => onChange("")}
          className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
          aria-label="Clear input"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      )}
      {actualListId && suggestions.length > 0 && (
        <datalist id={actualListId}>
          {suggestions.slice(0, 50).map((item) => (
            <option key={item} value={item} />
          ))}
        </datalist>
      )}
    </div>
  )
}
