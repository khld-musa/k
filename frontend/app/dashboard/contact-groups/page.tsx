"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { DataTable } from "@/components/data-table"
import { type ColumnDef } from "@tanstack/react-table"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  MoreVertical, Plus, UsersRound, Trash2, Edit2, Eye, ExternalLink
} from "lucide-react"
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger, DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu"
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from "@/components/ui/dialog"
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { toast } from "sonner"
import { useAuth } from "@/lib/auth-context"
import { useRouter } from "next/navigation"
import Link from "next/link"

interface Contact {
  id: number
  name: string
  email: string
  phoneNumber: string
}

interface ContactGroup {
  id: number
  name: string
  description: string
  contacts: Contact[]
}

export default function ContactGroupsPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [groups, setGroups] = React.useState<ContactGroup[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [groupModalOpen, setGroupModalOpen] = React.useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = React.useState(false)
  const [selectedGroup, setSelectedGroup] = React.useState<ContactGroup | null>(null)
  const [groupForm, setGroupForm] = React.useState<Partial<ContactGroup>>({})
  const [groupErrors, setGroupErrors] = React.useState<{ name?: string; description?: string }>({})

  const openGroupForm = (values: Partial<ContactGroup>) => {
    setGroupForm(values)
    setGroupErrors({})
    setGroupModalOpen(true)
  }

  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.")
      router.push("/dashboard")
    }
  }, [user, router])

  const fetchGroups = React.useCallback(async () => {
    setIsLoading(true)
    try {
      const data = await apiClient("/groups")
      setGroups(data)
    } catch (e: any) {
      toast.error("Failed to load groups: " + e.message)
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => { if (user?.role === "ADMIN") fetchGroups() }, [user?.role, fetchGroups])

  const handleSaveGroup = async () => {
    const errors: typeof groupErrors = {}
    if (!groupForm.name?.trim()) errors.name = "Group name is required."
    else if (groupForm.name.length > 255) errors.name = "Group name must not exceed 255 characters."
    else if (groups.some(g => g.id !== groupForm.id && g.name.trim().toLowerCase() === groupForm.name!.trim().toLowerCase())) {
      errors.name = "A group with this name already exists."
    }
    setGroupErrors(errors)
    if (Object.keys(errors).length > 0) return

    try {
      if (groupForm.id) {
        await apiClient(`/groups/${groupForm.id}`, { method: "PUT", body: JSON.stringify(groupForm) })
        toast.success("Group updated successfully")
      } else {
        await apiClient("/groups", { method: "POST", body: JSON.stringify(groupForm) })
        toast.success("Group created successfully")
      }
      setGroupModalOpen(false)
      setGroupForm({})
      fetchGroups()
    } catch (e: any) {
      const fieldErrors = getFieldErrors(e)
      if (Object.keys(fieldErrors).length > 0) setGroupErrors(fieldErrors)
      else toast.error((groupForm.id ? "Failed to update group: " : "Failed to create group: ") + e.message)
    }
  }

  const handleDeleteGroup = async () => {
    if (!selectedGroup) return
    try {
      await apiClient(`/groups/${selectedGroup.id}`, { method: "DELETE" })
      toast.success("Group deleted successfully")
      setDeleteDialogOpen(false)
      setSelectedGroup(null)
      fetchGroups()
    } catch (e: any) {
      toast.error("Failed to delete group: " + e.message)
    }
  }

  const confirmDelete = (group: ContactGroup) => {
    setSelectedGroup(group)
    setDeleteDialogOpen(true)
  }

  const columns: ColumnDef<ContactGroup>[] = [
    {
      accessorKey: "name",
      header: "Group Name",
      cell: ({ row }) => (
        <div className="flex items-center gap-2 font-medium">
          <UsersRound className="h-4 w-4 text-primary/70" />
          {row.getValue("name")}
        </div>
      ),
    },
    {
      accessorKey: "description",
      header: "Description",
      cell: ({ row }) => <span className="text-muted-foreground text-sm">{row.getValue("description") || "No description"}</span>,
    },
    {
      accessorKey: "contacts",
      header: "Members",
      cell: ({ row }) => <Badge variant="secondary">{(row.getValue("contacts") as Contact[])?.length || 0} contacts</Badge>,
    },
    {
      id: "actions",
      cell: ({ row }) => (
        <div className="flex justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            className="h-8 gap-1.5"
            asChild
          >
            {/* <Link href={`/dashboard/contact-groups/${row.original.id}`}>
              <Eye className="h-3.5 w-3.5" /> Details
            </Link> */}
          </Button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" className="h-8 w-8"><MoreVertical className="h-4 w-4" /></Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48">
              <DropdownMenuItem asChild>
                <Link href={`/dashboard/contact-groups/${row.original.id}`}>
                  <ExternalLink className="mr-2 h-4 w-4" /> Manage Members
                </Link>
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => openGroupForm(row.original)}>
                <Edit2 className="mr-2 h-4 w-4" /> Edit Group
              </DropdownMenuItem>
              <DropdownMenuItem className="text-destructive focus:text-destructive" onClick={() => confirmDelete(row.original)}>
                <Trash2 className="mr-2 h-4 w-4" /> Delete Group
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      ),
    },
  ]

  return (
    <>
      <DashboardHeader breadcrumbs={[
        { label: "Dashboard", href: "/dashboard" },
        { label: "Management", href: "#" },
        { label: "Contact Groups" },
      ]} />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="flex items-center justify-between">
          <div className="grid gap-2">
            <h1 className="text-3xl font-bold tracking-tight">Contact Groups</h1>
            <p className="text-muted-foreground text-lg">Manage notification contact groups for system alerts.</p>
          </div>
          <Button onClick={() => openGroupForm({})}>
            <Plus className="mr-2 h-4 w-4" /> New Group
          </Button>
        </div>

        <div className="flex-1">
          <DataTable
            columns={columns}
            data={groups}
            title="Notification Groups"
            description="Manage groups to organize alert recipients"
          />
        </div>
      </div>

      {/* Group Modal */}
      <Dialog open={groupModalOpen} onOpenChange={setGroupModalOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{groupForm.id ? "Edit Group" : "Create Group"}</DialogTitle>
            <DialogDescription>Define a contact group for alarm notifications.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="group-name" className={groupErrors.name ? "text-destructive" : ""}>Group Name</Label>
              <Input
                id="group-name"
                value={groupForm.name || ""}
                onChange={e => {
                  setGroupForm({ ...groupForm, name: e.target.value })
                  if (groupErrors.name) setGroupErrors({ ...groupErrors, name: undefined })
                }}
                placeholder="Operations Team"
                aria-invalid={!!groupErrors.name}
                className={groupErrors.name ? "border-destructive focus-visible:ring-destructive" : ""}
              />
              {groupErrors.name && <p className="text-xs font-medium text-destructive">{groupErrors.name}</p>}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="group-description" className={groupErrors.description ? "text-destructive" : ""}>Description</Label>
              <Input
                id="group-description"
                value={groupForm.description || ""}
                onChange={e => {
                  setGroupForm({ ...groupForm, description: e.target.value })
                  if (groupErrors.description) setGroupErrors({ ...groupErrors, description: undefined })
                }}
                placeholder="Optional description"
                aria-invalid={!!groupErrors.description}
                className={groupErrors.description ? "border-destructive focus-visible:ring-destructive" : ""}
              />
              {groupErrors.description && <p className="text-xs font-medium text-destructive">{groupErrors.description}</p>}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setGroupModalOpen(false)}>Cancel</Button>
            <Button onClick={handleSaveGroup}>{groupForm.id ? "Update" : "Create"}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Are you absolutely sure?</AlertDialogTitle>
            <AlertDialogDescription>
              This will permanently delete the group <strong>{selectedGroup?.name}</strong> and remove all its contacts. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteGroup} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              Delete Group
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
