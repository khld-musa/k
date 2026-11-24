"use client"

import * as React from "react"
import Link from "next/link"
import { format, parseISO } from "date-fns"
import {
  Calendar as CalendarIcon,
  Download,
  RefreshCw,
  PlayCircle,
  DownloadCloud,
  ArrowRight,
  TrendingUp,
  Server,
  Package,
  AlertTriangle,
  Search,
  X,
  Layers,
} from "lucide-react"
import { toast } from "sonner"
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts"

import { DashboardHeader } from "@/components/dashboard-header"
import { ReportsNav } from "@/components/reports-nav"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { FilterSearchInput } from "@/components/ui/filter-search-input"
import { ReportExportButton } from "@/components/report-export-button"
import { cn } from "@/lib/utils"
import { apiClient, API_BASE_URL } from "@/lib/api-client"
import { useAuth } from "@/lib/auth-context"

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
  dailyBreakdown: { day: string; total: number; success: number; failed: number }[]
  nodeBreakdown: { nodeIp: string; total: number }[]
  topErrorCodes: { errorCode: string; total: number; sampleMessage: string | null }[]
  topShortCodes: { shortCode: string; productName: string | null; total: number; success: number; failed: number }[]
  topPackages?: { productName: string; shortCode: string | null; total: number; success: number; failed: number }[]
}

interface ServerOption {
  id: string
  name: string
  ipAddress: string
}

interface FetchResult {
  serverName: string
  status: "FETCHED" | "NO_NEW_FILES" | "SKIPPED" | "ERROR"
  filesFetched: number
  message: string
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
        <p className="mt-2 text-3xl font-semibold tabular-nums">{value}</p>
        {detail && <p className="mt-1 text-xs text-muted-foreground">{detail}</p>}
      </CardContent>
    </Card>
  )
}

function DailyTooltip({ active, payload }: { active?: boolean; payload?: { payload: { label: string; success: number; failed: number } }[] }) {
  if (!active || !payload?.length) return null
  const d = payload[0].payload
  const total = d.success + d.failed
  return (
    <div className="rounded-md border bg-popover px-3 py-2 text-xs shadow-sm">
      <p className="mb-1 font-medium">{d.label}</p>
      <p className="flex items-center gap-2">
        <span className="h-2 w-2 rounded-full" style={{ background: STATUS_GOOD }} />
        Successful <span className="ml-auto pl-4 tabular-nums">{numberFormat.format(d.success)}</span>
      </p>
      <p className="flex items-center gap-2">
        <span className="h-2 w-2 rounded-full" style={{ background: STATUS_CRITICAL }} />
        Failed <span className="ml-auto pl-4 tabular-nums">{numberFormat.format(d.failed)}</span>
      </p>
      <p className="mt-1 border-t pt-1 text-muted-foreground">
        Total <span className="float-right tabular-nums">{numberFormat.format(total)}</span>
      </p>
    </div>
  )
}

export default function ExecutiveSummaryPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === "ADMIN"

  const getToday = () => new Date()

  const [selectedDate, setSelectedDate] = React.useState<Date>(getToday)
  const [nodeIp, setNodeIp] = React.useState<string>("all")
  const [statusFilter, setStatusFilter] = React.useState<string>("all")
  const [shortCode, setShortCode] = React.useState<string>("")
  const [shortCodeOptions, setShortCodeOptions] = React.useState<string[]>([])
  const [servers, setServers] = React.useState<ServerOption[]>([])

  const [hasSearched, setHasSearched] = React.useState(false)
  const [appliedFilters, setAppliedFilters] = React.useState<{
    date: Date
    nodeIp: string
    status: string
    shortCode: string
  } | null>(null)

  const [summary, setSummary] = React.useState<ReportSummary | null>(null)
  const [isLoading, setIsLoading] = React.useState(false)
  const [isIngesting, setIsIngesting] = React.useState(false)
  const [isFetching, setIsFetching] = React.useState(false)
  const [isRebuildingRollup, setIsRebuildingRollup] = React.useState(false)

  React.useEffect(() => {
    apiClient("/servers/active")
      .then((data: unknown[]) => {
        setServers(
          data.map((s) => {
            const srv = s as Record<string, unknown>
            return { id: String(srv.id), name: srv.name as string, ipAddress: srv.ipAddress as string }
          })
        )
      })
      .catch(() => {})

    apiClient("/reports/filters/short-codes")
      .then((data: string[]) => setShortCodeOptions(data || []))
      .catch(() => {})
  }, [])

  const executeFetch = async (filters: { date: Date; nodeIp: string; status: string; shortCode: string }) => {
    setIsLoading(true)
    try {
      const params = new URLSearchParams()
      const dStr = format(filters.date, "yyyy-MM-dd")
      params.set("startDate", dStr)
      params.set("endDate", dStr)
      if (filters.nodeIp !== "all") params.set("nodeIp", filters.nodeIp)
      if (filters.status !== "all") params.set("status", filters.status)
      if (filters.shortCode && filters.shortCode !== "all" && filters.shortCode.trim()) {
        params.set("shortCode", filters.shortCode.trim())
      }

      const data = await apiClient(`/reports/summary?${params.toString()}`)
      setSummary(data)
    } catch (e: unknown) {
      toast.error("Failed to load summary: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsLoading(false)
    }
  }

  const handleSearch = () => {
    const filters = { date: selectedDate, nodeIp, status: statusFilter, shortCode }
    setAppliedFilters(filters)
    setHasSearched(true)
    setSummary(null)
    executeFetch(filters)
  }

  const handleClearFilters = () => {
    setNodeIp("all")
    setStatusFilter("all")
    setShortCode("")
    setSelectedDate(getToday())
  }

  const handleRunIngestion = async () => {
    setIsIngesting(true)
    try {
      const res = (await apiClient("/reports/ingest/run?reingestAll=true", { method: "POST" })) as { message?: string }
      toast.success(res?.message || "USSD log ingestion and backfill completed")
      if (appliedFilters) {
        await executeFetch(appliedFilters)
      }
    } catch (e: unknown) {
      toast.error("Ingestion failed: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsIngesting(false)
    }
  }

  const handleRunFetch = async () => {
    setIsFetching(true)
    try {
      const results = (await apiClient("/reports/fetch/run", { method: "POST" })) as FetchResult[]
      const fetched = results.filter((r) => r.status === "FETCHED")
      const totalFiles = fetched.reduce((sum, r) => sum + r.filesFetched, 0)
      toast.success(
        totalFiles > 0
          ? `FTP fetch completed: pulled ${totalFiles} file(s) across ${fetched.length} server(s).`
          : "FTP fetch completed: no new files were available."
      )
      if (appliedFilters) {
        await executeFetch(appliedFilters)
      }
    } catch (e: unknown) {
      toast.error("Fetch failed: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsFetching(false)
    }
  }

  const handleRebuildRollup = async () => {
    setIsRebuildingRollup(true)
    try {
      const dStr = format(selectedDate, "yyyy-MM-dd")
      await apiClient(`/reports/rollup/rebuild?startDate=${dStr}&endDate=${dStr}`, { method: "POST" })
      toast.success(`Hourly rollup cache rebuilt for ${dStr}`)
      if (appliedFilters) {
        await executeFetch(appliedFilters)
      }
    } catch (e: unknown) {
      toast.error("Rollup rebuild failed: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsRebuildingRollup(false)
    }
  }

  const chartData = (summary?.dailyBreakdown || []).map((d) => {
    const [y, m, dayNum] = d.day.split("-").map(Number)
    const localDate = new Date(y, (m || 1) - 1, dayNum || 1)
    return {
      day: format(localDate, "dd MMM"),
      label: format(localDate, "EEE dd MMM yyyy"),
      success: d.success,
      failed: d.failed,
    }
  })

  const maxErrorCount = Math.max(1, ...(summary?.topErrorCodes || []).map((e) => e.total))

  return (
    <>
      <DashboardHeader breadcrumbs={[{ label: "Dashboard", href: "/dashboard" }, { label: "Reports", href: "/dashboard/reports" }, { label: "Executive Summary" }]} />
      <ReportsNav />
      <div className="flex flex-1 flex-col gap-6 p-4 sm:p-6 w-full min-w-0 max-w-full overflow-x-hidden">
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="grid gap-1">
            <h1 className="text-2xl font-semibold tracking-tight">Executive Summary Report</h1>
            <p className="text-sm text-muted-foreground">
              Gateway performance overview & daily traffic health across all nodes
            </p>
          </div>
          <div className="flex items-center gap-2">
            {isAdmin && (
              <Button variant="outline" onClick={handleRunFetch} disabled={isFetching}>
                <DownloadCloud className={cn("mr-2 h-4 w-4", isFetching && "animate-spin")} />
                Run FTP fetch
              </Button>
            )}
            {isAdmin && (
              <Button variant="outline" onClick={handleRunIngestion} disabled={isIngesting}>
                <PlayCircle className={cn("mr-2 h-4 w-4", isIngesting && "animate-spin")} />
                Run ingestion
              </Button>
            )}
            {isAdmin && (
              <Button
                variant="outline"
                onClick={handleRebuildRollup}
                disabled={isRebuildingRollup}
                title="Rebuild hourly aggregation rollup cache for selected date"
              >
                <Layers className={cn("mr-2 h-4 w-4", isRebuildingRollup && "animate-spin")} />
                Rebuild rollup
              </Button>
            )}
            <ReportExportButton
              reportType="GENERAL"
              defaultLabel="Export Excel"
              disabled={!(summary?.totalTransactions)}
              getFilters={() => {
                const filters = appliedFilters || { date: selectedDate, nodeIp, status: statusFilter, shortCode }
                const dStr = format(filters.date, "yyyy-MM-dd")
                return {
                  startDate: dStr,
                  endDate: dStr,
                  date: dStr,
                  nodeIp: filters.nodeIp,
                  status: filters.status,
                  shortCode: filters.shortCode,
                }
              }}
            />
          </div>
        </div>

        {/* Global Controls */}
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
                  value={shortCode}
                  onChange={setShortCode}
                  onSearch={handleSearch}
                  suggestions={shortCodeOptions}
                  placeholder="e.g. *400 or *123#..."
                  className="w-[180px]"
                  inputClassName="font-mono text-xs"
                />
              </div>

              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Gateway Node</Label>
                <SearchableSelect
                  value={nodeIp}
                  onValueChange={setNodeIp}
                  options={servers.map((s) => ({ value: s.ipAddress, label: `${s.name} (${s.ipAddress})` }))}
                  allOptionLabel="All nodes"
                  placeholder="All nodes"
                  searchPlaceholder="Search node or IP..."
                  triggerClassName="w-[190px]"
                />
              </div>

              <div className="grid gap-1.5">
                <Label className="text-xs font-medium text-muted-foreground">Status</Label>
                <SearchableSelect
                  value={statusFilter}
                  onValueChange={setStatusFilter}
                  options={[
                    { value: "SUCCESS", label: "Success only" },
                    { value: "FAILED", label: "Failed only" },
                  ]}
                  allOptionLabel="All outcomes"
                  placeholder="All outcomes"
                  searchPlaceholder="Filter status..."
                  triggerClassName="w-[140px]"
                />
              </div>

              <Button onClick={handleSearch} disabled={isLoading} className="gap-2">
                <Search className={cn("h-4 w-4", isLoading && "animate-spin")} />
                Search
              </Button>

              {(nodeIp !== "all" || statusFilter !== "all" || (shortCode && shortCode !== "all") || format(selectedDate, "yyyy-MM-dd") !== format(getToday(), "yyyy-MM-dd")) && (
                <Button variant="ghost" size="sm" onClick={handleClearFilters} className="gap-1.5 text-muted-foreground">
                  <X className="h-3.5 w-3.5" />
                  Clear Filters
                </Button>
              )}
            </div>
          </CardContent>
        </Card>

        {isLoading && !summary ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-20 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Search className="h-8 w-8 text-primary animate-spin" />
              </div>
              <h3 className="text-lg font-semibold">Loading Executive Summary Data...</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Generating gateway performance summary for {format(selectedDate, "dd MMM yyyy")}. Please wait while metrics are computed.
              </p>
            </CardContent>
          </Card>
        ) : !hasSearched ? (
          <Card className="shadow-none border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-16 text-center">
              <div className="rounded-full bg-primary/10 p-4 mb-4">
                <Search className="h-8 w-8 text-primary" />
              </div>
              <h3 className="text-lg font-semibold">Ready to Generate Executive Summary</h3>
              <p className="mt-1 text-sm text-muted-foreground max-w-md">
                Selected date is {format(selectedDate, "dd MMM yyyy")}. Click Search to load summary data, or adjust filters above.
              </p>
              <Button onClick={handleSearch} disabled={isLoading} className="mt-4 gap-2">
                <Search className={cn("h-4 w-4", isLoading && "animate-spin")} />
                Search {format(selectedDate, "yyyy-MM-dd")} Summary
              </Button>
            </CardContent>
          </Card>
        ) : (
          <>
            {/* Top KPI Cards */}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatTile
            label="Total Calls"
            value={numberFormat.format(summary?.totalTransactions ?? 0)}
            icon={TrendingUp}
            detail={`${summary?.startDate || ""} to ${summary?.endDate || ""}`}
          />
          <StatTile
            label="Success Rate"
            value={`${summary?.successRatePercent ?? 0}%`}
            icon={TrendingUp}
            detail={`${numberFormat.format(summary?.successCount ?? 0)} calls completed`}
          />
          <StatTile
            label="Failed Calls"
            value={numberFormat.format(summary?.failedCount ?? 0)}
            icon={AlertTriangle}
            detail={summary?.totalTransactions ? `${Math.round(((summary.failedCount || 0) * 100) / summary.totalTransactions)}% error rate` : "No failures"}
          />
          <StatTile
            label="Average Latency"
            value={summary?.averageLatencyMs != null ? `${numberFormat.format(Math.round(summary.averageLatencyMs))} ms` : "—"}
            icon={Server}
            detail="End-to-end response time"
          />
        </div>

        {/* Daily Outcome Chart */}
        <Card className="shadow-none">
          <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2 pb-2">
            <div>
              <CardTitle className="text-base font-semibold">Daily Call Volume Trend</CardTitle>
              <CardDescription>Successful and failed USSD interactions across selected dates</CardDescription>
            </div>
            <div className="flex items-center gap-4 text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <span className="h-2.5 w-2.5 rounded-sm" style={{ background: STATUS_GOOD }} />
                Successful
              </span>
              <span className="flex items-center gap-1.5">
                <span className="h-2.5 w-2.5 rounded-sm" style={{ background: STATUS_CRITICAL }} />
                Failed
              </span>
            </div>
          </CardHeader>
          <CardContent className="h-[300px] pb-4">
            {chartData.length === 0 ? (
              <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                No calls recorded in this time range.
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barCategoryGap="30%">
                  <CartesianGrid vertical={false} stroke="var(--border)" />
                  <XAxis dataKey="day" fontSize={12} tickLine={false} axisLine={false} tick={{ fill: "var(--muted-foreground)" }} />
                  <YAxis
                    fontSize={12}
                    allowDecimals={false}
                    tickLine={false}
                    axisLine={false}
                    width={48}
                    tick={{ fill: "var(--muted-foreground)" }}
                    tickFormatter={(v: number) => numberFormat.format(v)}
                  />
                  <Tooltip content={<DailyTooltip />} cursor={{ fill: "var(--muted)", opacity: 0.5 }} />
                  <Bar dataKey="success" name="Successful" stackId="calls" fill={STATUS_GOOD} stroke="var(--card)" strokeWidth={2} maxBarSize={40} />
                  <Bar dataKey="failed" name="Failed" stackId="calls" fill={STATUS_CRITICAL} stroke="var(--card)" strokeWidth={2} radius={[4, 4, 0, 0]} maxBarSize={40} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>

        {/* Drill-down Cards */}
        <div className="grid gap-6 lg:grid-cols-2">
          {/* Top Short Codes */}
          <Card className="shadow-none">
            <CardHeader className="flex flex-row items-center justify-between pb-2">
              <div>
                <CardTitle className="text-base font-semibold">Top Short Codes</CardTitle>
                <CardDescription>Highest volume USSD service codes</CardDescription>
              </div>
              <Button variant="ghost" size="sm" asChild className="gap-1 text-xs">
                <Link href="/dashboard/reports/packages">
                  Full Report <ArrowRight className="h-3 w-3" />
                </Link>
              </Button>
            </CardHeader>
            <CardContent>
              {(summary?.topShortCodes || []).length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">No short code data found.</p>
              ) : (
                <div className="overflow-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-xs text-muted-foreground">
                        <th className="py-2 font-medium">Code</th>
                        <th className="py-2 font-medium">Product</th>
                        <th className="py-2 text-right font-medium">Total Calls</th>
                        <th className="py-2 text-right font-medium">Success %</th>
                      </tr>
                    </thead>
                    <tbody>
                      {summary!.topShortCodes.slice(0, 6).map((c) => (
                        <tr key={c.shortCode} className="border-b last:border-0 hover:bg-muted/30">
                          <td className="py-2.5 font-mono text-xs font-semibold">{c.shortCode}</td>
                          <td className="py-2.5">{c.productName && c.productName !== c.shortCode ? c.productName : "—"}</td>
                          <td className="py-2.5 text-right tabular-nums">{numberFormat.format(c.total)}</td>
                          <td className="py-2.5 text-right tabular-nums">
                            {c.total ? Math.round((c.success * 100) / c.total) : 0}%
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Top Error Codes */}
          <Card className="shadow-none">
            <CardHeader className="flex flex-row items-center justify-between pb-2">
              <div>
                <CardTitle className="text-base font-semibold">Top Failure Causes</CardTitle>
                <CardDescription>Most frequent errors and subscriber messages</CardDescription>
              </div>
              <Button variant="ghost" size="sm" asChild className="gap-1 text-xs">
                <Link href="/dashboard/reports/messages">
                  Full Report <ArrowRight className="h-3 w-3" />
                </Link>
              </Button>
            </CardHeader>
            <CardContent>
              {(summary?.topErrorCodes || []).length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">Zero failures in this period!</p>
              ) : (
                <ul className="divide-y">
                  {summary!.topErrorCodes.slice(0, 5).map((e) => (
                    <li key={e.errorCode} className="py-2.5">
                      <div className="flex items-baseline justify-between gap-3">
                        <span className="font-mono text-xs font-semibold text-destructive">{e.errorCode}</span>
                        <span className="text-sm font-medium tabular-nums">{numberFormat.format(e.total)} calls</span>
                      </div>
                      {e.sampleMessage && (
                        <p className="mt-0.5 truncate text-xs text-muted-foreground" title={e.sampleMessage} dir="auto">
                          {e.sampleMessage}
                        </p>
                      )}
                      <div className="mt-1.5 h-1.5 w-full rounded-full bg-muted">
                        <div className="h-1.5 rounded-full bg-destructive/70" style={{ width: `${(e.total / maxErrorCount) * 100}%` }} />
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </div>
          </>
        )}
      </div>
    </>
  )
}
