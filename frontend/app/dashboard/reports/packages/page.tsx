"use client"

import * as React from "react"
import { format } from "date-fns"
import {
  Calendar as CalendarIcon,
  Download,
  RefreshCw,
  Package as PackageIcon,
  CheckCircle2,
  XCircle,
  CircleHelp,
  Search,
  X,
  Filter,
} from "lucide-react"
import { toast } from "sonner"
import { type ColumnDef } from "@tanstack/react-table"

import { DashboardHeader } from "@/components/dashboard-header"
import { ReportsNav } from "@/components/reports-nav"
import { DataTable } from "@/components/data-table"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { FilterSearchInput } from "@/components/ui/filter-search-input"
import { ReportExportButton } from "@/components/report-export-button"
import { Badge } from "@/components/ui/badge"
import { TruncatedHoverCell } from "@/components/ui/truncated-hover-cell"
import { cn } from "@/lib/utils"
import { apiClient, API_BASE_URL } from "@/lib/api-client"

const STATUS_GOOD = "#0ca30c"
const STATUS_CRITICAL = "#d03b3b"

interface ReportSummary {
  startDate: string
  endDate: string
  nodeIp: string | null
  totalTransactions: number
  successCount: number
  failedCount: number
  successRatePercent: number
  averageLatencyMs: number | null
  topShortCodes: { shortCode: string; productName: string | null; total: number; success: number; failed: number }[]
  topPackages?: { productName: string; shortCode: string | null; total: number; success: number; failed: number }[]
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

function StatTile({ label, value, detail }: { label: string; value: React.ReactNode; detail?: React.ReactNode }) {
  return (
    <Card className="shadow-none">
      <CardContent className="p-5">
        <p className="text-sm font-medium text-muted-foreground">{label}</p>
        <p className="mt-1 text-2xl font-bold tabular-nums">{value}</p>
        {detail && <p className="mt-1 text-xs text-muted-foreground">{detail}</p>}
      </CardContent>
    </Card>
  )
}

export default function PackagesReportPage() {
  const getToday = () => new Date()

  const [selectedDate, setSelectedDate] = React.useState<Date>(getToday)
  const [selectedShortCode, setSelectedShortCode] = React.useState<string>("")
  const [selectedProduct, setSelectedProduct] = React.useState<string>("")
  const [statusFilter, setStatusFilter] = React.useState<string>("all")

  const [shortCodeOptions, setShortCodeOptions] = React.useState<string[]>([])
  const [productOptions, setProductOptions] = React.useState<string[]>([])

  const [hasSearched, setHasSearched] = React.useState(false)
  const [appliedFilters, setAppliedFilters] = React.useState<{
    date: Date
    shortCode: string
    productName: string
    status: string
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

    apiClient("/reports/filters/products")
      .then((data: string[]) => setProductOptions(data || []))
      .catch(() => {})
  }, [])

  const buildQuery = (
    filters: { date: Date; shortCode: string; productName: string; status: string },
    page: number,
    size: number
  ) => {
    const params = new URLSearchParams()
    const dStr = format(filters.date, "yyyy-MM-dd")
    params.set("startDate", dStr)
    params.set("endDate", dStr)
    if (filters.shortCode && filters.shortCode !== "all" && filters.shortCode.trim()) {
      params.set("shortCode", filters.shortCode.trim())
    }
    if (filters.productName && filters.productName !== "all" && filters.productName.trim()) {
      params.set("productName", filters.productName.trim())
    }
    if (filters.status && filters.status !== "all") {
      params.set("status", filters.status)
    }
    params.set("page", String(page))
    params.set("size", String(size))
    return params
  }

  const handleSearch = async () => {
    const filters = {
      date: selectedDate,
      shortCode: selectedShortCode,
      productName: selectedProduct,
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
    setSelectedShortCode("")
    setSelectedProduct("")
    setStatusFilter("all")
    setSelectedDate(getToday())
  }

  const handlePaginationChange = async (next: { pageIndex: number; pageSize: number }) => {
    setPagination(next)
    const filters = appliedFilters || {
      date: selectedDate,
      shortCode: selectedShortCode,
      productName: selectedProduct,
      status: statusFilter,
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
      header: "Timestamp",
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
      accessorKey: "shortCode",
      header: "Short Code",
      cell: ({ row }) => <span className="font-mono text-xs font-semibold">{row.getValue("shortCode") || "—"}</span>,
    },
    {
      accessorKey: "productName",
      header: "Package / Product",
      cell: ({ row }) => <TruncatedHoverCell text={row.getValue("productName") as string || "—"} maxChars={22} />,
    },
    {
      accessorKey: "msisdn",
      header: "MSISDN",
      cell: ({ row }) => <span className="font-mono text-xs tabular-nums">{row.getValue("msisdn") || "—"}</span>,
    },
    {
      accessorKey: "status",
      header: "Outcome",
      cell: ({ row }) => <StatusLabel status={row.getValue("status")} />,
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
      accessorKey: "channel",
      header: "Channel",
      cell: ({ row }) => <span className="text-xs text-muted-foreground">{row.original.channel || "—"}</span>,
    },
    {
      accessorKey: "nodeIp",
      header: "Node",
      cell: ({ row }) => <span className="font-mono text-xs">{row.getValue("nodeIp") || "—"}</span>,
    },
  ]

  const isFiltered =
    Boolean(selectedShortCode && selectedShortCode !== "all") ||
    Boolean(selectedProduct && selectedProduct !== "all") ||
    statusFilter !== "all" ||
    format(selectedDate, "yyyy-MM-dd") !== format(getToday(), "yyyy-MM-dd")

  const handleQuickFilter = (code: string) => {
    setSelectedShortCode(code)
    const filters = {
      date: appliedFilters?.date || selectedDate,
      shortCode: code,
      productName: "",
      status: appliedFilters?.status || "all",
    }
    setAppliedFilters(filters)
    setHasSearched(true)
    setPagination((p) => ({ ...p, pageIndex: 0 }))
    setIsLoading(true)
    const q = buildQuery(filters, 0, pagination.pageSize)
    Promise.all([
      apiClient(`/reports/summary?${q.toString()}`),
      apiClient(`/reports/transactions?${q.toString()}`) as Promise<PageResponse<UssdTransactionRow>>,
    ])
      .then(([summaryData, txData]) => {
        setSummary(summaryData)
        setRows(txData.content)
        setTotalElements(txData.totalElements)
      })
      .catch((e: unknown) => toast.error("Failed to load: " + String(e)))
      .finally(() => setIsLoading(false))
  }

  return (
    <>
      <DashboardHeader breadcrumbs={[{ label: "Dashboard", href: "/dashboard" }, { label: "Reports", href: "/dashboard/reports" }, { label: "Packages & Short Codes" }]} />
      <ReportsNav />
      <div className="flex flex-1 flex-col gap-6 p-4 sm:p-6 w-full min-w-0 max-w-full overflow-x-hidden">
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="grid gap-1">
            <h1 className="text-2xl font-semibold tracking-tight">Package & Short Code Report</h1>
            <p className="text-sm text-muted-foreground">
              Analyze traffic volume, success rates, and customer requests per package and short code
            </p>
          </div>
          <ReportExportButton
            reportType="PACKAGES"
            defaultLabel="Export Package Report (.xlsx)"
            disabled={totalElements === 0}
            getFilters={() => {
              const filters = appliedFilters || {
                date: selectedDate,
                shortCode: selectedShortCode,
                productName: selectedProduct,
                status: statusFilter,
              }
              const dStr = format(filters.date, "yyyy-MM-dd")
              return {
                date: dStr,
                startDate: dStr,
                endDate: dStr,
                shortCode: filters.shortCode,
                productName: filters.productName,
                status: filters.status,
              }
            }}
          />
        </div>

        {/* Filters */}
        <Card className="shadow-none">
          <CardContent className="p-4">
            <div className="flex flex-wrap items-end gap-4">
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

              {/* Short Code Search Input */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Short Code (كود الخدمة)</Label>
                <FilterSearchInput
                  value={selectedShortCode}
                  onChange={setSelectedShortCode}
                  onSearch={handleSearch}
                  suggestions={shortCodeOptions}
                  placeholder="e.g. *400 or *123#..."
                  className="w-[190px]"
                  inputClassName="font-mono text-xs"
                />
              </div>

              {/* Package / Product Search Input */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Package / Product (اسم الباقة)</Label>
                <FilterSearchInput
                  value={selectedProduct}
                  onChange={setSelectedProduct}
                  onSearch={handleSearch}
                  suggestions={productOptions}
                  placeholder="e.g. Daily, Monthly..."
                  className="w-[210px]"
                  inputClassName="text-xs"
                />
              </div>

              {/* Outcome */}
              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Outcome</Label>
                <SearchableSelect
                  value={statusFilter}
                  onValueChange={setStatusFilter}
                  options={[
                    { value: "SUCCESS", label: "Success Only" },
                    { value: "FAILED", label: "Failed Only" },
                  ]}
                  allOptionLabel="All Statuses"
                  placeholder="All Statuses"
                  searchPlaceholder="Filter status..."
                  triggerClassName="w-[140px]"
                />
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
              <h3 className="text-lg font-semibold">Loading Package Report Data...</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Searching package and product records for {format(selectedDate, "dd MMM yyyy")}. Please wait while records are fetched.
              </p>
            </CardContent>
          </Card>
        ) : !hasSearched ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-16 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Search className="h-8 w-8 text-primary" />
              </div>
              <h3 className="text-lg font-semibold">Ready to Generate Package Report</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Selected date is {format(selectedDate, "dd MMM yyyy")}. Click Search to load all package records, or select specific filters above.
              </p>
              <Button onClick={handleSearch} disabled={isLoading} className="mt-4 gap-2">
                <Search className={cn("h-4 w-4", isLoading && "animate-spin")} />
                Search {format(selectedDate, "yyyy-MM-dd")} Records
              </Button>
            </CardContent>
          </Card>
        ) : (
          <>
            {/* Dynamic Metric Cards (strictly calculate for the applied filter!) */}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatTile
            label={isFiltered ? "Filtered Calls" : "Total Package Calls"}
            value={numberFormat.format(summary?.totalTransactions ?? 0)}
            detail={isFiltered ? "Matching current filter" : "Across all packages"}
          />
          <StatTile
            label="Package Success Rate"
            value={`${summary?.successRatePercent ?? 0}%`}
            detail={`${numberFormat.format(summary?.successCount ?? 0)} completed`}
          />
          <StatTile
            label="Failed Requests"
            value={numberFormat.format(summary?.failedCount ?? 0)}
            detail="Error or rejected orders"
          />
          <StatTile
            label="Average Latency"
            value={summary?.averageLatencyMs != null ? `${numberFormat.format(Math.round(summary.averageLatencyMs))} ms` : "—"}
            detail="Processing response time"
          />
        </div>

        {/* Comparison Table of Packages & Short Codes */}
        <Card className="shadow-none">
          <CardHeader className="pb-2">
            <CardTitle className="text-base font-semibold">Service Performance Overview</CardTitle>
            <CardDescription>
              Volume and success rate breakdown across packages & short codes. Click &quot;Filter&quot; to inspect any code.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {(summary?.topShortCodes || []).length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">No service data for the selected period.</p>
            ) : (
              <div className="overflow-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2.5 font-medium">Short Code</th>
                      <th className="py-2.5 font-medium">Package / Product</th>
                      <th className="py-2.5 text-right font-medium">Total Volume</th>
                      <th className="py-2.5 text-right font-medium">Successful</th>
                      <th className="py-2.5 text-right font-medium">Failed</th>
                      <th className="py-2.5 text-right font-medium">Success Rate</th>
                      <th className="py-2.5 text-center font-medium">Quick Filter</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary!.topShortCodes.map((item) => {
                      const successRate = item.total ? Math.round((item.success * 100) / item.total) : 0
                      const isSelected = selectedShortCode === item.shortCode
                      return (
                        <tr
                          key={item.shortCode}
                          className={cn("border-b last:border-0 hover:bg-muted/30 transition-colors", isSelected && "bg-muted/40 font-medium")}
                        >
                          <td className="py-2.5 font-mono text-xs font-semibold">{item.shortCode}</td>
                          <td className="py-2.5">{item.productName && item.productName !== item.shortCode ? item.productName : "—"}</td>
                          <td className="py-2.5 text-right tabular-nums">{numberFormat.format(item.total)}</td>
                          <td className="py-2.5 text-right tabular-nums text-emerald-600">{numberFormat.format(item.success)}</td>
                          <td className="py-2.5 text-right tabular-nums text-rose-600">{numberFormat.format(item.failed)}</td>
                          <td className="py-2.5 text-right tabular-nums">{successRate}%</td>
                          <td className="py-2.5 text-center">
                            <Button
                              variant={isSelected ? "secondary" : "ghost"}
                              size="sm"
                              className="h-7 text-xs gap-1"
                              onClick={() => handleQuickFilter(item.shortCode)}
                            >
                              <Filter className="h-3 w-3" />
                              {isSelected ? "Active" : "Filter"}
                            </Button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Server-Side Paginated Transactions Table */}
        <div className="flex-1 w-full min-w-0 max-w-full overflow-hidden">
          <DataTable
            columns={columns}
            data={rows}
            title="Package Transaction Records"
            description={`${numberFormat.format(totalElements)} record${totalElements === 1 ? "" : "s"}${
              selectedShortCode !== "all" ? ` · Short Code: ${selectedShortCode}` : ""
            }${selectedProduct !== "all" ? ` · Package: ${selectedProduct}` : ""}`}
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
