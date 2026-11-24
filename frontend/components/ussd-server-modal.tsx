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
import { Checkbox } from "@/components/ui/checkbox"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { isValidIp, isSafePath, isValidSshUsername } from "@/lib/validators"
import { toast } from "sonner"
import { Server } from "lucide-react"

interface UssdServer {
    id?: number
    name: string
    ipAddress: string
    description: string
    logPath: string
    localLogPath?: string
    active: boolean
    sshUsername?: string
    sshPassword?: string
    ftpUsername?: string
    ftpPassword?: string
    ftpRemotePath?: string
}

interface UssdServerModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess: () => void
  server?: UssdServer | null // If provided, we are in edit mode
}

export function UssdServerModal({ open, onOpenChange, onSuccess, server }: UssdServerModalProps) {
  const [name, setName] = useState("")
  const [ipAddress, setIpAddress] = useState("")
  const [description, setDescription] = useState("")
  const [logPath, setLogPath] = useState("/home/khld/ussd/logs")
  const [localLogPath, setLocalLogPath] = useState("/var/log/ussd")
  const [active, setActive] = useState(true)
  const [sshUsername, setSshUsername] = useState("")
  const [sshPassword, setSshPassword] = useState("")
  const [ftpUsername, setFtpUsername] = useState("")
  const [ftpPassword, setFtpPassword] = useState("")
  const [ftpRemotePath, setFtpRemotePath] = useState("/mediation")

  const [errors, setErrors] = useState<{
    name?: string
    ipAddress?: string
    logPath?: string
    localLogPath?: string
    sshUsername?: string
    ftpUsername?: string
    ftpRemotePath?: string
    general?: string
  }>({})

  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    if (server) {
      setName(server.name)
      setIpAddress(server.ipAddress)
      setDescription(server.description || "")
      setLogPath(server.logPath || "/home/khld/ussd/logs")
      setLocalLogPath(server.localLogPath || "/var/log/ussd")
      setActive(server.active)
      setSshUsername(server.sshUsername || "")
      setSshPassword("") // Don't pre-fill password from server (for security and since it's not sent back)
      setFtpUsername(server.ftpUsername || "")
      setFtpPassword("")
      setFtpRemotePath(server.ftpRemotePath || "/mediation")
      setErrors({})
    } else {
      resetForm()
    }
  }, [server, open])

  const resetForm = () => {
    setName("")
    setIpAddress("")
    setDescription("")
    setLogPath("/home/khld/ussd/logs")
    setLocalLogPath("/var/log/ussd")
    setActive(true)
    setSshUsername("")
    setSshPassword("")
    setFtpUsername("")
    setFtpPassword("")
    setFtpRemotePath("/mediation")
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

    if (!localLogPath.trim()) {
      newErrors.localLogPath = "Local log path is required."
      isValid = false
    } else if (!isSafePath(localLogPath)) {
      newErrors.localLogPath = "Path may only contain letters, digits and + _ . / -"
      isValid = false
    }

    if (sshUsername.trim() && !isValidSshUsername(sshUsername)) {
      newErrors.sshUsername = "Only letters, digits, dot, underscore and hyphen are allowed."
      isValid = false
    }

    if (ftpUsername.trim() && !isValidSshUsername(ftpUsername)) {
      newErrors.ftpUsername = "Only letters, digits, dot, underscore and hyphen are allowed."
      isValid = false
    }

    if (ftpRemotePath.trim() && !isSafePath(ftpRemotePath)) {
      newErrors.ftpRemotePath = "Path may only contain letters, digits and + _ . / -"
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
      const url = server ? `/servers/${server.id}` : `/servers`
      
      await apiClient(url, {
        method,
        body: JSON.stringify({
          name, ipAddress, description, logPath, localLogPath, active,
          sshUsername, sshPassword,
          ftpUsername, ftpPassword, ftpRemotePath,
        }),
      })
      
      toast.success(server ? "Server updated successfully." : "Server created successfully.")
      onOpenChange(false)
      onSuccess()
    } catch (error: any) {
      const fieldErrors = getFieldErrors(error)
      if (Object.keys(fieldErrors).length > 0) {
        setErrors({
          name: fieldErrors.name, ipAddress: fieldErrors.ipAddress, logPath: fieldErrors.logPath,
          localLogPath: fieldErrors.localLogPath,
          sshUsername: fieldErrors.sshUsername, ftpUsername: fieldErrors.ftpUsername, ftpRemotePath: fieldErrors.ftpRemotePath,
        })
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
            <DialogTitle>{server ? "Edit Server" : "Add New USSD Server"}</DialogTitle>
          </div>
          <DialogDescription>
            {server ? "Modify the server details below." : "Enter the details of the new USSD node to be added to the platform."}
          </DialogDescription>
        </DialogHeader>

        {errors.general && (
          <div className="p-3 bg-destructive/10 text-destructive text-sm rounded-md border border-destructive/20">
            {errors.general}
          </div>
        )}

        <form noValidate onSubmit={handleSave} className="space-y-4 py-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="name">Server Name</Label>
            <Input
              id="name"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
                if (errors.name) setErrors({ ...errors, name: undefined })
              }}
              placeholder="e.g. Node 1"
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
              placeholder="10.177.x.x"
              className={errors.ipAddress ? "border-destructive focus-visible:ring-destructive" : ""}
            />
            {errors.ipAddress && (
              <p className="text-xs font-medium text-destructive">{errors.ipAddress}</p>
            )}
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
              placeholder="/home/khld/ussd/logs"
              className={`font-mono text-xs ${errors.logPath ? "border-destructive focus-visible:ring-destructive" : ""}`}
            />
            {errors.logPath && (
              <p className="text-xs font-medium text-destructive">{errors.logPath}</p>
            )}
            <p className="text-[10px] text-muted-foreground">Remote directory on this server used for live SSH log search and saved traces.</p>
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
                placeholder={server ? "Leave blank to keep current" : "Optional"}
                className="text-xs"
              />
            </div>
            <p className="col-span-2 text-[10px] text-muted-foreground">
              Optional: Provide these to enable <strong>Instant Background Reports</strong> and <strong>Saved Traces</strong>.
            </p>
          </div>

          <div className="grid grid-cols-2 gap-4 border-t pt-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="ftpUsername">FTP Username</Label>
              <Input
                id="ftpUsername"
                name="ftp-username"
                autoComplete="off"
                data-lpignore="true"
                data-1p-ignore
                value={ftpUsername}
                onChange={(e) => {
                  setFtpUsername(e.target.value)
                  if (errors.ftpUsername) setErrors({ ...errors, ftpUsername: undefined })
                }}
                placeholder="e.g. ussdftp"
                className={`text-xs ${errors.ftpUsername ? "border-destructive focus-visible:ring-destructive" : ""}`}
              />
              {errors.ftpUsername && (
                <p className="text-xs font-medium text-destructive">{errors.ftpUsername}</p>
              )}
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="ftpPassword">FTP Password</Label>
              <Input
                id="ftpPassword"
                name="ftp-password"
                autoComplete="new-password"
                data-lpignore="true"
                data-1p-ignore
                type="password"
                value={ftpPassword}
                onChange={(e) => setFtpPassword(e.target.value)}
                placeholder={server ? "Leave blank to keep current" : "Optional"}
                className="text-xs"
              />
            </div>
            <div className="col-span-2 flex flex-col gap-2">
              <Label htmlFor="ftpRemotePath">FTP Remote Log Path</Label>
              <Input
                id="ftpRemotePath"
                value={ftpRemotePath}
                onChange={(e) => {
                  setFtpRemotePath(e.target.value)
                  if (errors.ftpRemotePath) setErrors({ ...errors, ftpRemotePath: undefined })
                }}
                placeholder="/mediation"
                className={`font-mono text-xs ${errors.ftpRemotePath ? "border-destructive focus-visible:ring-destructive" : ""}`}
              />
              {errors.ftpRemotePath && (
                <p className="text-xs font-medium text-destructive">{errors.ftpRemotePath}</p>
              )}
              <p className="text-[10px] text-muted-foreground">Directory on the remote FTP server that holds the daily log file.</p>
            </div>
            <div className="col-span-2 flex flex-col gap-2">
              <Label htmlFor="localLogPath">Local Log Path</Label>
              <Input
                id="localLogPath"
                value={localLogPath}
                onChange={(e) => {
                  setLocalLogPath(e.target.value)
                  if (errors.localLogPath) setErrors({ ...errors, localLogPath: undefined })
                }}
                placeholder="/var/log/ussd"
                className={`font-mono text-xs ${errors.localLogPath ? "border-destructive focus-visible:ring-destructive" : ""}`}
              />
              {errors.localLogPath && (
                <p className="text-xs font-medium text-destructive">{errors.localLogPath}</p>
              )}
              <p className="text-[10px] text-muted-foreground">
                Local directory on this backend host where fetched files are stored, under a <code>{"{name}"}</code> subfolder.
                Must be writable by the backend process (e.g. under <code>/var/log/ussd</code>).
              </p>
            </div>
            <p className="col-span-2 text-[10px] text-muted-foreground">
              Optional: Provide these to enable the nightly <strong>FTP log fetch</strong> job, which pulls this
              server&apos;s daily log file in and deletes it from the remote FTP server afterwards.
            </p>
          </div>


          <div className="flex items-center justify-between space-x-2 border-t pt-4 mt-2">
            <div className="flex flex-col gap-1">
                <Label htmlFor="active-mode">Active Status</Label>
                <p className="text-[10px] text-muted-foreground">Inactive servers won't appear in trace tools.</p>
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
