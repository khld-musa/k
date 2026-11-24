"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { useAuth } from "@/lib/auth-context"
import { apiClient } from "@/lib/api-client"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import {
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  AreaChart,
  Area,
} from 'recharts'
import {
  Search,
  Activity,
  ArrowRight,
  AlertTriangle,
  Shield,
  Server,
  Radio,
  HeartPulse,
  RefreshCw,
} from "lucide-react"
import Link from "next/link"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"
import { Button } from "@/components/ui/button"
import { ServerHealthView } from "@/components/server-health-view"

// ─── Types ───────────────────────────────────────────────────

interface AuditLogDto {
  action: string
  username: string
  details: string
  timeAgo: string
}

interface BalanceStatus {
  accountName: string
  clientId: string
  currentBalance: number | null
  threshold: number
  isLow: boolean
}

interface RecentAlarm {
  accountName: string
  balance: number
  threshold: number
  timeAgo: string
}

interface DashboardStats {
  dataDate: string | null
  totalNodes: number
  searchesToday: number
  tracesToday: number
  totalAlarmConfigs: number
  activityChart: { date: string; searches: number; traces: number }[]
  recentActivity: AuditLogDto[]
  balanceStatuses: BalanceStatus[]
  recentAlarms: RecentAlarm[]
}

interface DashboardSummary {
  dataDate: string | null
  totalNodes: number
  searchesToday: number
  tracesToday: number
}

// ═════════════════════════════════════════════════════════════
//  MAIN DASHBOARD PAGE
// ═════════════════════════════════════════════════════════════

export default function DashboardPage() {
  const { user } = useAuth()
  const [stats, setStats] = React.useState<DashboardStats | null>(null)
  const [summary, setSummary] = React.useState<DashboardSummary | null>(null)
  const [myActivity, setMyActivity] = React.useState<AuditLogDto[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [isRefreshing, setIsRefreshing] = React.useState(false)

  const fetchData = React.useCallback(async (silent = false) => {
    if (!user) return
    if (!silent) setIsLoading(true)
    else setIsRefreshing(true)

    try {
      if (user.role === 'ADMIN') {
        const data = await apiClient("/dashboard/stats")
        setStats(data)
      } else {
        // Regular user: fetch lightweight summary + personal activity
        const [summaryData, activityData] = await Promise.all([
          apiClient("/dashboard/summary"),
          apiClient("/dashboard/my-activity"),
        ])
        setSummary(summaryData)
        setMyActivity(activityData)
      }
    } catch {
      if (!silent) {
        toast.error("Failed to load dashboard data")
      }
    } finally {
      setIsLoading(false)
      setIsRefreshing(false)
    }
  }, [user])

  React.useEffect(() => {
    fetchData()

    // Auto-refresh dashboard data every 30 seconds
    const interval = setInterval(() => {
      fetchData(true)
    }, 30000)

    return () => clearInterval(interval)
  }, [fetchData])

  return (
    <>
      <DashboardHeader breadcrumbs={[{ label: "Dashboard" }]} />

      <div className="flex flex-1 flex-col gap-6 p-6 lg:p-10 bg-muted/10">
        <Tabs defaultValue="overview" className="w-full space-y-6">
          <div className="flex flex-wrap items-center justify-between gap-4 border-b pb-4">
            <div className="flex flex-col gap-1">
              <h1 className="text-3xl font-bold tracking-tight text-foreground">
                {user?.role === 'ADMIN' ? 'Operations Overview' : `Welcome back, ${user?.username}`}
              </h1>
              <p className="text-muted-foreground text-sm">
                {user?.role === 'ADMIN'
                  ? "Platform activity, server diagnostics, and system health at a glance."
                  : "Your workspace — quick links, today's activity, and server diagnostics."}
              </p>
            </div>

            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => fetchData(true)}
                disabled={isRefreshing || isLoading}
                className="gap-1.5 text-xs h-8"
              >
                <RefreshCw className={cn("h-3.5 w-3.5", isRefreshing && "animate-spin")} />
                Refresh
              </Button>
              <TabsList className="bg-muted p-1 rounded-lg">
                <TabsTrigger value="overview" className="gap-2 text-xs font-semibold px-4 py-1.5">
                  <Activity className="h-4 w-4" />
                  Operations Overview
                </TabsTrigger>
                <TabsTrigger value="health" className="gap-2 text-xs font-semibold px-4 py-1.5">
                  <HeartPulse className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                  Server Health Check
                </TabsTrigger>
              </TabsList>
            </div>
          </div>

          <TabsContent value="overview" className="mt-0 space-y-6">
            {user?.role === 'ADMIN' ? (
              <AdminDashboard stats={stats} isLoading={isLoading} />
            ) : (
              <UserDashboard summary={summary} myActivity={myActivity} isLoading={isLoading} />
            )}
          </TabsContent>

          <TabsContent value="health" className="mt-0 space-y-6">
            <ServerHealthView />
          </TabsContent>
        </Tabs>
      </div>
    </>
  )
}

// ═════════════════════════════════════════════════════════════
//  ADMIN DASHBOARD
// ═════════════════════════════════════════════════════════════

function AdminDashboard({ stats, isLoading }: { stats: DashboardStats | null; isLoading: boolean }) {
  if (isLoading) {
    return (
      <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
        {[...Array(4)].map((_, i) => (
          <Card key={i} className="border-none bg-card animate-pulse"><CardContent className="p-6 h-28" /></Card>
        ))}
      </div>
    )
  }

  return (
    <>
      {/* ── KPI Cards — Active Feature Metrics ── */}
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Card className="border-none bg-card shadow-sm">
          <CardContent className="p-5">
            <div className="flex items-center justify-between mb-3">
              <p className="text-muted-foreground font-medium text-xs uppercase tracking-wider">USSD Servers</p>
              <div className="p-1.5 bg-indigo-100 dark:bg-indigo-900/30 rounded-md text-indigo-600"><Server size={16} /></div>
            </div>
            <h2 className="text-3xl font-bold tabular-nums">{stats?.totalNodes || 0}</h2>
            <p className="text-muted-foreground text-xs mt-1">Active nodes configured</p>
          </CardContent>
        </Card>

        <Card className="border-none bg-card shadow-sm">
          <CardContent className="p-5">
            <div className="flex items-center justify-between mb-3">
              <p className="text-muted-foreground font-medium text-xs uppercase tracking-wider">Searches Today</p>
              <div className="p-1.5 bg-blue-100 dark:bg-blue-900/30 rounded-md text-blue-600"><Search size={16} /></div>
            </div>
            <h2 className="text-3xl font-bold tabular-nums">{stats?.searchesToday || 0}</h2>
            <p className="text-muted-foreground text-xs mt-1">Log searches performed</p>
          </CardContent>
        </Card>

        <Card className="border-none bg-card shadow-sm">
          <CardContent className="p-5">
            <div className="flex items-center justify-between mb-3">
              <p className="text-muted-foreground font-medium text-xs uppercase tracking-wider">Live Traces Today</p>
              <div className="p-1.5 bg-emerald-100 dark:bg-emerald-900/30 rounded-md text-emerald-600"><Radio size={16} /></div>
            </div>
            <h2 className="text-3xl font-bold tabular-nums">{stats?.tracesToday || 0}</h2>
            <p className="text-muted-foreground text-xs mt-1">Real-time traces started</p>
          </CardContent>
        </Card>

        <Card className="border-none bg-card shadow-sm">
          <CardContent className="p-5">
            <div className="flex items-center justify-between mb-3">
              <p className="text-muted-foreground font-medium text-xs uppercase tracking-wider">Balance Alarms</p>
              <div className="p-1.5 bg-amber-100 dark:bg-amber-900/30 rounded-md text-amber-600"><Shield size={16} /></div>
            </div>
            <h2 className="text-3xl font-bold tabular-nums">{stats?.totalAlarmConfigs || 0}</h2>
            <p className="text-muted-foreground text-xs mt-1">Active alarm configurations</p>
          </CardContent>
        </Card>
      </div>

      {/* ── Balance Status (only if configs exist) ── */}
      {stats?.balanceStatuses && stats.balanceStatuses.length > 0 && (
        <Card className="border-none bg-card shadow-sm">
          <CardHeader className="pb-3">
            <CardTitle className="text-base font-semibold flex items-center gap-2">
              <Shield className="h-4 w-4 text-primary" /> Balance Status
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {stats.balanceStatuses.map((b, i) => {
                const ratio = b.currentBalance != null && b.threshold > 0
                  ? b.currentBalance / b.threshold : null
                const pct = ratio != null ? Math.min(ratio / 10 * 100, 100) : 0
                return (
                  <div key={i} className="flex items-center gap-4">
                    <div className="w-32 truncate text-sm font-medium">{b.accountName}</div>
                    <div className="flex-1">
                      <div className="h-2.5 rounded-full bg-muted overflow-hidden">
                        <div
                          className={cn(
                            "h-full rounded-full transition-all",
                            b.isLow ? "bg-red-500" : "bg-emerald-500"
                          )}
                          style={{ width: `${pct}%` }}
                        />
                      </div>
                    </div>
                    <div className="flex items-center gap-2 text-sm tabular-nums min-w-[200px] justify-end">
                      <span className={cn("font-semibold", b.isLow ? "text-red-600" : "text-foreground")}>
                        {b.currentBalance != null ? b.currentBalance.toLocaleString() : '—'}
                      </span>
                      <span className="text-muted-foreground">/</span>
                      <span className="text-muted-foreground">{b.threshold.toLocaleString()}</span>
                      {b.isLow && <AlertTriangle className="h-3.5 w-3.5 text-red-500" />}
                    </div>
                  </div>
                )
              })}
            </div>
          </CardContent>
        </Card>
      )}

      {/* ── Platform Usage Chart + Recent Activity ── */}
      <div className="grid gap-6 lg:grid-cols-7">
        <Card className="lg:col-span-4 border-none bg-card shadow-sm">
          <CardHeader>
            <CardTitle className="text-base font-semibold">Platform Usage (7 days)</CardTitle>
            <CardDescription>Search and live trace activity on DS Management System.</CardDescription>
          </CardHeader>
          <CardContent className="h-[300px] pl-2">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={stats?.activityChart || []}>
                <defs>
                  <linearGradient id="colorActive" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#6366f1" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} className="stroke-border" />
                <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fill: 'hsl(var(--muted-foreground))', fontSize: 12 }} dy={10} />
                <YAxis axisLine={false} tickLine={false} tick={{ fill: 'hsl(var(--muted-foreground))', fontSize: 12 }} />
                <Tooltip contentStyle={{ backgroundColor: 'hsl(var(--card))', borderRadius: '8px', border: '1px solid hsl(var(--border))', color: 'hsl(var(--card-foreground))' }} />
                <Area type="monotone" dataKey="searches" name="Searches" stroke="#6366f1" strokeWidth={3} fillOpacity={1} fill="url(#colorActive)" />
                <Area type="monotone" dataKey="traces" name="Live Traces" stroke="#10b981" strokeWidth={3} fillOpacity={0} fill="transparent" />
              </AreaChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        <Card className="lg:col-span-3 border-none bg-card shadow-sm">
          <CardHeader>
            <CardTitle className="text-base font-semibold">Recent Activity</CardTitle>
            <CardDescription>Latest searches and traces performed.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-5 mt-1">
              {stats?.recentActivity?.map((log, i) => (
                <div key={i} className="flex items-start gap-3">
                  <div className={cn(
                    "p-1.5 rounded-full mt-0.5",
                    log.action === 'SEARCH_LOGS' ? 'bg-blue-100 text-blue-600 dark:bg-blue-900/30' : 'bg-emerald-100 text-emerald-600 dark:bg-emerald-900/30'
                  )}>
                    {log.action === 'SEARCH_LOGS' ? <Search size={14} /> : <Activity size={14} />}
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium leading-none">
                      {log.username} <span className="text-muted-foreground font-normal ml-1">{log.action === 'SEARCH_LOGS' ? 'searched logs' : 'started a live trace'}</span>
                    </p>
                    <p className="text-xs text-muted-foreground mt-1 line-clamp-1">{log.details}</p>
                  </div>
                  <div className="text-[11px] text-muted-foreground whitespace-nowrap">{log.timeAgo}</div>
                </div>
              ))}
              {(!stats?.recentActivity || stats.recentActivity.length === 0) && (
                <p className="text-center py-8 text-muted-foreground text-sm">No recent activity.</p>
              )}
            </div>
          </CardContent>
        </Card>
      </div>
    </>
  )
}

// ═════════════════════════════════════════════════════════════
//  USER DASHBOARD — "My Workspace"
// ═════════════════════════════════════════════════════════════

function UserDashboard({ summary, myActivity, isLoading }: { summary: DashboardSummary | null; myActivity: AuditLogDto[]; isLoading: boolean }) {
  if (isLoading) {
    return (
      <div className="grid gap-6 md:grid-cols-3">
        {[...Array(3)].map((_, i) => (
          <Card key={i} className="border-none bg-card animate-pulse"><CardContent className="p-6 h-32" /></Card>
        ))}
      </div>
    )
  }

  return (
    <>
      {/* ── Quick Actions ── */}
      <div className="grid gap-4 md:grid-cols-2">
        <Link href="/dashboard/ussd-logs/history" className="group">
          <Card className="border-none bg-card shadow-sm hover:shadow-md transition-all group-hover:border-primary/20 h-full">
            <CardContent className="p-5 flex items-center gap-4">
              <div className="p-3 bg-indigo-100 dark:bg-indigo-900/30 rounded-xl text-indigo-600">
                <Search size={22} />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-sm">Search Logs</h3>
                <p className="text-xs text-muted-foreground mt-0.5">Query USSD transaction history</p>
              </div>
              <ArrowRight className="h-4 w-4 text-muted-foreground group-hover:text-primary transition-colors" />
            </CardContent>
          </Card>
        </Link>

        <Link href="/dashboard/ussd-logs/live-trace" className="group">
          <Card className="border-none bg-card shadow-sm hover:shadow-md transition-all group-hover:border-primary/20 h-full">
            <CardContent className="p-5 flex items-center gap-4">
              <div className="p-3 bg-emerald-100 dark:bg-emerald-900/30 rounded-xl text-emerald-600">
                <Activity size={22} />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-sm">Live Trace</h3>
                <p className="text-xs text-muted-foreground mt-0.5">Real-time USSD session monitoring</p>
              </div>
              <ArrowRight className="h-4 w-4 text-muted-foreground group-hover:text-primary transition-colors" />
            </CardContent>
          </Card>
        </Link>
      </div>

      {/* ── Summary Cards ── */}
      <div className="grid gap-4 md:grid-cols-3">
        <Card className="border-none bg-card shadow-sm">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <p className="text-muted-foreground font-medium text-[11px] uppercase tracking-wider">USSD Servers</p>
              <Server size={14} className="text-indigo-500" />
            </div>
            <h3 className="text-2xl font-bold tabular-nums">{summary?.totalNodes || 0}</h3>
            <p className="text-[11px] text-muted-foreground">Active nodes configured</p>
          </CardContent>
        </Card>
        <Card className="border-none bg-card shadow-sm">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <p className="text-muted-foreground font-medium text-[11px] uppercase tracking-wider">Searches Today</p>
              <Search size={14} className="text-blue-500" />
            </div>
            <h3 className="text-2xl font-bold tabular-nums">{summary?.searchesToday || 0}</h3>
            <p className="text-[11px] text-muted-foreground">Log searches performed</p>
          </CardContent>
        </Card>
        <Card className="border-none bg-card shadow-sm">
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-2">
              <p className="text-muted-foreground font-medium text-[11px] uppercase tracking-wider">Live Traces Today</p>
              <Radio size={14} className="text-emerald-500" />
            </div>
            <h3 className="text-2xl font-bold tabular-nums">{summary?.tracesToday || 0}</h3>
            <p className="text-[11px] text-muted-foreground">Real-time traces started</p>
          </CardContent>
        </Card>
      </div>

      {/* ── Personal Activity ── */}
      <Card className="border-none bg-card shadow-sm">
        <CardHeader className="pb-2">
          <CardTitle className="text-base font-semibold">Your Recent Activity</CardTitle>
          <CardDescription>Your last searches &amp; traces</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {myActivity.length > 0 ? myActivity.map((log, i) => (
              <div key={i} className="flex items-start gap-3">
                <div className={cn(
                  "p-1.5 rounded-full mt-0.5",
                  log.action === 'SEARCH_LOGS' ? 'bg-blue-100 text-blue-600 dark:bg-blue-900/30' : 'bg-emerald-100 text-emerald-600 dark:bg-emerald-900/30'
                )}>
                  {log.action === 'SEARCH_LOGS' ? <Search size={14} /> : <Activity size={14} />}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-medium">
                    {log.action === 'SEARCH_LOGS' ? 'Searched logs' : 'Started live trace'}
                  </p>
                  <p className="text-[11px] text-muted-foreground mt-0.5 line-clamp-1">{log.details}</p>
                </div>
                <span className="text-[10px] text-muted-foreground whitespace-nowrap">{log.timeAgo}</span>
              </div>
            )) : (
              <p className="text-sm text-muted-foreground text-center py-6">No activity yet. Start by searching logs!</p>
            )}
          </div>
        </CardContent>
      </Card>
    </>
  )
}
