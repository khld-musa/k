"use client"

import * as React from "react"
import { DashboardHeader } from "@/components/dashboard-header"
import { ServerHealthView } from "@/components/server-health-view"

export default function ServerHealthPage() {
  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "Dashboard", href: "/dashboard" },
          { label: "Server Health" },
        ]}
      />
      <div className="flex flex-1 flex-col gap-6 p-6">
        <ServerHealthView />
      </div>
    </>
  )
}
