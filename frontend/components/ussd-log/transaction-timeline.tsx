"use client"

import {
  Server,
  Smartphone,
  ArrowRight,
  ArrowLeft,
  ArrowRightLeft,
  CheckCircle2,
  XCircle,
  ChevronRight,
  Maximize2,
  Terminal,
  ChevronDown,
  AlertCircle,
  Radio,
  Send,
  MessageSquare,
  Phone,
} from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

/**
 * [FIX UI-6] Shared transaction timeline component extracted from History and LiveTrace pages.
 * Renders the step-by-step flow of a USSD session (ENTRY → HTTP REQ → HTTP RES → USSD RES → CDR).
 */

// Helper to decode unicode characters in Java logs (e.g. \u0627)
export function decodeUnicode(str: string | null | undefined): string {
  if (!str) return '';
  try {
    return str.replace(/\\u[\dA-F]{4}/gi, (match) => {
      return String.fromCharCode(parseInt(match.replace(/\\u/g, ''), 16));
    });
  } catch {
    return str;
  }
}

export interface LogEvent {
  timestamp: string
  eventType: string
  details: string
  payload?: string
  rawLine: string
}

interface TransactionTimelineProps {
  events: LogEvent[]
  logs: string[]
  callId: string
  resultMessage?: string
  onMaximizePayload: (title: string, content: string) => void
}

export function TransactionTimeline({ events, logs, callId, resultMessage, onMaximizePayload }: TransactionTimelineProps) {
  return (
    <div className="p-4 border-t bg-card space-y-4">
      <div className="grid grid-cols-2 gap-4 text-sm bg-muted/30 p-3 rounded-lg border">
        <div>
          <span className="text-muted-foreground mr-2 font-medium">Session ID:</span>
          <code className="bg-muted px-1.5 py-0.5 rounded text-primary">{callId}</code>
        </div>
        {resultMessage && (
          <div>
            <span className="text-muted-foreground mr-2 font-medium">Final Message:</span>
            <span className="font-semibold">{decodeUnicode(resultMessage)}</span>
          </div>
        )}
      </div>

      <div className="space-y-4 pt-4">
        <h4 className="text-xs font-semibold tracking-wider uppercase text-muted-foreground mb-4">
          Transaction Sequence Flow
        </h4>
        <div className="space-y-6 relative before:absolute before:inset-0 before:ml-5 before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-gradient-to-b before:from-transparent before:via-border before:to-transparent">
          {events && events.map((evt, idx) => {
            let Icon = Server;
            let colorClass = "text-muted-foreground border-border bg-muted/10";
            let iconBgClass = "bg-muted text-muted-foreground";

            if (evt.eventType === "USSD ENTRY") { Icon = Smartphone; colorClass = "border-blue-500/30 bg-blue-500/5"; iconBgClass = "bg-blue-500 text-white"; }
            else if (evt.eventType === "HTTP REQUEST") { Icon = ArrowRight; colorClass = "border-purple-500/30 bg-purple-500/5"; iconBgClass = "bg-purple-500 text-white"; }
            else if (evt.eventType === "HTTP RESPONSE") { Icon = ArrowLeft; colorClass = "border-amber-500/30 bg-amber-500/5"; iconBgClass = "bg-amber-500 text-white"; }
            else if (evt.eventType === "USSD RESPONSE") {
              Icon = evt.details.includes("SUCCESS") || evt.details.includes("200") ? CheckCircle2 : XCircle;
              colorClass = evt.details.includes("SUCCESS") || evt.details.includes("200") ? "border-green-500/30 bg-green-500/5" : "border-red-500/30 bg-red-500/5";
              iconBgClass = evt.details.includes("SUCCESS") || evt.details.includes("200") ? "bg-green-500 text-white" : "bg-red-500 text-white";
            } else if (evt.eventType === "CDR LOG") { Icon = ArrowRightLeft; colorClass = "border-slate-500/30 bg-slate-500/5"; iconBgClass = "bg-slate-500 text-white"; }
            else if (evt.eventType === "ATI TIMEOUT") { Icon = AlertCircle; colorClass = "border-amber-500/30 bg-amber-500/5"; iconBgClass = "bg-amber-500 text-white"; }
            else if (evt.eventType === "ATI RESULT") {
              const isReachable = evt.details.includes("camelBusy") || evt.details.includes("assumedIdle");
              Icon = isReachable ? Radio : XCircle;
              colorClass = isReachable ? "border-green-500/30 bg-green-500/5" : "border-slate-500/30 bg-slate-500/5";
              iconBgClass = isReachable ? "bg-green-500 text-white" : "bg-slate-500 text-white";
            }
            else if (evt.eventType === "SMSC SEND") { Icon = Send; colorClass = "border-green-500/30 bg-green-500/5"; iconBgClass = "bg-green-500 text-white"; }
            else if (evt.eventType === "SMS CONTENT") { Icon = MessageSquare; colorClass = "border-teal-500/30 bg-teal-500/5"; iconBgClass = "bg-teal-500 text-white"; }
            else if (evt.eventType === "SIP CALL EVENT") {
              const isCancelled = evt.details.includes("Cancelled");
              Icon = Phone;
              colorClass = isCancelled ? "border-slate-500/30 bg-slate-500/5" : "border-orange-500/30 bg-orange-500/5";
              iconBgClass = isCancelled ? "bg-slate-500 text-white" : "bg-orange-500 text-white";
            }

            return (
              <div key={idx} className="relative flex items-center justify-between md:justify-normal md:odd:flex-row-reverse group is-active">
                <div className={`flex items-center justify-center w-10 h-10 rounded-full border-4 border-background shrink-0 md:order-1 md:group-odd:-translate-x-1/2 md:group-even:translate-x-1/2 shadow-sm z-10 ${iconBgClass}`}>
                  <Icon className="w-4 h-4 shadow-sm" />
                </div>
                <div className={`w-[calc(100%-4rem)] md:w-[calc(50%-2.5rem)] p-4 rounded-xl border shadow-sm ${colorClass} transition-all hover:shadow-md`}>
                  <div className="flex flex-col gap-1.5 min-w-0">
                    <div className="flex items-center justify-between gap-2 mb-1">
                      <Badge variant="outline" className="text-[10px] uppercase font-mono tracking-wider font-semibold border-current/20 bg-background/50">
                        {evt.eventType}
                      </Badge>
                      <time className="text-xs font-medium text-muted-foreground tabular-nums">
                        {evt.timestamp}
                      </time>
                    </div>
                    <p className="text-sm font-semibold leading-snug truncate whitespace-normal">
                      {decodeUnicode(evt.details)}
                    </p>
                    {evt.payload && (
                      <div className="mt-3">
                        <details className="text-xs group/details [&_summary::-webkit-details-marker]:hidden">
                          <summary className="flex items-center gap-1 cursor-pointer font-medium text-primary/80 hover:text-primary transition-colors select-none">
                            <ChevronRight className="h-3 w-3 transition-transform group-open/details:rotate-90" />
                            Inspect Data Payload
                          </summary>
                          <div className="relative group/payload-container">
                            <Button
                              variant="ghost"
                              size="icon"
                              className="absolute right-2 top-2 h-7 w-7 bg-white/10 hover:bg-white/20 text-white rounded-md opacity-0 group-hover/payload-container:opacity-100 transition-opacity z-20"
                              onClick={(e) => {
                                e.preventDefault();
                                e.stopPropagation();
                                const raw = decodeUnicode(evt.payload);
                                let content = raw;
                                try { content = JSON.stringify(JSON.parse(raw), null, 2); } catch {}
                                onMaximizePayload(evt.eventType + " Payload", content);
                              }}
                            >
                              <Maximize2 className="h-3.5 w-3.5" />
                            </Button>
                            <div className="mt-2 bg-[#0d1117] text-[#c9d1d9] p-3 rounded-lg font-mono text-[11px] overflow-x-auto border border-[#30363d] shadow-inner max-h-[300px] overflow-y-auto custom-scrollbar">
                              <pre className="m-0 leading-relaxed">
                                {(() => {
                                  try {
                                    const raw = decodeUnicode(evt.payload);
                                    return JSON.stringify(JSON.parse(raw), null, 2);
                                  } catch {
                                    return decodeUnicode(evt.payload);
                                  }
                                })()}
                              </pre>
                            </div>
                          </div>
                        </details>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      <div className="mt-8 pt-6 border-t border-dashed">
        <details className="group/raw">
          <summary className="inline-flex items-center text-xs font-semibold tracking-wider uppercase text-muted-foreground cursor-pointer hover:text-primary transition-colors select-none">
            <Terminal className="h-4 w-4 mr-2" />
            <span>Show Raw Node Logs</span>
            <ChevronDown className="h-4 w-4 ml-1 opacity-50 transition-transform group-open/raw:rotate-180" />
          </summary>
          <div className="bg-[#0c0c0c] text-green-400 p-4 rounded-lg font-mono text-xs overflow-x-auto overflow-y-auto max-h-[400px] whitespace-pre mt-4 border border-[#333] shadow-inner custom-scrollbar">
            {logs.map((log, idx) => (
              <div key={idx} className="mb-1.5 hover:bg-white/5 px-1 -mx-1 rounded transition-colors break-all whitespace-pre-wrap">{decodeUnicode(log)}</div>
            ))}
          </div>
        </details>
      </div>
    </div>
  )
}

