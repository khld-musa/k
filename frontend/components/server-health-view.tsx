"use client"

import * as React from "react"
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Cpu,
  Database,
  HardDrive,
  HeartPulse,
  Layers,
  RefreshCw,
  Server,
  ShieldAlert,
  ShieldCheck,
  TrendingUp,
  XCircle,
  Clock,
  ArrowRight,
} from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Progress } from "@/components/ui/progress"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { apiClient } from "@/lib/api-client"
import { cn } from "@/lib/utils"

export interface ServerHealthData {
  overallStatus: "HEALTHY" | "WARNING" | "CRITICAL"
  timestamp: string
  uptime: string
  uptimeSeconds: number
  os: {
    name: string
    arch: string
    version: string
    availableProcessors: number
    systemCpuPercent: number | null
    processCpuPercent: number | null
    systemLoadAverage: number | null
  }
  memory: {
    totalPhysicalBytes: number
    usedPhysicalBytes: number
    freePhysicalBytes: number
    physicalUsagePercent: number
    jvmHeapUsedBytes: number
    jvmHeapMaxBytes: number
    jvmHeapCommittedBytes: number
    jvmHeapUsagePercent: number
    jvmNonHeapUsedBytes: number
    status: "HEALTHY" | "WARNING" | "CRITICAL"
  }
  storage: {
    path: string
    name: string
    totalBytes: number
    freeBytes: number
    usableBytes: number
    usedBytes: number
    usagePercent: number
    status: "HEALTHY" | "WARNING" | "CRITICAL"
  }[]
  database: {
    status: "CONNECTED" | "DEGRADED" | "DOWN"
    databaseProductName: string
    activeConnections: number
    idleConnections: number
    totalConnections: number
    maxConnections: number
    pingLatencyMs: number | null
  }
  jvm: {
    javaVersion: string
    jvmVendor: string
    threadCount: number
    peakThreadCount: number
    daemonThreadCount: number
  }
  nodes: {
    id: number
    name: string
    ipAddress: string
    active: boolean
    status: "REACHABLE" | "UNREACHABLE" | "INACTIVE"
    latencyMs: number | null
    error: string | null
  }[]
  alerts: {
    level: "INFO" | "WARNING" | "CRITICAL"
    category: "STORAGE" | "RAM" | "CPU" | "DATABASE" | "NODE"
    title: string
    message: string
    recommendation: string
  }[]
}

function formatBytes(bytes: number) {
  if (!bytes || bytes <= 0) return "0 B"
  const k = 1024
  const sizes = ["B", "KB", "MB", "GB", "TB", "PB"]
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i]
}

export function ServerHealthView() {
  const [data, setData] = React.useState<ServerHealthData | null>(null)
  const [isLoading, setIsLoading] = React.useState(true)
  const [autoRefreshInterval, setAutoRefreshInterval] = React.useState<string>("30")
  const [lastUpdated, setLastUpdated] = React.useState<Date | null>(null)

  const fetchHealth = React.useCallback(async (showToast = false) => {
    try {
      const result: ServerHealthData = await apiClient("/system/health")
      setData(result)
      setLastUpdated(new Date())
      if (showToast) {
        toast.success("Health status refreshed")
      }
    } catch (e: unknown) {
      toast.error("Failed to load server health: " + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => {
    fetchHealth()
  }, [fetchHealth])

  React.useEffect(() => {
    if (autoRefreshInterval === "off") return
    const intervalMs = parseInt(autoRefreshInterval, 10) * 1000
    const timer = setInterval(() => {
      fetchHealth(false)
    }, intervalMs)
    return () => clearInterval(timer)
  }, [autoRefreshInterval, fetchHealth])

  const overall = data?.overallStatus || "HEALTHY"

  const highestDisk = data?.storage?.length
    ? data.storage.reduce((prev, curr) => (curr.usagePercent > prev.usagePercent ? curr : prev), data.storage[0])
    : null

  if (isLoading && !data) {
    return (
      <div className="flex flex-col gap-6">
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {[...Array(4)].map((_, i) => (
            <Card key={i} className="shadow-none animate-pulse">
              <CardContent className="p-6 h-32" />
            </Card>
          ))}
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header Controls */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary/10 rounded-lg text-primary">
            <HeartPulse className="h-5 w-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold tracking-tight">Production Server Resource Health</h2>
              {data && (
                <Badge
                  variant={overall === "HEALTHY" ? "secondary" : "destructive"}
                  className={cn(
                    "text-xs px-2.5 py-0.5 font-semibold uppercase tracking-wider",
                    overall === "HEALTHY" && "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20",
                    overall === "WARNING" && "bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20",
                    overall === "CRITICAL" && "bg-red-500/10 text-red-600 dark:text-red-400 border-red-500/20"
                  )}
                >
                  {overall}
                </Badge>
              )}
            </div>
            <p className="text-xs text-muted-foreground mt-0.5">
              Live checks on Storage, RAM, CPU load, and DB connection pool to avoid service disruption.
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Clock className="h-3.5 w-3.5" />
            <span>Auto-refresh:</span>
            <Select value={autoRefreshInterval} onValueChange={setAutoRefreshInterval}>
              <SelectTrigger className="h-8 w-[95px] text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="off">Off</SelectItem>
                <SelectItem value="10">10s</SelectItem>
                <SelectItem value="30">30s</SelectItem>
                <SelectItem value="60">60s</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <Button
            variant="outline"
            size="sm"
            onClick={() => fetchHealth(true)}
            disabled={isLoading}
            className="gap-2"
          >
            <RefreshCw className={cn("h-3.5 w-3.5", isLoading && "animate-spin")} />
            Refresh
          </Button>
        </div>
      </div>

      {/* Global Health Status Banner */}
      {data && (
        <Card
          className={cn(
            "shadow-none border-l-4",
            overall === "HEALTHY" && "border-l-emerald-500 bg-emerald-50/40 dark:bg-emerald-950/20 border-emerald-200 dark:border-emerald-900/50",
            overall === "WARNING" && "border-l-amber-500 bg-amber-50/40 dark:bg-amber-950/20 border-amber-200 dark:border-amber-900/50",
            overall === "CRITICAL" && "border-l-red-500 bg-red-50/40 dark:bg-red-950/20 border-red-200 dark:border-red-900/50"
          )}
        >
          <CardContent className="p-4 flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              {overall === "HEALTHY" ? (
                <ShieldCheck className="h-6 w-6 text-emerald-600 dark:text-emerald-400 shrink-0" />
              ) : overall === "WARNING" ? (
                <AlertTriangle className="h-6 w-6 text-amber-600 dark:text-amber-400 shrink-0" />
              ) : (
                <ShieldAlert className="h-6 w-6 text-red-600 dark:text-red-400 shrink-0" />
              )}
              <div>
                <h3 className="text-sm font-semibold">
                  {overall === "HEALTHY"
                    ? "All Production Resources Operating Normally"
                    : overall === "WARNING"
                    ? "Resource Usage Approaching Warning Thresholds"
                    : "Critical Resource Usage Detected — Action Required"}
                </h3>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {overall === "HEALTHY"
                    ? "Storage partitions, RAM memory, CPU load, and database connections are within safe operational limits."
                    : overall === "WARNING"
                    ? "One or more resources have crossed 80% usage. Review recommendations below to avoid disruption."
                    : "One or more resources have crossed 90% usage or are disconnected. Immediate intervention needed to prevent service stoppage."}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-6 text-xs text-muted-foreground">
              <div>
                <span className="font-medium text-foreground">System Uptime:</span>{" "}
                <span className="font-mono">{data.uptime}</span>
              </div>
              {lastUpdated && (
                <div>
                  <span className="font-medium text-foreground">Last Check:</span>{" "}
                  <span className="font-mono">{lastUpdated.toLocaleTimeString()}</span>
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Actionable Alerts & Recommendations */}
      {data && data.alerts && data.alerts.length > 0 && (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold tracking-tight flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 text-amber-500" />
            Active System Warnings & Recommendations ({data.alerts.length})
          </h3>
          <div className="grid gap-3">
            {data.alerts.map((alert, idx) => (
              <Card
                key={idx}
                className={cn(
                  "shadow-none border-l-4",
                  alert.level === "CRITICAL"
                    ? "border-l-red-500 bg-red-50/20 dark:bg-red-950/10"
                    : "border-l-amber-500 bg-amber-50/20 dark:bg-amber-950/10"
                )}
              >
                <CardContent className="p-4 space-y-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <Badge
                        variant={alert.level === "CRITICAL" ? "destructive" : "secondary"}
                        className={cn(
                          "text-[10px] uppercase font-bold",
                          alert.level === "WARNING" && "bg-amber-500/10 text-amber-600 border-amber-500/20"
                        )}
                      >
                        {alert.level}
                      </Badge>
                      <Badge variant="outline" className="text-[10px] uppercase font-mono">
                        {alert.category}
                      </Badge>
                      <span className="text-sm font-semibold">{alert.title}</span>
                    </div>
                  </div>
                  <p className="text-xs text-muted-foreground leading-relaxed">{alert.message}</p>
                  <div className="flex items-center gap-1.5 pt-1 text-xs font-medium text-primary">
                    <ArrowRight className="h-3.5 w-3.5 text-primary shrink-0" />
                    <span>{alert.recommendation}</span>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        </div>
      )}

      {/* 4 Primary Resource Metric Cards */}
      {data && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {/* 1. Host Physical RAM */}
          <Card className="shadow-none">
            <CardContent className="p-5">
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  Host Physical RAM
                </p>
                <Layers className="h-4 w-4 text-muted-foreground/60" />
              </div>
              <div className="mt-2 flex items-baseline justify-between">
                <p className="text-2xl font-bold tabular-nums">
                  {data.memory.physicalUsagePercent}%
                </p>
                <span className="text-xs text-muted-foreground">
                  {formatBytes(data.memory.usedPhysicalBytes)} / {formatBytes(data.memory.totalPhysicalBytes)}
                </span>
              </div>
              <Progress
                value={data.memory.physicalUsagePercent}
                className="mt-3 h-2"
                indicatorClassName={cn(
                  data.memory.physicalUsagePercent >= 90
                    ? "bg-red-500"
                    : data.memory.physicalUsagePercent >= 80
                    ? "bg-amber-500"
                    : "bg-emerald-500"
                )}
              />
              <p className="mt-2 text-xs text-muted-foreground">
                Free RAM: <span className="font-semibold text-foreground">{formatBytes(data.memory.freePhysicalBytes)}</span>
              </p>
            </CardContent>
          </Card>

          {/* 2. Primary Disk / Storage */}
          <Card className="shadow-none">
            <CardContent className="p-5">
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  Storage Capacity
                </p>
                <HardDrive className="h-4 w-4 text-muted-foreground/60" />
              </div>
              <div className="mt-2 flex items-baseline justify-between">
                <p className="text-2xl font-bold tabular-nums">
                  {highestDisk ? `${highestDisk.usagePercent}%` : "—"}
                </p>
                <span className="text-xs text-muted-foreground">
                  {highestDisk ? `${formatBytes(highestDisk.usedBytes)} used` : ""}
                </span>
              </div>
              <Progress
                value={highestDisk?.usagePercent ?? 0}
                className="mt-3 h-2"
                indicatorClassName={cn(
                  (highestDisk?.usagePercent ?? 0) >= 90
                    ? "bg-red-500"
                    : (highestDisk?.usagePercent ?? 0) >= 80
                    ? "bg-amber-500"
                    : "bg-emerald-500"
                )}
              />
              <p className="mt-2 text-xs text-muted-foreground">
                Free on highest partition:{" "}
                <span className="font-semibold text-foreground">
                  {highestDisk ? formatBytes(highestDisk.usableBytes) : "—"}
                </span>
              </p>
            </CardContent>
          </Card>

          {/* 3. CPU Load & Cores */}
          <Card className="shadow-none">
            <CardContent className="p-5">
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  CPU & System Load
                </p>
                <Cpu className="h-4 w-4 text-muted-foreground/60" />
              </div>
              <div className="mt-2 flex items-baseline justify-between">
                <p className="text-2xl font-bold tabular-nums">
                  {data.os.systemCpuPercent != null ? `${data.os.systemCpuPercent}%` : "—"}
                </p>
                <span className="text-xs text-muted-foreground">
                  {data.os.availableProcessors} Cores
                </span>
              </div>
              <Progress
                value={data.os.systemCpuPercent ?? 0}
                className="mt-3 h-2"
                indicatorClassName={cn(
                  (data.os.systemCpuPercent ?? 0) >= 85
                    ? "bg-red-500"
                    : (data.os.systemCpuPercent ?? 0) >= 70
                    ? "bg-amber-500"
                    : "bg-emerald-500"
                )}
              />
              <p className="mt-2 text-xs text-muted-foreground">
                System Load Average:{" "}
                <span className="font-semibold text-foreground">
                  {data.os.systemLoadAverage != null ? data.os.systemLoadAverage : "N/A"}
                </span>
              </p>
            </CardContent>
          </Card>

          {/* 4. Database Pool & Ping */}
          <Card className="shadow-none">
            <CardContent className="p-5">
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  Database Pool
                </p>
                <Database className="h-4 w-4 text-muted-foreground/60" />
              </div>
              <div className="mt-2 flex items-baseline justify-between">
                <div className="flex items-center gap-1.5">
                  <span
                    className={cn(
                      "h-2.5 w-2.5 rounded-full",
                      data.database.status === "CONNECTED"
                        ? "bg-emerald-500 animate-pulse"
                        : "bg-red-500"
                    )}
                  />
                  <p className="text-xl font-bold">{data.database.status}</p>
                </div>
                <span className="text-xs text-muted-foreground">
                  {data.database.pingLatencyMs != null ? `${data.database.pingLatencyMs} ms` : "—"}
                </span>
              </div>
              <Progress
                value={
                  data.database.maxConnections > 0
                    ? (data.database.activeConnections * 100) / data.database.maxConnections
                    : 0
                }
                className="mt-3 h-2"
              />
              <p className="mt-2 text-xs text-muted-foreground">
                Connections:{" "}
                <span className="font-semibold text-foreground">
                  {data.database.activeConnections} active / {data.database.totalConnections} total (max {data.database.maxConnections})
                </span>
              </p>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Detailed Sections: Storage Partitions & Memory Breakdown */}
      {data && (
        <div className="grid gap-6 lg:grid-cols-2">
          {/* Storage Partitions Breakdown */}
          <Card className="shadow-none">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-semibold flex items-center gap-2">
                <HardDrive className="h-4 w-4 text-primary" /> Storage & Disk Partition Diagnostics
              </CardTitle>
              <CardDescription>
                Monitors root filesystems and USSD log repository directories to prevent disk-full service interruption.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {data.storage.map((part, i) => (
                <div key={i} className="rounded-lg border p-3.5 space-y-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div>
                      <div className="flex items-center gap-2">
                        <p className="text-sm font-semibold">{part.name}</p>
                        <Badge
                          variant={part.status === "HEALTHY" ? "secondary" : "destructive"}
                          className={cn(
                            "text-[10px] px-1.5 py-0 font-medium uppercase",
                            part.status === "HEALTHY" && "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
                            part.status === "WARNING" && "bg-amber-500/10 text-amber-600 dark:text-amber-400"
                          )}
                        >
                          {part.status}
                        </Badge>
                      </div>
                      <p className="text-xs font-mono text-muted-foreground mt-0.5">{part.path}</p>
                    </div>
                    <div className="text-right">
                      <span className="text-base font-bold tabular-nums">{part.usagePercent}%</span>
                      <p className="text-xs text-muted-foreground">
                        {formatBytes(part.usableBytes)} free
                      </p>
                    </div>
                  </div>

                  <Progress
                    value={part.usagePercent}
                    className="h-2"
                    indicatorClassName={cn(
                      part.usagePercent >= 90
                        ? "bg-red-500"
                        : part.usagePercent >= 80
                        ? "bg-amber-500"
                        : "bg-emerald-500"
                    )}
                  />

                  <div className="flex justify-between text-xs text-muted-foreground pt-1">
                    <span>Used: {formatBytes(part.usedBytes)}</span>
                    <span>Total: {formatBytes(part.totalBytes)}</span>
                  </div>
                </div>
              ))}
            </CardContent>
          </Card>

          {/* RAM & Memory Diagnostics */}
          <Card className="shadow-none">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-semibold flex items-center gap-2">
                <Layers className="h-4 w-4 text-primary" /> Memory & JVM Resource Allocation
              </CardTitle>
              <CardDescription>
                Detailed breakdown of host operating system physical RAM and Java Virtual Machine Heap allocation.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* Host OS Physical RAM */}
              <div className="rounded-lg border p-3.5 space-y-2">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-semibold">Host Operating System Physical RAM</p>
                    <p className="text-xs text-muted-foreground">Total hardware RAM available to Linux host</p>
                  </div>
                  <span className="text-base font-bold tabular-nums">
                    {data.memory.physicalUsagePercent}%
                  </span>
                </div>
                <Progress
                  value={data.memory.physicalUsagePercent}
                  className="h-2"
                  indicatorClassName={cn(
                    data.memory.physicalUsagePercent >= 90
                      ? "bg-red-500"
                      : data.memory.physicalUsagePercent >= 80
                      ? "bg-amber-500"
                      : "bg-emerald-500"
                  )}
                />
                <div className="grid grid-cols-3 gap-2 text-xs text-muted-foreground pt-1">
                  <div>
                    <span className="text-foreground font-medium">{formatBytes(data.memory.usedPhysicalBytes)}</span> Used
                  </div>
                  <div>
                    <span className="text-foreground font-medium">{formatBytes(data.memory.freePhysicalBytes)}</span> Free
                  </div>
                  <div className="text-right">
                    <span className="text-foreground font-medium">{formatBytes(data.memory.totalPhysicalBytes)}</span> Total
                  </div>
                </div>
              </div>

              {/* JVM Heap Memory */}
              <div className="rounded-lg border p-3.5 space-y-2">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-semibold">JVM Heap Memory Allocation</p>
                    <p className="text-xs text-muted-foreground">Memory dedicated to application objects and queries</p>
                  </div>
                  <span className="text-base font-bold tabular-nums">
                    {data.memory.jvmHeapUsagePercent}%
                  </span>
                </div>
                <Progress
                  value={data.memory.jvmHeapUsagePercent}
                  className="h-2"
                  indicatorClassName={cn(
                    data.memory.jvmHeapUsagePercent >= 90
                      ? "bg-red-500"
                      : data.memory.jvmHeapUsagePercent >= 80
                      ? "bg-amber-500"
                      : "bg-emerald-500"
                  )}
                />
                <div className="grid grid-cols-3 gap-2 text-xs text-muted-foreground pt-1">
                  <div>
                    <span className="text-foreground font-medium">{formatBytes(data.memory.jvmHeapUsedBytes)}</span> Used
                  </div>
                  <div>
                    <span className="text-foreground font-medium">{formatBytes(data.memory.jvmHeapCommittedBytes)}</span> Committed
                  </div>
                  <div className="text-right">
                    <span className="text-foreground font-medium">{formatBytes(data.memory.jvmHeapMaxBytes)}</span> Max
                  </div>
                </div>
              </div>

              {/* Additional JVM & OS Metrics */}
              <div className="grid grid-cols-2 gap-3 pt-1">
                <div className="rounded-lg bg-muted/40 p-3">
                  <p className="text-xs text-muted-foreground">JVM Non-Heap / Metaspace</p>
                  <p className="mt-1 text-sm font-semibold">{formatBytes(data.memory.jvmNonHeapUsedBytes)}</p>
                </div>
                <div className="rounded-lg bg-muted/40 p-3">
                  <p className="text-xs text-muted-foreground">Active JVM Threads</p>
                  <p className="mt-1 text-sm font-semibold">
                    {data.jvm.threadCount} threads ({data.jvm.peakThreadCount} peak)
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* USSD Nodes Connectivity & Host Environment */}
      {data && (
        <div className="grid gap-6 lg:grid-cols-2">
          {/* USSD Production Nodes Reachability */}
          <Card className="shadow-none">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-semibold flex items-center gap-2">
                <Server className="h-4 w-4 text-primary" /> USSD Production Nodes Connectivity
              </CardTitle>
              <CardDescription>
                SSH port (22) reachability and response latency for configured gateway nodes.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {data.nodes.length === 0 ? (
                <p className="text-center py-6 text-sm text-muted-foreground">
                  No USSD nodes configured in the system.
                </p>
              ) : (
                <div className="space-y-2.5">
                  {data.nodes.map((node) => (
                    <div
                      key={node.id}
                      className="flex items-center justify-between p-3 rounded-lg border hover:bg-muted/30 transition-colors"
                    >
                      <div className="flex items-center gap-3">
                        <span
                          className={cn(
                            "h-2.5 w-2.5 rounded-full shrink-0",
                            node.status === "REACHABLE"
                              ? "bg-emerald-500"
                              : node.status === "UNREACHABLE"
                              ? "bg-red-500"
                              : "bg-muted-foreground/40"
                          )}
                        />
                        <div>
                          <p className="text-sm font-semibold">{node.name}</p>
                          <p className="text-xs font-mono text-muted-foreground">{node.ipAddress}</p>
                        </div>
                      </div>

                      <div className="text-right">
                        <Badge
                          variant={node.status === "REACHABLE" ? "secondary" : "destructive"}
                          className={cn(
                            "text-xs px-2 py-0.5",
                            node.status === "REACHABLE" && "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
                            node.status === "INACTIVE" && "bg-muted text-muted-foreground"
                          )}
                        >
                          {node.status}
                        </Badge>
                        {node.latencyMs != null && (
                          <p className="text-[11px] font-mono text-muted-foreground mt-0.5">
                            {node.latencyMs} ms
                          </p>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Host Environment & Runtime Specs */}
          <Card className="shadow-none">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-semibold flex items-center gap-2">
                <Cpu className="h-4 w-4 text-primary" /> Host Environment & Runtime Specs
              </CardTitle>
              <CardDescription>
                Hardware architecture, operating system release, and Java Virtual Machine specifications.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="divide-y rounded-lg border">
                <div className="flex items-center justify-between p-3 text-xs">
                  <span className="text-muted-foreground">Operating System</span>
                  <span className="font-semibold">{data.os.name} ({data.os.arch})</span>
                </div>
                <div className="flex items-center justify-between p-3 text-xs">
                  <span className="text-muted-foreground">Kernel / OS Version</span>
                  <span className="font-mono">{data.os.version}</span>
                </div>
                <div className="flex items-center justify-between p-3 text-xs">
                  <span className="text-muted-foreground">CPU Core Count</span>
                  <span className="font-semibold">{data.os.availableProcessors} Logical Cores</span>
                </div>
                <div className="flex items-center justify-between p-3 text-xs">
                  <span className="text-muted-foreground">Java Runtime Version</span>
                  <span className="font-mono">{data.jvm.javaVersion} ({data.jvm.jvmVendor})</span>
                </div>
                <div className="flex items-center justify-between p-3 text-xs">
                  <span className="text-muted-foreground">Database Engine</span>
                  <span className="font-medium text-right max-w-[280px] truncate">{data.database.databaseProductName}</span>
                </div>
                <div className="flex items-center justify-between p-3 text-xs">
                  <span className="text-muted-foreground">Application Process CPU</span>
                  <span className="font-semibold">{data.os.processCpuPercent != null ? `${data.os.processCpuPercent}%` : "—"}</span>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}
