"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { isIntInRange } from "@/lib/validators"
import { DataTable } from "@/components/data-table"
import { type ColumnDef } from "@tanstack/react-table"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import {
  MoreVertical, Plus, Bell, Trash2, Edit2, Circle, Eye, EyeOff, Check, ChevronsUpDown, RefreshCw, Activity,
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
import {
  Popover, PopoverContent, PopoverTrigger,
} from "@/components/ui/popover"
import { Checkbox } from "@/components/ui/checkbox"
import { toast } from "sonner"
import { useAuth } from "@/lib/auth-context"
import { useRouter } from "next/navigation"

interface ContactGroup {
  id: number
  name: string
}

interface AlarmConfig {
  id: number
  username: string
  clientId: string
  apiKey: string
  minimumThreshold: number
  checkIntervalMinutes: number
  cooldownMinutes: number
  isActive: boolean
  subscribedGroupIds: number[]
}

const emptyForm: Omit<AlarmConfig, "id"> = {
  username: "", clientId: "", apiKey: "", minimumThreshold: 0,
  checkIntervalMinutes: 30, cooldownMinutes: 360, isActive: true, subscribedGroupIds: [],
}

export default function BalanceAlarmsConfigPage() {
  const { user } = useAuth()
  const router = useRouter()
  const [configs, setConfigs] = React.useState<AlarmConfig[]>([])
  const [groups, setGroups] = React.useState<ContactGroup[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [modalOpen, setModalOpen] = React.useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = React.useState(false)
  const [selectedConfig, setSelectedConfig] = React.useState<AlarmConfig | null>(null)
  const [form, setForm] = React.useState<Partial<AlarmConfig>>(emptyForm)
  const [showApiKey, setShowApiKey] = React.useState(false)
  const [isSyncing, setIsSyncing] = React.useState(false)
  type FormErrors = Partial<Record<"username" | "clientId" | "apiKey" | "minimumThreshold" | "checkIntervalMinutes" | "cooldownMinutes", string>>
  const [errors, setErrors] = React.useState<FormErrors>({})

  const openForm = (values: Partial<AlarmConfig>) => {
    setForm(values)
    setErrors({})
    setShowApiKey(false)
    setModalOpen(true)
  }

  const updateField = <K extends keyof AlarmConfig>(key: K, value: AlarmConfig[K] | undefined) => {
    setForm(prev => ({ ...prev, [key]: value }))
    if (errors[key as keyof FormErrors]) setErrors(prev => ({ ...prev, [key]: undefined }))
  }

  const parseNumber = (raw: string) => (raw.trim() === "" ? undefined : Number(raw))


  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.")
      router.push("/dashboard")
    }
  }, [user, router])

  const fetchData = React.useCallback(async () => {
    try {
      const [alarmsData, groupsData] = await Promise.all([
        apiClient("/alarms"),
        apiClient("/groups"),
      ])
      setConfigs(alarmsData)
      setGroups(groupsData)
    } catch (e: any) {
      toast.error("Failed to load alarm data: " + e.message)
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => { if (user?.role === "ADMIN") fetchData() }, [user?.role, fetchData])

  const handleSave = async () => {
    // Mirrors backend AlarmConfigDto constraints
    const newErrors: FormErrors = {}
    if (!form.username?.trim()) newErrors.username = "Username / label is required."
    else if (form.username.length > 255) newErrors.username = "Must not exceed 255 characters."
    if (!form.clientId?.trim()) newErrors.clientId = "Client ID is required."
    else if (form.clientId.length > 255) newErrors.clientId = "Must not exceed 255 characters."
    // API key is only required for new configurations
    if (!form.id && !form.apiKey?.trim()) newErrors.apiKey = "API Key is required."
    if (form.minimumThreshold === undefined || isNaN(form.minimumThreshold)) newErrors.minimumThreshold = "Minimum threshold is required."
    else if (form.minimumThreshold < 0) newErrors.minimumThreshold = "Must be 0 or greater."
    if (!isIntInRange(form.checkIntervalMinutes ?? "", 1, 1440)) newErrors.checkIntervalMinutes = "Whole number between 1 and 1440."
    if (!isIntInRange(form.cooldownMinutes ?? "", 1, 1440)) newErrors.cooldownMinutes = "Whole number between 1 and 1440."
    setErrors(newErrors)
    if (Object.keys(newErrors).length > 0) return

    try {
      await apiClient("/alarms", { method: "POST", body: JSON.stringify(form) })
      toast.success(form.id ? "Alarm updated successfully" : "Alarm created successfully")
      setModalOpen(false)
      setForm(emptyForm)
      fetchData()
    } catch (e: any) {
      const fieldErrors = getFieldErrors(e)
      if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors as FormErrors)
      else toast.error("Failed to save: " + e.message)
    }
  }

  const handleDelete = async () => {
    if (!selectedConfig) return
    try {
      await apiClient(`/alarms/${selectedConfig.id}`, { method: "DELETE" })
      toast.success("Alarm configuration deleted successfully")
      setDeleteDialogOpen(false)
      setSelectedConfig(null)
      fetchData()
    } catch (e: any) {
      toast.error("Failed to delete alarm: " + e.message)
    }
  }

  const confirmDelete = (config: AlarmConfig) => {
    setSelectedConfig(config)
    setDeleteDialogOpen(true)
  }

  const handleResetCooldown = async (id: number) => {
    try {
      await apiClient(`/alarms/${id}/reset-cooldown`, { method: "POST" })
      toast.success("Cooldown reset. Immediate re-trigger enabled.")
      fetchData()
    } catch (e: any) {
      toast.error("Reset failed: " + e.message)
    }
  }

  const handleSync = async () => {
    setIsSyncing(true)
    try {
      await apiClient("/alarms/trigger-sync", { method: "POST" })
      toast.success("Balance check triggered successfully")
    } catch (e: any) {
      toast.error("Sync failed: " + e.message)
    } finally {
      setIsSyncing(false)
    }
  }

  const toggleGroupSelection = (groupId: number) => {
    const current = form.subscribedGroupIds || []
    if (current.includes(groupId)) {
      setForm({ ...form, subscribedGroupIds: current.filter(id => id !== groupId) })
    } else {
      setForm({ ...form, subscribedGroupIds: [...current, groupId] })
    }
  }

  const columns: ColumnDef<AlarmConfig>[] = [
    {
      accessorKey: "username",
      header: "Username",
      cell: ({ row }) => (
        <div className="flex items-center gap-2 font-medium">
          <Bell className="h-4 w-4 text-primary/70" />
          {row.getValue("username") || "-"}
        </div>
      ),
    },
    {
      accessorKey: "clientId",
      header: "Client ID",
      cell: ({ row }) => <code className="bg-muted px-1.5 py-0.5 rounded text-xs font-mono">{row.getValue("clientId")}</code>,
    },
    // [FIX P1-9] API Key column removed — sensitive credential should never be displayed in the UI
    {
      accessorKey: "minimumThreshold",
      header: "Threshold",
      cell: ({ row }) => <span className="font-semibold tabular-nums">{(row.getValue("minimumThreshold") as number)?.toLocaleString()}</span>,
    },
    {
      accessorKey: "checkIntervalMinutes",
      header: "Check Interval",
      cell: ({ row }) => {
        const mins = row.getValue("checkIntervalMinutes") as number
        if (!mins) return <span className="text-muted-foreground text-xs">30 min</span>
        if (mins >= 60 && mins % 60 === 0) return <span className="tabular-nums text-sm">{mins / 60}h</span>
        if (mins >= 60) return <span className="tabular-nums text-sm">{Math.floor(mins / 60)}h {mins % 60}m</span>
        return <span className="tabular-nums text-sm">{mins} min</span>
      },
    },
    {
      accessorKey: "cooldownMinutes",
      header: "Alert Cooldown",
      cell: ({ row }) => {
        const mins = row.getValue("cooldownMinutes") as number
        if (!mins) return <span className="text-muted-foreground text-xs">6h</span>
        if (mins >= 60 && mins % 60 === 0) return <span className="tabular-nums text-sm">{mins / 60}h</span>
        if (mins >= 60) return <span className="tabular-nums text-sm">{Math.floor(mins / 60)}h {mins % 60}m</span>
        return <span className="tabular-nums text-sm">{mins} min</span>
      },
    },
    {
      accessorKey: "subscribedGroupIds",
      header: "Subscribed Groups",
      cell: ({ row }) => (
        <div className="flex flex-wrap gap-1">
          {(row.getValue("subscribedGroupIds") as number[])?.length > 0
            ? (row.getValue("subscribedGroupIds") as number[]).map(gid => (
              <Badge key={gid} variant="outline" className="text-xs">{groups.find(g => g.id === gid)?.name || `#${gid}`}</Badge>
            ))
            : <span className="text-muted-foreground text-xs">None</span>
          }
        </div>
      ),
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
            <DropdownMenuItem onClick={() => openForm({ ...row.original })}>
              <Edit2 className="mr-2 h-4 w-4" /> Edit
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => handleResetCooldown(row.original.id)}>
              <RefreshCw className="mr-2 h-4 w-4" /> Reset Cooldown
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
        { label: "Balance Alarms", href: "#" },
        { label: "Configurations" },
      ]} />
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="flex items-center justify-between">
          <div className="grid gap-2">
            <h1 className="text-3xl font-bold tracking-tight">Balance Alarms</h1>
            <p className="text-muted-foreground text-lg">Configure Sesme API integrations to monitor credit balances.</p>
          </div>
          <div className="flex items-center gap-3">
            <Button variant="outline" asChild>
              <a href="/dashboard/balance-alarms/records">
                <Activity className="mr-2 h-4 w-4" />
                View Records
              </a>
            </Button>
            <Button variant="outline" onClick={handleSync} disabled={isSyncing}>
              <RefreshCw className={`mr-2 h-4 w-4 ${isSyncing ? "animate-spin" : ""}`} />
              {isSyncing ? "Syncing..." : "Sync Now"}
            </Button>
            <Button onClick={() => openForm(emptyForm)}>
              <Plus className="mr-2 h-4 w-4" /> Add Alarm
            </Button>
          </div>
        </div>

        <div className="flex-1">
          <DataTable 
            columns={columns} 
            data={configs} 
            title="Alarm Configurations"
            description="Active balance monitoring rules"
          />
        </div>
      </div>

      <Dialog open={modalOpen} onOpenChange={setModalOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{form.id ? "Edit Balance Alarm" : "Add Balance Alarm"}</DialogTitle>
            <DialogDescription>Configure Sesme API credentials and alert threshold.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="alarm-username" className={errors.username ? "text-destructive" : ""}>Username / Label</Label>
              <Input id="alarm-username" aria-invalid={!!errors.username} className={errors.username ? "border-destructive focus-visible:ring-destructive" : ""} value={form.username || ""} onChange={e => updateField("username", e.target.value)} placeholder="Sesme Account Name" />
              {errors.username && <p className="text-xs font-medium text-destructive">{errors.username}</p>}
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="grid gap-2">
                <Label htmlFor="alarm-client-id" className={errors.clientId ? "text-destructive" : ""}>Client ID</Label>
                <Input id="alarm-client-id" aria-invalid={!!errors.clientId} className={errors.clientId ? "border-destructive focus-visible:ring-destructive" : ""} value={form.clientId || ""} onChange={e => updateField("clientId", e.target.value)} placeholder="client_id" />
                {errors.clientId && <p className="text-xs font-medium text-destructive">{errors.clientId}</p>}
              </div>
              <div className="grid gap-2">
                <Label htmlFor="alarm-api-key" className={errors.apiKey ? "text-destructive" : ""}>API Key</Label>
                <div className="relative">
                  <Input
                    id="alarm-api-key"
                    type={showApiKey ? "text" : "password"}
                    value={form.apiKey || ""}
                    onChange={e => updateField("apiKey", e.target.value)}
                    placeholder="••••••••"
                    aria-invalid={!!errors.apiKey}
                    className={errors.apiKey ? "border-destructive focus-visible:ring-destructive" : ""}
                  />
                  <Button
                    type="button" variant="ghost" size="icon"
                    className="absolute right-0 top-0 h-full px-3 hover:bg-transparent"
                    onClick={() => setShowApiKey(!showApiKey)}
                  >
                    {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </Button>
                </div>
                {errors.apiKey && <p className="text-xs font-medium text-destructive">{errors.apiKey}</p>}
              </div>
            </div>
            <div className="grid gap-2">
              <Label htmlFor="alarm-threshold" className={errors.minimumThreshold ? "text-destructive" : ""}>Minimum Threshold</Label>
              <Input id="alarm-threshold" type="number" min="0" step="0.01" aria-invalid={!!errors.minimumThreshold} className={errors.minimumThreshold ? "border-destructive focus-visible:ring-destructive" : ""} value={form.minimumThreshold ?? ""} onChange={e => updateField("minimumThreshold", parseNumber(e.target.value))} placeholder="50.00" />
              {errors.minimumThreshold && <p className="text-xs font-medium text-destructive">{errors.minimumThreshold}</p>}
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="grid gap-2">
                <Label htmlFor="alarm-interval" className={errors.checkIntervalMinutes ? "text-destructive" : ""}>Check Interval (minutes)</Label>
                <Input
                  id="alarm-interval"
                  type="number"
                  min="1"
                  max="1440"
                  step="1"
                  value={form.checkIntervalMinutes ?? ""}
                  onChange={e => updateField("checkIntervalMinutes", parseNumber(e.target.value))}
                  placeholder="30"
                  aria-invalid={!!errors.checkIntervalMinutes}
                  className={errors.checkIntervalMinutes ? "border-destructive focus-visible:ring-destructive" : ""}
                />
                {errors.checkIntervalMinutes && <p className="text-xs font-medium text-destructive">{errors.checkIntervalMinutes}</p>}
                <p className="text-xs text-muted-foreground">
                  Balance check frequency. Default: 30 min.
                </p>
              </div>
              <div className="grid gap-2">
                <Label htmlFor="alarm-cooldown" className={errors.cooldownMinutes ? "text-destructive" : ""}>Alert Cooldown (minutes)</Label>
                <Input
                  id="alarm-cooldown"
                  type="number"
                  min="1"
                  max="1440"
                  step="1"
                  value={form.cooldownMinutes ?? ""}
                  onChange={e => updateField("cooldownMinutes", parseNumber(e.target.value))}
                  placeholder="360"
                  aria-invalid={!!errors.cooldownMinutes}
                  className={errors.cooldownMinutes ? "border-destructive focus-visible:ring-destructive" : ""}
                />
                {errors.cooldownMinutes && <p className="text-xs font-medium text-destructive">{errors.cooldownMinutes}</p>}
                <p className="text-xs text-muted-foreground">
                  Suppress repeat alerts for this long. Default: 360 min (6h).
                </p>
              </div>
            </div>
            <div className="grid gap-2">
              <Label>Subscribed Contact Groups</Label>
              <Popover>
                <PopoverTrigger asChild>
                  <Button variant="outline" role="combobox" className="w-full justify-between font-normal">
                    {(form.subscribedGroupIds?.length || 0) > 0
                      ? `${form.subscribedGroupIds?.length} group(s) selected`
                      : "Select groups..."}
                    <ChevronsUpDown className="ml-2 h-3.5 w-3.5 shrink-0 opacity-50" />
                  </Button>
                </PopoverTrigger>
                <PopoverContent className="w-[300px] p-0" align="start">
                  <div className="p-2 space-y-1">
                    {groups.length === 0 ? (
                      <p className="p-3 text-center text-xs text-muted-foreground">No groups found. Create one first.</p>
                    ) : (
                      groups.map((g) => (
                        <div
                          key={g.id}
                          className="flex items-center space-x-2 p-2 hover:bg-accent rounded-md cursor-pointer"
                          onClick={() => toggleGroupSelection(g.id)}
                        >
                          <Checkbox checked={form.subscribedGroupIds?.includes(g.id)} onCheckedChange={() => toggleGroupSelection(g.id)} />
                          <span className="text-sm font-medium">{g.name}</span>
                        </div>
                      ))
                    )}
                  </div>
                </PopoverContent>
              </Popover>
            </div>
            <div className="flex items-center gap-2">
              <Switch checked={form.isActive ?? true} onCheckedChange={(v: boolean) => setForm({ ...form, isActive: v })} />
              <Label>Active</Label>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setModalOpen(false)}>Cancel</Button>
            <Button onClick={handleSave}>{form.id ? "Update" : "Create"}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Are you absolutely sure?</AlertDialogTitle>
            <AlertDialogDescription>
              This will permanently delete the balance alarm for <strong>{selectedConfig?.username}</strong>. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              Delete Alarm
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
