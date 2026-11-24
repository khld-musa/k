"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { isValidHost, isValidPort, isValidEmail } from "@/lib/validators"
import { DataTable } from "@/components/data-table"
import { type ColumnDef } from "@tanstack/react-table"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import {
  MoreVertical, Plus, Mail, Trash2, Edit2, Circle, Eye, EyeOff, Loader2,
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

interface EmailConfig {
  id: number
  name: string
  smtpHost: string
  smtpPort: number
  username: string
  password: string
  fromAddress: string
  useTls: boolean
  isActive: boolean
}

const emptyForm: Omit<EmailConfig, "id"> = {
  name: "", smtpHost: "", smtpPort: 587, username: "", password: "",
  fromAddress: "", useTls: true, isActive: true,
}

export default function MailServerPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [configs, setConfigs] = React.useState<EmailConfig[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [modalOpen, setModalOpen] = React.useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = React.useState(false)
  const [selectedServer, setSelectedServer] = React.useState<EmailConfig | null>(null)
  const [form, setForm] = React.useState<Partial<EmailConfig>>(emptyForm)
  const [showPassword, setShowPassword] = React.useState(false)
  type FormErrors = Partial<Record<"name" | "smtpHost" | "smtpPort" | "username" | "password" | "fromAddress", string>>
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
    if (!form.name?.trim()) newErrors.name = "Name is required."
    else if (form.name.length > 255) newErrors.name = "Name must not exceed 255 characters."
    if (!form.smtpHost?.trim()) newErrors.smtpHost = "SMTP host is required."
    else if (!isValidHost(form.smtpHost)) newErrors.smtpHost = "Enter a valid hostname or IPv4 address."
    if (form.smtpPort === undefined || !isValidPort(form.smtpPort)) newErrors.smtpPort = "Port must be 1-65535."
    if (!form.username?.trim()) newErrors.username = "Username is required."
    if (requirePassword && (!form.password || form.password === "********")) newErrors.password = "Password is required."
    if (!form.fromAddress?.trim()) newErrors.fromAddress = "From address is required."
    else if (!isValidEmail(form.fromAddress)) newErrors.fromAddress = "Enter a valid email address."
    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }
  const [isTesting, setIsLoadingTesting] = React.useState(false)

  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.")
      router.push("/dashboard")
    }
  }, [user, router])

  const fetchConfigs = React.useCallback(async () => {
    try {
      const data = await apiClient("/email-configs")
      setConfigs(data)
    } catch (e: any) {
      toast.error("Failed to load mail configs: " + e.message)
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => { if (user?.role === "ADMIN") fetchConfigs() }, [user?.role, fetchConfigs])

  const handleSave = async () => {
    if (!validateForm(!form.id)) return
    try {
      await apiClient("/email-configs", { method: "POST", body: JSON.stringify(form) })
      toast.success(form.id ? "Mail server updated successfully" : "Mail server created successfully")
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
    setIsLoadingTesting(true)
    try {
      await apiClient("/email-configs/test", { method: "POST", body: JSON.stringify(form) })
      toast.success("SMTP Connection Successful")
    } catch (e: any) {
      toast.error("SMTP Connection failed: " + e.message)
    } finally {
      setIsLoadingTesting(false)
    }
  }

  const handleDelete = async () => {
    if (!selectedServer) return
    try {
      await apiClient(`/email-configs/${selectedServer.id}`, { method: "DELETE" })
      toast.success("Mail server deleted successfully")
      setDeleteDialogOpen(false)
      setSelectedServer(null)
      fetchConfigs()
    } catch (e: any) {
      toast.error("Failed to delete server: " + e.message)
    }
  }

  const confirmDelete = (server: EmailConfig) => {
    setSelectedServer(server)
    setDeleteDialogOpen(true)
  }

  const columns: ColumnDef<EmailConfig>[] = [
    {
      accessorKey: "name",
      header: "Server Name",
      cell: ({ row }) => (
        <div className="flex items-center gap-2 font-medium">
          <Mail className="h-4 w-4 text-primary/70" />
          {row.getValue("name")}
        </div>
      ),
    },
    {
      accessorKey: "smtpHost",
      header: "SMTP Host",
      cell: ({ row }) => <code className="bg-muted px-1.5 py-0.5 rounded text-xs font-mono">{row.getValue("smtpHost")}</code>,
    },
    {
      accessorKey: "smtpPort",
      header: "Port",
    },
    {
      accessorKey: "username",
      header: "Username",
    },
    {
      accessorKey: "fromAddress",
      header: "From Address",
    },
    {
      accessorKey: "useTls",
      header: "TLS",
      cell: ({ row }) => row.getValue("useTls") ? <Badge className="bg-green-500">Yes</Badge> : <Badge variant="secondary">No</Badge>,
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
        { label: "Mail Server" },
      ]} />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="flex items-center justify-between">
          <div className="grid gap-2">
            <h1 className="text-3xl font-bold tracking-tight">Mail Server</h1>
            <p className="text-muted-foreground text-lg">Configure SMTP servers for email notifications.</p>
          </div>
          <Button onClick={() => openForm(emptyForm)}>
            <Plus className="mr-2 h-4 w-4" /> Add Server
          </Button>
        </div>

        <div className="flex-1">
          <DataTable 
            columns={columns} 
            data={configs} 
            title="Email Server Configurations"
            description="Active SMTP relay connections"
          />
        </div>
      </div>

      <Dialog open={modalOpen} onOpenChange={setModalOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{form.id ? "Edit Mail Server" : "Add Mail Server"}</DialogTitle>
            <DialogDescription>Configure SMTP connection details.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="cfg-name" className={errors.name ? "text-destructive" : ""}>Name</Label>
              <Input id="cfg-name" aria-invalid={!!errors.name} className={errors.name ? "border-destructive focus-visible:ring-destructive" : ""} value={form.name || ""} onChange={e => updateField("name", e.target.value)} placeholder="Primary SMTP" />
              {errors.name && <p className="text-xs font-medium text-destructive">{errors.name}</p>}
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div className="col-span-2 grid gap-2">
                <Label htmlFor="cfg-smtpHost" className={errors.smtpHost ? "text-destructive" : ""}>SMTP Host</Label>
                <Input id="cfg-smtpHost" aria-invalid={!!errors.smtpHost} className={errors.smtpHost ? "border-destructive focus-visible:ring-destructive" : ""} value={form.smtpHost || ""} onChange={e => updateField("smtpHost", e.target.value)} placeholder="smtp.example.com" />
                {errors.smtpHost && <p className="text-xs font-medium text-destructive">{errors.smtpHost}</p>}
              </div>
              <div className="grid gap-2">
                <Label htmlFor="cfg-smtpPort" className={errors.smtpPort ? "text-destructive" : ""}>Port</Label>
                <Input id="cfg-smtpPort" type="number" min="1" max="65535" aria-invalid={!!errors.smtpPort} className={errors.smtpPort ? "border-destructive focus-visible:ring-destructive" : ""} value={form.smtpPort ?? ""} onChange={e => updateField("smtpPort", e.target.value === "" ? undefined : Number(e.target.value))} placeholder="587" />
                {errors.smtpPort && <p className="text-xs font-medium text-destructive">{errors.smtpPort}</p>}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="grid gap-2">
                <Label htmlFor="cfg-username" className={errors.username ? "text-destructive" : ""}>Username</Label>
                <Input id="cfg-username" aria-invalid={!!errors.username} className={errors.username ? "border-destructive focus-visible:ring-destructive" : ""} value={form.username || ""} onChange={e => updateField("username", e.target.value)} placeholder="user@example.com" />
                {errors.username && <p className="text-xs font-medium text-destructive">{errors.username}</p>}
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
            <div className="grid gap-2">
              <Label htmlFor="cfg-fromAddress" className={errors.fromAddress ? "text-destructive" : ""}>From Address</Label>
              <Input id="cfg-fromAddress" aria-invalid={!!errors.fromAddress} className={errors.fromAddress ? "border-destructive focus-visible:ring-destructive" : ""} value={form.fromAddress || ""} onChange={e => updateField("fromAddress", e.target.value)} placeholder="alerts@example.com" />
              {errors.fromAddress && <p className="text-xs font-medium text-destructive">{errors.fromAddress}</p>}
            </div>
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2">
                <Switch checked={form.useTls ?? true} onCheckedChange={(v: boolean) => setForm({ ...form, useTls: v })} />
                <Label>Use TLS</Label>
              </div>
              <div className="flex items-center gap-2">
                <Switch checked={form.isActive ?? true} onCheckedChange={(v: boolean) => setForm({ ...form, isActive: v })} />
                <Label>Active</Label>
              </div>
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
              This will permanently delete the mail server <strong>{selectedServer?.name}</strong>. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              Delete Server
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
