"use client"

import * as React from "react"
import { useState } from "react"
import { format } from "date-fns"
import {
  Calendar as CalendarIcon,
  Search,
  ChevronDown,
  ChevronUp,
  ChevronRight,
  Settings2,
  Filter,
  RefreshCw,
  Activity,
  Download,
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
import { Textarea } from "@/components/ui/textarea"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { useAuth } from "@/lib/auth-context"
import { apiClient } from "@/lib/api-client"
import { isValidSshUsername } from "@/lib/validators"
import { ChevronsUpDown } from "lucide-react"

// [FIX UI-6] Shared components
import { TransactionTimeline, LogEvent } from "@/components/ussd-log/transaction-timeline"
import { PaginationControls } from "@/components/ussd-log/pagination-controls"
import { PayloadViewerDialog } from "@/components/ussd-log/payload-viewer-dialog"


// DTO from backend
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

interface HistoryServer {
  id: string
  name: string
  ipAddress: string
  sshUsername?: string
  hasPasswordStored?: boolean
}

export default function UssdHistoryPage() {
  useAuth() // Ensure user is authenticated

  const [dynamicServers, setDynamicServers] = useState<HistoryServer[]>([])
  const [isLoadingServers, setIsLoadingServers] = useState(true)
  const [date, setDate] = useState<Date>(new Date())
  const [selectedServerIds, setSelectedServerIds] = useState<string[]>([])
  const [username, setUsername] = useState<string>("")
  const [password, setPassword] = useState<string>("")
  const [usingStoredPassword, setUsingStoredPassword] = useState<boolean>(false)
  const [msisdnsText, setMsisdnsText] = useState<string>("")

  const [isLoading, setIsLoading] = useState(false)
  const [results, setResults] = useState<UssdLogGroup[]>([])
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{ msisdns?: string, username?: string, password?: string, servers?: string }>({})
  const [configExpanded, setConfigExpanded] = useState(true)

  // Search and Pagination State
  const [searchTerm, setSearchTerm] = useState("")
  const [currentPage, setCurrentPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

  // [FIX UI-6] Shared payload viewer dialog state
  const [maximizedPayload, setMaximizedPayload] = useState<{ title: string, content: string } | null>(null)

  // Track expanded rows
  const [expandedRows, setExpandedRows] = useState<Set<string>>(new Set())

  const toggleRow = (id: string) => {
    const newExpanded = new Set(expandedRows)
    if (newExpanded.has(id)) {
      newExpanded.delete(id)
    } else {
      newExpanded.add(id)
    }
    setExpandedRows(newExpanded)
  }

  // [FIX H-12] Clear password on unmount to avoid leaking credentials in memory
  React.useEffect(() => {
    return () => {
      setPassword("")
    }
  }, [])

  React.useEffect(() => {
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
  }, [])

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

  const handleSearch = async () => {
    setIsLoading(true)
    setError(null)
    setFieldErrors({})
    setResults([])

    try {
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
        // [FIX H-11] Strengthened validation to match backend: 7-15 digits, optional + prefix
        const invalidMsisdns = msisdns.filter(m => !/^\+?\d{7,15}$/.test(m));
        if (invalidMsisdns.length > 0) {
          newErrors.msisdns = `Invalid MSISDN format (7-15 digits): ${invalidMsisdns.join(", ")}`;
          hasErrors = true;
        }
        if (msisdns.length > 50) {
          newErrors.msisdns = "Maximum 50 MSISDNs allowed per search";
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
        setIsLoading(false);
        return;
      }

      const formattedDate = format(date, "yyyy-MM-dd")

      const nodes: Record<string, string> = {};
      selectedServerIds.forEach(id => {
        const s = dynamicServers.find(srv => srv.id === id);
        if (s) nodes[id] = s.ipAddress;
      });

      // [FIX SEC-2] Step 1: Register SSH credentials securely via /search/start.
      // When using stored credentials, pass useStoredCredentials+nodeId so the backend resolves the password from DB.
      // Only the opaque sessionId travels in the actual /search request body.
      const startPayload = usingStoredPassword && !password
        ? { username, useStoredCredentials: true, nodeId: selectedServerIds[0] }
        : { username, password }

      const startResponse = await apiClient("/logs/search/start", {
        method: "POST",
        body: JSON.stringify(startPayload),
      })
      const searchSessionId = startResponse.searchSessionId
      if (!searchSessionId) {
        setError("Failed to create search session. Please try again.")
        setIsLoading(false)
        return
      }

      // [FIX SEC-2] Step 2: Search using only the session ID — no password in this request
      const responseData = await apiClient("/logs/search", {
        method: "POST",
        body: JSON.stringify({
          nodes,
          sessionId: searchSessionId,
          date: formattedDate,
          msisdns
        })
      })

      // Defensively parse the response in case it's wrapped in a 'data', 'content', or 'records' object
      let parsedResults: UssdLogGroup[] = []
      let parsedErrors: string[] = []
      if (Array.isArray(responseData)) {
        parsedResults = responseData
      } else if (responseData && typeof responseData === 'object') {
        parsedResults = responseData.data || responseData.content || responseData.records || responseData.results || []
        parsedErrors = responseData.errors || []
      }

      setResults(parsedResults)
      setConfigExpanded(false)

      if (parsedErrors.length > 0) {
        parsedErrors.forEach(err => {
          const isFileNotFound = err.toLowerCase().includes("not found") ||
            err.toLowerCase().includes("not readable") ||
            err.toLowerCase().includes("no such file")
          toast[isFileNotFound ? "error" : "warning"](isFileNotFound ? "Log File Not Found" : "Trace Diagnostics", {
            description: err,
            duration: 8000,
          })
        })
      }

      const returnedMsisdns = new Set(parsedResults.map(r => r.msisdn))
      const missingMsisdns = msisdns.filter(m => !returnedMsisdns.has(m))

      if (missingMsisdns.length > 0) {
        toast.info(parsedResults.length === 0 ? "No Records Found" : "Partial Records Found", {
          description: `No logs matched the requested MSISDN(s): ${missingMsisdns.join(", ")}`,
          duration: parsedResults.length === 0 ? 5000 : 7000,
        })
      }

      setCurrentPage(1)

      // [FIX H-15] err: any → err: unknown
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "An error occurred while fetching logs"
      setError(msg)
      if (msg.includes("not found") || msg.includes("not readable") || msg.includes("No such file")) {
        toast.error("Log File Not Found", {
          description: `No log file exists for ${format(date, "PPP")} on the target node(s).`,
          duration: 6000,
        })
      } else {
        toast.error("Search Failed", { description: msg })
      }
    } finally {
      setIsLoading(false)
    }
  }

  // Client-side filtering
  const filteredResults = results.filter(group =>
    (group.msisdn?.toLowerCase() || "").includes(searchTerm.toLowerCase()) ||
    (group.callId?.toLowerCase() || "").includes(searchTerm.toLowerCase()) ||
    (group.shortCode?.toLowerCase() || "").includes(searchTerm.toLowerCase()) ||
    (dynamicServers.find(s => s.id === group.serverId)?.name || "").toLowerCase().includes(searchTerm.toLowerCase())
  )

  // Pagination logic
  const totalResults = filteredResults.length
  const totalPages = Math.ceil(totalResults / pageSize)

  React.useEffect(() => {
    if (currentPage > totalPages && totalPages > 0) {
      setCurrentPage(totalPages)
    }
  }, [totalPages, currentPage])

  const startIndex = (currentPage - 1) * pageSize
  const paginatedResults = filteredResults.slice(startIndex, startIndex + pageSize)

  const goToPage = (page: number) => {
    setCurrentPage(Math.max(1, Math.min(page, totalPages)))
  }

  // [FIX P2-13] CSV export — exports all filtered results (not just current page)
  const handleExportCsv = () => {
    if (filteredResults.length === 0) { toast.error("No results to export"); return }
    const headers = ["MSISDN", "Call ID", "Short Code", "Call Source", "Status", "Start Time", "Server IP", "Result Message"]
    const rows = filteredResults.map(g => [
      g.msisdn || "",
      g.callId || "",
      g.shortCode || "",
      g.callSource || "",
      g.status || "",
      g.startTime || "",
      g.serverIp || "",
      (g.resultMessage || "").replace(/,/g, ";"),
    ])
    const csv = [headers, ...rows].map(r => r.map(v => `"${String(v).replace(/"/g, '""')}"`).join(",")).join("\n")
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url; a.download = `ussd-history-${format(date, "yyyy-MM-dd")}.csv`; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
    toast.success(`Exported ${filteredResults.length} records to CSV`)
  }

  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "USSD Logs", href: "/dashboard/ussd-logs/history" },
          { label: "History" }
        ]}
      />
      <div className="flex-1 space-y-4 p-4 pt-6 md:p-8">
        <div className="flex items-center justify-between space-y-2">
          <div>
            <h2 className="text-3xl font-bold tracking-tight">USSD History</h2>
            <p className="text-muted-foreground">Search and analyze historical USSD interactions across backend nodes.</p>
          </div>
          {/* [FIX P2-13] CSV Export button — only visible when results are present */}
          {results.length > 0 && (
            <Button variant="outline" size="sm" onClick={handleExportCsv}>
              <Download className="mr-2 h-4 w-4" />
              Export CSV ({filteredResults.length})
            </Button>
          )}
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
                <div className="h-8 w-8 rounded-lg bg-primary/10 flex items-center justify-center">
                  <Settings2 className="h-4 w-4 text-primary" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">Search Configuration</h3>
                  {!configExpanded && (
                    <p className="text-xs text-muted-foreground mt-0.5">
                      {format(date, "PPP")} · {selectedServerIds.length} node(s) · {username || "no user"}
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
                      <PopoverTrigger asChild disabled={isLoading}>
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

                  {/* Log Date */}
                  <div className="space-y-1.5">
                    <Label className="text-xs">Log Date</Label>
                    <Popover>
                      <PopoverTrigger asChild disabled={isLoading}>
                        <Button
                          variant={"outline"}
                          className={cn(
                            "w-full justify-start text-left font-normal h-9 text-sm",
                            !date && "text-muted-foreground"
                          )}
                        >
                          <CalendarIcon className="mr-2 h-3.5 w-3.5" />
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

                  {/* SSH User */}
                  <div className="space-y-1.5">
                    <Label htmlFor="username" className={cn("text-xs", fieldErrors.username && "text-destructive")}>
                      SSH Username <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="username"
                      name="ssh-username"
                      autoComplete="off"
                      data-lpignore="true"
                      data-1p-ignore
                      type="text"
                      disabled={isLoading}
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

                  {/* SSH Password */}
                  <div className="space-y-1.5">
                    <Label htmlFor="password" className={cn("text-xs", fieldErrors.password && "text-destructive")}>
                      SSH Password <span className="text-destructive">*</span>
                    </Label>
                    {usingStoredPassword && !password ? (
                      <div
                        className="flex h-9 items-center gap-2 rounded-md border border-input bg-muted/50 px-3 text-sm cursor-pointer"
                        onClick={() => { setUsingStoredPassword(false); }}
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
                        disabled={isLoading}
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

                {/* MSISDNs */}
                <div className="space-y-1.5">
                  <Label htmlFor="msisdns" className={cn("text-xs", fieldErrors.msisdns && "text-destructive")}>Target MSISDNs</Label>
                  <Textarea
                    id="msisdns"
                    placeholder="Enter numbers separated by comma or new line (e.g. 249121096876)"
                    disabled={isLoading}
                    className={cn("min-h-[80px] resize-y", fieldErrors.msisdns && "border-destructive focus-visible:ring-destructive")}
                    value={msisdnsText}
                    onChange={e => {
                      setMsisdnsText(e.target.value)
                      if (fieldErrors.msisdns) setFieldErrors({ ...fieldErrors, msisdns: undefined })
                    }}
                  />
                  {fieldErrors.msisdns && <p className="text-xs font-medium text-destructive">{fieldErrors.msisdns}</p>}
                </div>

                <Button
                  className="w-full sm:w-auto mt-2 cursor-pointer hover:cursor-pointer transition-transform shadow-md"
                  onClick={handleSearch}
                  disabled={isLoading}
                >
                  {isLoading ? (
                    <><RefreshCw className="mr-2 h-4 w-4 animate-spin" /> Searching Nodes...</>
                  ) : (
                    <><Search className="mr-2 h-4 w-4" /> Multi-Node Search</>
                  )}
                </Button>
              </div>
            )}
          </Card>

          {/* Results Panel */}
          <Card className="flex flex-col border-none shadow-none bg-transparent">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-4 px-0">
              <CardTitle className="text-xl">Search Results {totalResults > 0 && `(${totalResults})`}</CardTitle>
              {results.length > 0 && (
                <div className="flex items-center gap-2 max-w-sm w-full">
                  <Search className="h-4 w-4 text-muted-foreground mr-1 shrink-0" />
                  <Input
                    placeholder="Filter by MSISDN, Call ID, Node..."
                    value={searchTerm}
                    onChange={(e) => { setSearchTerm(e.target.value); setCurrentPage(1); }}
                    className="h-9"
                  />
                </div>
              )}
            </CardHeader>
            <CardContent className="flex-1 flex flex-col p-0">
              {error && (
                <div className="bg-destructive/10 text-destructive rounded-md p-4 mb-4 text-sm font-medium">
                  {error}
                </div>
              )}

              {results.length === 0 && !isLoading && !error && (
                <div className="flex h-[400px] shrink-0 items-center justify-center rounded-xl border border-dashed bg-muted/20">
                  <div className="mx-auto flex max-w-[420px] flex-col items-center justify-center text-center">
                    <div className="h-16 w-16 bg-primary/10 rounded-full flex items-center justify-center mb-4">
                      <Search className="h-8 w-8 text-primary" />
                    </div>
                    <h3 className="text-xl font-bold">No Records Found</h3>
                    <p className="mt-2 text-sm text-muted-foreground">Enter parameters above and run a search to view remote logs.</p>
                  </div>
                </div>
              )}

              {isLoading && results.length === 0 && (
                <div className="flex h-[400px] shrink-0 items-center justify-center rounded-xl border border-dashed bg-muted/20 animate-pulse">
                  <div className="mx-auto flex max-w-[420px] flex-col items-center justify-center text-center">
                    <div className="relative mb-4">
                      <Activity className="h-10 w-10 text-primary animate-pulse" />
                      <div className="absolute inset-0 h-10 w-10 rounded-full border-2 border-primary/20 animate-ping" />
                    </div>
                    <h3 className="text-lg font-semibold">Scanning {selectedServerIds.length} Nodes...</h3>
                    <p className="mt-2 text-sm text-muted-foreground italic">Fetching historical logs from cluster.</p>
                  </div>
                </div>
              )}

              {filteredResults.length > 0 && (
                <div className="space-y-4">
                  {paginatedResults.map((group) => {
                    const sessionKey = `${group.serverId}:${group.callId}`;
                    return (
                      <div key={sessionKey} className="border rounded-md overflow-hidden relative">
                        {/* Server Origin Tag */}
                        <div className="absolute top-0 right-0 p-1 z-10">
                          <Badge variant="outline" className="text-[9px] bg-background/80 backdrop-blur-sm border-primary/20 text-primary">
                            Node: {dynamicServers.find(s => s.id === group.serverId)?.name || 'Unknown'}
                          </Badge>
                        </div>

                        {/* Accordion Header */}
                        <div
                          className="flex items-center p-3 bg-muted/50 cursor-pointer hover:bg-muted transition-colors pr-24"
                          onClick={() => toggleRow(sessionKey)}
                        >
                          {expandedRows.has(sessionKey) ? <ChevronDown className="h-4 w-4 mr-2" /> : <ChevronRight className="h-4 w-4 mr-2" />}

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
                              ) : group.status ? (
                                <Badge variant="destructive">{group.status}</Badge>
                              ) : (
                                <Badge variant="outline">Pending</Badge>
                              )}
                            </div>
                            <div>
                              <p className="text-xs text-muted-foreground">Time</p>
                              <span className="text-xs tabular-nums">{group.startTime || '-'}</span>
                            </div>
                          </div>
                        </div>

                        {/* [FIX UI-6] Expanded Details — using shared TransactionTimeline */}
                        {expandedRows.has(sessionKey) && (
                          <TransactionTimeline
                            events={group.events}
                            logs={group.logs}
                            callId={group.callId}
                            resultMessage={group.resultMessage}
                            onMaximizePayload={(title, content) => setMaximizedPayload({ title, content })}
                          />
                        )}
                      </div>
                    )
                  })}
                </div>
              )}

              {/* [FIX UI-6] Shared pagination controls */}
              {filteredResults.length > 0 && (
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
