"use client"

import * as React from "react"
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"

interface AlarmHistory {
  id: number
  triggeredAt: string
  currentBalance: number
  threshold: number
}

interface BalanceTrendsProps {
  data: AlarmHistory[]
}

export function BalanceTrends({ data }: BalanceTrendsProps) {
  // Sort data by date ascending
  const chartData = [...data]
    .sort((a, b) => new Date(a.triggeredAt).getTime() - new Date(b.triggeredAt).getTime())
    .map((item) => ({
      date: new Date(item.triggeredAt).toLocaleDateString("en-GB", {
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
      }),
      balance: item.currentBalance,
      threshold: item.threshold,
    }))
    .slice(-10) // Only last 10 breaches for focus

  if (data.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Balance Depletion Trends</CardTitle>
          <CardDescription>Visualizing recent threshold breaches</CardDescription>
        </CardHeader>
        <CardContent className="flex h-[300px] items-center justify-center text-muted-foreground">
          No history data available to plot trends.
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Balance Depletion Trends</CardTitle>
        <CardDescription>
          Tracking current balance vs threshold over last {chartData.length} alert events
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="h-[300px] w-full mt-4">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chartData}>
              <defs>
                <linearGradient id="colorBalance" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#ef4444" stopOpacity={0.1} />
                  <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
              <XAxis
                dataKey="date"
                fontSize={10}
                tickLine={false}
                axisLine={false}
                tick={{ fill: "#64748b" }}
              />
              <YAxis
                fontSize={10}
                tickLine={false}
                axisLine={false}
                tick={{ fill: "#64748b" }}
                tickFormatter={(val) => val.toLocaleString()}
              />
              <Tooltip
                content={({ active, payload }) => {
                  if (active && payload && payload.length) {
                    return (
                      <div className="rounded-lg border bg-background p-2 shadow-sm text-xs">
                        <p className="font-bold text-slate-500 mb-1">{payload[0].payload.date}</p>
                        <div className="space-y-1">
                          <p className="flex justify-between gap-4">
                            <span className="text-muted-foreground">Balance:</span>
                            <span className="font-mono text-destructive">{payload[0].value?.toLocaleString()}</span>
                          </p>
                          <p className="flex justify-between gap-4">
                            <span className="text-muted-foreground">Threshold:</span>
                            <span className="font-mono">{payload[1]?.value?.toLocaleString()}</span>
                          </p>
                        </div>
                      </div>
                    )
                  }
                  return null
                }}
              />
              <Area
                type="monotone"
                dataKey="balance"
                stroke="#ef4444"
                strokeWidth={2}
                fillOpacity={1}
                fill="url(#colorBalance)"
                name="Current Balance"
              />
              <Area
                type="stepAfter"
                dataKey="threshold"
                stroke="#64748b"
                strokeWidth={1}
                strokeDasharray="5 5"
                fill="none"
                name="Minimum Threshold"
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </CardContent>
    </Card>
  )
}
