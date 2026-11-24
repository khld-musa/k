"use client"

import * as React from "react"
import { Check, ChevronsUpDown, Search, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

export interface SearchableOption {
  value: string
  label: string
  sublabel?: string
}

interface SearchableSelectProps {
  value: string
  onValueChange: (value: string) => void
  options: (string | SearchableOption)[]
  placeholder?: string
  searchPlaceholder?: string
  allOptionLabel?: string
  maxItems?: number
  className?: string
  triggerClassName?: string
  disabled?: boolean
}

export function SearchableSelect({
  value,
  onValueChange,
  options,
  placeholder = "Select an option...",
  searchPlaceholder = "Type to search...",
  allOptionLabel = "All options",
  maxItems = 5,
  className,
  triggerClassName,
  disabled = false,
}: SearchableSelectProps) {
  const [open, setOpen] = React.useState(false)
  const [search, setSearch] = React.useState("")
  const inputRef = React.useRef<HTMLInputElement>(null)

  // Normalize options to SearchableOption[]
  const normalizedOptions: SearchableOption[] = React.useMemo(() => {
    return options.map((opt) => {
      if (typeof opt === "string") {
        return { value: opt, label: opt }
      }
      return opt
    })
  }, [options])

  // Reset search when opening
  React.useEffect(() => {
    if (open) {
      setSearch("")
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [open])

  // Filtered list
  const filteredOptions = React.useMemo(() => {
    const trimmed = search.trim().toLowerCase()
    if (!trimmed) {
      // Empty search: return only 5 sample suggestions
      return normalizedOptions.slice(0, maxItems)
    }
    return normalizedOptions
      .filter((opt) => {
        const matchLabel = opt.label.toLowerCase().includes(trimmed)
        const matchValue = opt.value.toLowerCase().includes(trimmed)
        const matchSub = opt.sublabel?.toLowerCase().includes(trimmed)
        return matchLabel || matchValue || matchSub
      })
      .slice(0, maxItems)
  }, [normalizedOptions, search, maxItems])

  const totalMatches = React.useMemo(() => {
    const trimmed = search.trim().toLowerCase()
    if (!trimmed) return normalizedOptions.length
    return normalizedOptions.filter((opt) => {
      const matchLabel = opt.label.toLowerCase().includes(trimmed)
      const matchValue = opt.value.toLowerCase().includes(trimmed)
      const matchSub = opt.sublabel?.toLowerCase().includes(trimmed)
      return matchLabel || matchValue || matchSub
    }).length
  }, [normalizedOptions, search])

  // Find currently selected label
  const selectedOption = normalizedOptions.find((opt) => opt.value === value)
  const displayLabel =
    value === "all" || !value
      ? allOptionLabel
      : selectedOption
      ? selectedOption.label
      : value

  const handleSelect = (val: string) => {
    onValueChange(val)
    setOpen(false)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild disabled={disabled}>
        <Button
          variant="outline"
          role="combobox"
          aria-expanded={open}
          className={cn(
            "w-[200px] justify-between font-normal text-left h-9 text-xs px-3",
            !value && "text-muted-foreground",
            triggerClassName
          )}
        >
          <span className="truncate">{displayLabel}</span>
          <ChevronsUpDown className="ml-2 h-3.5 w-3.5 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        className={cn("w-[260px] p-0 shadow-md", className)}
        align="start"
      >
        <div className="p-2 border-b">
          <div className="relative">
            <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
            <Input
              ref={inputRef}
              placeholder={searchPlaceholder}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="h-8 pl-7 pr-7 text-xs"
              dir="auto"
            />
            {search && (
              <button
                type="button"
                onClick={() => setSearch("")}
                className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        </div>

        <div className="max-h-[220px] overflow-y-auto p-1">
          {/* Default 'All' option if provided */}
          {allOptionLabel && !search && (
            <button
              type="button"
              onClick={() => handleSelect("all")}
              className={cn(
                "relative flex w-full cursor-pointer select-none items-center rounded-sm px-2 py-1.5 text-xs outline-none hover:bg-accent hover:text-accent-foreground",
                (value === "all" || !value) && "bg-accent font-medium text-accent-foreground"
              )}
            >
              <Check
                className={cn(
                  "mr-2 h-3.5 w-3.5 shrink-0",
                  value === "all" || !value ? "opacity-100" : "opacity-0"
                )}
              />
              <span className="truncate font-semibold">{allOptionLabel}</span>
            </button>
          )}

          {/* Options list (capped at 5 samples or 5 filtered results) */}
          {filteredOptions.length === 0 ? (
            <div className="py-4 text-center text-xs text-muted-foreground">
              {search ? `No matches for "${search}"` : "No options available."}
            </div>
          ) : (
            filteredOptions.map((opt) => {
              const isSelected = value === opt.value
              return (
                <button
                  key={opt.value}
                  type="button"
                  onClick={() => handleSelect(opt.value)}
                  className={cn(
                    "relative flex w-full cursor-pointer select-none items-center rounded-sm px-2 py-1.5 text-xs outline-none hover:bg-accent hover:text-accent-foreground",
                    isSelected && "bg-accent font-medium text-accent-foreground"
                  )}
                >
                  <Check
                    className={cn(
                      "mr-2 h-3.5 w-3.5 shrink-0 text-primary",
                      isSelected ? "opacity-100" : "opacity-0"
                    )}
                  />
                  <div className="flex flex-col text-left truncate flex-1" dir="auto">
                    <span className="truncate">{opt.label}</span>
                    {opt.sublabel && (
                      <span className="text-[10px] text-muted-foreground truncate">
                        {opt.sublabel}
                      </span>
                    )}
                  </div>
                </button>
              )
            })
          )}
        </div>

        {/* Footer info: explains only 5 samples are shown */}
        <div className="border-t px-2.5 py-1.5 text-[10px] text-muted-foreground bg-muted/20">
          {!search ? (
            <span>Showing top 5 samples ({normalizedOptions.length} total). Type to search.</span>
          ) : totalMatches > maxItems ? (
            <span>Found {totalMatches} matches (showing first 5). Type more to narrow.</span>
          ) : (
            <span>{totalMatches} match(es) found.</span>
          )}
        </div>
      </PopoverContent>
    </Popover>
  )
}
