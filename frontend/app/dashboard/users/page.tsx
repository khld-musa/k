"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { DataTable } from "@/components/data-table"
import { apiClient } from "@/lib/api-client"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { MoreVertical, Circle, Plus } from "lucide-react"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu"
import { format } from "date-fns"
import { toast } from "sonner"
import type { ColumnDef } from "@tanstack/react-table"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/lib/auth-context"
import { CreateUserModal } from "@/components/create-user-modal"
import Link from "next/link"
import { useRouter } from "next/navigation"

interface User {
  id: number
  username: string
  email: string
  role: string
  active: boolean
  lastLogin: string | null
}

const columns: ColumnDef<User>[] = [
  {
    accessorKey: "username",
    header: "Username",
    cell: ({ row }) => <div className="font-medium">{row.getValue("username")}</div>,
  },
  {
    accessorKey: "email",
    header: "Email",
  },
  {
    accessorKey: "role",
    header: "Role",
    cell: ({ row }) => {
      const role = row.getValue("role") as string
      return (
        <Badge
          variant={role === "ADMIN" ? "default" : "outline"}
          className={role === "ADMIN"
            ? "font-bold"
            : "font-medium text-muted-foreground"}
        >
          {role === "ADMIN" ? "Admin" : "User"}
        </Badge>
      )
    },
  },
  {
    accessorKey: "active",
    header: "Status",
    cell: ({ row }) => {
      const active = row.getValue("active") as boolean
      return (
        <div className="flex items-center gap-2">
          <Circle className={`h-2 w-2 fill-current ${active ? "text-green-500" : "text-muted-foreground"}`} />
          <span className="text-sm">{active ? "Active" : "Inactive"}</span>
        </div>
      )
    },
  },
  {
    accessorKey: "lastLogin",
    header: "Last Login",
    cell: ({ row }) => {
      const date = row.getValue("lastLogin") as string
      try {
        return date ? format(new Date(date), "MMM d, h:mm a") : "Never"
      } catch {
        return "Never"
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
        <DropdownMenuContent align="end" className="w-32">
          <DropdownMenuItem asChild>
            <Link href={`/dashboard/users/${row.original.id}`}>
              Edit User
            </Link>
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => toast.info("Coming soon")}>
            {row.original.active ? "Deactivate" : "Activate"}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    ),
  },
]

export default function UsersPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [users, setUsers] = React.useState<User[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [isCreateModalOpen, setIsCreateModalOpen] = React.useState(false)

  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.", { id: "role-error" })
      router.push("/dashboard")
    }
  }, [user, router])

  const fetchUsers = React.useCallback(async () => {
    try {
      const data = await apiClient("/users")
      const usersList = data?.content || (Array.isArray(data) ? data : [])
      // Filter out the currently logged-in admin
      setUsers(usersList.filter((u: User) => u.id !== user?.id))
    } catch (error: any) {
      toast.error("Failed to load users: " + error.message)
    } finally {
      setIsLoading(false)
    }
  }, [user?.id])

  React.useEffect(() => {
    if (user?.id) {
      fetchUsers()
    }
  }, [user?.id, fetchUsers])

  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "Dashboard", href: "/dashboard" },
          { label: "Management" },
          { label: "Users" },
        ]}
      />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="grid gap-2">
          <h1 className="text-3xl font-bold tracking-tight">User Management</h1>
          <p className="text-muted-foreground text-lg">Manage system users, roles, and account statuses.</p>
        </div>

        <div className="flex-1">
          {isLoading ? (
            <div className="flex flex-col gap-3">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-14 w-full rounded-lg" />
              ))}
            </div>
          ) : (
            <DataTable
              columns={columns}
              data={users}
              title="System Users"
              description="A list of all users currently registered in the system."
              actions={
                <Button size="sm" onClick={() => setIsCreateModalOpen(true)}>
                  <Plus className="mr-2 h-4 w-4" />
                  Add User
                </Button>
              }
            />
          )}
        </div>
      </div>

      <CreateUserModal 
        open={isCreateModalOpen} 
        onOpenChange={setIsCreateModalOpen} 
        onSuccess={fetchUsers} 
      />
    </>
  )
}
