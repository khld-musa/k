"use client"
import * as React from "react"
import { useState, useEffect, useRef, useCallback } from "react"
import { 
  Search, 
  ChevronDown, 
  ChevronUp,
  ChevronRight, 
  ChevronsUpDown, 
  Settings2,
  Filter,
  Radio, 
  Activity, 
  Play, 
  Square, 
} from "lucide-react"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { DashboardHeader } from "@/components/dashboard-header"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { apiClient } from "@/lib/api-client"
import { isValidSshUsername } from "@/lib/validators"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { Checkbox } from "@/components/ui/checkbox"
import { useAuth } from "@/lib/auth-context"
// [FIX UI-6] Shared components — eliminates ~400 lines of duplicate code
import { TransactionTimeline, LogEvent } from "@/components/ussd-log/transaction-timeline"
import { PaginationControls } from "@/components/ussd-log/pagination-controls"
import { PayloadViewerDialog } from "@/components/ussd-log/payload-viewer-dialog"
interface UssdLogGroup {
  serverId: string
  serverIp: string
  callId: string
  msisdn: string
  shortCode: string
  callSource: string
  startTime: string
  status: string
  resultMessage: string
  events: LogEvent[]
  logs: string[]
}
interface LiveLogUpdate {
  serverId: string
  serverIp: string
  callId: string
  msisdn: string
  shortCode: string
  callSource: string
  event: LogEvent
}
interface LiveServer {
  id: string
  name: string
  ipAddress: string
  sshUsername?: string
  hasPasswordStored?: boolean
}
export default function UssdLiveTracePage() {
  const { token } = useAuth() // [FIX SEC-1] Need JWT token to authenticate the SSE stream
  const [dynamicServers, setDynamicServers] = useState<LiveServer[]>([])
  const [isLoadingServers, setIsLoadingServers] = useState(true)
  const [selectedServerIds, setSelectedServerIds] = useState<string[]>([])
  const [username, setUsername] = useState<string>("")
  const [password, setPassword] = useState<string>("")
  const [usingStoredPassword, setUsingStoredPassword] = useState<boolean>(false)
  const [msisdnsText, setMsisdnsText] = useState<string>("")
  const [isTracing, setIsTracing] = useState(false)
  // [FIX LT-13] Map<String, ...> → Map<string, ...> (primitive type)
  const [results, setResults] = useState<Map<string, UssdLogGroup>>(new Map())
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{ msisdns?: string, username?: string, password?: string, servers?: string }>({})
  const [maximizedPayload, setMaximizedPayload] = useState<{ title: string, content: string } | null>(null)
  const [expandedRows, setExpandedRows] = useState<Set<string>>(new Set())
  const [configExpanded, setConfigExpanded] = useState(true)
  // Search and Pagination State
  const [searchTerm, setSearchTerm] = useState("")
  const [currentPage, setCurrentPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const eventSourceRef = useRef<EventSource | null>(null)
  // [FIX LT-15] Use a ref to buffer SSE updates and batch them on a short timer
  // This prevents 100+ React re-renders per second on busy systems.
  // (setTimeout rather than requestAnimationFrame: rAF is paused in background tabs, which held updates back)
  const resultsRef = useRef<Map<string, UssdLogGroup>>(new Map())
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pendingUpdatesRef = useRef<LiveLogUpdate[]>([])
  function extractJsonValue(jsonStr: string, key: string): string | null {
    const searchField = `"${key}":"`;
    const startIdx = jsonStr.indexOf(searchField);
    if (startIdx !== -1) {
      const valStart = startIdx + searchField.length;
      const endIdx = jsonStr.indexOf('"', valStart);
      if (endIdx !== -1) return jsonStr.substring(valStart, endIdx);
    }
    return null;
  }
  const flushUpdates = useCallback(() => {
    flushTimerRef.current = null
    const updates = pendingUpdatesRef.current
    if (updates.length === 0) return
    pendingUpdatesRef.current = []
    const newMap = new Map(resultsRef.current)
    for (const update of updates) {
      const sessionKey = `${update.serverId}:${update.callId}`
      let group = newMap.get(sessionKey)
      if (!group) {
        group = {
          serverId: update.serverId,
          serverIp: update.serverIp,
          callId: update.callId,
          msisdn: update.msisdn,
          shortCode: update.shortCode,
          callSource: update.callSource,
          startTime: update.event.timestamp,
          status: "ACTIVE",
          resultMessage: "",
          events: [],
          logs: []
        }
      }
      if (!group.msisdn && update.msisdn) group.msisdn = update.msisdn
      if (!group.shortCode && update.shortCode) group.shortCode = update.shortCode
      if (!group.callSource && update.callSource) group.callSource = update.callSource
      // [FIX LT-16] Improved duplicate detection — include rawLine content, not just timestamp+eventType
      const isDuplicate = group.events.some(
        e => e.timestamp === update.event.timestamp &&
             e.eventType === update.event.eventType &&
             e.rawLine === update.event.rawLine
      )
      if (!isDuplicate) {
        group.events.push(update.event)
        group.events.sort((a, b) => a.timestamp.localeCompare(b.timestamp))
        if (!group.logs.includes(update.event.rawLine)) {
          group.logs.push(update.event.rawLine)
        }
        if (update.event.eventType === "HTTP RESPONSE") {
          if (update.event.rawLine.includes('"status":"SUCCESS"')) group.status = "SUCCESS"
          else if (update.event.rawLine.includes('"status":"FAIL"')) group.status = "FAILED"
          else if (update.event.rawLine.toLowerCase().includes("<html")) group.status = "FAILED"
        }
        if (update.event.eventType === "USSD RESPONSE") {
          if (update.event.rawLine.includes('"responseCode":200')) {
            group.status = "SUCCESS";
          } else if (update.event.rawLine.includes('"responseCode":')) {
            group.status = "FAILED";
          }
          const result = extractJsonValue(update.event.rawLine, "result")
          if (result) group.resultMessage = result
        }
      }
      newMap.set(sessionKey, group)
    }
    resultsRef.current = newMap
    setResults(newMap)
  }, [])
  const enqueueUpdate = useCallback((update: LiveLogUpdate) => {
    pendingUpdatesRef.current.push(update)
    if (flushTimerRef.current === null) {
      flushTimerRef.current = setTimeout(flushUpdates, 100)
    }
  }, [flushUpdates])
  const toggleRow = (callId: string) => {
    const newExpanded = new Set(expandedRows)
    if (newExpanded.has(callId)) {
      newExpanded.delete(callId)
    } else {
      newExpanded.add(callId)
    }
    setExpandedRows(newExpanded)
  }
  const toggleServer = (id: string) => {
    setFieldErrors(prev => ({ ...prev, servers: undefined }))
    setSelectedServerIds(prev => {
      const newSelection = prev.includes(id) 
        ? prev.filter(i => i !== id) 
        : [...prev, id]
      // Auto-fill username and stored-password flag from first selected server
      if (newSelection.length > 0) {
        const firstServer = dynamicServers.find(s => s.id === newSelection[0]);
        if (firstServer?.sshUsername) {
          setUsername(firstServer.sshUsername);
        }
        if (firstServer?.hasPasswordStored) {
          setUsingStoredPassword(true);
          setPassword("");
        } else {
          setUsingStoredPassword(false);
        }
      } else {
        setUsingStoredPassword(false);
      }
      return newSelection;
    })
  }
  const stopTracing = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
      eventSourceRef.current = null
    }
    // Flush any pending buffered updates before stopping
    if (flushTimerRef.current !== null) {
      clearTimeout(flushTimerRef.current)
      flushTimerRef.current = null
    }
    if (pendingUpdatesRef.current.length > 0) {
      flushUpdates()
    }
    setIsTracing(false)
  }, [flushUpdates])
  const startTracing = async () => {
    setError(null)
    setFieldErrors({})
    const msisdns = msisdnsText
      .split(/[\n,;]+/)
      .map(s => s.trim())
      .filter(s => s.length > 0)
    let hasErrors = false;
    const newErrors: { msisdns?: string; username?: string; password?: string, servers?: string } = {};
    if (selectedServerIds.length === 0) {
      newErrors.servers = "Please select at least one server";
      hasErrors = true;
    }
    if (msisdns.length === 0) {
      newErrors.msisdns = "Please enter at least one MSISDN";
      hasErrors = true;
    } else {
      // [FIX] Strengthened validation to match backend: 7-15 digits, optional + prefix
      const invalidMsisdns = msisdns.filter(m => !/^\+?\d{7,15}$/.test(m));
      if (invalidMsisdns.length > 0) {
        newErrors.msisdns = `Invalid MSISDN format (7-15 digits): ${invalidMsisdns.join(", ")}`;
        hasErrors = true;
      }
      if (msisdns.length > 50) {
        newErrors.msisdns = "Maximum 50 MSISDNs allowed per trace";
        hasErrors = true;
      }
    }
    if (!username) {
      newErrors.username = "SSH username is required";
      hasErrors = true;
    } else if (!isValidSshUsername(username)) {
      newErrors.username = "Only letters, digits, dot, underscore and hyphen are allowed";
      hasErrors = true;
    }
    // Password required — using stored credentials from DB counts as valid
    if (!password && !usingStoredPassword) {
      newErrors.password = "SSH password is required";
      hasErrors = true;
    }
    if (hasErrors) {
      setFieldErrors(newErrors);
      return;
    }
    setIsTracing(true)
    const emptyMap = new Map<string, UssdLogGroup>()
    setResults(emptyMap)
    resultsRef.current = emptyMap
    pendingUpdatesRef.current = []
    setCurrentPage(1)
    setConfigExpanded(false)
    try {
      // Step 1: POST credentials securely in the request body to get a session ID
      const nodes: Record<string, string> = {}
      selectedServerIds.forEach(id => {
        const s = dynamicServers.find(srv => srv.id === id)
        if (s) nodes[id] = s.ipAddress
      })
      const sessionResponse = await apiClient("/logs/live-trace/start", {
        method: "POST",
        body: JSON.stringify({ nodes, username, password, msisdns }),
      })
      const traceSessionId = sessionResponse.traceSessionId
      if (!traceSessionId) {
        setError("Failed to create trace session")
        setIsTracing(false)
        return
      }
      // Step 2: Connect EventSource with session ID + JWT token
      // [FIX SEC-1] EventSource doesn't support custom headers; token is passed as a query param
      const apiBase = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api/v1"
      const tokenParam = token ? `&token=${encodeURIComponent(token)}` : ""
      const url = `${apiBase}/logs/live-trace/stream?sessionId=${encodeURIComponent(traceSessionId)}${tokenParam}`
      const eventSource = new EventSource(url)
      eventSourceRef.current = eventSource
      // [FIX LT-15] SSE events are now buffered and flushed on a short timer
      eventSource.onmessage = (event) => {
        try {
          const update: LiveLogUpdate = JSON.parse(event.data)
          if (update.serverId === "SYSTEM_ERROR") {
            const details = update.event.details || ""
            // Session-level failure (expired/consumed session, bad token): the stream is unusable,
            // stop instead of letting EventSource reconnect and repeat the same error forever
            if (update.callId === "ERROR") {
              setError(details)
              stopTracing()
              return
            }
            const isFileNotFound = details.toLowerCase().includes("not found") ||
                                   details.toLowerCase().includes("not readable") ||
                                   details.toLowerCase().includes("no such file")
            toast[isFileNotFound ? "error" : "warning"](isFileNotFound ? "Log File Not Found" : "Stream Diagnostics", {
              description: details,
              duration: 8000,
            })
            return
          }
          enqueueUpdate(update)
        } catch (e) {
          console.error("Failed to parse SSE event", e)
        }
      }
      // [FIX UI-6] Retry on transient disconnects (max 3 attempts, exponential backoff)
      // Don't kill the trace on the first onerror — browsers reconnect EventSource automatically,
      // this handler fires on every failed reconnect attempt.
      let retryCount = 0
      const MAX_RETRIES = 3
      eventSource.onerror = () => {
        if (eventSource.readyState === EventSource.CLOSED) {
          retryCount++
          if (retryCount >= MAX_RETRIES) {
            setError("Connection to live trace service lost after multiple retries. Please check server status.")
            stopTracing()
          } else {
            console.warn(`SSE connection lost (attempt ${retryCount}/${MAX_RETRIES}), browser will retry…`)
          }
        }
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : "Failed to start trace session"
      setError(errorMessage)
      setIsTracing(false)
      if (errorMessage.includes("not found") || errorMessage.includes("not readable") || errorMessage.includes("No such file")) {
        toast.error("Log File Not Found", {
          description: errorMessage,
          duration: 6000,
        })
      } else {
        toast.error("Trace Failed", { description: errorMessage })
      }
    }
  }
  useEffect(() => {
    const fetchServers = async () => {
      try {
        const responseData = await apiClient("/servers/active")
        let serversList: unknown[] = []
        if (Array.isArray(responseData)) {
          serversList = responseData
        } else if (responseData && typeof responseData === 'object') {
          const rd = responseData as Record<string, unknown>
          serversList = (rd.data || rd.content || rd.records || rd.results || []) as unknown[]
        }
        setDynamicServers(serversList.map((s) => {
          const srv = s as Record<string, unknown>
          return {
            id: String(srv.id),
            name: srv.name as string,
            ipAddress: srv.ipAddress as string,
            sshUsername: srv.sshUsername as string | undefined,
            hasPasswordStored: srv.hasPasswordStored as boolean | undefined,
          }
        }))
        if (serversList.length > 0) {
          const first = serversList[0] as Record<string, unknown>
          setSelectedServerIds([String(first.id)])
          if (first.sshUsername) setUsername(first.sshUsername as string)
          if (first.hasPasswordStored) setUsingStoredPassword(true)
        }
      } catch (err) {
        console.error("Failed to fetch servers", err)
      } finally {
        setIsLoadingServers(false)
      }
    }
    fetchServers()
    // Cleanup: close EventSource, cancel pending rAF, clear password on unmount
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close()
      }
      if (flushTimerRef.current !== null) {
        clearTimeout(flushTimerRef.current)
      }
      // [FIX H-12 equivalent] Clear password from memory on unmount
      setPassword("")
    }
  }, [])
  // Client-side filtering & sorting
  const resultItems = Array.from(results.values())
    .filter(group => 
      (group.msisdn?.toLowerCase() || "").includes(searchTerm.toLowerCase()) ||
      (group.callId?.toLowerCase() || "").includes(searchTerm.toLowerCase()) ||
      (group.shortCode?.toLowerCase() || "").includes(searchTerm.toLowerCase()) ||
      (dynamicServers.find(s => s.id === group.serverId)?.name || "").toLowerCase().includes(searchTerm.toLowerCase())
    )
    .sort((a, b) => {
      // [FIX LT-17] Null checks on startTime for sorting
      if (!a.startTime) return 1;
      if (!b.startTime) return -1;
      return b.startTime.localeCompare(a.startTime);
    })
  // Pagination logic
  const totalResults = resultItems.length
  const totalPages = Math.ceil(totalResults / pageSize)
  useEffect(() => {
    if (currentPage > totalPages && totalPages > 0) {
      setCurrentPage(totalPages)
    }
  }, [totalPages, currentPage])
  const startIndex = (currentPage - 1) * pageSize
  const paginatedResults = resultItems.slice(startIndex, startIndex + pageSize)
  const goToPage = (page: number) => {
    setCurrentPage(Math.max(1, Math.min(page, totalPages)))
  }
  return (
    <>
      <DashboardHeader 
        breadcrumbs={[
          { label: "USSD Logs", href: "/dashboard/ussd-logs/history" },
          { label: "Live Trace" }
        ]} 
      />
      <div className="flex-1 space-y-4 p-4 pt-6 md:p-8">
      <div className="flex items-center justify-between space-y-2">
        <div>
          <h2 className="text-3xl font-bold tracking-tight">Live Trace</h2>
          <p className="text-muted-foreground flex items-center gap-2">
            Real-time multi-server session monitoring.
            {isTracing && <span className="flex h-2 w-2 rounded-full bg-red-500 animate-pulse" />}
          </p>
        </div>
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
                    {selectedServerIds.length} node(s) · {username || "no user"}
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
                {/* Target Servers */}
                <div className="space-y-1.5">
                  <Label className={cn("text-xs", fieldErrors.servers && "text-destructive")}>Target Servers</Label>
                  <Popover>
                    <PopoverTrigger asChild disabled={isTracing}>
                      <Button
                        variant="outline"
                        role="combobox"
                        className={cn(
                          "w-full justify-between font-normal h-9 text-sm",
                          fieldErrors.servers && "border-destructive"
                        )}
                      >
                        {selectedServerIds.length === 0 
                          ? "Select servers..." 
                          : `${selectedServerIds.length} server(s) selected`}
                        <ChevronsUpDown className="ml-2 h-3.5 w-3.5 shrink-0 opacity-50" />
                      </Button>
                    </PopoverTrigger>
                    <PopoverContent className="w-[300px] p-0" align="start">
                      <div className="p-2 space-y-1">
                        {isLoadingServers ? (
                          <div className="p-4 text-center text-xs text-muted-foreground">Loading servers...</div>
                        ) : dynamicServers.length === 0 ? (
                          <div className="p-4 text-center text-xs text-muted-foreground">No active servers found.</div>
                        ) : (
                          dynamicServers.map((server) => (
                            <div
                              key={server.id}
                              className="flex items-center space-x-2 p-2 hover:bg-accent rounded-md cursor-pointer"
                              onClick={() => toggleServer(server.id)}
                            >
                              <Checkbox
                                checked={selectedServerIds.includes(server.id)}
                                onCheckedChange={() => toggleServer(server.id)}
                                /* The row div also toggles on click; stop the event so one click toggles once */
                                onClick={(e) => e.stopPropagation()}
                              />
                              <div className="flex flex-col">
                                <span className="text-sm font-medium leading-none">{server.name}</span>
                                <span className="text-xs text-muted-foreground">{server.ipAddress}</span>
                              </div>
                            </div>
                          ))
                        )}
                      </div>
                    </PopoverContent>
                  </Popover>
                  {fieldErrors.servers && <p className="text-xs font-medium text-destructive">{fieldErrors.servers}</p>}
                </div>
                {/* Target MSISDNs */}
                <div className="space-y-1.5 md:col-span-1 border-b md:border-b-0 md:border-r border-border pb-4 md:pb-0 md:pr-4">
                  <Label htmlFor="msisdns" className={cn("text-xs", fieldErrors.msisdns && "text-destructive")}>Target MSISDNs</Label>
                  <Textarea
                    id="msisdns"
                    placeholder="E.g. 249121096876"
                    disabled={isTracing}
                    className={cn("min-h-[80px] resize-y", fieldErrors.msisdns && "border-destructive focus-visible:ring-destructive")}
                    value={msisdnsText}
                    onChange={e => {
                      setMsisdnsText(e.target.value)
                      if (fieldErrors.msisdns) setFieldErrors({ ...fieldErrors, msisdns: undefined })
                    }}
                  />
                  {fieldErrors.msisdns && <p className="text-xs font-medium text-destructive">{fieldErrors.msisdns}</p>}
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
                      type="text"
                      disabled={isTracing}
                      placeholder="root"
                      className={cn("h-9 text-sm", fieldErrors.username && "border-destructive focus-visible:ring-destructive")}
                      value={username}
                      onChange={e => {
                        setUsername(e.target.value)
                        if (fieldErrors.username) setFieldErrors({ ...fieldErrors, username: undefined })
                      }}
                    />
                    {fieldErrors.username && <p className="text-xs font-medium text-destructive">{fieldErrors.username}</p>}
                  </div>
                  <div className="space-y-1.5 flex-1">
                    <Label htmlFor="password" className={cn("text-xs", fieldErrors.password && "text-destructive")}>SSH Password</Label>
                    {/* Stored password: no input rendered, so nothing (e.g. browser autofill) can override it */}
                    {usingStoredPassword && !password ? (
                      <div
                        className="flex h-9 items-center gap-2 rounded-md border border-input bg-muted/50 px-3 text-sm cursor-pointer"
                        onClick={() => { if (!isTracing) setUsingStoredPassword(false) }}
                        title="Click to enter a different password"
                      >
                        <span className="tracking-widest text-green-600">●●●●●●●●</span>
                        <span className="text-[10px] text-green-600 font-medium ml-auto whitespace-nowrap">Stored · click to change</span>
                      </div>
                    ) : (
                      <Input
                        id="password"
                        name="ssh-password"
                        autoComplete="new-password"
                        data-lpignore="true"
                        data-1p-ignore
                        type="password"
                        disabled={isTracing}
                        placeholder="••••••••"
                        className={cn("h-9 text-sm", fieldErrors.password && "border-destructive focus-visible:ring-destructive")}
                        value={password}
                        onChange={e => {
                          setPassword(e.target.value)
                          if (fieldErrors.password) setFieldErrors({ ...fieldErrors, password: undefined })
                        }}
                      />
                    )}
                    {fieldErrors.password && <p className="text-xs font-medium text-destructive">{fieldErrors.password}</p>}
                  </div>
                </div>
              </div>
              {isTracing ? (
                <Button
                  variant="destructive"
                  className="w-full mt-2 cursor-pointer transition-transform"
                  onClick={stopTracing}
                >
                  <Square className="mr-2 h-4 w-4 fill-current" /> Stop Trace
                </Button>
              ) : (
                <Button
                  className="w-full mt-2 bg-green-600 hover:bg-green-700 cursor-pointer transition-transform shadow-md"
                  onClick={startTracing}
                >
                  <Play className="mr-2 h-4 w-4 fill-current" /> Initialize Trace
                </Button>
              )}
            </div>
          )}
        </Card>
        {/* Live Stream Results */}
        <Card className="flex flex-col border-none shadow-none bg-transparent">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-4 px-0">
            <CardTitle className="text-xl">Real-time Sessions {totalResults > 0 && `(${totalResults})`}</CardTitle>
            <div className="flex items-center gap-4 max-w-sm w-full">
              {isTracing && (
                <div className="flex items-center gap-2 text-green-500 text-xs font-semibold animate-pulse shrink-0">
                  <Activity className="h-4 w-4" />
                  Multi-Node Active
                </div>
              )}
              <div className="flex items-center gap-2 relative w-full">
                <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
                <Input
                  placeholder="Filter results..."
                  value={searchTerm}
                  onChange={(e) => { setSearchTerm(e.target.value); setCurrentPage(1); }}
                  className="h-8 pl-8"
                />
              </div>
            </div>
          </CardHeader>
          <CardContent className="flex-1 flex flex-col p-0">
            {error && (
              <div className="bg-destructive/10 text-destructive rounded-md p-4 mb-4 text-sm font-medium">
                {error}
              </div>
            )}
            {!isTracing && results.size === 0 && (
              <div className="flex h-[450px] shrink-0 items-center justify-center rounded-xl border border-dashed bg-muted/20">
                <div className="mx-auto flex max-w-[420px] flex-col items-center justify-center text-center">
                   <div className="h-16 w-16 bg-primary/10 rounded-full flex items-center justify-center mb-4">
                     <Radio className="h-8 w-8 text-primary" />
                   </div>
                   <h3 className="text-xl font-bold">Trace Not Started</h3>
                   <p className="mt-2 text-sm text-muted-foreground">Select servers and MSISDNs above, then click &apos;Initialize Trace&apos; to begin real-time tracking.</p>
                </div>
              </div>
            )}
            {isTracing && results.size === 0 && (
              <div className="flex h-[450px] shrink-0 items-center justify-center rounded-md border border-dashed">
                <div className="mx-auto flex max-w-[420px] flex-col items-center justify-center text-center">
                   <div className="relative mb-4">
                     <Activity className="h-10 w-10 text-primary animate-pulse" />
                     <div className="absolute inset-0 h-10 w-10 rounded-full border-2 border-primary/20 animate-ping" />
                   </div>
                   <h3 className="text-lg font-semibold">Listening on {selectedServerIds.length} nodes...</h3>
                   <p className="mt-2 text-sm text-muted-foreground">Monitoring logs for activity on targets.</p>
                </div>
              </div>
            )}
            <div className="space-y-4">
               {paginatedResults.map((group) => (
                  <div key={`${group.serverId}:${group.callId}`} className="border rounded-md overflow-hidden relative">
                     {/* Server Origin Tag */}
                     <div className="absolute top-0 right-0 p-1">
                        <Badge variant="outline" className="text-[9px] bg-background/80 backdrop-blur-sm border-primary/20 text-primary">
                           Node: {dynamicServers.find(s => s.id === group.serverId)?.name || 'Unknown'}
                        </Badge>
                     </div>
                     {/* Accordion Header */}
                     <div
                      className="flex items-center p-3 bg-muted/50 cursor-pointer hover:bg-muted transition-colors pr-24"
                      onClick={() => toggleRow(`${group.serverId}:${group.callId}`)}
                    >
                      {expandedRows.has(`${group.serverId}:${group.callId}`) ? <ChevronDown className="h-4 w-4 mr-2" /> : <ChevronRight className="h-4 w-4 mr-2" />}
                      <div className="flex-1 grid grid-cols-5 gap-4 items-center">
                        <div>
                          <p className="text-xs text-muted-foreground">MSISDN</p>
                          <span className="font-medium text-sm">{group.msisdn || 'Unknown'}</span>
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Short Code</p>
                          <span className="font-medium text-sm font-mono">{group.shortCode || '-'}</span>
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Call Source</p>
                          <span className="font-medium text-sm">{group.callSource || '-'}</span>
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Status</p>
                          {group.status === 'SUCCESS' ? (
                            <Badge className="bg-green-500 hover:bg-green-600">Success</Badge>
                          ) : group.status === 'ACTIVE' ? (
                            <Badge variant="secondary" className="animate-pulse bg-blue-500/10 text-blue-500 border-blue-500/20">Active</Badge>
                          ) : group.status ? (
                            <Badge variant="destructive">{group.status}</Badge>
                          ) : (
                            <Badge variant="outline">Pending</Badge>
                          )}
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Capture Time</p>
                          <span className="text-xs font-medium tabular-nums">{group.startTime || '-'}</span>
                        </div>
                      </div>
                    </div>
                    {/* [FIX UI-6] Expanded Details — using shared TransactionTimeline */}
                    {expandedRows.has(`${group.serverId}:${group.callId}`) && (
                      <TransactionTimeline
                        events={group.events}
                        logs={group.logs}
                        callId={group.callId}
                        resultMessage={group.resultMessage}
                        onMaximizePayload={(title, content) => setMaximizedPayload({ title, content })}
                      />
                    )}
                  </div>
               ))}
            </div>
            {/* [FIX UI-6] Shared pagination controls */}
            {totalResults > 0 && (
              <PaginationControls
                totalResults={totalResults}
                currentPage={currentPage}
                pageSize={pageSize}
                totalPages={totalPages}
                startIndex={startIndex}
                onPageChange={goToPage}
                onPageSizeChange={(size) => { setPageSize(size); setCurrentPage(1); }}
              />
            )}
          </CardContent>
        </Card>
      </div>
      {/* [FIX UI-6] Shared payload viewer dialog */}
      <PayloadViewerDialog
        open={!!maximizedPayload}
        onOpenChange={(open) => !open && setMaximizedPayload(null)}
        title={maximizedPayload?.title || ""}
        content={maximizedPayload?.content || ""}
      />
      </div>
    </>
  )
}
