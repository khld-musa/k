"use client"

import { AppSidebar } from "@/components/app-sidebar"
import { ProtectedRoute } from "@/components/protected-route"
import {
  SidebarInset,
  SidebarProvider,
} from "@/components/ui/sidebar"
import { useAuth } from "@/lib/auth-context"
import { useIdleTimeout } from "@/hooks/use-idle-timeout"
import { toast } from "sonner"
import { useCallback } from "react"

function DashboardContent({ children }: { children: React.ReactNode }) {
  const { logout, token } = useAuth()

  const handleIdleTimeout = useCallback(() => {
    toast.warning("You have been logged out due to inactivity.", { duration: 5000 })
    logout()
  }, [logout])

  useIdleTimeout({
    timeout: 10 * 60 * 1000, // 10 minutes
    onTimeout: handleIdleTimeout,
    enabled: !!token,
  })

  return (
    <SidebarProvider>
      <AppSidebar />
      <SidebarInset>
        {children}
      </SidebarInset>
    </SidebarProvider>
  )
}

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <ProtectedRoute>
      <DashboardContent>{children}</DashboardContent>
    </ProtectedRoute>
  )
}
