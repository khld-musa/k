"use client"

import * as React from "react"
import { useState, useEffect } from "react"
import { format } from "date-fns"
import {
  Calendar as CalendarIcon,
  Search,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Loader2,
  Settings2,
  Radio,
  ChevronUp,
  ChevronDown,
  Filter,
} from "lucide-react"
import { toast } from "sonner"

import { cn } from "@/lib/utils"
import { DashboardHeader } from "@/components/dashboard-header"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { useAuth } from "@/lib/auth-context"
import { apiClient } from "@/lib/api-client"
import { isValidMsisdn, isValidSshUsername } from "@/lib/validators"

import { McnTraceResults } from "@/components/mcn-trace-results"
interface McnCheckResponse {
  sipChecked: boolean
  sipRecordsFound: boolean
  sipMessage: string
  sipRecords: any[]
  notifierChecked: boolean
  notifierRecordsFound: boolean
  notifierMessage: string
  notifierRecords: any[]
}

export default function McnCheckerPage() {
  useAuth()

  const [msisdn, setMsisdn] = useState("")
  const [date, setDate] = useState<Date>(new Date())
  const [isLoading, setIsLoading] = useState(false)

  // SSH credentials (auto-filled from database or falls back to user input)
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [usingStoredPassword, setUsingStoredPassword] = useState(false)

  // Fetch active MCN servers to auto-fill credentials
  useEffect(() => {
    const fetchMcnServers = async () => {
      try {
        const data = await apiClient("/mcn-servers")
        const activeServers = (data as any[]).filter(s => s.active)
        
        if (activeServers.length > 0) {
          const serverWithUsername = activeServers.find(s => s.sshUsername)
          if (serverWithUsername) {
            setUsername(serverWithUsername.sshUsername)
          } else {
            setUsername("root")
          }

          const anyHasPassword = activeServers.some(s => s.hasPasswordStored)
          if (anyHasPassword) {
            setUsingStoredPassword(true)
          }
        } else {
          setUsername("root")
        }
      } catch (err) {
        console.error("Failed to load MCN servers for credentials check", err)
        setUsername("root")
      }
    }
    fetchMcnServers()
  }, [])

  // Stepper Visual States
  const [step1State, setStep1State] = useState<"idle" | "running" | "success" | "failed">("idle")
  const [step2State, setStep2State] = useState<"idle" | "running" | "success" | "failed" | "skipped">("idle")

  const [response, setResponse] = useState<McnCheckResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [configExpanded, setConfigExpanded] = useState(true)
  const [fieldErrors, setFieldErrors] = useState<{ msisdn?: string; username?: string }>({})



  const handleCheck = async () => {
    const errors: typeof fieldErrors = {}
    if (!msisdn.trim()) {
      errors.msisdn = "MSISDN is required."
    } else if (!isValidMsisdn(msisdn)) {
      errors.msisdn = "Invalid MSISDN format (7-15 digits, optional + prefix)."
    }
    if (!usingStoredPassword) {
      if (!username.trim()) errors.username = "SSH username is required."
      else if (!isValidSshUsername(username)) errors.username = "Only letters, digits, dot, underscore and hyphen are allowed."
    }
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) return

    setIsLoading(true)
    setError(null)
    setResponse(null)
    setConfigExpanded(false)
    
    // Reset Stepper
    setStep1State("running")
    setStep2State("idle")

    try {
      const formattedDate = format(date, "yyyy-MM-dd")

      const data = await apiClient("/logs/mcn/check", {
        method: "POST",
        body: JSON.stringify({
          msisdn: msisdn.trim(),
          date: formattedDate,
          username,
          password: usingStoredPassword ? undefined : password,
          useStoredCredentials: usingStoredPassword,
        }),
      })

      const res = data as McnCheckResponse

      // Step 1 resolution
      if (res.sipRecordsFound) {
        setStep1State("success")
        setStep2State("running")

        if (res.notifierRecordsFound) {
          setStep2State("success")
          toast.success("MCN Logs successfully traced to Notifier dispatches!")
        } else {
          setStep2State("failed")
          if (res.notifierMessage && (res.notifierMessage.includes("Connection Errors") || res.notifierMessage.includes("Exception") || res.notifierMessage.includes("Auth fail"))) {
            toast.warning("Could not complete Notifier scan due to a connection issue.")
            res.notifierMessage = "Failed to scan Notifier nodes due to connection issues or invalid credentials."
          } else {
            toast.warning("MCN Trigger present, but SMS dispatch not found.")
          }
        }
      } else {
        setStep1State("failed")
        setStep2State("skipped")
        if (res.sipMessage && (res.sipMessage.includes("Connection Errors") || res.sipMessage.includes("Exception") || res.sipMessage.includes("Auth fail"))) {
          toast.error("Unable to communicate with the SIP cluster. Please check server status.")
          res.sipMessage = "Failed to scan SIP nodes due to connection issues or invalid credentials."
        } else {
          toast.error("No trace found on SIP cluster.")
        }
      }

      setResponse(res)
    } catch (err: any) {
      setStep1State("failed")
      setStep2State("skipped")
      setError("An unexpected system error occurred while verifying the MCN logs. Please check your credentials and ensure the target nodes are reachable.")
      toast.error("MCN trace encountered a system error.")
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "MCN Service", href: "#" },
          { label: "MCN Log Checker" }
        ]}
      />
      <div className="flex-1 space-y-6 p-4 pt-6 md:p-8">
        <div>
          <h2 className="text-3xl font-bold tracking-tight">MCN Log Checker</h2>
          <p className="text-muted-foreground">
            Step-by-step trace of Missed Call Notification (MCN) logs across SIP and Notifier clusters.
          </p>
        </div>

        <div className="space-y-4">
          {/* Collapsible Config Bar */}
          <Card className="overflow-hidden">
            {/* Collapsed summary bar */}
            <div 
              className="flex items-center justify-between p-4 cursor-pointer hover:bg-muted/50 transition-colors"
              onClick={() => setConfigExpanded(!configExpanded)}
            >
              <div className="flex items-center gap-3">
                <div className="h-8 w-8 rounded-lg bg-green-600/10 flex items-center justify-center">
                  <Settings2 className="h-4 w-4 text-green-600" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">Trace Configuration</h3>
                  {!configExpanded && (
                    <p className="text-xs text-muted-foreground mt-0.5">
                      {msisdn || "No MSISDN"} · {date ? format(date, "MMM d, yyyy") : "No date"}
                    </p>
                  )}
                </div>
              </div>
              <div className="flex items-center gap-2">
                {!configExpanded && (
                  <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); setConfigExpanded(true) }}>
                    <Filter className="mr-2 h-3.5 w-3.5" /> Reconfigure
                  </Button>
                )}
                {configExpanded ? <ChevronUp className="h-4 w-4 text-muted-foreground" /> : <ChevronDown className="h-4 w-4 text-muted-foreground" />}
              </div>
            </div>

            {/* Expanded config form */}
            {configExpanded && (
              <div className="border-t px-4 pb-4 pt-4 space-y-4">
                <div className="grid gap-4 md:grid-cols-4">
                  
                  {/* Target MSISDN */}
                  <div className="space-y-1.5 md:col-span-1 border-b md:border-b-0 md:border-r border-border pb-4 md:pb-0 md:pr-4">
                    <Label htmlFor="msisdn" className={cn("text-xs", fieldErrors.msisdn && "text-destructive")}>Target MSISDN</Label>
                    <Input
                      id="msisdn"
                      placeholder="e.g. 249123456789"
                      value={msisdn}
                      onChange={(e) => {
                        setMsisdn(e.target.value)
                        if (fieldErrors.msisdn) setFieldErrors({ ...fieldErrors, msisdn: undefined })
                      }}
                      disabled={isLoading}
                      aria-invalid={!!fieldErrors.msisdn}
                      className={cn("h-9 text-sm", fieldErrors.msisdn && "border-destructive focus-visible:ring-destructive")}
                    />
                    {fieldErrors.msisdn && <p className="text-xs font-medium text-destructive">{fieldErrors.msisdn}</p>}
                  </div>

                  {/* Search Date */}
                  <div className="space-y-1.5 md:col-span-1 border-b md:border-b-0 md:border-r border-border pb-4 md:pb-0 md:pr-4">
                    <Label className="text-xs">Search Date</Label>
                    <Popover>
                      <PopoverTrigger asChild disabled={isLoading}>
                        <Button
                          variant={"outline"}
                          className={cn(
                            "w-full justify-start text-left font-normal h-9 text-sm",
                            !date && "text-muted-foreground"
                          )}
                        >
                          <CalendarIcon className="mr-2 h-4 w-4" />
                          {date ? format(date, "PPP") : <span>Pick a date</span>}
                        </Button>
                      </PopoverTrigger>
                      <PopoverContent className="w-auto p-0">
                        <Calendar
                          mode="single"
                          selected={date}
                          onSelect={(d) => d && setDate(d)}
                          disabled={(d) => d > new Date()}
                          initialFocus
                        />
                      </PopoverContent>
                    </Popover>
                  </div>

                  {/* SSH Credentials block taking up 2 cols */}
                  <div className="md:col-span-2 space-y-4 md:space-y-0 md:flex md:gap-4 md:pl-0">
                    <div className="space-y-1.5 flex-1">
                      <Label htmlFor="username" className={cn("text-xs", fieldErrors.username && "text-destructive")}>SSH Username</Label>
                      <Input
                        id="username"
                        name="ssh-username"
                        autoComplete="off"
                        data-lpignore="true"
                        data-1p-ignore
                        value={username}
                        onChange={(e) => {
                          setUsername(e.target.value)
                          if (fieldErrors.username) setFieldErrors({ ...fieldErrors, username: undefined })
                        }}
                        placeholder="root"
                        aria-invalid={!!fieldErrors.username}
                        className={cn("h-9 text-sm", fieldErrors.username && "border-destructive focus-visible:ring-destructive")}
                        disabled={usingStoredPassword || isLoading}
                      />
                      {fieldErrors.username && <p className="text-xs font-medium text-destructive">{fieldErrors.username}</p>}
                      <div className="flex items-center space-x-2 pt-2">
                        <Checkbox
                          id="useStoredPassword"
                          checked={usingStoredPassword}
                          onCheckedChange={(checked: boolean) => {
                            setUsingStoredPassword(checked)
                            if (checked) {
                              setPassword("")
                              setFieldErrors({ ...fieldErrors, username: undefined })
                            }
                          }}
                        />
                        <label
                          htmlFor="useStoredPassword"
                          className="text-[10px] font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70"
                        >
                          Use credentials stored on server profiles
                        </label>
                      </div>
                    </div>

                    {!usingStoredPassword && (
                      <div className="space-y-1.5 flex-1">
                        <Label htmlFor="password" className="text-xs">SSH Password</Label>
                        <Input
                          id="password"
                          name="ssh-password"
                          autoComplete="new-password"
                          data-lpignore="true"
                          data-1p-ignore
                          type="password"
                          value={password}
                          onChange={(e) => setPassword(e.target.value)}
                          placeholder="••••••••"
                          className="h-9 text-sm"
                          disabled={isLoading}
                        />
                        <p className="text-[9px] text-muted-foreground">Leave blank to use credentials stored on nodes.</p>
                      </div>
                    )}
                  </div>
                </div>

                <Button
                  className="w-full mt-2 bg-green-600 hover:bg-green-700 cursor-pointer transition-transform shadow-md"
                  onClick={handleCheck}
                  disabled={isLoading}
                >
                  {isLoading ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Traced Checking...
                    </>
                  ) : (
                    <>
                      <Search className="mr-2 h-4 w-4" />
                      Trace MCN Logs
                    </>
                  )}
                </Button>
              </div>
            )}
          </Card>

          {/* Stepper & Trace Output Panel */}
          <div className="space-y-6">
            {/* Visual Stepper */}
            <Card>
              <CardHeader className="pb-3">
                <CardTitle>Trace Pipeline</CardTitle>
                <CardDescription>Sequential scan status across clusters.</CardDescription>
              </CardHeader>
              <CardContent className="grid gap-6">
                {/* Step 1 */}
                <div className="flex items-start gap-4">
                  <div className="flex h-8 w-8 items-center justify-center rounded-full border border-primary/20 bg-primary/5 text-primary shrink-0 font-bold text-sm">
                    1
                  </div>
                  <div className="flex-1 space-y-1">
                    <div className="flex items-center justify-between">
                      <h4 className="text-sm font-semibold">SIP Server Log Check (SIP1 / SIP2)</h4>
                      <div>
                        {step1State === "idle" && <Badge variant="secondary">Pending</Badge>}
                        {step1State === "running" && (
                          <Badge variant="outline" className="animate-pulse text-blue-500 border-blue-500/30">
                            <Loader2 className="mr-1 h-3 w-3 animate-spin inline-block" /> Scanning Nodes
                          </Badge>
                        )}
                        {step1State === "success" && <Badge className="bg-green-500 hover:bg-green-600">Records Found</Badge>}
                        {step1State === "failed" && <Badge variant="destructive">No Records</Badge>}
                      </div>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      Scans incoming SIP triggers for the specified MSISDN to ensure registration on SIP nodes.
                    </p>
                    {response?.sipChecked && (
                      <div className={cn(
                        "p-2.5 rounded text-xs font-medium border mt-2",
                        response.sipRecordsFound ? "bg-green-500/5 border-green-500/20 text-green-600" : "bg-destructive/5 border-destructive/20 text-destructive"
                      )}>
                        {response.sipMessage}
                      </div>
                    )}
                  </div>
                </div>

                {/* Separator line */}
                <div className="h-[1px] bg-border ml-12" />

                {/* Step 2 */}
                <div className="flex items-start gap-4">
                  <div className="flex h-8 w-8 items-center justify-center rounded-full border border-primary/20 bg-primary/5 text-primary shrink-0 font-bold text-sm">
                    2
                  </div>
                  <div className="flex-1 space-y-1">
                    <div className="flex items-center justify-between">
                      <h4 className="text-sm font-semibold">Notifier Server Log Check (Notifier1 / Notifier2)</h4>
                      <div>
                        {step2State === "idle" && <Badge variant="secondary">Pending</Badge>}
                        {step2State === "running" && (
                          <Badge variant="outline" className="animate-pulse text-blue-500 border-blue-500/30">
                            <Loader2 className="mr-1 h-3 w-3 animate-spin inline-block" /> Scanning Nodes
                          </Badge>
                        )}
                        {step2State === "success" && <Badge className="bg-green-500 hover:bg-green-600">Records Found</Badge>}
                        {step2State === "failed" && <Badge variant="destructive">No Records</Badge>}
                        {step2State === "skipped" && <Badge variant="secondary" className="opacity-60">Aborted</Badge>}
                      </div>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      Traces SMS notification queue, template rendering, and delivery status to the SMSC.
                    </p>
                    {response?.notifierChecked && (
                      <div className={cn(
                        "p-2.5 rounded text-xs font-medium border mt-2",
                        response.notifierRecordsFound ? "bg-green-500/5 border-green-500/20 text-green-600" : "bg-amber-500/5 border-amber-500/20 text-amber-600"
                      )}>
                        {response.notifierMessage}
                      </div>
                    )}
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* No Search Made State */}
            {!response && !isLoading && !error && (
              <div className="flex h-[320px] items-center justify-center rounded-xl border border-dashed bg-muted/10">
                <div className="text-center p-6 space-y-3">
                  <div className="mx-auto h-12 w-12 bg-green-500/10 rounded-full flex items-center justify-center text-green-600">
                    <Radio className="h-6 w-6 animate-pulse" />
                  </div>
                  <h3 className="font-bold text-lg">System Idle</h3>
                  <p className="text-sm text-muted-foreground max-w-sm">
                    Enter an MSISDN on the left sidebar parameters panel and run the log trace to begin checking.
                  </p>
                </div>
              </div>
            )}

            {/* Error State */}
            {error && (
              <div className="bg-destructive/10 text-destructive border border-destructive/20 rounded-lg p-4 flex gap-3">
                <XCircle className="h-5 w-5 shrink-0 mt-0.5" />
                <div className="space-y-1">
                  <h4 className="font-semibold text-sm">Trace Verification Failed</h4>
                  <p className="text-xs">{error}</p>
                </div>
              </div>
            )}

            {/* Response Visualization */}
            {response && !error && (
              <McnTraceResults response={response} msisdn={msisdn} />
            )}
          </div>
        </div>
      </div>
    </>
  )
}
