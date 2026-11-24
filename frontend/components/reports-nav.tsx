"use client"

import * as React from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { BarChart3, Package, Users, AlertTriangle, Radio } from "lucide-react"
import { cn } from "@/lib/utils"

const reportTabs = [
  {
    title: "Executive Summary",
    href: "/dashboard/reports",
    icon: BarChart3,
    description: "Overview metrics, daily traffic & health",
  },
  {
    title: "Packages & Short Codes",
    href: "/dashboard/reports/packages",
    icon: Package,
    description: "Traffic, success rates & volume per service",
  },
  {
    title: "Subscriber (MSISDN)",
    href: "/dashboard/reports/subscribers",
    icon: Users,
    description: "Search customer calls, journey & subscriptions",
  },
  {
    title: "Errors & Messages",
    href: "/dashboard/reports/messages",
    icon: AlertTriangle,
    description: "Search specific responses & Arabic error text",
  },
  {
    title: "APN Requests",
    href: "/dashboard/reports/apn",
    icon: Radio,
    description: "Real-time external APN push traffic & latency",
  },
]

export function ReportsNav() {
  const pathname = usePathname()

  return (
    <div className="border-b bg-card/50 w-full min-w-0 max-w-full overflow-x-auto">
      <div className="flex items-center gap-1 px-4 sm:px-6 pt-2 min-w-max">
        {reportTabs.map((tab) => {
          const Icon = tab.icon
          const isActive = pathname === tab.href
          return (
            <Link
              key={tab.href}
              href={tab.href}
              className={cn(
                "inline-flex items-center gap-2 border-b-2 px-4 py-2.5 text-sm font-medium transition-colors hover:text-foreground",
                isActive
                  ? "border-primary text-foreground font-semibold"
                  : "border-transparent text-muted-foreground hover:border-muted-foreground/30"
              )}
            >
              <Icon className={cn("h-4 w-4", isActive ? "text-primary" : "text-muted-foreground")} />
              <span>{tab.title}</span>
            </Link>
          )
        })}
      </div>
    </div>
  )
}
