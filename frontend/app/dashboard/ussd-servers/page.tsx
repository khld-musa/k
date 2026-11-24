"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { DataTable } from "@/components/data-table"
import { apiClient } from "@/lib/api-client"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { MoreVertical, Circle, Plus, Server, Trash2, Edit2 } from "lucide-react"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  DropdownMenuSeparator
} from "@/components/ui/dropdown-menu"
import { format } from "date-fns"
import { toast } from "sonner"
import type { ColumnDef } from "@tanstack/react-table"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/lib/auth-context"
import { useRouter } from "next/navigation"
import { UssdServerModal } from "@/components/ussd-server-modal"

interface UssdServer {
  id: number
  name: string
  ipAddress: string
  description: string
  logPath: string
  localLogPath?: string
  active: boolean
  ftpRemotePath?: string
  hasFtpPasswordStored?: boolean
  lastFetchDate?: string | null
  createdAt: string
  updatedAt: string
}

export default function UssdServersPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [servers, setServers] = React.useState<UssdServer[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [isModalOpen, setIsModalOpen] = React.useState(false)
  const [editingServer, setEditingServer] = React.useState<UssdServer | null>(null)

  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.")
      router.push("/dashboard")
    }
  }, [user, router])

  const fetchServers = React.useCallback(async () => {
    try {
      const data = await apiClient("/servers")
      setServers(data)
    } catch (error: any) {
      toast.error("Failed to load servers: " + error.message)
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => {
    if (user?.role === "ADMIN") {
      fetchServers()
    }
  }, [user?.role, fetchServers])

  const handleDelete = async (id: number) => {
    if (!confirm("Are you sure you want to delete this server? This action cannot be undone.")) return

    try {
      await apiClient(`/servers/${id}`, { method: "DELETE" })
      toast.success("Server deleted successfully.")
      fetchServers()
    } catch (error: any) {
      toast.error("Failed to delete server: " + error.message)
    }
  }

  const columns: ColumnDef<UssdServer>[] = [
    {
      accessorKey: "name",
      header: "Server Name",
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <Server className="h-4 w-4 text-primary/70" />
          <span className="font-medium">{row.getValue("name")}</span>
        </div>
      ),
    },
    {
      accessorKey: "ipAddress",
      header: "IP Address",
      cell: ({ row }) => <code className="bg-muted px-1.5 py-0.5 rounded text-xs font-mono">{row.getValue("ipAddress")}</code>,
    },
    {
      accessorKey: "description",
      header: "Description",
      cell: ({ row }) => <span className="text-muted-foreground">{row.getValue("description") || "-"}</span>,
    },
    {
      accessorKey: "logPath",
      header: "Log Path",
      cell: ({ row }) => <code className="bg-muted px-1.5 py-0.5 rounded text-[10px] font-mono">{row.getValue("logPath") || "-"}</code>,
    },
    {
      accessorKey: "lastFetchDate",
      header: "Last FTP Fetch",
      cell: ({ row }) => {
        const date = row.getValue("lastFetchDate") as string | null | undefined
        if (!date) {
          return <span className="text-muted-foreground text-xs">Never</span>
        }
        try {
          return <span className="text-xs">{format(new Date(date), "MMM d, yyyy")}</span>
        } catch {
          return "-"
        }
      },
    },
    {
      accessorKey: "active",
      header: "Status",
      cell: ({ row }) => {
        const active = row.getValue("active") as boolean
        return (
          <Badge variant={active ? "default" : "secondary"} className="gap-1">
            <Circle className={`h-2 w-2 fill-current ${active ? "text-green-400" : "text-slate-400"}`} />
            {active ? "Active" : "Inactive"}
          </Badge>
        )
      },
    },
    {
      accessorKey: "updatedAt",
      header: "Last Updated",
      cell: ({ row }) => {
        const date = row.getValue("updatedAt") as string
        try {
          return date ? format(new Date(date), "MMM d, yyyy") : "-"
        } catch {
          return "-"
        }
      },
    },
    {
      id: "actions",
      cell: ({ row }) => (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="h-8 w-8">
              <MoreVertical className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-40">
            <DropdownMenuItem onClick={() => {
              setEditingServer(row.original)
              setIsModalOpen(true)
            }}>
              <Edit2 className="mr-2 h-4 w-4" />
              Edit Server
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              className="text-destructive focus:text-destructive"
              onClick={() => handleDelete(row.original.id)}
            >
              <Trash2 className="mr-2 h-4 w-4" />
              Delete Server
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ),
    },
  ]

  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "Dashboard", href: "/dashboard" },
          { label: "Management", href: "#" },
          { label: "USSD Servers" },
        ]}
      />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="flex items-center justify-between">
          <div className="grid gap-2">
            <h1 className="text-3xl font-bold tracking-tight">USSD Servers</h1>
            <p className="text-muted-foreground text-lg">Manage the USSD backend nodes for tracing and historical analysis.</p>
          </div>
          <Button onClick={() => {
            setEditingServer(null)
            setIsModalOpen(true)
          }}>
            <Plus className="mr-2 h-4 w-4" />
            Add Server
          </Button>
        </div>

        <div className="flex-1">
          {isLoading ? (
            <div className="flex flex-col gap-3">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-16 w-full rounded-lg" />
              ))}
            </div>
          ) : (
            <DataTable
              columns={columns}
              data={servers}
              title="USSD Nodes"
              description="A centralized list of all servers configured in the cluster."
            />
          )}
        </div>
      </div>

      <UssdServerModal
        open={isModalOpen}
        onOpenChange={setIsModalOpen}
        onSuccess={fetchServers}
        server={editingServer}
      />
    </>
  )
}
