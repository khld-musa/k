"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { apiClient } from "@/lib/api-client"
import { DataTable } from "@/components/data-table"
import { type ColumnDef } from "@tanstack/react-table"
import { Button } from "@/components/ui/button"
import {
  History, RefreshCw, MessageSquare, Mail, Calendar
} from "lucide-react"
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription
} from "@/components/ui/dialog"
import { toast } from "sonner"
import { useAuth } from "@/lib/auth-context"
import { useRouter } from "next/navigation"
import DOMPurify from "dompurify"
import { BalanceTrends } from "@/components/balance-trends"

interface AlarmHistory {
  id: number
  alarmConfigId: number
  username: string
  clientId: string
  currentBalance: number
  threshold: number
  triggeredAt: string
  smsMessage: string
  emailMessage: string
}

export default function AlarmHistoryPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [history, setHistory] = React.useState<AlarmHistory[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [selectedItem, setSelectedItem] = React.useState<AlarmHistory | null>(null)
  const [viewType, setViewType] = React.useState<"SMS" | "EMAIL">("SMS")

  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.")
      router.push("/dashboard")
    }
  }, [user, router])

  const fetchHistory = React.useCallback(async () => {
    setIsLoading(true)
    try {
      const data = await apiClient("/alarms/history")
      setHistory(data)
    } catch (e: any) {
      toast.error("Failed to load history: " + e.message)
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => { if (user?.role === "ADMIN") fetchHistory() }, [user?.role, fetchHistory])

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString('en-GB', {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    })
  }

  const columns: ColumnDef<AlarmHistory>[] = [
    {
      accessorKey: "triggeredAt",
      header: "Triggered At",
      cell: ({ row }) => (
        <div className="flex items-center gap-2 whitespace-nowrap">
          <Calendar className="h-4 w-4 text-muted-foreground" />
          {formatDate(row.getValue("triggeredAt"))}
        </div>
      ),
    },
    {
      accessorKey: "username",
      header: "Account",
      cell: ({ row }) => (
        <div className="flex flex-col">
          <span className="font-semibold text-sm">{row.getValue("username")}</span>
          <span className="text-[10px] text-muted-foreground font-mono">{row.original.clientId}</span>
        </div>
      ),
    },
    {
      accessorKey: "currentBalance",
      header: "Balance",
      cell: ({ row }) => (
        <span className="text-destructive font-bold tabular-nums">
          {(row.getValue("currentBalance") as number).toLocaleString()}
        </span>
      ),
    },
    {
      accessorKey: "threshold",
      header: "Threshold",
      cell: ({ row }) => (
        <span className="text-muted-foreground tabular-nums">
          {(row.getValue("threshold") as number).toLocaleString()}
        </span>
      ),
    },
    {
      id: "actions",
      header: () => <div className="text-right">Actions</div>,
      cell: ({ row }) => (
        <div className="flex justify-end gap-2">
          <Button 
            variant="outline" 
            size="sm" 
            className="h-8 gap-2"
            onClick={() => { setSelectedItem(row.original); setViewType("SMS") }}
          >
            <MessageSquare className="h-3.5 w-3.5" /> SMS
          </Button>
          <Button 
            variant="outline" 
            size="sm" 
            className="h-8 gap-2"
            onClick={() => { setSelectedItem(row.original); setViewType("EMAIL") }}
          >
            <Mail className="h-3.5 w-3.5" /> Email
          </Button>
        </div>
      ),
    },
  ]

  return (
    <>
      <DashboardHeader breadcrumbs={[
        { label: "Dashboard", href: "/dashboard" },
        { label: "Management", href: "#" },
        { label: "Balance Alarms", href: "/dashboard/balance-alarms/config" },
        { label: "Alarm History" },
      ]} />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="flex items-center justify-between">
          <div className="grid gap-2">
            <h1 className="text-3xl font-bold tracking-tight">Alarm History</h1>
            <p className="text-muted-foreground text-lg">Audit trail of all triggered balance alerts.</p>
          </div>
          <div className="flex items-center gap-3">
            <Button variant="outline" asChild>
              <a href="/dashboard/balance-alarms/records">
                View All Records
              </a>
            </Button>
            <Button variant="outline" onClick={fetchHistory} disabled={isLoading}>
              <RefreshCw className={`mr-2 h-4 w-4 ${isLoading ? "animate-spin" : ""}`} />
              Refresh
            </Button>
          </div>
        </div>

        <BalanceTrends data={history} />

        <div className="flex-1">
          <DataTable 
            columns={columns} 
            data={history} 
            title="Notification Log"
            description="Recent balance alerts and messages"
          />
        </div>
      </div>

      <Dialog open={!!selectedItem} onOpenChange={(open) => !open && setSelectedItem(null)}>
        <DialogContent className="sm:max-w-2xl max-h-[80vh] overflow-hidden flex flex-col">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              {viewType === "SMS" ? <MessageSquare className="h-5 w-5" /> : <Mail className="h-5 w-5" />}
              {viewType} Content
            </DialogTitle>
            <DialogDescription>
              Detailed alert content sent to subscribers on {selectedItem && formatDate(selectedItem.triggeredAt)}
            </DialogDescription>
          </DialogHeader>
          
          <div className="flex-1 overflow-y-auto mt-4 rounded-md border bg-slate-50 p-4 font-sans text-slate-900">
            {viewType === "SMS" ? (
              <div className="bg-white rounded-xl shadow-sm border p-4 max-w-[400px] mx-auto relative group">
                <div className="absolute -left-2 top-4 w-4 h-4 bg-white border-l border-b rotate-45" />
                <p className="text-sm leading-relaxed text-slate-800 whitespace-pre-wrap">
                  {selectedItem?.smsMessage}
                </p>
              </div>
            ) : (
              <div className="bg-white border shadow-sm rounded-md overflow-hidden min-h-[300px]">
                <div className="bg-slate-100 p-2 border-b text-[10px] text-muted-foreground uppercase tracking-widest px-4">
                  HTML Preview
                </div>
                <div 
                  className="p-6 overflow-x-auto"
                  dangerouslySetInnerHTML={{ __html: typeof window !== 'undefined' ? DOMPurify.sanitize(selectedItem?.emailMessage || "") : "" }} 
                />
              </div>
            )}
          </div>
          
          <div className="flex justify-between items-center mt-4">
               <div className="flex gap-2">
                  <Button 
                    variant={viewType === "SMS" ? "default" : "outline"} 
                    size="sm" 
                    onClick={() => setViewType("SMS")}
                  >
                    SMS
                  </Button>
                  <Button 
                    variant={viewType === "EMAIL" ? "default" : "outline"} 
                    size="sm" 
                    onClick={() => setViewType("EMAIL")}
                  >
                    Email
                  </Button>
               </div>
               <Button variant="ghost" onClick={() => setSelectedItem(null)}>Close</Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}
