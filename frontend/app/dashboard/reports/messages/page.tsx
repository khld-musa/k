"use client"

import * as React from "react"
import { format } from "date-fns"
import {
  Calendar as CalendarIcon,
  Download,
  Search,
  X,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  MessageSquare,
  Hash,
  PlayCircle,
} from "lucide-react"
import { toast } from "sonner"
import { type ColumnDef } from "@tanstack/react-table"

import { DashboardHeader } from "@/components/dashboard-header"
import { ReportsNav } from "@/components/reports-nav"
import { DataTable } from "@/components/data-table"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Calendar } from "@/components/ui/calendar"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { FilterSearchInput } from "@/components/ui/filter-search-input"
import { ReportExportButton } from "@/components/report-export-button"
import { TruncatedHoverCell } from "@/components/ui/truncated-hover-cell"
import { cn } from "@/lib/utils"
import { apiClient, API_BASE_URL } from "@/lib/api-client"
import { useAuth } from "@/lib/auth-context"

interface ReportSummary {
  startDate: string
  endDate: string
  totalTransactions: number
  successCount: number
  failedCount: number
  successRatePercent: number
  averageLatencyMs: number | null
  topErrorCodes: { errorCode: string; total: number; sampleMessage: string | null }[]
}

interface UssdTransactionRow {
  id: number
  callId: string
  msisdn: string
  transactionId: string | null
  status: string | null
  latencyMs: number | null
  shortCode: string | null
  productName: string | null
  errorCode: string | null
  errorMessage: string | null
  tibcoResponse: string | null
  errorReason: string | null
  remoteUrl: string | null
  subscriberReply: string | null
  responseTime: string | null
  serviceType: string | null
  lang: string | null
  nodeIp: string | null
  cellId: string | null
  vlr: string | null
  channel: string | null
  triggeredAt: string
}

interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

const numberFormat = new Intl.NumberFormat("en-US")

function StatTile({
  label,
  value,
  detail,
  icon: Icon,
}: {
  label: string
  value: React.ReactNode
  detail?: React.ReactNode
  icon?: React.ElementType
}) {
  return (
    <Card className="shadow-none">
      <CardContent className="p-5">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium text-muted-foreground">{label}</p>
          {Icon && <Icon className="h-4 w-4 text-muted-foreground/60" />}
        </div>
        <p className="mt-2 text-2xl font-bold tabular-nums">{value}</p>
        {detail && <p className="mt-1 text-xs text-muted-foreground">{detail}</p>}
      </CardContent>
    </Card>
  )
}

export default function ErrorAndMessageReportPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === "ADMIN"

  const getToday = () => new Date()

  // Single Day Selection
  const [selectedDate, setSelectedDate] = React.useState<Date>(getToday)
  const [messageInput, setMessageInput] = React.useState("")
  const [selectedErrorCode, setSelectedErrorCode] = React.useState<string>("")
  const [selectedShortCode, setSelectedShortCode] = React.useState<string>("")
  const [errorCodeOptions, setErrorCodeOptions] = React.useState<string[]>([])
  const [shortCodeOptions, setShortCodeOptions] = React.useState<string[]>([])

  const [hasSearched, setHasSearched] = React.useState(false)
  const [appliedFilters, setAppliedFilters] = React.useState<{
    date: Date
    message: string
    errorCode: string
    shortCode: string
  } | null>(null)

  const [summary, setSummary] = React.useState<ReportSummary | null>(null)
  const [rows, setRows] = React.useState<UssdTransactionRow[]>([])
  const [totalElements, setTotalElements] = React.useState(0)
  const [pagination, setPagination] = React.useState({ pageIndex: 0, pageSize: 20 })

  const [isLoading, setIsLoading] = React.useState(false)
  const [isIngesting, setIsIngesting] = React.useState(false)

  const handleRunIngestion = async () => {
    setIsIngesting(true)
    try {
      const res = (await apiClient("/reports/ingest/run?reingestAll=true", { method: "POST" })) as { message?: string }
      toast.success(res?.message || "USSD log ingestion and backfill completed")
      if (hasSearched) {
        handleSearch()
      }
    } catch (e: unknown) {
      toast.error("Ingestion failed: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsIngesting(false)
    }
  }

  React.useEffect(() => {
    apiClient("/reports/filters/error-codes")
      .then((data: string[]) => setErrorCodeOptions(data || []))
      .catch(() => {})

    apiClient("/reports/filters/short-codes")
      .then((data: string[]) => setShortCodeOptions(data || []))
      .catch(() => {})
  }, [])

  const buildQuery = (
    filters: { date: Date; message: string; errorCode: string; shortCode: string },
    page: number,
    size: number
  ) => {
    const params = new URLSearchParams()
    const dStr = format(filters.date, "yyyy-MM-dd")
    params.set("date", dStr)
    params.set("startDate", dStr)
    params.set("endDate", dStr)
    params.set("status", "FAILED")
    if (filters.message.trim()) params.set("message", filters.message.trim())
    if (filters.errorCode && filters.errorCode !== "all" && filters.errorCode.trim()) {
      params.set("errorCode", filters.errorCode.trim())
    }
    if (filters.shortCode && filters.shortCode !== "all" && filters.shortCode.trim()) {
      params.set("shortCode", filters.shortCode.trim())
    }
    params.set("page", String(page))
    params.set("size", String(size))
    return params
  }

  const buildSummaryQuery = (filters: { date: Date; message: string; errorCode: string; shortCode: string }) => {
    const params = new URLSearchParams()
    const dStr = format(filters.date, "yyyy-MM-dd")
    params.set("date", dStr)
    params.set("startDate", dStr)
    params.set("endDate", dStr)
    if (filters.message.trim()) params.set("message", filters.message.trim())
    if (filters.errorCode && filters.errorCode !== "all" && filters.errorCode.trim()) {
      params.set("errorCode", filters.errorCode.trim())
    }
    if (filters.shortCode && filters.shortCode !== "all" && filters.shortCode.trim()) {
      params.set("shortCode", filters.shortCode.trim())
    }
    return params
  }

  const handleSearch = async () => {
    const filters = {
      date: selectedDate,
      message: messageInput.trim(),
      errorCode: selectedErrorCode,
      shortCode: selectedShortCode,
    }
    setAppliedFilters(filters)
    setHasSearched(true)
    setRows([])
    setSummary(null)
    setPagination((p) => ({ ...p, pageIndex: 0 }))

    setIsLoading(true)
    const q = buildQuery(filters, 0, pagination.pageSize)
    const summaryQ = buildSummaryQuery(filters)

    const txPromise = apiClient(`/reports/transactions?${q.toString()}`) as Promise<PageResponse<UssdTransactionRow>>
    const summaryPromise = apiClient(`/reports/summary?${summaryQ.toString()}`) as Promise<ReportSummary>

    txPromise
      .then((txData) => {
        setRows(txData.content || [])
        setTotalElements(txData.totalElements || 0)
      })
      .catch((e: unknown) => {
        toast.error("Failed to load error transactions: " + (e instanceof Error ? e.message : String(e)))
      })

    summaryPromise
      .then((summaryData) => {
        setSummary(summaryData)
      })
      .catch((e: unknown) => {
        toast.error("Failed to load report summary: " + (e instanceof Error ? e.message : String(e)))
      })

    try {
      await Promise.allSettled([txPromise, summaryPromise])
    } finally {
      setIsLoading(false)
    }
  }

  const handleClearFilters = () => {
    setMessageInput("")
    setSelectedErrorCode("")
    setSelectedShortCode("")
    setSelectedDate(getToday())
  }

  const handleQuickFilter = (code: string) => {
    setSelectedErrorCode(code)
    const filters = {
      date: appliedFilters?.date || selectedDate,
      message: appliedFilters?.message || "",
      errorCode: code,
      shortCode: appliedFilters?.shortCode || selectedShortCode,
    }
    setAppliedFilters(filters)
    setHasSearched(true)
    setPagination((p) => ({ ...p, pageIndex: 0 }))
    setIsLoading(true)

    const q = buildQuery(filters, 0, pagination.pageSize)
    const summaryQ = buildSummaryQuery(filters)

    const txPromise = apiClient(`/reports/transactions?${q.toString()}`) as Promise<PageResponse<UssdTransactionRow>>
    const summaryPromise = apiClient(`/reports/summary?${summaryQ.toString()}`) as Promise<ReportSummary>

    txPromise
      .then((txData) => {
        setRows(txData.content || [])
        setTotalElements(txData.totalElements || 0)
      })
      .catch((e: unknown) => toast.error("Failed to load records: " + String(e)))

    summaryPromise
      .then((summaryData) => setSummary(summaryData))
      .catch((e: unknown) => toast.error("Failed to load summary: " + String(e)))

    Promise.allSettled([txPromise, summaryPromise]).finally(() => setIsLoading(false))
  }

  const handlePaginationChange = async (next: { pageIndex: number; pageSize: number }) => {
    setPagination(next)
    const filters = appliedFilters || {
      date: selectedDate,
      message: messageInput.trim(),
      errorCode: selectedErrorCode,
      shortCode: selectedShortCode,
    }
    setIsLoading(true)
    try {
      const q = buildQuery(filters, next.pageIndex, next.pageSize)
      const txData: PageResponse<UssdTransactionRow> = await apiClient(`/reports/transactions?${q.toString()}`)
      setRows(txData.content)
      setTotalElements(txData.totalElements)
    } catch (e: unknown) {
      toast.error("Error navigating page: " + String(e))
    } finally {
      setIsLoading(false)
    }
  }

  const maxErrorCount = Math.max(1, ...(summary?.topErrorCodes || []).map((e) => e.total))

  const columns: ColumnDef<UssdTransactionRow>[] = [
    {
      id: "index",
      header: "#",
      cell: ({ row }) => (
        <span className="font-mono text-xs text-muted-foreground">
          {pagination.pageIndex * pagination.pageSize + row.index + 1}
        </span>
      ),
    },
    {
      accessorKey: "responseTime",
      header: "Error Time",
      cell: ({ row }) => {
        const t = row.original.responseTime || row.original.triggeredAt
        return (
          <span className="whitespace-nowrap font-mono text-xs tabular-nums text-destructive font-medium">
            {t ? format(new Date(t), "yyyy-MM-dd HH:mm:ss") : "—"}
          </span>
        )
      },
    },
    {
      accessorKey: "triggeredAt",
      header: "Request Time",
      cell: ({ row }) => (
        <span className="whitespace-nowrap font-mono text-xs tabular-nums text-muted-foreground">
          {row.getValue("triggeredAt") ? format(new Date(row.getValue("triggeredAt")), "yyyy-MM-dd HH:mm:ss") : "—"}
        </span>
      ),
    },
    {
      accessorKey: "msisdn",
      header: "MSISDN",
      cell: ({ row }) => <span className="font-mono text-xs tabular-nums font-medium">{row.getValue("msisdn") || "—"}</span>,
    },
    {
      accessorKey: "shortCode",
      header: "Shortcode",
      cell: ({ row }) => <span className="font-mono text-xs font-semibold">{row.getValue("shortCode") || "—"}</span>,
    },
    {
      id: "tibcoResponse",
      header: "TIBCO Response",
      cell: ({ row }) => {
        const raw = row.original.tibcoResponse || row.original.errorMessage
        return (
          <TruncatedHoverCell
            text={raw}
            maxChars={30}
            maxWidthClass="max-w-[220px]"
            variant="destructive"
            mono
          />
        )
      },
    },
    {
      id: "errorReason",
      header: "Reason",
      cell: ({ row }) => (
        <TruncatedHoverCell
          text={row.original.errorReason}
          maxChars={28}
          maxWidthClass="max-w-[200px]"
          variant="amber"
        />
      ),
    },
    {
      accessorKey: "subscriberReply",
      header: "Reply to subscriber",
      cell: ({ row }) => (
        <TruncatedHoverCell
          text={row.getValue("subscriberReply")}
          maxChars={28}
          maxWidthClass="max-w-[200px]"
          variant="muted"
          dir="auto"
        />
      ),
    },
    {
      accessorKey: "errorCode",
      header: "Error Code",
      cell: ({ row }) => {
        const code = row.getValue("errorCode") as string | null
        if (!code) return <span className="text-muted-foreground text-xs">—</span>
        return <Badge variant="outline" className="font-mono text-xs">{code}</Badge>
      },
    },
    {
      accessorKey: "status",
      header: "Status",
      cell: ({ row }) => {
        const s = row.getValue("status") as string | null
        if (!s) return <span className="text-muted-foreground text-xs">—</span>
        return (
          <Badge variant={s === "FAILED" ? "destructive" : "secondary"} className="text-[11px] font-semibold">
            {s}
          </Badge>
        )
      },
    },
    {
      accessorKey: "latencyMs",
      header: "Duration (s)",
      cell: ({ row }) => {
        const ms = row.getValue("latencyMs") as number | null
        if (ms == null) return <span className="text-muted-foreground text-xs">—</span>
        return <span className="tabular-nums font-mono text-xs">{(ms / 1000).toFixed(2)}s</span>
      },
    },
    {
      accessorKey: "remoteUrl",
      header: "TIBCO URL",
      cell: ({ row }) => (
        <TruncatedHoverCell
          text={row.getValue("remoteUrl")}
          maxChars={26}
          maxWidthClass="max-w-[190px]"
          variant="muted"
          mono
        />
      ),
    },
    {
      accessorKey: "productName",
      header: "Product",
      cell: ({ row }) => (
        <TruncatedHoverCell
          text={row.getValue("productName")}
          maxChars={22}
          maxWidthClass="max-w-[160px]"
        />
      ),
    },
    {
      accessorKey: "serviceType",
      header: "Service Type",
      cell: ({ row }) => <span className="text-xs text-muted-foreground">{row.getValue("serviceType") || "—"}</span>,
    },
    {
      accessorKey: "channel",
      header: "Channel",
      cell: ({ row }) => <span className="text-xs font-mono text-muted-foreground">{row.getValue("channel") || "—"}</span>,
    },
    {
      accessorKey: "lang",
      header: "Lang",
      cell: ({ row }) => <span className="text-xs font-mono uppercase">{row.getValue("lang") || "—"}</span>,
    },
    {
      accessorKey: "callId",
      header: "Call ID",
      cell: ({ row }) => (
        <TruncatedHoverCell
          text={row.getValue("callId")}
          maxChars={18}
          maxWidthClass="max-w-[140px]"
          variant="muted"
          mono
        />
      ),
    },
    {
      accessorKey: "transactionId",
      header: "Transaction ID",
      cell: ({ row }) => (
        <TruncatedHoverCell
          text={row.getValue("transactionId")}
          maxChars={18}
          maxWidthClass="max-w-[140px]"
          variant="muted"
          mono
        />
      ),
    },
    {
      accessorKey: "nodeIp",
      header: "Node IP",
      cell: ({ row }) => <span className="font-mono text-xs">{row.getValue("nodeIp") || "—"}</span>,
    },
    {
      accessorKey: "cellId",
      header: "Cell ID",
      cell: ({ row }) => <span className="font-mono text-xs text-muted-foreground">{row.getValue("cellId") || "—"}</span>,
    },
    {
      accessorKey: "vlr",
      header: "VLR",
      cell: ({ row }) => <span className="font-mono text-xs text-muted-foreground">{row.getValue("vlr") || "—"}</span>,
    },
  ]

  const isFiltered =
    !!messageInput ||
    Boolean(selectedErrorCode && selectedErrorCode !== "all") ||
    Boolean(selectedShortCode && selectedShortCode !== "all") ||
    format(selectedDate, "yyyy-MM-dd") !== format(getToday(), "yyyy-MM-dd")

  return (
    <>
      <DashboardHeader breadcrumbs={[{ label: "Dashboard", href: "/dashboard" }, { label: "Reports", href: "/dashboard/reports" }, { label: "Errors & Messages" }]} />
      <ReportsNav />
      <div className="flex flex-1 flex-col gap-6 p-4 sm:p-6 w-full min-w-0 max-w-full overflow-x-hidden">
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="grid gap-1">
            <h1 className="text-2xl font-semibold tracking-tight">Errors & Response Messages Report</h1>
            <p className="text-sm text-muted-foreground">
              Examine TIBCO error responses, timeouts, and subscriber replies for any selected day
            </p>
          </div>
          <div className="flex items-center gap-2">
            {isAdmin && (
              <Button variant="outline" onClick={handleRunIngestion} disabled={isIngesting}>
                <PlayCircle className={cn("mr-2 h-4 w-4", isIngesting && "animate-spin")} />
                Run Ingestion & Backfill
              </Button>
            )}
            <ReportExportButton
              reportType="TIBCO_ERRORS"
              defaultLabel="Export TIBCO Error Report (.xlsx)"
              disabled={totalElements === 0}
              getFilters={() => {
                const filters = appliedFilters || {
                  date: selectedDate,
                  message: messageInput.trim(),
                  errorCode: selectedErrorCode,
                  shortCode: selectedShortCode,
                }
                const dStr = format(filters.date, "yyyy-MM-dd")
                return {
                  date: dStr,
                  startDate: dStr,
                  endDate: dStr,
                  message: filters.message,
                  errorCode: filters.errorCode,
                  shortCode: filters.shortCode,
                }
              }}
            />
          </div>
        </div>

        {/* Message and Error Filter Bar */}
        <Card className="shadow-none border-destructive/20">
          <CardContent className="p-4 space-y-3">
            <div className="flex flex-wrap items-end gap-4">
              {/* Error Code or Response Message Search (Arabic & English) */}
              <div className="grid gap-1.5 flex-1 min-w-[280px]">
                <Label className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                  <MessageSquare className="h-3.5 w-3.5 text-primary" /> Error or Message Search (رمز الخطأ أو نص الرسالة)
                </Label>
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    placeholder="Search error response, timeout, or message (e.g. Read timed out, 504, ESB-00051, انت غير مشترك)..."
                    value={messageInput}
                    onChange={(e) => setMessageInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") handleSearch()
                    }}
                    className="pl-9 pr-8 text-sm"
                    dir="auto"
                  />
                  {messageInput && (
                    <button
                      onClick={() => setMessageInput("")}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>

              {/* Shortcode Search */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground flex items-center gap-1">
                  <Hash className="h-3 w-3" /> Shortcode (كود الخدمة)
                </Label>
                <FilterSearchInput
                  value={selectedShortCode}
                  onChange={setSelectedShortCode}
                  onSearch={handleSearch}
                  suggestions={shortCodeOptions}
                  placeholder="e.g. *400 or *123#..."
                  className="w-[180px]"
                  inputClassName="font-mono text-xs"
                />
              </div>

              {/* Error Code Search Input */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Error Code (رمز الخطأ)</Label>
                <FilterSearchInput
                  value={selectedErrorCode}
                  onChange={setSelectedErrorCode}
                  onSearch={handleSearch}
                  suggestions={errorCodeOptions}
                  placeholder="e.g. ESB-00051..."
                  className="w-[180px]"
                  inputClassName="font-mono text-xs"
                />
              </div>

              {/* Single Report Date */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Report Date (اليوم المحدد)</Label>
                <Popover>
                  <PopoverTrigger asChild>
                    <Button variant="outline" className="w-[160px] justify-start gap-2 font-normal">
                      <CalendarIcon className="h-4 w-4" />
                      {format(selectedDate, "yyyy-MM-dd")}
                    </Button>
                  </PopoverTrigger>
                  <PopoverContent className="w-auto p-0">
                    <Calendar mode="single" selected={selectedDate} onSelect={(d) => { if (d) setSelectedDate(d) }} />
                  </PopoverContent>
                </Popover>
              </div>

              <Button onClick={handleSearch} disabled={isLoading} className="gap-2">
                <Search className={cn("h-4 w-4", isLoading && "animate-spin")} />
                Search
              </Button>

              {isFiltered && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={handleClearFilters}
                  className="gap-1.5 text-muted-foreground"
                >
                  <X className="h-3.5 w-3.5" />
                  Clear Filters
                </Button>
              )}
            </div>
          </CardContent>
        </Card>

        {isLoading && rows.length === 0 ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-20 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Search className="h-8 w-8 text-primary animate-spin" />
              </div>
              <h3 className="text-lg font-semibold">Loading Error Report Data...</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Searching USSD failure records for {format(selectedDate, "dd MMM yyyy")}. Please wait while records are fetched.
              </p>
            </CardContent>
          </Card>
        ) : !hasSearched ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-16 text-center">
              <div className="rounded-full bg-destructive/10 p-4 mb-4">
                <AlertTriangle className="h-8 w-8 text-destructive" />
              </div>
              <h3 className="text-lg font-semibold">Ready to Generate Error & Message Report</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Selected date is {format(selectedDate, "dd MMM yyyy")}. Click Search to load all error logs, TIBCO responses, and subscriber replies, or enter specific search criteria above.
              </p>
              <Button onClick={handleSearch} disabled={isLoading} className="mt-4 gap-2">
                <Search className={cn("h-4 w-4", isLoading && "animate-spin")} />
                Search {format(selectedDate, "yyyy-MM-dd")} Records
              </Button>
            </CardContent>
          </Card>
        ) : (
          <>
            {/* Dynamic Metric Cards */}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <StatTile
                label={isFiltered ? "Matching Failures" : "Total Failed Calls"}
                value={numberFormat.format(summary?.failedCount ?? 0)}
                icon={AlertTriangle}
                detail={isFiltered ? "Calls matching current search criteria" : "All failures on selected day"}
              />
              <StatTile
                label="Total Analyzed"
                value={numberFormat.format(summary?.totalTransactions ?? 0)}
                icon={CheckCircle2}
                detail="Total interaction volume on selected day"
              />
              <StatTile
                label="Error Share"
                value={summary?.totalTransactions ? `${Math.round(((summary.failedCount || 0) * 100) / summary.totalTransactions)}%` : "0%"}
                icon={XCircle}
                detail="Percent of volume failing"
              />
              <StatTile
                label="Failure Latency"
                value={summary?.averageLatencyMs != null ? `${numberFormat.format(Math.round(summary.averageLatencyMs))} ms` : "—"}
                detail="Average time before failure response"
              />
            </div>

            {/* Breakdown of Most Frequent Errors */}
            <Card className="shadow-none">
              <CardHeader className="pb-2">
                <CardTitle className="text-base font-semibold">Error Distribution & Sample Messages</CardTitle>
                <CardDescription>
                  Click any code to filter the transactions table directly
                </CardDescription>
              </CardHeader>
              <CardContent>
                {(summary?.topErrorCodes || []).length === 0 ? (
                  <p className="py-6 text-center text-sm text-muted-foreground">No errors found for this search.</p>
                ) : (
                  <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {summary!.topErrorCodes.map((item) => {
                      const isSelected = selectedErrorCode === item.errorCode
                      return (
                        <div
                          key={item.errorCode}
                          onClick={() => handleQuickFilter(item.errorCode)}
                          className={cn(
                            "rounded-lg border p-3 cursor-pointer hover:border-primary transition-all",
                            isSelected ? "border-primary bg-primary/5" : "bg-card"
                          )}
                        >
                          <div className="flex items-baseline justify-between gap-2">
                            <span className="font-mono text-xs font-bold text-destructive">{item.errorCode}</span>
                            <span className="text-xs font-semibold tabular-nums">{numberFormat.format(item.total)} calls</span>
                          </div>
                          {item.sampleMessage && (
                            <p className="mt-1 text-xs text-muted-foreground line-clamp-2" dir="auto" title={item.sampleMessage}>
                              {item.sampleMessage}
                            </p>
                          )}
                          <div className="mt-2 h-1.5 w-full rounded-full bg-muted">
                            <div
                              className="h-1.5 rounded-full bg-destructive/70"
                              style={{ width: `${(item.total / maxErrorCount) * 100}%` }}
                            />
                          </div>
                        </div>
                      )
                    })}
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Paginated Transactions Table */}
            <div className="flex-1 w-full min-w-0 max-w-full overflow-hidden">
              <DataTable
                columns={columns}
                data={rows}
                title="Failure Transaction Logs"
                description={`${numberFormat.format(totalElements)} record${totalElements === 1 ? "" : "s"} on ${format(selectedDate, "yyyy-MM-dd")}${
                  appliedFilters?.message ? ` · Search: "${appliedFilters.message}"` : ""
                }${appliedFilters?.shortCode ? ` · Shortcode: ${appliedFilters.shortCode}` : ""}${
                  appliedFilters?.errorCode && appliedFilters.errorCode !== "all" ? ` · Error Code: ${appliedFilters.errorCode}` : ""
                }`}
                pageCount={Math.ceil(totalElements / pagination.pageSize) || 1}
                pagination={pagination}
                onPaginationChange={handlePaginationChange}
              />
            </div>
          </>
        )}
      </div>
    </>
  )
}
