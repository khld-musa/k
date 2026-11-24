"use client"

import * as React from "react"
import { format } from "date-fns"
import {
  Calendar as CalendarIcon,
  Download,
  Search,
  X,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Zap,
  Clock,
  Radio,
  ShieldCheck,
  ShieldAlert,
} from "lucide-react"
import { toast } from "sonner"
import { type ColumnDef } from "@tanstack/react-table"

import { DashboardHeader } from "@/components/dashboard-header"
import { ReportsNav } from "@/components/reports-nav"
import { DataTable } from "@/components/data-table"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { ReportExportButton } from "@/components/report-export-button"
import { TruncatedHoverCell } from "@/components/ui/truncated-hover-cell"
import { apiClient, API_BASE_URL } from "@/lib/api-client"
import { cn } from "@/lib/utils"

const STATUS_GOOD = "#0ca30c"
const STATUS_WARNING = "#d97706"
const STATUS_CRITICAL = "#d03b3b"

interface ApnReportSummary {
  startDate: string
  endDate: string
  totalRequests: number
  successCount: number
  throttledCount: number
  failedCount: number
  successRatePercent: number
  averageLatencyMs: number | null
  circuitBreakerState: string
  dailyBreakdown: { day: string; total: number; success: number; throttled: number; failed: number }[]
  statusBreakdown: { status: string; total: number }[]
}

interface ApnRequestRow {
  id: number
  callId: string | null
  msisdn: string
  imsi: string
  status: string
  responseMessage: string | null
  httpStatus: number | null
  latencyMs: number | null
  createdAt: string
}

interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

const numberFormat = new Intl.NumberFormat("en-US")

function ApnStatusBadge({ status }: { status: string }) {
  switch (status) {
    case "SUCCESS":
      return (
        <Badge variant="outline" className="gap-1 border-emerald-500/30 bg-emerald-50 text-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-400">
          <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600" /> Success
        </Badge>
      )
    case "THROTTLED":
      return (
        <Badge variant="outline" className="gap-1 border-amber-500/30 bg-amber-50 text-amber-700 dark:bg-amber-950/30 dark:text-amber-400">
          <AlertTriangle className="h-3.5 w-3.5 text-amber-600" /> Throttled (&gt;18 TPS)
        </Badge>
      )
    case "TIMEOUT":
      return (
        <Badge variant="outline" className="gap-1 border-orange-500/30 bg-orange-50 text-orange-700 dark:bg-orange-950/30 dark:text-orange-400">
          <Clock className="h-3.5 w-3.5 text-orange-600" /> Timeout
        </Badge>
      )
    case "CIRCUIT_OPEN":
      return (
        <Badge variant="outline" className="gap-1 border-rose-500/30 bg-rose-50 text-rose-700 dark:bg-rose-950/30 dark:text-rose-400">
          <ShieldAlert className="h-3.5 w-3.5 text-rose-600" /> Circuit Open
        </Badge>
      )
    default:
      return (
        <Badge variant="outline" className="gap-1 border-destructive/30 bg-destructive/10 text-destructive">
          <XCircle className="h-3.5 w-3.5" /> {status || "Failed"}
        </Badge>
      )
  }
}

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

export default function ApnReportPage() {
  const getToday = () => new Date()

  const [selectedDate, setSelectedDate] = React.useState<Date>(getToday)
  const [msisdnInput, setMsisdnInput] = React.useState("")
  const [imsiInput, setImsiInput] = React.useState("")
  const [statusFilter, setStatusFilter] = React.useState<string>("all")

  const [hasSearched, setHasSearched] = React.useState(false)
  const [appliedFilters, setAppliedFilters] = React.useState<{
    date: Date
    msisdn: string
    imsi: string
    status: string
  } | null>(null)

  const [summary, setSummary] = React.useState<ApnReportSummary | null>(null)
  const [rows, setRows] = React.useState<ApnRequestRow[]>([])
  const [totalElements, setTotalElements] = React.useState(0)
  const [pagination, setPagination] = React.useState({ pageIndex: 0, pageSize: 20 })

  const [isLoading, setIsLoading] = React.useState(false)

  const isFiltered =
    !!msisdnInput.trim() ||
    !!imsiInput.trim() ||
    statusFilter !== "all" ||
    format(selectedDate, "yyyy-MM-dd") !== format(getToday(), "yyyy-MM-dd")

  const buildQuery = (
    filters: { date: Date; msisdn: string; imsi: string; status: string },
    page: number,
    size: number
  ) => {
    const params = new URLSearchParams()
    const dStr = format(filters.date, "yyyy-MM-dd")
    params.set("startDate", dStr)
    params.set("endDate", dStr)
    if (filters.msisdn.trim()) params.set("msisdn", filters.msisdn.trim())
    if (filters.imsi.trim()) params.set("imsi", filters.imsi.trim())
    if (filters.status !== "all") params.set("status", filters.status)
    params.set("page", String(page))
    params.set("size", String(size))
    return params
  }

  const handleSearch = async () => {
    const filters = {
      date: selectedDate,
      msisdn: msisdnInput.trim(),
      imsi: imsiInput.trim(),
      status: statusFilter,
    }
    setAppliedFilters(filters)
    setHasSearched(true)
    setRows([])
    setSummary(null)
    setPagination((p) => ({ ...p, pageIndex: 0 }))

    setIsLoading(true)
    const q = buildQuery(filters, 0, pagination.pageSize)
    const qStr = q.toString()

    const txPromise = apiClient(`/reports/apn/transactions?${qStr}`) as Promise<PageResponse<ApnRequestRow>>
    const summaryPromise = apiClient(`/reports/apn/summary?${qStr}`) as Promise<ApnReportSummary>

    txPromise
      .then((txData) => {
        setRows(txData.content || [])
        setTotalElements(txData.totalElements || 0)
      })
      .catch((e: unknown) => {
        toast.error("Failed to load APN transactions: " + (e instanceof Error ? e.message : String(e)))
      })

    summaryPromise
      .then((summaryData) => {
        setSummary(summaryData)
      })
      .catch((e: unknown) => {
        toast.error("Failed to load APN summary: " + (e instanceof Error ? e.message : String(e)))
      })

    try {
      await Promise.allSettled([txPromise, summaryPromise])
    } finally {
      setIsLoading(false)
    }
  }

  const handleClearFilters = () => {
    setMsisdnInput("")
    setImsiInput("")
    setStatusFilter("all")
    setSelectedDate(getToday())
  }

  const handleQuickFilter = (status: string) => {
    setStatusFilter(status)
    const filters = {
      date: appliedFilters?.date || selectedDate,
      msisdn: appliedFilters?.msisdn || "",
      imsi: appliedFilters?.imsi || "",
      status: status,
    }
    setAppliedFilters(filters)
    setHasSearched(true)
    setPagination((p) => ({ ...p, pageIndex: 0 }))
    setIsLoading(true)
    const q = buildQuery(filters, 0, pagination.pageSize)
    Promise.all([
      apiClient(`/reports/apn/summary?${q.toString()}`),
      apiClient(`/reports/apn/transactions?${q.toString()}`) as Promise<PageResponse<ApnRequestRow>>,
    ])
      .then(([summaryData, txData]) => {
        setSummary(summaryData)
        setRows(txData.content)
        setTotalElements(txData.totalElements)
      })
      .catch((e: unknown) => toast.error("Failed to load: " + String(e)))
      .finally(() => setIsLoading(false))
  }

  const handlePaginationChange = async (next: { pageIndex: number; pageSize: number }) => {
    setPagination(next)
    const filters = appliedFilters || {
      date: selectedDate,
      msisdn: msisdnInput.trim(),
      imsi: imsiInput.trim(),
      status: statusFilter,
    }
    setIsLoading(true)
    try {
      const q = buildQuery(filters, next.pageIndex, next.pageSize)
      const txData: PageResponse<ApnRequestRow> = await apiClient(`/reports/apn/transactions?${q.toString()}`)
      setRows(txData.content)
      setTotalElements(txData.totalElements)
    } catch (e: unknown) {
      toast.error("Error navigating page: " + String(e))
    } finally {
      setIsLoading(false)
    }
  }

  const columns: ColumnDef<ApnRequestRow>[] = [
    {
      accessorKey: "createdAt",
      header: "Timestamp",
      cell: ({ row }) => (
        <span className="whitespace-nowrap font-mono text-xs tabular-nums">
          {format(new Date(row.getValue("createdAt")), "yyyy-MM-dd HH:mm:ss")}
        </span>
      ),
    },
    {
      accessorKey: "msisdn",
      header: "Subscriber MSISDN",
      cell: ({ row }) => <span className="font-mono text-xs font-semibold">{row.getValue("msisdn")}</span>,
    },
    {
      accessorKey: "imsi",
      header: "IMSI",
      cell: ({ row }) => <span className="font-mono text-xs text-muted-foreground">{row.getValue("imsi")}</span>,
    },
    {
      accessorKey: "status",
      header: "Status",
      cell: ({ row }) => <ApnStatusBadge status={row.getValue("status")} />,
    },
    {
      accessorKey: "responseMessage",
      header: "Flash / Response Message",
      cell: ({ row }) => {
        const msg = row.getValue("responseMessage") as string | null
        if (!msg) return <span className="text-muted-foreground">—</span>
        return <TruncatedHoverCell text={msg} maxChars={30} dir="auto" />
      },
    },
    {
      accessorKey: "latencyMs",
      header: () => <div className="text-right">Latency</div>,
      cell: ({ row }) => {
        const ms = row.getValue("latencyMs") as number | null
        return <div className="text-right tabular-nums text-xs">{ms != null ? `${numberFormat.format(ms)} ms` : "—"}</div>
      },
    },
    {
      accessorKey: "httpStatus",
      header: "HTTP",
      cell: ({ row }) => {
        const code = row.getValue("httpStatus") as number | null
        return <span className="font-mono text-xs text-muted-foreground">{code || "—"}</span>
      },
    },
    {
      accessorKey: "callId",
      header: "Call ID",
      cell: ({ row }) => (
        <TruncatedHoverCell text={row.getValue("callId") as string || "—"} maxChars={18} mono variant="muted" />
      ),
    },
  ]

  return (
    <>
      <DashboardHeader breadcrumbs={[{ label: "Dashboard", href: "/dashboard" }, { label: "Reports", href: "/dashboard/reports" }, { label: "APN Requests" }]} />
      <ReportsNav />
      <div className="flex flex-1 flex-col gap-6 p-4 sm:p-6 w-full min-w-0 max-w-full overflow-x-hidden">
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="grid gap-1">
            <h1 className="text-2xl font-semibold tracking-tight">Real-Time APN Push Report</h1>
            <p className="text-sm text-muted-foreground">
              Monitor external APN API calls triggered by USSD subscribers, rate limiting (&lt;20 TPS), and latency
            </p>
          </div>
          <ReportExportButton
            reportType="APN"
            defaultLabel="Export APN Records (.xlsx)"
            disabled={totalElements === 0}
            getFilters={() => {
              const filters = appliedFilters || {
                date: selectedDate,
                msisdn: msisdnInput.trim(),
                imsi: imsiInput.trim(),
                status: statusFilter,
              }
              const dStr = format(filters.date, "yyyy-MM-dd")
              return {
                date: dStr,
                startDate: dStr,
                endDate: dStr,
                msisdn: filters.msisdn,
                imsi: filters.imsi,
                status: filters.status,
              }
            }}
          />
        </div>

        {/* Filter Controls Bar */}
        <Card className="shadow-none">
          <CardContent className="p-4 space-y-3">
            <div className="flex flex-wrap items-end gap-4">
              {/* MSISDN */}
              <div className="grid gap-1.5 min-w-[200px]">
                <Label className="text-xs font-medium text-muted-foreground">Subscriber MSISDN</Label>
                <div className="relative">
                  <Input
                    placeholder="e.g. 249123004940"
                    value={msisdnInput}
                    onChange={(e) => setMsisdnInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") handleSearch()
                    }}
                    className="font-mono text-sm"
                  />
                  {msisdnInput && (
                    <button
                      onClick={() => setMsisdnInput("")}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>

              {/* IMSI */}
              <div className="grid gap-1.5 min-w-[200px]">
                <Label className="text-xs font-medium text-muted-foreground">IMSI</Label>
                <div className="relative">
                  <Input
                    placeholder="e.g. 634070587204271"
                    value={imsiInput}
                    onChange={(e) => setImsiInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") handleSearch()
                    }}
                    className="font-mono text-sm"
                  />
                  {imsiInput && (
                    <button
                      onClick={() => setImsiInput("")}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>

              {/* Status */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Status</Label>
                <SearchableSelect
                  value={statusFilter}
                  onValueChange={setStatusFilter}
                  options={[
                    { value: "SUCCESS", label: "Success" },
                    { value: "THROTTLED", label: "Throttled (>18 TPS)" },
                    { value: "FAILED", label: "Failed" },
                    { value: "TIMEOUT", label: "Timeout" },
                    { value: "CIRCUIT_OPEN", label: "Circuit Open" },
                  ]}
                  allOptionLabel="All Statuses"
                  placeholder="All Statuses"
                  searchPlaceholder="Filter status..."
                  triggerClassName="w-[170px]"
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
                <Button variant="ghost" size="sm" onClick={handleClearFilters} className="gap-1.5 text-muted-foreground">
                  <X className="h-3.5 w-3.5" />
                  Clear Filters
                </Button>
              )}
            </div>
          </CardContent>
        </Card>

        {/* Results */}
        {isLoading && rows.length === 0 ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-20 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Search className="h-8 w-8 text-primary animate-spin" />
              </div>
              <h3 className="text-lg font-semibold">Loading APN Report Data...</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Searching APN push records for {format(selectedDate, "dd MMM yyyy")}. Please wait while records are fetched.
              </p>
            </CardContent>
          </Card>
        ) : !hasSearched ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-16 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Radio className="h-8 w-8 text-primary" />
              </div>
              <h3 className="text-lg font-semibold">Ready to Generate APN Push Report</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Selected date is {format(selectedDate, "dd MMM yyyy")}. Click Search to load APN push records, or enter an MSISDN/IMSI above.
              </p>
              <Button onClick={handleSearch} disabled={isLoading} className="mt-4 gap-2">
                <Search className={cn("h-4 w-4", isLoading && "animate-spin")} />
                Search {format(selectedDate, "yyyy-MM-dd")} Records
              </Button>
            </CardContent>
          </Card>
        ) : (
          <>
            {/* Metric KPI Tiles */}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
              <StatTile
                label="Total APN Calls"
                value={numberFormat.format(summary?.totalRequests ?? 0)}
                icon={Radio}
                detail="Direct USSD requests received"
              />
              <StatTile
                label="Success Rate"
                value={`${summary?.successRatePercent ?? 0}%`}
                icon={CheckCircle2}
                detail={`${numberFormat.format(summary?.successCount ?? 0)} delivered`}
              />
              <StatTile
                label="Throttled (<20 TPS)"
                value={numberFormat.format(summary?.throttledCount ?? 0)}
                icon={AlertTriangle}
                detail="Capped to protect Laravel API"
              />
              <StatTile
                label="Failed / Timeouts"
                value={numberFormat.format(summary?.failedCount ?? 0)}
                icon={XCircle}
                detail="API errors or session drops"
              />
              <StatTile
                label="Average Latency"
                value={summary?.averageLatencyMs != null ? `${numberFormat.format(Math.round(summary.averageLatencyMs))} ms` : "—"}
                icon={Zap}
                detail={`Circuit: ${summary?.circuitBreakerState ?? "CLOSED"}`}
              />
            </div>

            {/* Status Breakdown Chips */}
            {(summary?.statusBreakdown || []).length > 0 && (
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-medium text-muted-foreground">Quick filter by status:</span>
                {summary!.statusBreakdown.map((item) => (
                  <button
                    key={item.status}
                    onClick={() => handleQuickFilter(item.status)}
                    className={cn(
                      "inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors hover:bg-muted",
                      statusFilter === item.status && "border-primary bg-primary/10 text-primary"
                    )}
                  >
                    <span>{item.status}</span>
                    <span className="rounded-full bg-muted-foreground/20 px-1.5 py-0.2 text-[10px] tabular-nums">
                      {numberFormat.format(item.total)}
                    </span>
                  </button>
                ))}
              </div>
            )}

            {/* Paginated Transactions Table */}
            <div className="flex-1 w-full min-w-0 max-w-full overflow-hidden">
              <DataTable
                columns={columns}
                data={rows}
                title="APN Transaction Logs"
                description={`${numberFormat.format(totalElements)} record${totalElements === 1 ? "" : "s"} found${
                  appliedFilters?.status && appliedFilters.status !== "all" ? ` · Status: ${appliedFilters.status}` : ""
                }${appliedFilters?.msisdn ? ` · MSISDN: ${appliedFilters.msisdn}` : ""}`}
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
