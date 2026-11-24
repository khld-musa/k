"use client"

import * as React from "react"
import { toast } from "sonner"
import { TrendingDown, TrendingUp, Activity, Download, RefreshCw, Calendar, FileSpreadsheet } from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { apiClient } from "@/lib/api-client"
import { DashboardHeader } from "@/components/dashboard-header"
import { DataTable } from "@/components/data-table"
import { type ColumnDef } from "@tanstack/react-table"

interface BalanceRecord {
  id: number
  alarmConfigId: number
  username: string
  clientId: string
  balance: number
  threshold: number
  checkedAt: string
  isBelowThreshold: boolean
  alertTriggered: boolean
}

export default function BalanceRecordsPage() {
  const [records, setRecords] = React.useState<BalanceRecord[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [isRefreshing, setIsRefreshing] = React.useState(false)

  React.useEffect(() => {
    fetchAllRecords()

    // Auto-refresh every 60 seconds
    const interval = setInterval(() => {
      fetchAllRecords()
    }, 60000)

    return () => clearInterval(interval)
  }, [])

  async function fetchAllRecords() {
    try {
      const data = await apiClient("/balance-records")
      setRecords(data)
    } catch (error) {
      toast.error("Failed to load balance records")
      setRecords([])
    } finally {
      setIsLoading(false)
    }
  }

  async function handleManualRefresh() {
    setIsRefreshing(true)
    try {
      await fetchAllRecords()
      toast.success("Records refreshed successfully")
    } catch (error) {
      toast.error("Failed to refresh records")
    } finally {
      setIsRefreshing(false)
    }
  }

  async function handleExport() {
    try {
      const token = typeof window !== "undefined" ? localStorage.getItem("auth_token") : null
      const response = await fetch(`${process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api/v1"}/balance-records/export/excel`, {
        method: "GET",
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })

      if (!response.ok) {
        const err = await response.text().catch(() => "")
        throw new Error(err || response.statusText)
      }

      const blob = await response.blob()
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement("a")
      a.href = url
      a.download = `balance-status-${new Date().toISOString().split("T")[0]}.xlsx`
      a.click()
      setTimeout(() => window.URL.revokeObjectURL(url), 60_000)
      toast.success("Balance records exported as Excel successfully")
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error)
      toast.error("Failed to export Excel file", { description: msg })
    }
  }

  const stats = React.useMemo(() => {
    if (records.length === 0) return { total: 0, belowThreshold: 0, alerts: 0, avgBalance: 0 }

    const balances = records.map(r => r.balance)
    return {
      total: records.length,
      belowThreshold: records.filter(r => r.isBelowThreshold).length,
      alerts: records.filter(r => r.alertTriggered).length,
      avgBalance: balances.reduce((a, b) => a + b, 0) / balances.length
    }
  }, [records])

  // DataTable columns definition
  const columns: ColumnDef<BalanceRecord>[] = [
    {
      accessorKey: "username",
      header: "Username",
      cell: ({ row }) => (
        <div className="font-medium">{row.getValue("username") || "—"}</div>
      ),
    },
    {
      accessorKey: "clientId",
      header: "Client ID",
      cell: ({ row }) => (
        <code className="bg-muted px-2 py-1 rounded text-xs font-mono">
          {row.getValue("clientId")}
        </code>
      ),
    },
    {
      accessorKey: "balance",
      header: "Current Balance",
      cell: ({ row }) => {
        const isBelowThreshold = row.original.isBelowThreshold
        return (
          <span className={`font-mono font-semibold ${isBelowThreshold ? "text-destructive" : "text-green-600"
            }`}>
            {(row.getValue("balance") as number).toFixed(2)}
          </span>
        )
      },
    },
    {
      accessorKey: "threshold",
      header: "Threshold",
      cell: ({ row }) => (
        <span className="font-mono">{(row.getValue("threshold") as number).toFixed(2)}</span>
      ),
    },
    {
      accessorKey: "isBelowThreshold",
      header: "Status",
      cell: ({ row }) => {
        const isBelowThreshold = row.getValue("isBelowThreshold")
        return isBelowThreshold ? (
          <Badge variant="destructive" className="gap-1">
            <TrendingDown className="h-3 w-3" />
            Below Threshold
          </Badge>
        ) : (
          <Badge variant="outline" className="gap-1 bg-green-100 text-green-800 border-green-200">
            <TrendingUp className="h-3 w-3" />
            OK
          </Badge>
        )
      },
    },
    {
      accessorKey: "alertTriggered",
      header: "Alert Status",
      cell: ({ row }) => {
        const alertTriggered = row.getValue("alertTriggered")
        return alertTriggered ? (
          <Badge variant="outline" className="bg-orange-100 text-orange-800 border-orange-200">
            ⚠️ Alert Active
          </Badge>
        ) : (
          <span className="text-muted-foreground text-sm">—</span>
        )
      },
    },
    {
      accessorKey: "checkedAt",
      header: "Last Checked",
      cell: ({ row }) => (
        <div className="flex items-center gap-2 whitespace-nowrap text-sm text-muted-foreground">
          <Calendar className="h-4 w-4" />
          {new Date(row.getValue("checkedAt")).toLocaleString()}
        </div>
      ),
    },
  ]

  if (isLoading) {
    return (
      <div className="flex h-[400px] items-center justify-center">
        <div className="text-center">
          <Activity className="h-8 w-8 animate-spin mx-auto mb-2 text-primary" />
          <p className="text-sm text-muted-foreground">Loading balance records...</p>
        </div>
      </div>
    )
  }

  return (
    <>
      <DashboardHeader breadcrumbs={[
        { label: "Dashboard", href: "/dashboard" },
        { label: "Balance Alarms", href: "/dashboard/balance-alarms" },
        { label: "Current Balance Status" },
      ]} />

      <div className="flex flex-1 flex-col gap-6 p-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Current Balance Status</h1>
            <p className="text-muted-foreground text-lg">
              Real-time balance status for all monitored accounts
              <span className="text-xs ml-2 text-muted-foreground/70">
                {isRefreshing ? "Refreshing..." : "Auto-refreshes every 60s"}
              </span>
            </p>
          </div>
          <div className="flex items-center gap-3">
            <Button
              variant="outline"
              onClick={handleManualRefresh}
              disabled={isRefreshing}
            >
              <RefreshCw className={`mr-2 h-4 w-4 ${isRefreshing ? "animate-spin" : ""}`} />
              Refresh
            </Button>
            <Button onClick={handleExport} disabled={records.length === 0} variant="secondary">
              <FileSpreadsheet className="mr-2 h-4 w-4 text-green-600" />
              Export Excel
            </Button>
          </div>
        </div>

        {/* Statistics Cards */}
        <div className="grid gap-4 md:grid-cols-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Total Accounts</CardTitle>
              <Activity className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{stats.total}</div>
              <p className="text-xs text-muted-foreground">monitored accounts</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Average Balance</CardTitle>
              <TrendingUp className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{stats.avgBalance.toFixed(2)}</div>
              <p className="text-xs text-muted-foreground">across all accounts</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Below Threshold</CardTitle>
              <TrendingDown className="h-4 w-4 text-destructive" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold text-destructive">{stats.belowThreshold}</div>
              <p className="text-xs text-muted-foreground">
                {stats.total > 0 ? ((stats.belowThreshold / stats.total) * 100).toFixed(1) : 0}% of accounts
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Active Alerts</CardTitle>
              <Activity className="h-4 w-4 text-orange-500" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold text-orange-500">{stats.alerts}</div>
              <p className="text-xs text-muted-foreground">alerts currently active</p>
            </CardContent>
          </Card>
        </div>

        {/* Balance Records Table */}
        <DataTable
          columns={columns}
          data={records}
          title="Balance Status"
          description="Current balance status for all monitored accounts (one record per account, updated on each check)"
        />
      </div>
    </>
  )
}

