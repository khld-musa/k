"use client"

import React, { useState, useEffect } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Checkbox } from "@/components/ui/checkbox"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { isValidIp, isSafePath, isValidSshUsername } from "@/lib/validators"
import { toast } from "sonner"
import { Server } from "lucide-react"

export interface McnServer {
  id?: number
  name: string
  ipAddress: string
  description: string
  logPath: string
  serverType: "SIP" | "NOTIFIER"
  active: boolean
  sshUsername?: string
  sshPassword?: string
  hasPasswordStored?: boolean
  createdAt?: string
  updatedAt?: string
}

interface McnServerModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess: () => void
  server?: McnServer | null
}

export function McnServerModal({ open, onOpenChange, onSuccess, server }: McnServerModalProps) {
  const [name, setName] = useState("")
  const [ipAddress, setIpAddress] = useState("")
  const [description, setDescription] = useState("")
  const [logPath, setLogPath] = useState("")
  const [serverType, setServerType] = useState<"SIP" | "NOTIFIER">("SIP")
  const [active, setActive] = useState(true)
  const [sshUsername, setSshUsername] = useState("")
  const [sshPassword, setSshPassword] = useState("")
  
  const [errors, setErrors] = useState<{
    name?: string
    ipAddress?: string
    logPath?: string
    sshUsername?: string
    general?: string
  }>({})

  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    if (server) {
      setName(server.name)
      setIpAddress(server.ipAddress)
      setDescription(server.description || "")
      setLogPath(server.logPath || "")
      setServerType(server.serverType || "SIP")
      setActive(server.active)
      setSshUsername(server.sshUsername || "")
      setSshPassword("")
      setErrors({})
    } else {
      resetForm()
    }
  }, [server, open])

  const resetForm = () => {
    setName("")
    setIpAddress("")
    setDescription("")
    setLogPath("")
    setServerType("SIP")
    setActive(true)
    setSshUsername("")
    setSshPassword("")
    setErrors({})
  }

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen && !isSaving) {
      onOpenChange(false)
    }
    if (newOpen) {
      onOpenChange(true)
    }
  }

  const validateForm = () => {
    const newErrors: typeof errors = {}
    let isValid = true

    if (!name.trim()) {
      newErrors.name = "Server name is required."
      isValid = false
    }

    if (!ipAddress.trim()) {
      newErrors.ipAddress = "IP Address is required."
      isValid = false
    } else if (!isValidIp(ipAddress)) {
      newErrors.ipAddress = "Please enter a valid IPv4 address (e.g. 10.177.1.20)."
      isValid = false
    }

    if (!logPath.trim()) {
      newErrors.logPath = "Log file path is required."
      isValid = false
    } else if (!isSafePath(logPath)) {
      newErrors.logPath = "Path may only contain letters, digits and + _ . / -"
      isValid = false
    }

    if (sshUsername.trim() && !isValidSshUsername(sshUsername)) {
      newErrors.sshUsername = "Only letters, digits, dot, underscore and hyphen are allowed."
      isValid = false
    }

    setErrors(newErrors)
    return isValid
  }

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    
    if (!validateForm()) return

    setIsSaving(true)
    try {
      const method = server ? "PUT" : "POST"
      const url = server ? `/mcn-servers/${server.id}` : `/mcn-servers`
      
      await apiClient(url, {
        method,
        body: JSON.stringify({ name, ipAddress, description, logPath, serverType, active, sshUsername, sshPassword }),
      })
      
      toast.success(server ? "Server updated successfully." : "Server created successfully.")
      onOpenChange(false)
      onSuccess()
    } catch (error: any) {
      const fieldErrors = getFieldErrors(error)
      if (Object.keys(fieldErrors).length > 0) {
        setErrors({ name: fieldErrors.name, ipAddress: fieldErrors.ipAddress, logPath: fieldErrors.logPath, sshUsername: fieldErrors.sshUsername, general: fieldErrors.serverType })
      } else {
        setErrors({ general: error.message || "Failed to save server." })
      }
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent 
        className="sm:max-w-[425px]"
        onInteractOutside={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Server className="h-5 w-5 text-primary" />
            <DialogTitle>{server ? "Edit MCN Server" : "Add New MCN Server"}</DialogTitle>
          </div>
          <DialogDescription>
            {server ? "Modify the MCN server details below." : "Enter the details of the new MCN node (SIP or NOTIFIER) to be added."}
          </DialogDescription>
        </DialogHeader>

        {errors.general && (
          <div className="p-3 bg-destructive/10 text-destructive text-sm rounded-md border border-destructive/20">
            {errors.general}
          </div>
        )}

        <form noValidate onSubmit={handleSave} className="space-y-4 py-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="name">Server Name</Label>
            <Input
              id="name"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
                if (errors.name) setErrors({ ...errors, name: undefined })
              }}
              placeholder="e.g. SIP1"
              className={errors.name ? "border-destructive focus-visible:ring-destructive" : ""}
            />
            {errors.name && (
              <p className="text-xs font-medium text-destructive">{errors.name}</p>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="ipAddress">IP Address</Label>
            <Input
              id="ipAddress"
              value={ipAddress}
              onChange={(e) => {
                setIpAddress(e.target.value)
                if (errors.ipAddress) setErrors({ ...errors, ipAddress: undefined })
              }}
              placeholder="10.10.x.x"
              className={errors.ipAddress ? "border-destructive focus-visible:ring-destructive" : ""}
            />
            {errors.ipAddress && (
              <p className="text-xs font-medium text-destructive">{errors.ipAddress}</p>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="serverType">Server Type</Label>
            <Select 
              value={serverType} 
              onValueChange={(val: "SIP" | "NOTIFIER") => setServerType(val)}
            >
              <SelectTrigger id="serverType">
                <SelectValue placeholder="Select type" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="SIP">SIP Server</SelectItem>
                <SelectItem value="NOTIFIER">NOTIFIER Server</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="description">Description (Optional)</Label>
            <Input
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Physical location or role"
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="logPath">Log File Path</Label>
            <Input
              id="logPath"
              value={logPath}
              onChange={(e) => {
                setLogPath(e.target.value)
                if (errors.logPath) setErrors({ ...errors, logPath: undefined })
              }}
              placeholder="/var/log/mcn"
              className={`font-mono text-xs ${errors.logPath ? "border-destructive focus-visible:ring-destructive" : ""}`}
            />
            {errors.logPath && (
              <p className="text-xs font-medium text-destructive">{errors.logPath}</p>
            )}
            <p className="text-[10px] text-muted-foreground">Base directory where MCN logs are stored on this server.</p>
          </div>

          <div className="grid grid-cols-2 gap-4 border-t pt-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="sshUsername">SSH Username</Label>
              <Input
                id="sshUsername"
                name="ssh-username"
                autoComplete="off"
                data-lpignore="true"
                data-1p-ignore
                value={sshUsername}
                onChange={(e) => {
                  setSshUsername(e.target.value)
                  if (errors.sshUsername) setErrors({ ...errors, sshUsername: undefined })
                }}
                placeholder="e.g. root"
                className={`text-xs ${errors.sshUsername ? "border-destructive focus-visible:ring-destructive" : ""}`}
              />
              {errors.sshUsername && (
                <p className="text-xs font-medium text-destructive">{errors.sshUsername}</p>
              )}
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="sshPassword">SSH Password</Label>
              <Input
                id="sshPassword"
                name="ssh-password"
                autoComplete="new-password"
                data-lpignore="true"
                data-1p-ignore
                type="password"
                value={sshPassword}
                onChange={(e) => setSshPassword(e.target.value)}
                placeholder={server?.hasPasswordStored ? "•••••••• (unchanged if left blank)" : "Optional"}
                className="text-xs"
              />
            </div>
          </div>

          <div className="flex items-center justify-between space-x-2 border-t pt-4 mt-2">
            <div className="flex flex-col gap-1">
                <Label htmlFor="active-mode">Active Status</Label>
                <p className="text-[10px] text-muted-foreground">Inactive servers won't be scanned during log tracing.</p>
            </div>
            <Checkbox
              id="active-mode"
              checked={active}
              onCheckedChange={(checked) => setActive(checked === true)}
            />
          </div>

          <DialogFooter className="pt-4 flex gap-2 sm:justify-end">
            <Button 
              type="button" 
              variant="outline" 
              onClick={() => handleOpenChange(false)}
              disabled={isSaving}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={isSaving} className="gap-2">
              {isSaving ? "Saving..." : "Save Server"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
