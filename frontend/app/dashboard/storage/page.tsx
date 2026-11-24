"use client"

import * as React from "react"
import {
  Database,
  HardDrive,
  Trash2,
  RefreshCw,
  AlertTriangle,
  CheckCircle2,
  Layers,
  Clock,
  Sparkles,
  ArrowDownToLine,
  ShieldAlert,
} from "lucide-react"
import { apiClient } from "@/lib/api-client"
import { useAuth } from "@/lib/auth-context"
import { Button } from "@/components/ui/button"

interface StorageStats {
  databaseSize?: string
  databaseSizeBytes?: number
  ussdTransactionsTotal?: string
  ussdTransactionsTotalBytes?: number
  ussdTransactionsTableOnly?: string
  ussdTransactionsIndexes?: string
  ussdTransactionsIndexesBytes?: number
  estimatedRowCount?: number
  deadTuples?: number
  liveTuples?: number
  lastVacuum?: string | null
  lastAutovacuum?: string | null
  lastAnalyze?: string | null
  retentionDays?: number
  dataEarliest?: string | null
  dataLatest?: string | null
  indexes?: Array<{ indexname: string; size: string; size_bytes: number }>
  topTables?: Array<{ table_name: string; total_size: string; total_bytes: number }>
  error?: string
}

export default function StorageManagementPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === "ADMIN"

  const [stats, setStats] = React.useState<StorageStats | null>(null)
  const [loading, setLoading] = React.useState(true)
  const [actionLoading, setActionLoading] = React.useState<string | null>(null)
  const [retentionDaysInput, setRetentionDaysInput] = React.useState<number>(30)
  const [statusMessage, setStatusMessage] = React.useState<{ type: "success" | "error"; text: string } | null>(null)

  const fetchStats = React.useCallback(async () => {
    try {
      setLoading(true)
      const data = (await apiClient("/admin/storage/stats")) as StorageStats
      setStats(data)
      if (data.retentionDays) {
        setRetentionDaysInput(data.retentionDays)
      }
    } catch (err: any) {
      setStatusMessage({ type: "error", text: err.message || "Failed to load storage statistics" })
    } finally {
      setLoading(false)
    }
  }, [])

  React.useEffect(() => {
    fetchStats()
  }, [fetchStats])

  const handleAction = async (endpoint: string, actionName: string, confirmPrompt?: string) => {
    if (confirmPrompt && !window.confirm(confirmPrompt)) {
      return
    }

    try {
      setActionLoading(actionName)
      setStatusMessage(null)
      const res: any = await apiClient(endpoint, { method: "POST" })
      setStatusMessage({
        type: "success",
        text: res.message || `${actionName} completed successfully!`,
      })
      await fetchStats()
    } catch (err: any) {
      setStatusMessage({
        type: "error",
        text: err.message || `${actionName} failed`,
      })
    } finally {
      setActionLoading(null)
    }
  }

  const handlePurge = async () => {
    const prompt = `Are you sure you want to permanently purge records older than ${retentionDaysInput} days? This will free space from old ussd_transactions.`
    if (!window.confirm(prompt)) return

    try {
      setActionLoading("purge")
      setStatusMessage(null)
      const res: any = await apiClient(`/admin/storage/purge?retentionDays=${retentionDaysInput}`, { method: "POST" })
      setStatusMessage({
        type: "success",
        text: `Purge completed: ${res.rowsDeleted?.toLocaleString() || 0} rows deleted.`,
      })
      await fetchStats()
    } catch (err: any) {
      setStatusMessage({ type: "error", text: err.message || "Purge failed" })
    } finally {
      setActionLoading(null)
    }
  }

  if (!isAdmin) {
    return (
      <div className="p-8 text-center">
        <ShieldAlert className="w-12 h-12 text-amber-500 mx-auto mb-4" />
        <h2 className="text-xl font-bold">Access Restricted</h2>
        <p className="text-muted-foreground mt-2">Only system administrators can access storage management.</p>
      </div>
    )
  }

  return (
    <div className="p-6 space-y-6 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <HardDrive className="w-6 h-6 text-primary" />
            Database Storage & Cleanup
          </h1>
          <p className="text-muted-foreground text-sm">
            Monitor PostgreSQL disk consumption, reclaim space from deleted transactions, and maintain table health.
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={fetchStats}
          disabled={loading || !!actionLoading}
          className="flex items-center gap-2"
        >
          <RefreshCw className={`w-4 h-4 ${loading ? "animate-spin" : ""}`} />
          Refresh Stats
        </Button>
      </div>

      {/* Status Alert */}
      {statusMessage && (
        <div
          className={`p-4 rounded-lg flex items-center gap-3 text-sm ${
            statusMessage.type === "success"
              ? "bg-green-500/10 text-green-700 dark:text-green-400 border border-green-500/20"
              : "bg-red-500/10 text-red-700 dark:text-red-400 border border-red-500/20"
          }`}
        >
          {statusMessage.type === "success" ? (
            <CheckCircle2 className="w-5 h-5 flex-shrink-0" />
          ) : (
            <AlertTriangle className="w-5 h-5 flex-shrink-0" />
          )}
          <span>{statusMessage.text}</span>
        </div>
      )}

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Total Database Size */}
        <div className="bg-card border rounded-xl p-5 shadow-sm space-y-2">
          <div className="flex justify-between items-center text-muted-foreground text-sm font-medium">
            <span>Database Total</span>
            <Database className="w-4 h-4 text-primary" />
          </div>
          <div className="text-3xl font-bold tracking-tight">
            {loading ? "..." : stats?.databaseSize || "N/A"}
          </div>
          <p className="text-xs text-muted-foreground">
            Entire PostgreSQL cluster storage
          </p>
        </div>

        {/* USSD Transactions Total */}
        <div className="bg-card border rounded-xl p-5 shadow-sm space-y-2">
          <div className="flex justify-between items-center text-muted-foreground text-sm font-medium">
            <span>ussd_transactions</span>
            <Layers className="w-4 h-4 text-blue-500" />
          </div>
          <div className="text-3xl font-bold tracking-tight text-blue-600 dark:text-blue-400">
            {loading ? "..." : stats?.ussdTransactionsTotal || "N/A"}
          </div>
          <p className="text-xs text-muted-foreground">
            Table: {stats?.ussdTransactionsTableOnly || "N/A"} | Indexes: {stats?.ussdTransactionsIndexes || "N/A"}
          </p>
        </div>

        {/* Estimated Rows & Dead Tuples */}
        <div className="bg-card border rounded-xl p-5 shadow-sm space-y-2">
          <div className="flex justify-between items-center text-muted-foreground text-sm font-medium">
            <span>Live / Dead Rows</span>
            <Clock className="w-4 h-4 text-amber-500" />
          </div>
          <div className="text-2xl font-bold tracking-tight">
            {loading ? "..." : (stats?.liveTuples ?? stats?.estimatedRowCount)?.toLocaleString() || "0"}
          </div>
          <p className="text-xs text-muted-foreground">
            Dead rows (bloat): <span className="font-semibold text-amber-600">{stats?.deadTuples?.toLocaleString() || 0}</span>
          </p>
        </div>

        {/* Data Range */}
        <div className="bg-card border rounded-xl p-5 shadow-sm space-y-2">
          <div className="flex justify-between items-center text-muted-foreground text-sm font-medium">
            <span>Stored Data Window</span>
            <Sparkles className="w-4 h-4 text-emerald-500" />
          </div>
          <div className="text-sm font-semibold truncate">
            {stats?.dataEarliest ? stats.dataEarliest.substring(0, 10) : "N/A"} to{" "}
            {stats?.dataLatest ? stats.dataLatest.substring(0, 10) : "N/A"}
          </div>
          <p className="text-xs text-muted-foreground">
            Retention setting: {stats?.retentionDays ?? 30} days
          </p>
        </div>
      </div>

      {/* Maintenance Actions Section */}
      <div className="bg-card border rounded-xl p-6 shadow-sm space-y-6">
        <div>
          <h2 className="text-lg font-semibold tracking-tight">Storage Reclamation Actions</h2>
          <p className="text-sm text-muted-foreground">
            Execute database maintenance operations to free disk blocks in <code>/var/lib/postgresql/15/main</code>.
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Action 1: Retention Purge */}
          <div className="border rounded-lg p-5 flex flex-col justify-between space-y-4 bg-muted/20">
            <div className="space-y-2">
              <div className="flex items-center gap-2 font-semibold">
                <Trash2 className="w-4 h-4 text-red-500" />
                <span>1. Purge Old Records</span>
              </div>
              <p className="text-xs text-muted-foreground">
                Deletes transactions older than the specified retention window in small batches (10k rows) without locking queries.
              </p>
              <div className="pt-2 flex items-center gap-2">
                <label className="text-xs font-medium">Days to keep:</label>
                <input
                  type="number"
                  min="1"
                  max="365"
                  value={retentionDaysInput}
                  onChange={(e) => setRetentionDaysInput(parseInt(e.target.value) || 30)}
                  className="w-20 px-2 py-1 border rounded text-sm bg-background"
                />
              </div>
            </div>
            <Button
              variant="destructive"
              size="sm"
              onClick={handlePurge}
              disabled={!!actionLoading}
              className="w-full flex items-center justify-center gap-2"
            >
              {actionLoading === "purge" ? (
                <RefreshCw className="w-4 h-4 animate-spin" />
              ) : (
                <Trash2 className="w-4 h-4" />
              )}
              Purge Older Than {retentionDaysInput} Days
            </Button>
          </div>

          {/* Action 2: Online VACUUM ANALYZE */}
          <div className="border rounded-lg p-5 flex flex-col justify-between space-y-4 bg-muted/20">
            <div className="space-y-2">
              <div className="flex items-center gap-2 font-semibold">
                <ArrowDownToLine className="w-4 h-4 text-blue-500" />
                <span>2. Reclaim Space (Online)</span>
              </div>
              <p className="text-xs text-muted-foreground">
                Runs <strong>VACUUM ANALYZE</strong> on <code>ussd_transactions</code>. Reclaims dead row space for reuse and updates PostgreSQL query optimizer.
              </p>
              <p className="text-xs text-emerald-600 dark:text-emerald-400 font-medium">
                Safe to run anytime — does not lock the table.
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => handleAction("/admin/storage/vacuum", "VACUUM ANALYZE")}
              disabled={!!actionLoading}
              className="w-full flex items-center justify-center gap-2"
            >
              {actionLoading === "VACUUM ANALYZE" ? (
                <RefreshCw className="w-4 h-4 animate-spin" />
              ) : (
                <ArrowDownToLine className="w-4 h-4" />
              )}
              Run VACUUM ANALYZE
            </Button>
          </div>

          {/* Action 3: Deep Shrink (VACUUM FULL) */}
          <div className="border rounded-lg p-5 flex flex-col justify-between space-y-4 bg-muted/20">
            <div className="space-y-2">
              <div className="flex items-center gap-2 font-semibold text-amber-600 dark:text-amber-400">
                <AlertTriangle className="w-4 h-4" />
                <span>3. Deep Shrink (VACUUM FULL)</span>
              </div>
              <p className="text-xs text-muted-foreground">
                Rewrites the table file to minimum possible size and <strong>returns disk space directly to Linux OS</strong>.
              </p>
              <p className="text-xs text-amber-600 dark:text-amber-400 font-medium">
                Notice: Locks table for reads/writes during execution.
              </p>
            </div>
            <Button
              variant="secondary"
              size="sm"
              onClick={() =>
                handleAction(
                  "/admin/storage/vacuum-full",
                  "VACUUM FULL",
                  "VACUUM FULL will acquire an exclusive lock on ussd_transactions while rewriting the file. Proceed?"
                )
              }
              disabled={!!actionLoading}
              className="w-full flex items-center justify-center gap-2 font-medium"
            >
              {actionLoading === "VACUUM FULL" ? (
                <RefreshCw className="w-4 h-4 animate-spin" />
              ) : (
                <Sparkles className="w-4 h-4" />
              )}
              Run VACUUM FULL
            </Button>
          </div>
        </div>
      </div>

      {/* Index and Table Size Details */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Index Breakdown */}
        <div className="bg-card border rounded-xl p-5 shadow-sm space-y-4">
          <div className="flex justify-between items-center">
            <h3 className="font-semibold text-sm">Indexes on ussd_transactions</h3>
            <Button
              variant="ghost"
              size="sm"
              onClick={() =>
                handleAction(
                  "/admin/storage/reindex",
                  "REINDEX",
                  "REINDEX will rebuild all indexes on ussd_transactions. Proceed?"
                )
              }
              disabled={!!actionLoading}
              className="text-xs h-7"
            >
              Rebuild Indexes
            </Button>
          </div>
          <div className="overflow-x-auto max-h-72">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b text-muted-foreground text-left">
                  <th className="pb-2 font-medium">Index Name</th>
                  <th className="pb-2 font-medium text-right">Size</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {stats?.indexes?.map((idx) => (
                  <tr key={idx.indexname} className="hover:bg-muted/30">
                    <td className="py-2 font-mono text-[11px] truncate max-w-xs">{idx.indexname}</td>
                    <td className="py-2 text-right font-semibold">{idx.size}</td>
                  </tr>
                ))}
                {!stats?.indexes?.length && (
                  <tr>
                    <td colSpan={2} className="py-4 text-center text-muted-foreground">
                      No indexes found
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Top Database Tables */}
        <div className="bg-card border rounded-xl p-5 shadow-sm space-y-4">
          <h3 className="font-semibold text-sm">Top Tables by Disk Consumption</h3>
          <div className="overflow-x-auto max-h-72">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b text-muted-foreground text-left">
                  <th className="pb-2 font-medium">Table Name</th>
                  <th className="pb-2 font-medium text-right">Total Size (Table + Indexes)</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {stats?.topTables?.map((tbl) => (
                  <tr key={tbl.table_name} className="hover:bg-muted/30">
                    <td className="py-2 font-mono text-[11px] font-medium">{tbl.table_name}</td>
                    <td className="py-2 text-right font-semibold">{tbl.total_size}</td>
                  </tr>
                ))}
                {!stats?.topTables?.length && (
                  <tr>
                    <td colSpan={2} className="py-4 text-center text-muted-foreground">
                      No tables found
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  )
}
