"use client"

import * as React from "react"
import {
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Phone,
  PhoneOff,
  PhoneMissed,
  Send,
  MessageSquare,
  Server,
  Activity,
  ChevronDown,
  Terminal,
  Signal,
  SignalZero,
} from "lucide-react"
import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { cn } from "@/lib/utils"

// ─── Types ───────────────────────────────────────────────────────────────────

interface LogEvent {
  timestamp: string
  eventType: string
  details: string
  payload?: string
  rawLine: string
}

interface LogGroup {
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

interface McnCheckResponse {
  sipChecked: boolean
  sipRecordsFound: boolean
  sipMessage: string
  sipRecords: LogGroup[]
  notifierChecked: boolean
  notifierRecordsFound: boolean
  notifierMessage: string
  notifierRecords: LogGroup[]
}

interface McnTraceResultsProps {
  response: McnCheckResponse
  msisdn: string
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function decodeUnicode(str: string | null | undefined): string {
  if (!str) return ""
  try {
    return str.replace(/\\u[\dA-F]{4}/gi, (match) =>
      String.fromCharCode(parseInt(match.replace(/\\u/g, ""), 16))
    )
  } catch {
    return str
  }
}

const SIP_CODE_MAP: Record<string, { label: string; color: string }> = {
  "480": { label: "Subscriber Unreachable", color: "text-amber-500" },
  "487": { label: "Phone Powered Off", color: "text-red-500" },
}

// ─── Subcomponents ───────────────────────────────────────────────────────────

function DeliveryVerdict({ response }: { response: McnCheckResponse }) {
  // Determine overall verdict from notifier results
  const notifierGroup = response.notifierRecords?.[0]
  const status = notifierGroup?.status

  let icon = <AlertTriangle className="h-7 w-7" />
  let title = "Unknown Status"
  let subtitle = "Could not determine delivery outcome."
  let bgClass = "from-slate-500/10 to-slate-600/5 border-slate-500/20"
  let iconColor = "text-slate-400"
  let badgeClass = "bg-slate-500"

  if (!response.sipRecordsFound) {
    icon = <PhoneOff className="h-7 w-7" />
    title = "No Missed Call Triggers"
    subtitle = "No SIP records found — the subscriber had no missed calls logged on this date."
    bgClass = "from-slate-500/10 to-slate-600/5 border-slate-500/20"
    iconColor = "text-slate-400"
    badgeClass = "bg-slate-500"
  } else if (status === "SUCCESS") {
    icon = <CheckCircle2 className="h-7 w-7" />
    title = "SMS Successfully Delivered"
    subtitle = notifierGroup?.resultMessage || "The missed call notification was dispatched via SMSC."
    bgClass = "from-emerald-500/10 to-green-600/5 border-emerald-500/20"
    iconColor = "text-emerald-500"
    badgeClass = "bg-emerald-500"
  } else if (status === "NOT_SENT") {
    icon = <SignalZero className="h-7 w-7" />
    title = "SMS Not Sent — Subscriber Unreachable"
    subtitle = notifierGroup?.resultMessage || "ATI lookup determined subscriber is not reachable."
    bgClass = "from-amber-500/10 to-orange-600/5 border-amber-500/20"
    iconColor = "text-amber-500"
    badgeClass = "bg-amber-500"
  } else if (status === "ERROR") {
    icon = <XCircle className="h-7 w-7" />
    title = "Delivery Failed — ATI Timeout"
    subtitle = notifierGroup?.resultMessage || "ATI lookup timed out, no SMS was dispatched."
    bgClass = "from-red-500/10 to-red-600/5 border-red-500/20"
    iconColor = "text-red-500"
    badgeClass = "bg-red-500"
  } else if (response.sipRecordsFound && !response.notifierRecordsFound) {
    icon = <AlertTriangle className="h-7 w-7" />
    title = "SIP Trigger Found — No Notifier Record"
    subtitle = "A missed call was logged on the SIP cluster, but no corresponding entry exists on the Notifier servers."
    bgClass = "from-amber-500/10 to-orange-600/5 border-amber-500/20"
    iconColor = "text-amber-500"
    badgeClass = "bg-amber-500"
  }

  return (
    <div className={cn("rounded-xl border bg-gradient-to-br p-5", bgClass)}>
      <div className="flex items-start gap-4">
        <div className={cn("mt-0.5 shrink-0", iconColor)}>{icon}</div>
        <div className="flex-1 min-w-0 space-y-1">
          <div className="flex items-center gap-3">
            <h3 className="text-lg font-bold tracking-tight">{title}</h3>
            <Badge className={cn("text-[10px] uppercase font-semibold", badgeClass)}>
              {status || (response.sipRecordsFound ? "PARTIAL" : "NO DATA")}
            </Badge>
          </div>
          <p className="text-sm text-muted-foreground">{subtitle}</p>
        </div>
      </div>
    </div>
  )
}

function SipMissedCallsTable({ sipRecords }: { sipRecords: LogGroup[] }) {
  // Flatten all SIP events from all server groups, tagging each with source server
  const allCalls: { time: string; caller: string; sipCode: string; meaning: string; server: string; serverIp: string }[] = []

  for (const group of sipRecords) {
    for (const evt of group.events) {
      if (evt.eventType !== "SIP CALL EVENT") continue
      // Parse "Missed call from 249129280533 - Temporarily Unavailable"
      const match = evt.details.match(/Missed call from (\d+) - (.+)/)
      if (match) {
        allCalls.push({
          time: evt.timestamp,
          caller: match[1],
          sipCode: evt.rawLine?.match(/,(\d{3})\s*$/)?.[1] || "???",
          meaning: match[2],
          server: group.callSource,
          serverIp: group.serverIp,
        })
      }
    }
  }

  // Sort by timestamp
  allCalls.sort((a, b) => a.time.localeCompare(b.time))

  if (allCalls.length === 0) return null

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <PhoneMissed className="h-5 w-5 text-orange-500" />
            <CardTitle className="text-base">Missed Call Events</CardTitle>
          </div>
          <Badge variant="secondary" className="font-mono text-xs">
            {allCalls.length} call{allCalls.length !== 1 ? "s" : ""}
          </Badge>
        </div>
        <CardDescription className="text-xs mt-1">
          Showing missed call attempts to <code className="bg-muted/50 px-1 py-0.5 rounded font-mono text-primary">{sipRecords[0]?.msisdn}</code> (the Callee). The <strong>Caller</strong> column lists the phone numbers that tried to reach this subscriber.
        </CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        <div className="max-h-[400px] overflow-y-auto">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/30">
                <TableHead className="text-xs font-semibold w-[140px]">Time</TableHead>
                <TableHead className="text-xs font-semibold">Caller</TableHead>
                <TableHead className="text-xs font-semibold w-[80px]">SIP Code</TableHead>
                <TableHead className="text-xs font-semibold">Status</TableHead>
                <TableHead className="text-xs font-semibold w-[140px]">Server</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {allCalls.map((call, idx) => {
                const codeInfo = SIP_CODE_MAP[call.sipCode]
                return (
                  <TableRow key={idx} className="group hover:bg-muted/20 transition-colors">
                    <TableCell className="font-mono text-xs tabular-nums text-muted-foreground">
                      {call.time.split(" ").pop()?.replace(",", ".")}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <Phone className="h-3.5 w-3.5 text-muted-foreground/50" />
                        <code className="text-sm font-mono font-medium">{call.caller}</code>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className={cn("font-mono text-[10px] font-bold", codeInfo?.color || "text-muted-foreground")}>
                        {call.sipCode}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {call.meaning}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5">
                        <Server className="h-3 w-3 text-muted-foreground/40" />
                        <span className="text-[11px] text-muted-foreground">{call.server}</span>
                      </div>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

function NotifierDeliveryCard({ notifierRecords }: { notifierRecords: LogGroup[] }) {
  if (!notifierRecords || notifierRecords.length === 0) return null

  // Aggregate events across all notifier groups
  const allAti: LogEvent[] = []
  const allSmsc: LogEvent[] = []
  const allSms: LogEvent[] = []
  const allRawLogs: string[] = []

  for (const group of notifierRecords) {
    for (const evt of group.events) {
      if (evt.eventType === "ATI RESULT" || evt.eventType === "ATI TIMEOUT") allAti.push(evt)
      else if (evt.eventType === "SMSC SEND") allSmsc.push(evt)
      else if (evt.eventType === "SMS CONTENT") allSms.push(evt)
    }
    allRawLogs.push(...group.logs)
  }

  // Determine ATI summary
  const atiStates: Record<string, number> = {}
  let atiTimeouts = 0
  for (const ati of allAti) {
    if (ati.eventType === "ATI TIMEOUT") {
      atiTimeouts++
    } else {
      const stateMatch = ati.details.match(/ATI lookup result: (.+)/)
      const state = stateMatch?.[1] || "unknown"
      atiStates[state] = (atiStates[state] || 0) + 1
    }
  }

  const dominantState = Object.entries(atiStates).sort((a, b) => b[1] - a[1])[0]
  const lastAti = allAti[allAti.length - 1]

  // SMSC info
  const smscEvent = allSmsc[0]
  const smscSuccess = smscEvent?.details.includes("Success")

  // SMS content
  const smsEvent = allSms[0]
  const smsFullText = smsEvent?.payload ? decodeUnicode(smsEvent.payload) : (smsEvent?.details ? decodeUnicode(smsEvent.details) : null)

  const [showAllAti, setShowAllAti] = React.useState(false)
  const [showRawLogs, setShowRawLogs] = React.useState(false)

  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-3">
        <div className="flex items-center gap-2">
          <Activity className="h-5 w-5 text-purple-500" />
          <CardTitle className="text-base">Notification Pipeline</CardTitle>
        </div>
        <CardDescription className="text-xs">ATI subscriber lookups, SMSC dispatch, and SMS delivery details.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* ATI Summary */}
        <div className="rounded-lg border bg-muted/20 p-4 space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Signal className="h-4 w-4 text-blue-500" />
              <span className="text-sm font-semibold">ATI Subscriber Lookups</span>
            </div>
            <Badge variant="secondary" className="font-mono text-xs">
              {allAti.length} check{allAti.length !== 1 ? "s" : ""}
            </Badge>
          </div>

          <div className="grid grid-cols-2 gap-3 text-xs">
            {dominantState && (
              <div className="rounded-md bg-background border p-2.5">
                <div className="text-muted-foreground mb-0.5 text-[10px] uppercase tracking-wider font-semibold">Last State</div>
                <div className="font-mono font-medium text-sm">{dominantState[0]}</div>
                <div className="text-muted-foreground text-[10px]">({dominantState[1]} occurrence{dominantState[1] !== 1 ? "s" : ""})</div>
              </div>
            )}
            {atiTimeouts > 0 && (
              <div className="rounded-md bg-background border p-2.5">
                <div className="text-muted-foreground mb-0.5 text-[10px] uppercase tracking-wider font-semibold">Timeouts</div>
                <div className="font-mono font-medium text-sm text-amber-500">{atiTimeouts}</div>
                <div className="text-muted-foreground text-[10px]">ATI read timeout{atiTimeouts !== 1 ? "s" : ""}</div>
              </div>
            )}
            {lastAti && (
              <div className="rounded-md bg-background border p-2.5 col-span-2">
                <div className="text-muted-foreground mb-0.5 text-[10px] uppercase tracking-wider font-semibold">Last Check Time</div>
                <div className="font-mono font-medium text-sm">{lastAti.timestamp}</div>
              </div>
            )}
          </div>

          {/* Expandable ATI event list */}
          {allAti.length > 0 && (
            <button
              onClick={() => setShowAllAti(!showAllAti)}
              className="flex items-center gap-1.5 text-[11px] text-primary/70 hover:text-primary font-medium transition-colors cursor-pointer"
            >
              <ChevronDown className={cn("h-3.5 w-3.5 transition-transform", showAllAti && "rotate-180")} />
              {showAllAti ? "Hide" : "Show"} all {allAti.length} ATI events
            </button>
          )}

          {showAllAti && (
            <div className="max-h-[250px] overflow-y-auto rounded-md border bg-background">
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/30">
                    <TableHead className="text-[10px] font-semibold w-[130px]">Time</TableHead>
                    <TableHead className="text-[10px] font-semibold">Type</TableHead>
                    <TableHead className="text-[10px] font-semibold">State</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {allAti.map((evt, idx) => (
                    <TableRow key={idx} className="text-xs">
                      <TableCell className="font-mono text-[10px] tabular-nums text-muted-foreground py-1.5">
                        {evt.timestamp.split(" ").pop()?.replace(",", ".")}
                      </TableCell>
                      <TableCell className="py-1.5">
                        <Badge
                          variant="outline"
                          className={cn("text-[9px] font-mono",
                            evt.eventType === "ATI TIMEOUT" ? "text-amber-500 border-amber-500/30" : "text-blue-500 border-blue-500/30"
                          )}
                        >
                          {evt.eventType === "ATI TIMEOUT" ? "TIMEOUT" : "RESULT"}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-[10px] text-muted-foreground py-1.5">{evt.details}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </div>

        {/* SMSC Delivery */}
        {smscEvent && (
          <div className={cn(
            "rounded-lg border p-4 flex items-center gap-4",
            smscSuccess ? "bg-emerald-500/5 border-emerald-500/20" : "bg-red-500/5 border-red-500/20"
          )}>
            <div className={cn(
              "h-10 w-10 rounded-full flex items-center justify-center shrink-0",
              smscSuccess ? "bg-emerald-500/10 text-emerald-500" : "bg-red-500/10 text-red-500"
            )}>
              <Send className="h-5 w-5" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-0.5">
                <span className="text-sm font-semibold">SMSC Dispatch</span>
                <Badge className={cn("text-[10px]", smscSuccess ? "bg-emerald-500" : "bg-red-500")}>
                  {smscSuccess ? "SUCCESS" : "FAILED"}
                </Badge>
              </div>
              <p className="text-xs text-muted-foreground font-mono">{smscEvent.details}</p>
              <p className="text-[10px] text-muted-foreground mt-0.5">at {smscEvent.timestamp}</p>
            </div>
          </div>
        )}

        {/* SMS Message Bubble */}
        {smsFullText && (
          <div className="rounded-lg border border-teal-500/20 bg-teal-500/5 p-4 space-y-3">
            <div className="flex items-center gap-2">
              <MessageSquare className="h-4 w-4 text-teal-500" />
              <span className="text-sm font-semibold">SMS Message Content</span>
            </div>
            <div className="relative">
              <div className="bg-background border rounded-2xl rounded-tl-sm px-4 py-3 shadow-sm max-w-[90%]">
                <p className="text-sm leading-relaxed whitespace-pre-wrap" dir="auto">
                  {smsFullText}
                </p>
              </div>
              {/* Delivery tick */}
              {smscSuccess && (
                <div className="flex items-center gap-1 mt-1.5 ml-2 text-[10px] text-emerald-500">
                  <CheckCircle2 className="h-3 w-3" />
                  <span>Delivered</span>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Raw Logs */}
        {allRawLogs.length > 0 && (
          <div className="border-t pt-4">
            <button
              onClick={() => setShowRawLogs(!showRawLogs)}
              className="inline-flex items-center text-xs font-semibold tracking-wider uppercase text-muted-foreground cursor-pointer hover:text-primary transition-colors select-none"
            >
              <Terminal className="h-4 w-4 mr-2" />
              <span>Show Raw Notifier Logs</span>
              <ChevronDown className={cn("h-4 w-4 ml-1 opacity-50 transition-transform", showRawLogs && "rotate-180")} />
            </button>

            {showRawLogs && (
              <div className="bg-[#0c0c0c] text-green-400 p-4 rounded-lg font-mono text-xs overflow-x-auto overflow-y-auto max-h-[400px] whitespace-pre mt-3 border border-[#333] shadow-inner">
                {allRawLogs.map((log, idx) => (
                  <div key={idx} className="mb-1.5 hover:bg-white/5 px-1 -mx-1 rounded transition-colors break-all whitespace-pre-wrap">
                    {decodeUnicode(log)}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

// ─── Main Export ──────────────────────────────────────────────────────────────

export function McnTraceResults({ response, msisdn }: McnTraceResultsProps) {
  return (
    <div className="space-y-5">
      {/* Verdict Banner */}
      <DeliveryVerdict response={response} />

      {/* SIP Missed Calls */}
      {response.sipRecordsFound && response.sipRecords && response.sipRecords.length > 0 && (
        <SipMissedCallsTable sipRecords={response.sipRecords} />
      )}

      {/* Notifier Delivery */}
      {response.notifierChecked && response.notifierRecords && response.notifierRecords.length > 0 && (
        <NotifierDeliveryCard notifierRecords={response.notifierRecords} />
      )}

      {/* No SIP — prominent empty state */}
      {response.sipChecked && !response.sipRecordsFound && (
        <Card className="border-dashed">
          <CardContent className="pt-6 flex items-start gap-3">
            <PhoneOff className="h-5 w-5 text-muted-foreground shrink-0 mt-0.5" />
            <div className="space-y-1">
              <h4 className="text-sm font-semibold text-muted-foreground">No Missed Call Records</h4>
              <p className="text-xs text-muted-foreground">
                The MSISDN <code className="bg-muted px-1 py-0.5 rounded font-mono text-[11px]">{msisdn}</code> has
                no activity on the SIP cluster for the selected date. Verify the number is correct and has an active MCN subscription.
              </p>
              {response.sipMessage && (
                <p className="text-[11px] text-destructive/80 mt-2 font-medium">{response.sipMessage}</p>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
