"use client"

import * as React from "react"
import { format } from "date-fns"
import {
  Calendar as CalendarIcon,
  Download,
  RefreshCw,
  Search,
  X,
  User,
  CheckCircle2,
  XCircle,
  CircleHelp,
  PhoneCall,
  Activity,
  Layers,
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
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { FilterSearchInput } from "@/components/ui/filter-search-input"
import { ReportExportButton } from "@/components/report-export-button"
import { TruncatedHoverCell } from "@/components/ui/truncated-hover-cell"
import { apiClient, API_BASE_URL } from "@/lib/api-client"
import { cn } from "@/lib/utils"

const STATUS_GOOD = "#0ca30c"
const STATUS_CRITICAL = "#d03b3b"

interface ReportSummary {
  startDate: string
  endDate: string
  totalTransactions: number
  successCount: number
  failedCount: number
  successRatePercent: number
  averageLatencyMs: number | null
  topShortCodes: { shortCode: string; productName: string | null; total: number; success: number; failed: number }[]
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
  serviceType: string | null
  channel: string | null
  cellId: string | null
  vlr: string | null
  nodeIp: string | null
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

function StatusLabel({ status }: { status: string | null }) {
  if (status === "SUCCESS") {
    return (
      <span className="inline-flex items-center gap-1.5 text-sm">
        <CheckCircle2 className="h-4 w-4" style={{ color: STATUS_GOOD }} aria-hidden /> Success
      </span>
    )
  }
  if (status === "FAILED") {
    return (
      <span className="inline-flex items-center gap-1.5 text-sm">
        <XCircle className="h-4 w-4" style={{ color: STATUS_CRITICAL }} aria-hidden /> Failed
      </span>
    )
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
      <CircleHelp className="h-4 w-4" aria-hidden /> Unknown
    </span>
  )
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

export default function SubscribersReportPage() {
  const getToday = () => new Date()

  const [selectedDate, setSelectedDate] = React.useState<Date>(getToday)
  const [msisdnInput, setMsisdnInput] = React.useState("")
  const [statusFilter, setStatusFilter] = React.useState<string>("all")
  const [shortCodeFilter, setShortCodeFilter] = React.useState<string>("")
  const [shortCodeOptions, setShortCodeOptions] = React.useState<string[]>([])

  const [hasSearched, setHasSearched] = React.useState(false)
  const [appliedFilters, setAppliedFilters] = React.useState<{
    date: Date
    msisdn: string
    status: string
    shortCode: string
  } | null>(null)

  const [summary, setSummary] = React.useState<ReportSummary | null>(null)
  const [rows, setRows] = React.useState<UssdTransactionRow[]>([])
  const [totalElements, setTotalElements] = React.useState(0)
  const [pagination, setPagination] = React.useState({ pageIndex: 0, pageSize: 20 })

  const [isLoading, setIsLoading] = React.useState(false)

  React.useEffect(() => {
    apiClient("/reports/filters/short-codes")
      .then((data: string[]) => setShortCodeOptions(data || []))
      .catch(() => {})
  }, [])

  const buildQuery = (
    filters: { date: Date; msisdn: string; status: string; shortCode: string },
    page: number,
    size: number
  ) => {
    const params = new URLSearchParams()
    const dStr = format(filters.date, "yyyy-MM-dd")
    params.set("startDate", dStr)
    params.set("endDate", dStr)
    if (filters.msisdn.trim()) params.set("msisdn", filters.msisdn.trim())
    if (filters.status && filters.status !== "all") params.set("status", filters.status)
    if (filters.shortCode && filters.shortCode !== "all" && filters.shortCode.trim()) {
      params.set("shortCode", filters.shortCode.trim())
    }
    params.set("page", String(page))
    params.set("size", String(size))
    return params
  }

  const handleSearch = async () => {
    const filters = {
      date: selectedDate,
      msisdn: msisdnInput.trim(),
      status: statusFilter,
      shortCode: shortCodeFilter,
    }
    setAppliedFilters(filters)
    setHasSearched(true)
    setRows([])
    setSummary(null)
    setPagination((p) => ({ ...p, pageIndex: 0 }))

    setIsLoading(true)
    const q = buildQuery(filters, 0, pagination.pageSize)
    const qStr = q.toString()

    const txPromise = apiClient(`/reports/transactions?${qStr}`) as Promise<PageResponse<UssdTransactionRow>>
    const summaryPromise = apiClient(`/reports/summary?${qStr}`) as Promise<ReportSummary>

    txPromise
      .then((txData) => {
        setRows(txData.content || [])
        setTotalElements(txData.totalElements || 0)
      })
      .catch((e: unknown) => {
        toast.error("Failed to load transactions: " + (e instanceof Error ? e.message : String(e)))
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
    setMsisdnInput("")
    setStatusFilter("all")
    setShortCodeFilter("")
    setSelectedDate(getToday())
  }

  const handlePaginationChange = async (next: { pageIndex: number; pageSize: number }) => {
    setPagination(next)
    const filters = appliedFilters || {
      date: selectedDate,
      msisdn: msisdnInput.trim(),
      status: statusFilter,
      shortCode: shortCodeFilter,
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

  const columns: ColumnDef<UssdTransactionRow>[] = [
    {
      accessorKey: "triggeredAt",
      header: "Call Time",
      cell: ({ row }) => {
        const val = row.getValue("triggeredAt")
        return (
          <span className="whitespace-nowrap font-mono text-xs tabular-nums">
            {val ? format(new Date(val as string), "yyyy-MM-dd HH:mm:ss") : "—"}
          </span>
        )
      },
    },
    {
      accessorKey: "msisdn",
      header: "MSISDN",
      cell: ({ row }) => <span className="font-mono text-xs font-semibold">{row.getValue("msisdn") || "—"}</span>,
    },
    {
      accessorKey: "shortCode",
      header: "Short Code",
      cell: ({ row }) => <span className="font-mono text-xs">{row.getValue("shortCode") || "—"}</span>,
    },
    {
      accessorKey: "productName",
      header: "Package / Service",
      cell: ({ row }) => <span className="text-sm font-medium">{row.getValue("productName") || "—"}</span>,
    },
    {
      accessorKey: "status",
      header: "Status",
      cell: ({ row }) => <StatusLabel status={row.getValue("status")} />,
    },
    {
      accessorKey: "errorMessage",
      header: "Response / Error Message",
      cell: ({ row }) => {
        const { errorCode, errorMessage } = row.original
        if (!errorCode && !errorMessage) return <span className="text-muted-foreground">—</span>
        return (
          <div className="max-w-[280px]">
            {errorCode && <p className="font-mono text-xs text-muted-foreground">{errorCode}</p>}
            {errorMessage && (
              <TruncatedHoverCell
                text={errorMessage}
                maxChars={35}
                maxWidthClass="max-w-[260px]"
                dir="auto"
              />
            )}
          </div>
        )
      },
    },
    {
      accessorKey: "latencyMs",
      header: () => <div className="text-right">Latency</div>,
      cell: ({ row }) => {
        const ms = row.getValue("latencyMs") as number | null
        return <div className="text-right tabular-nums">{ms != null ? `${numberFormat.format(ms)} ms` : "—"}</div>
      },
    },
    {
      accessorKey: "cellId",
      header: "Cell ID / VLR",
      cell: ({ row }) => {
        const { cellId, vlr } = row.original
        if (!cellId && !vlr) return <span className="text-muted-foreground">—</span>
        return (
          <div className="text-xs font-mono text-muted-foreground">
            {cellId && <p>Cell: {cellId}</p>}
            {vlr && <p>VLR: {vlr}</p>}
          </div>
        )
      },
    },
  ]

  const isFiltered =
    !!msisdnInput ||
    statusFilter !== "all" ||
    Boolean(shortCodeFilter && shortCodeFilter !== "all") ||
    format(selectedDate, "yyyy-MM-dd") !== format(getToday(), "yyyy-MM-dd")

  return (
    <>
      <DashboardHeader breadcrumbs={[{ label: "Dashboard", href: "/dashboard" }, { label: "Reports", href: "/dashboard/reports" }, { label: "Subscriber (MSISDN)" }]} />
      <ReportsNav />
      <div className="flex flex-1 flex-col gap-6 p-4 sm:p-6 w-full min-w-0 max-w-full overflow-x-hidden">
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="grid gap-1">
            <h1 className="text-2xl font-semibold tracking-tight">Subscriber (MSISDN) Report</h1>
            <p className="text-sm text-muted-foreground">
              Investigate subscriber interaction history, package subscriptions, and USSD journey
            </p>
          </div>
          <ReportExportButton
            reportType="SUBSCRIBERS"
            defaultLabel="Export Customer Records (.xlsx)"
            disabled={totalElements === 0}
            getFilters={() => {
              const filters = appliedFilters || {
                date: selectedDate,
                msisdn: msisdnInput.trim(),
                status: statusFilter,
                shortCode: shortCodeFilter,
              }
              const dStr = format(filters.date, "yyyy-MM-dd")
              return {
                date: dStr,
                startDate: dStr,
                endDate: dStr,
                msisdn: filters.msisdn,
                status: filters.status,
                shortCode: filters.shortCode,
              }
            }}
          />
        </div>

        {/* MSISDN Search and Filter Bar */}
        <Card className="shadow-none border-primary/20">
          <CardContent className="p-4">
            <div className="flex flex-wrap items-end gap-4">
              {/* MSISDN Input (Prominent) */}
              <div className="grid gap-1.5 flex-1 min-w-[240px] max-w-[340px]">
                <Label className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                  <User className="h-3.5 w-3.5 text-primary" /> Subscriber MSISDN
                </Label>
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    placeholder="e.g. 249912345678"
                    value={msisdnInput}
                    onChange={(e) => setMsisdnInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") handleSearch()
                    }}
                    className="pl-9 pr-8 font-mono text-sm"
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

              {/* Outcome filter */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Status</Label>
                <SearchableSelect
                  value={statusFilter}
                  onValueChange={setStatusFilter}
                  options={[
                    { value: "SUCCESS", label: "Success Only" },
                    { value: "FAILED", label: "Failed Only" },
                  ]}
                  allOptionLabel="All Outcomes"
                  placeholder="All Outcomes"
                  searchPlaceholder="Filter status..."
                  triggerClassName="w-[140px]"
                />
              </div>

              {/* Short code search filter */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Short Code (كود الخدمة)</Label>
                <FilterSearchInput
                  value={shortCodeFilter}
                  onChange={setShortCodeFilter}
                  onSearch={handleSearch}
                  suggestions={shortCodeOptions}
                  placeholder="e.g. *400 or *123#..."
                  className="w-[180px]"
                  inputClassName="font-mono text-xs"
                />
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

        {isLoading && rows.length === 0 ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-20 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Search className="h-8 w-8 text-primary animate-spin" />
              </div>
              <h3 className="text-lg font-semibold">Loading Subscriber Report Data...</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Searching subscriber transactions for {format(selectedDate, "dd MMM yyyy")}. Please wait while records are fetched.
              </p>
            </CardContent>
          </Card>
        ) : !hasSearched ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-16 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Search className="h-8 w-8 text-primary" />
              </div>
              <h3 className="text-lg font-semibold">Ready to Generate Subscriber Report</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Selected date is {format(selectedDate, "dd MMM yyyy")}. Click Search to load all subscriber interactions, or filter by MSISDN above.
              </p>
              <Button onClick={handleSearch} disabled={isLoading} className="mt-4 gap-2">
                <Search className={cn("h-4 w-4", isLoading && "animate-spin")} />
                Search {format(selectedDate, "yyyy-MM-dd")} Records
              </Button>
            </CardContent>
          </Card>
        ) : (
          <>
            {/* Dynamic Subscriber KPIs */}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <StatTile
                label={appliedFilters?.msisdn ? `Calls by ${appliedFilters.msisdn}` : "Total Calls in Range"}
                value={numberFormat.format(summary?.totalTransactions ?? 0)}
                icon={PhoneCall}
                detail={appliedFilters?.msisdn ? "Customer call history" : "Enter MSISDN to isolate single subscriber"}
              />
              <StatTile
                label="Success Rate"
                value={`${summary?.successRatePercent ?? 0}%`}
                icon={CheckCircle2}
                detail={`${numberFormat.format(summary?.successCount ?? 0)} completed`}
              />
              <StatTile
                label="Failed Interactions"
                value={numberFormat.format(summary?.failedCount ?? 0)}
                icon={XCircle}
                detail="Error responses or rejects"
              />
              <StatTile
                label="Packages / Codes Tried"
                value={(summary?.topShortCodes || []).length}
                icon={Layers}
                detail="Distinct services accessed"
              />
            </div>

            {/* Paginated Subscriber Transactions */}
            <div className="flex-1 w-full min-w-0 max-w-full overflow-hidden">
              <DataTable
                columns={columns}
                data={rows}
                title={appliedFilters?.msisdn ? `Customer Journey: ${appliedFilters.msisdn}` : "All Subscriber Calls"}
                description={`${numberFormat.format(totalElements)} record${totalElements === 1 ? "" : "s"} found`}
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
