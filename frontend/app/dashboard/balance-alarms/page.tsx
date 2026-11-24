"use client"

import { useEffect } from "react"
import { useRouter } from "next/navigation"

export default function BalanceAlarmsRedirect() {
  const router = useRouter()
  
  useEffect(() => {
    router.replace("/dashboard/balance-alarms/config")
  }, [router])

  return null
}
