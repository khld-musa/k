"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { DataTable } from "@/components/data-table"
import { apiClient } from "@/lib/api-client"
import { Badge } from "@/components/ui/badge"
import { format } from "date-fns"
import { toast } from "sonner"
import type { ColumnDef } from "@tanstack/react-table"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/lib/auth-context"
import { useRouter } from "next/navigation"

interface AuditLogEntry {
  id: number
  action: string
  username: string
  targetUser: string | null
  details: string | null
  ipAddress: string | null
  timestamp: string
}

function getActionVariant(action: string): "default" | "secondary" | "destructive" | "outline" {
  switch (action) {
    case "LOGIN":
    case "LOGOUT":
      return "default"
    case "CREATE_USER":
      return "secondary"
    case "DELETE_USER":
      return "destructive"
    case "UPDATE_USER":
    case "TOGGLE_USER_STATUS":
      return "outline"
    case "CHANGE_PASSWORD":
      return "secondary"
    default:
      return "outline"
  }
}

function formatAction(action: string): string {
  return action
    .split("_")
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(" ")
}

const columns: ColumnDef<AuditLogEntry>[] = [
  {
    accessorKey: "timestamp",
    header: "Timestamp",
    cell: ({ row }) => {
      const date = row.getValue("timestamp") as string
      try {
        return (
          <div className="text-sm whitespace-nowrap">
            {format(new Date(date), "MMM d, yyyy · h:mm:ss a")}
          </div>
        )
      } catch {
        return <span className="text-muted-foreground">—</span>
      }
    },
  },
  {
    accessorKey: "action",
    header: "Action",
    cell: ({ row }) => {
      const action = row.getValue("action") as string
      return (
        <Badge variant={getActionVariant(action)} className="font-mono text-xs">
          {formatAction(action)}
        </Badge>
      )
    },
  },
  {
    accessorKey: "username",
    header: "User",
    cell: ({ row }) => (
      <div className="font-medium">{row.getValue("username")}</div>
    ),
  },
  {
    accessorKey: "targetUser",
    header: "Target",
    cell: ({ row }) => {
      const target = row.getValue("targetUser") as string | null
      return target ? (
        <span>{target}</span>
      ) : (
        <span className="text-muted-foreground">—</span>
      )
    },
  },
  {
    accessorKey: "details",
    header: "Details",
    cell: ({ row }) => {
      const details = row.getValue("details") as string | null
      return details ? (
        <span className="text-sm text-muted-foreground max-w-[300px] truncate block">
          {details}
        </span>
      ) : (
        <span className="text-muted-foreground">—</span>
      )
    },
  },
  {
    accessorKey: "ipAddress",
    header: "IP Address",
    cell: ({ row }) => {
      const ip = row.getValue("ipAddress") as string | null
      return ip ? (
        <code className="text-xs bg-muted px-1.5 py-0.5 rounded font-mono">{ip}</code>
      ) : (
        <span className="text-muted-foreground">—</span>
      )
    },
  },
]

export default function AuditLogPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [logs, setLogs] = React.useState<AuditLogEntry[]>([])
  const [isLoading, setIsLoading] = React.useState(true)

  React.useEffect(() => {
    async function fetchLogs() {
      try {
        const data = await apiClient("/audit-logs")
        // Handle Spring Page object or direct array
        setLogs(Array.isArray(data) ? data : (data.content || []))
      } catch (error: any) {
        toast.error("Failed to load audit logs: " + error.message)
      } finally {
        setIsLoading(false)
      }
    }

    if (user?.role === "ADMIN") {
      fetchLogs()
    }
  }, [user])

  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "Dashboard", href: "/dashboard" },
          { label: "Audit Logs" },
        ]}
      />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="grid gap-2">
          <h1 className="text-3xl font-bold tracking-tight">Audit Logs</h1>
          <p className="text-muted-foreground text-lg">
            Track and review all user activity across the platform.
          </p>
        </div>

        <div className="flex-1">
          {isLoading ? (
            <div className="flex flex-col gap-3">
              {Array.from({ length: 8 }).map((_, i) => (
                <Skeleton key={i} className="h-14 w-full rounded-lg" />
              ))}
            </div>
          ) : (
            <DataTable
              columns={columns}
              data={logs}
              title="Activity Log"
              description="A complete log of all user actions recorded by the system."
            />
          )}
        </div>
      </div>
    </>
  )
}
