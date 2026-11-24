"use client"

import * as React from "react"
import Link from "next/link"
import {
  LayoutGrid,
  Users,
  BarChart3,
  Activity,
  ClipboardList,
  Search,
  Server,
  Mail,
  MessageSquare,
  UsersRound,
  Bell,
  ArrowLeftRight,
  HeartPulse,
  Database,
} from "lucide-react"
import { NavMain, type NavItem } from "@/components/nav-main"
import { NavUser } from "@/components/nav-user"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
} from "@/components/ui/sidebar"
import { useAuth } from "@/lib/auth-context"

// ── Group 1: Main (all users) ────────────────────────────────
const mainItems: NavItem[] = [
  { title: "Dashboard", url: "/dashboard", icon: LayoutGrid },
  { title: "Server Health", url: "/dashboard/health", icon: HeartPulse },
]

// ── Group 2: DS Operations (all users) ─────────────────────
const operationsItems: NavItem[] = [
  { title: "Log Search",  url: "/dashboard/ussd-logs/history",    icon: Search },
  { title: "Live Trace",  url: "/dashboard/ussd-logs/live-trace", icon: Activity },
  { title: "MCN Checker", url: "/dashboard/mcn-checker",          icon: ClipboardList },
  {
    title: "Reports",
    url: "/dashboard/reports",
    icon: BarChart3,
    items: [
      { title: "Executive Summary", url: "/dashboard/reports" },
      { title: "Packages & Short Codes", url: "/dashboard/reports/packages" },
      { title: "Subscriber (MSISDN)", url: "/dashboard/reports/subscribers" },
      { title: "Errors & Messages", url: "/dashboard/reports/messages" },
      { title: "APN Requests", url: "/dashboard/reports/apn" },
    ],
  },
]

// ── Group 3: Balance & Alerts (admin only) ───────────────────
const alertsItems: NavItem[] = [
  {
    title: "Balance Alarms",
    url: "/dashboard/balance-alarms/config",
    icon: Bell,
    items: [
      { title: "Configurations", url: "/dashboard/balance-alarms/config" },
      { title: "Trends & History", url: "/dashboard/balance-alarms/history" },
      { title: "API Config", url: "/dashboard/balance-alarms/api-config" },
    ],
  },
  { title: "Contact Groups", url: "/dashboard/contact-groups", icon: UsersRound },
]

// ── Group 4: Notification Channels (admin only) ──────────────
const notificationItems: NavItem[] = [
  { title: "Mail Server", url: "/dashboard/mail-server", icon: Mail },
  { title: "SMPP Accounts", url: "/dashboard/smpp-accounts", icon: MessageSquare },
]

// ── Group 5: System Admin (admin only) ───────────────────────
const systemAdminItems: NavItem[] = [
  { title: "Users", url: "/dashboard/users", icon: Users },
  { title: "USSD Servers", url: "/dashboard/ussd-servers",  icon: Server },
  { title: "MCN Servers", url: "/dashboard/mcn-servers",    icon: Server },
  { title: "Storage & DB", url: "/dashboard/storage",       icon: Database },
  { title: "Audit Logs", url: "/dashboard/audit", icon: ClipboardList },
]

export function AppSidebar({ ...props }: React.ComponentProps<typeof Sidebar>) {
  const { user } = useAuth()
  const isAdmin = user?.role === "ADMIN"

  const navUser = {
    name: user?.username || "Guest",
    email: user?.email || "",
  }

  return (
    <Sidebar collapsible="icon" {...props}>
      {/* Brand Header */}
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" asChild>
              <Link href="/dashboard" className="gap-3">
                <div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-white overflow-hidden p-0.5 shadow-sm">
                  <img src="/sudani-logo.png" alt="Sudani" className="h-full w-full object-contain" />
                </div>
                <div className="grid flex-1 text-left text-sm leading-tight">
                  <span className="truncate font-bold tracking-wide">ITDS</span>
                  <span className="truncate text-[10px] text-sidebar-foreground/70 uppercase">Management & Monitoring</span>
                </div>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <NavMain items={mainItems} label="Main" />
        <NavMain items={operationsItems} label="DS Operations" />
        {isAdmin && <NavMain items={alertsItems} label="Balance & Alerts" />}
        {isAdmin && <NavMain items={notificationItems} label="Notification Channels" />}
        {isAdmin && <NavMain items={systemAdminItems} label="System Admin" />}
      </SidebarContent>

      <SidebarFooter>
        <NavUser user={navUser} />
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  )
}
