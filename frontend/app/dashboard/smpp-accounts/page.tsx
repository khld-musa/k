"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { isValidHost, isValidPort } from "@/lib/validators"
import { DataTable } from "@/components/data-table"
import { type ColumnDef } from "@tanstack/react-table"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import {
  MoreVertical, Plus, MessageSquare, Trash2, Edit2, Circle, Eye, EyeOff, Loader2,
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

interface SmppConfig {
  id: number
  name: string
  smppHost: string
  smppPort: number
  systemId: string
  password: string
  systemType: string
  sourceAddress: string
  isActive: boolean
}

const emptyForm: Omit<SmppConfig, "id"> = {
  name: "", smppHost: "", smppPort: 2775, systemId: "", password: "",
  systemType: "", sourceAddress: "", isActive: true,
}

export default function SmppAccountsPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [configs, setConfigs] = React.useState<SmppConfig[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [modalOpen, setModalOpen] = React.useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = React.useState(false)
  const [selectedAccount, setSelectedAccount] = React.useState<SmppConfig | null>(null)
  const [form, setForm] = React.useState<Partial<SmppConfig>>(emptyForm)
  const [showPassword, setShowPassword] = React.useState(false)
  type FormErrors = Partial<Record<"name" | "smppHost" | "smppPort" | "systemId" | "password" | "systemType" | "sourceAddress", string>>
  const [errors, setErrors] = React.useState<FormErrors>({})

  const openForm = (values: typeof form) => {
    setForm(values)
    setErrors({})
    setShowPassword(false)
    setModalOpen(true)
  }

  const updateField = (key: keyof typeof form, value: unknown) => {
    setForm(prev => ({ ...prev, [key]: value }) as typeof form)
    if (errors[key as keyof FormErrors]) setErrors(prev => ({ ...prev, [key]: undefined }))
  }

  // requirePassword: saving a new config, or testing (the test uses the typed password, not the stored one)
  const validateForm = (requirePassword: boolean) => {
    const newErrors: FormErrors = {}
    // SMPP 3.4 field limits: system_id 16, password 9, system_type 13, source_addr 21
    if (!form.name?.trim()) newErrors.name = "Name is required."
    else if (form.name.length > 255) newErrors.name = "Name must not exceed 255 characters."
    if (!form.smppHost?.trim()) newErrors.smppHost = "SMPP host is required."
    else if (!isValidHost(form.smppHost)) newErrors.smppHost = "Enter a valid hostname or IPv4 address."
    if (form.smppPort === undefined || !isValidPort(form.smppPort)) newErrors.smppPort = "Port must be 1-65535."
    if (!form.systemId?.trim()) newErrors.systemId = "System ID is required."
    else if (form.systemId.length > 16) newErrors.systemId = "System ID must not exceed 16 characters."
    if (requirePassword && (!form.password || form.password === "********")) newErrors.password = "Password is required."
    else if (form.password && form.password !== "********" && form.password.length > 9) newErrors.password = "SMPP password must not exceed 9 characters."
    if ((form.systemType?.length || 0) > 13) newErrors.systemType = "System type must not exceed 13 characters."
    if ((form.sourceAddress?.length || 0) > 21) newErrors.sourceAddress = "Source address must not exceed 21 characters."
    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }
  const [isTesting, setIsTesting] = React.useState(false)

  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.")
      router.push("/dashboard")
    }
  }, [user, router])

  const fetchConfigs = React.useCallback(async () => {
    try {
      const data = await apiClient("/smpp-configs")
      setConfigs(data)
    } catch (e: any) {
      toast.error("Failed to load SMPP configs: " + e.message)
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => { if (user?.role === "ADMIN") fetchConfigs() }, [user?.role, fetchConfigs])

  const handleSave = async () => {
    if (!validateForm(!form.id)) return
    try {
      if (form.id) {
        await apiClient("/smpp-configs", { method: "POST", body: JSON.stringify(form) })
        toast.success("SMPP account updated successfuly")
      } else {
        await apiClient("/smpp-configs", { method: "POST", body: JSON.stringify(form) })
        toast.success("SMPP account created successfully")
      }
      setModalOpen(false)
      setForm(emptyForm)
      fetchConfigs()
    } catch (e: any) {
      const fieldErrors = getFieldErrors(e)
      if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors as FormErrors)
      else toast.error("Failed to save: " + e.message)
    }
  }

  const handleTest = async () => {
    if (!validateForm(true)) return
    setIsTesting(true)
    try {
      await apiClient("/smpp-configs/test", { method: "POST", body: JSON.stringify(form) })
      toast.success("SMPP Bind Successful")
    } catch (e: any) {
      toast.error("SMPP Bind failed: " + e.message)
    } finally {
      setIsTesting(false)
    }
  }

  const handleDelete = async () => {
    if (!selectedAccount) return
    try {
      await apiClient(`/smpp-configs/${selectedAccount.id}`, { method: "DELETE" })
      toast.success("SMPP account deleted successfully")
      setDeleteDialogOpen(false)
      setSelectedAccount(null)
      fetchConfigs()
    } catch (e: any) {
      toast.error("Failed to delete account: " + e.message)
    }
  }

  const confirmDelete = (account: SmppConfig) => {
    setSelectedAccount(account)
    setDeleteDialogOpen(true)
  }

  const columns: ColumnDef<SmppConfig>[] = [
    {
      accessorKey: "name",
      header: "Account Name",
      cell: ({ row }) => (
        <div className="flex items-center gap-2 font-medium">
          <MessageSquare className="h-4 w-4 text-primary/70" />
          {row.getValue("name")}
        </div>
      ),
    },
    {
      accessorKey: "smppHost",
      header: "Host",
      cell: ({ row }) => <code className="bg-muted px-1.5 py-0.5 rounded text-xs font-mono">{row.getValue("smppHost")}</code>,
    },
    {
      accessorKey: "smppPort",
      header: "Port",
    },
    {
      accessorKey: "systemId",
      header: "System ID",
      cell: ({ row }) => <code className="bg-muted px-1.5 py-0.5 rounded text-xs font-mono">{row.getValue("systemId")}</code>,
    },
    {
      accessorKey: "sourceAddress",
      header: "Sender ID",
      cell: ({ row }) => row.getValue("sourceAddress") || "-",
    },
    {
      accessorKey: "isActive",
      header: "Status",
      cell: ({ row }) => (
        <Badge variant={row.getValue("isActive") ? "default" : "secondary"} className="gap-1">
          <Circle className={`h-2 w-2 fill-current ${row.getValue("isActive") ? "text-green-400" : "text-slate-400"}`} />
          {row.getValue("isActive") ? "Active" : "Inactive"}
        </Badge>
      ),
    },
    {
      id: "actions",
      cell: ({ row }) => (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="h-8 w-8"><MoreVertical className="h-4 w-4" /></Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-40">
            <DropdownMenuItem onClick={() => openForm(row.original)}>
              <Edit2 className="mr-2 h-4 w-4" /> Edit
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem className="text-destructive focus:text-destructive" onClick={() => confirmDelete(row.original)}>
              <Trash2 className="mr-2 h-4 w-4" /> Delete
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ),
    },
  ]

  return (
    <>
      <DashboardHeader breadcrumbs={[
        { label: "Dashboard", href: "/dashboard" },
        { label: "Management", href: "#" },
        { label: "SMPP Accounts" },
      ]} />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="flex items-center justify-between">
          <div className="grid gap-2">
            <h1 className="text-3xl font-bold tracking-tight">SMPP Accounts</h1>
            <p className="text-muted-foreground text-lg">Manage SMPP connections for SMS notifications.</p>
          </div>
          <Button onClick={() => openForm(emptyForm)}>
            <Plus className="mr-2 h-4 w-4" /> Add Account
          </Button>
        </div>

        <div className="flex-1">
          <DataTable 
            columns={columns} 
            data={configs} 
            title="SMPP Configurations"
            description="Active SMS gateway connections"
          />
        </div>
      </div>

      <Dialog open={modalOpen} onOpenChange={setModalOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{form.id ? "Edit SMPP Account" : "Add SMPP Account"}</DialogTitle>
            <DialogDescription>Configure SMPP connection details.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="cfg-name" className={errors.name ? "text-destructive" : ""}>Name</Label>
              <Input id="cfg-name" aria-invalid={!!errors.name} className={errors.name ? "border-destructive focus-visible:ring-destructive" : ""} value={form.name || ""} onChange={e => updateField("name", e.target.value)} placeholder="Primary SMPP" />
              {errors.name && <p className="text-xs font-medium text-destructive">{errors.name}</p>}
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div className="col-span-2 grid gap-2">
                <Label htmlFor="cfg-smppHost" className={errors.smppHost ? "text-destructive" : ""}>SMPP Host</Label>
                <Input id="cfg-smppHost" aria-invalid={!!errors.smppHost} className={errors.smppHost ? "border-destructive focus-visible:ring-destructive" : ""} value={form.smppHost || ""} onChange={e => updateField("smppHost", e.target.value)} placeholder="smpp.provider.com" />
                {errors.smppHost && <p className="text-xs font-medium text-destructive">{errors.smppHost}</p>}
              </div>
              <div className="grid gap-2">
                <Label htmlFor="cfg-smppPort" className={errors.smppPort ? "text-destructive" : ""}>Port</Label>
                <Input id="cfg-smppPort" type="number" min="1" max="65535" aria-invalid={!!errors.smppPort} className={errors.smppPort ? "border-destructive focus-visible:ring-destructive" : ""} value={form.smppPort ?? ""} onChange={e => updateField("smppPort", e.target.value === "" ? undefined : Number(e.target.value))} placeholder="2775" />
                {errors.smppPort && <p className="text-xs font-medium text-destructive">{errors.smppPort}</p>}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="grid gap-2">
                <Label htmlFor="cfg-systemId" className={errors.systemId ? "text-destructive" : ""}>System ID</Label>
                <Input id="cfg-systemId" aria-invalid={!!errors.systemId} className={errors.systemId ? "border-destructive focus-visible:ring-destructive" : ""} value={form.systemId || ""} onChange={e => updateField("systemId", e.target.value)} placeholder="system_id" />
                {errors.systemId && <p className="text-xs font-medium text-destructive">{errors.systemId}</p>}
              </div>
              <div className="grid gap-2">
                <Label htmlFor="cfg-password" className={errors.password ? "text-destructive" : ""}>Password</Label>
                <div className="relative">
                  <Input
                    id="cfg-password"
                    type={showPassword ? "text" : "password"}
                    value={form.password || ""}
                    onChange={e => updateField("password", e.target.value)}
                    placeholder={form.id ? "Leave blank to keep current" : "••••••••"}
                    aria-invalid={!!errors.password}
                    className={errors.password ? "border-destructive focus-visible:ring-destructive" : ""}
                  />
                  <Button
                    type="button" variant="ghost" size="icon"
                    className="absolute right-0 top-0 h-full px-3 hover:bg-transparent"
                    onClick={() => setShowPassword(!showPassword)}
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </Button>
                </div>
                {errors.password && <p className="text-xs font-medium text-destructive">{errors.password}</p>}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="grid gap-2">
                <Label htmlFor="cfg-systemType" className={errors.systemType ? "text-destructive" : ""}>System Type</Label>
                <Input id="cfg-systemType" aria-invalid={!!errors.systemType} className={errors.systemType ? "border-destructive focus-visible:ring-destructive" : ""} value={form.systemType || ""} onChange={e => updateField("systemType", e.target.value)} placeholder="Optional" />
                {errors.systemType && <p className="text-xs font-medium text-destructive">{errors.systemType}</p>}
              </div>
              <div className="grid gap-2">
                <Label htmlFor="cfg-sourceAddress" className={errors.sourceAddress ? "text-destructive" : ""}>Source Address</Label>
                <Input id="cfg-sourceAddress" aria-invalid={!!errors.sourceAddress} className={errors.sourceAddress ? "border-destructive focus-visible:ring-destructive" : ""} value={form.sourceAddress || ""} onChange={e => updateField("sourceAddress", e.target.value)} placeholder="Sender ID" />
                {errors.sourceAddress && <p className="text-xs font-medium text-destructive">{errors.sourceAddress}</p>}
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Switch checked={form.isActive ?? true} onCheckedChange={(v: boolean) => setForm({ ...form, isActive: v })} />
              <Label>Active</Label>
            </div>
          </div>
          <DialogFooter className="justify-between sm:justify-between items-center">
            <Button variant="ghost" disabled={isTesting} onClick={handleTest}>
              {isTesting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
              Test Connection
            </Button>
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setModalOpen(false)}>Cancel</Button>
              <Button onClick={handleSave}>{form.id ? "Update" : "Create"}</Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Are you absolutely sure?</AlertDialogTitle>
            <AlertDialogDescription>
              This will permanently delete the SMPP account <strong>{selectedAccount?.name}</strong>. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              Delete Account
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
